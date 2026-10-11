package com.blockoutlines.addon.modules;

import com.blockoutlines.addon.BlockOutlinesAddon;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.ChunkDataEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.render.RenderUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.meteorclient.utils.world.Dimension;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.CropBlock;
import net.minecraft.block.entity.BeehiveBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.Registries;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Finds "OG" chunks: chunks from the old world generation, and old chunks that players have used.
 *
 * Old chunks (made before the 1.18 world height change) keep a flat bedrock floor around Y 0 to 4, while new chunks
 * have bedrock only at the very bottom (Y -64). A server that hides the deep chunk sections still sends Y 0 to 4,
 * so a flat bedrock layer there tells an old chunk apart without looking underground.
 *
 * On top of that, signs of activity rank a chunk higher: block entities (chests, furnaces, beds, ...), grown crops,
 * running repeaters, torches, beehives with bees, glow lichen near other players, and placed deepslate.
 *
 *  - gold:   old chunk with signs of use
 *  - pale:   old chunk only
 *  - orange: not old, but heavily used
 */
public class OgChunkFinder extends Module {
    private enum Cat { NONE, CROP, REPEATER, LIGHT, LICHEN, COBBLED, DEEPSLATE }

    private final SettingGroup sgDetection = settings.createGroup("Detection");
    private final SettingGroup sgSigns = settings.createGroup("Activity signs");
    private final SettingGroup sgRender = settings.createGroup("Render");
    private final SettingGroup sgPerformance = settings.createGroup("Performance");

    // -------- detection
    private final Setting<Boolean> overworldOnly = sgDetection.add(new BoolSetting.Builder()
        .name("overworld-only")
        .description("Only look in the overworld.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> bedrockOnly = sgDetection.add(new BoolSetting.Builder()
        .name("bedrock-only")
        .description("Only a flat bedrock layer counts as an old floor. Turn it off to accept any single block that fills a whole layer.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> floorMinY = sgDetection.add(new IntSetting.Builder()
        .name("floor-min-y")
        .description("Lowest Y checked for the old floor.")
        .defaultValue(-4)
        .range(-64, 64)
        .sliderRange(-16, 16)
        .build()
    );

    private final Setting<Integer> floorMaxY = sgDetection.add(new IntSetting.Builder()
        .name("floor-max-y")
        .description("Highest Y checked for the old floor. Old bedrock floors sit around Y 0 to 4.")
        .defaultValue(8)
        .range(-16, 64)
        .sliderRange(0, 32)
        .build()
    );

    private final Setting<Integer> coverageMin = sgDetection.add(new IntSetting.Builder()
        .name("coverage-min")
        .description("How many of the 256 blocks of one layer must be the same block to count as a floor. Lower finds more chunks but adds false positives.")
        .defaultValue(180)
        .range(100, 256)
        .sliderRange(120, 256)
        .build()
    );

    private final Setting<Integer> stackMin = sgDetection.add(new IntSetting.Builder()
        .name("stack-min")
        .description("How many blocks of the layer directly above or below the floor must be the same block. Confirms it is not a one-off flat surface.")
        .defaultValue(160)
        .range(80, 256)
        .sliderRange(100, 256)
        .build()
    );

    private final Setting<Integer> usageMin = sgDetection.add(new IntSetting.Builder()
        .name("usage-min")
        .description("A chunk with this many block entities (chests, furnaces, beds, signs, ...) counts as used.")
        .defaultValue(8)
        .range(1, 100)
        .sliderRange(2, 40)
        .build()
    );

    private final Setting<Integer> growthMin = sgDetection.add(new IntSetting.Builder()
        .name("growth-min")
        .description("A chunk with this many fully grown crops counts as a farm.")
        .defaultValue(30)
        .range(5, 500)
        .sliderRange(10, 200)
        .build()
    );

    private final Setting<Integer> activeMin = sgDetection.add(new IntSetting.Builder()
        .name("active-min")
        .description("Points from the activity signs a chunk needs to count as active.")
        .defaultValue(6)
        .range(1, 40)
        .sliderRange(2, 20)
        .build()
    );

    private final Setting<Boolean> showPlainOg = sgDetection.add(new BoolSetting.Builder()
        .name("show-plain-og")
        .description("Also show old chunks with no sign of use (pale).")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showActiveOnly = sgDetection.add(new BoolSetting.Builder()
        .name("show-used-new-chunks")
        .description("Also show heavily used chunks that are not old (orange).")
        .defaultValue(true)
        .build()
    );

    // -------- activity signs
    private final Setting<Boolean> sigRepeaters = sgSigns.add(new BoolSetting.Builder()
        .name("running-repeaters")
        .description("Repeaters and comparators that are powered right now (a machine is running).")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> sigLights = sgSigns.add(new BoolSetting.Builder()
        .name("lights")
        .description("Torches, lanterns, campfires and candles.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> sigBees = sgSigns.add(new BoolSetting.Builder()
        .name("beehives")
        .description("Beehives and bee nests that have bees inside.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> sigLichen = sgSigns.add(new BoolSetting.Builder()
        .name("glow-lichen-near-players")
        .description("Glow lichen within 112 blocks of another player.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> sigDeepslate = sgSigns.add(new BoolSetting.Builder()
        .name("placed-deepslate")
        .description("Cobbled deepslate and sideways deepslate pillars (placed by a player).")
        .defaultValue(true)
        .build()
    );

    // -------- render
    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How the chunk boxes are drawn.")
        .defaultValue(ShapeMode.Both)
        .build()
    );

    private final Setting<SettingColor> goldColor = sgRender.add(new ColorSetting.Builder()
        .name("og-used-color")
        .description("Color of old chunks with signs of use.")
        .defaultValue(new SettingColor(255, 205, 40, 70))
        .build()
    );

    private final Setting<SettingColor> paleColor = sgRender.add(new ColorSetting.Builder()
        .name("og-color")
        .description("Color of old chunks with no sign of use.")
        .defaultValue(new SettingColor(255, 235, 150, 40))
        .build()
    );

    private final Setting<SettingColor> usedColor = sgRender.add(new ColorSetting.Builder()
        .name("used-color")
        .description("Color of heavily used chunks that are not old.")
        .defaultValue(new SettingColor(255, 140, 40, 55))
        .build()
    );

    private final Setting<Boolean> sheen = sgRender.add(new BoolSetting.Builder()
        .name("sheen")
        .description("A slow shimmer across the boxes.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> sheenSpeed = sgRender.add(new DoubleSetting.Builder()
        .name("sheen-speed")
        .description("How fast the shimmer moves.")
        .defaultValue(1.2)
        .range(0.2, 4.0)
        .sliderRange(0.2, 4.0)
        .visible(sheen::get)
        .build()
    );

    private final Setting<Double> boxMinY = sgRender.add(new DoubleSetting.Builder()
        .name("box-min-y")
        .description("Bottom of the chunk box.")
        .defaultValue(0)
        .range(-64, 320)
        .sliderRange(-64, 320)
        .build()
    );

    private final Setting<Double> boxHeight = sgRender.add(new DoubleSetting.Builder()
        .name("box-height")
        .description("Height of the chunk box, measured up from box-min-y.")
        .defaultValue(96)
        .range(1, 384)
        .sliderRange(16, 256)
        .build()
    );

    private final Setting<Integer> renderRadius = sgRender.add(new IntSetting.Builder()
        .name("render-radius")
        .description("How many chunks away boxes are still drawn.")
        .defaultValue(24)
        .range(4, 64)
        .sliderRange(4, 48)
        .build()
    );

    private final Setting<Boolean> tracers = sgRender.add(new BoolSetting.Builder()
        .name("tracers")
        .description("Draw a line to each old chunk with signs of use.")
        .defaultValue(false)
        .build()
    );

    private final Setting<SettingColor> tracerColor = sgRender.add(new ColorSetting.Builder()
        .name("tracer-color")
        .description("Color of the tracers.")
        .defaultValue(new SettingColor(255, 205, 40, 200))
        .visible(tracers::get)
        .build()
    );

    private final Setting<Boolean> chatAlerts = sgRender.add(new BoolSetting.Builder()
        .name("chat-alerts")
        .description("Print a message in chat the first time a chunk is found.")
        .defaultValue(true)
        .build()
    );

    // -------- performance
    private final Setting<Integer> chunksPerTick = sgPerformance.add(new IntSetting.Builder()
        .name("chunks-per-tick")
        .description("How many chunks are scanned each tick. Lower it if you lose FPS.")
        .defaultValue(3)
        .range(1, 20)
        .sliderRange(1, 10)
        .build()
    );

    private final Setting<Integer> rescanInterval = sgPerformance.add(new IntSetting.Builder()
        .name("rescan-interval")
        .description("Ticks between re-scanning the chunks around you. Needed because a server can send the lower sections later, when you get close. 0 turns it off.")
        .defaultValue(120)
        .range(0, 1200)
        .sliderRange(0, 600)
        .build()
    );

    private final Setting<Integer> rescanRadius = sgPerformance.add(new IntSetting.Builder()
        .name("rescan-radius")
        .description("How many chunks around you are re-scanned each interval.")
        .defaultValue(3)
        .range(1, 10)
        .sliderRange(1, 8)
        .build()
    );

    // -------- state
    private static final int TIER_USED_NEW = 0, TIER_OG = 1, TIER_OG_USED = 2;

    private static class Hit {
        final int cx, cz;
        int tier;
        String reasons;

        Hit(int cx, int cz) {
            this.cx = cx;
            this.cz = cz;
        }
    }

    private final Map<Block, Cat> catCache = new ConcurrentHashMap<>();
    private final Map<Long, Hit> hits = new HashMap<>();
    private final Set<Long> announced = new HashSet<>();

    // the chunk data event comes from the network thread; scanning stays on the game thread
    private final Queue<Long> queue = new ConcurrentLinkedQueue<>();
    private final Set<Long> queued = ConcurrentHashMap.newKeySet();

    private int tickCount;

    private final Color side = new Color();
    private final Color line = new Color();
    private final Color tracer = new Color();

    public OgChunkFinder() {
        super(BlockOutlinesAddon.HUNTING, "og-chunk-finder", "Finds old-generation (OG) chunks by their flat bedrock floor, and ranks the ones players have used.");
    }

    @Override
    public void onActivate() {
        clearAll();
        if (mc.world == null) return;
        for (Chunk chunk : Utils.chunks()) enqueue(chunk.getPos().toLong());
    }

    @Override
    public void onDeactivate() {
        clearAll();
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        clearAll();
    }

    private void clearAll() {
        hits.clear();
        announced.clear();
        queue.clear();
        queued.clear();
    }

    private void enqueue(long key) {
        if (queued.add(key)) queue.add(key);
    }

    @Override
    public String getInfoString() {
        return hits.isEmpty() ? null : Integer.toString(hits.size());
    }

    // ------------------------------------------------------------------ classification

    private Cat classify(Block block) {
        Cat c = catCache.get(block);
        if (c == null) {
            c = compute(block);
            catCache.put(block, c);
        }
        return c;
    }

    private static Cat compute(Block block) {
        if (block instanceof CropBlock) return Cat.CROP;
        if (block == Blocks.REPEATER || block == Blocks.COMPARATOR) return Cat.REPEATER;
        if (block == Blocks.GLOW_LICHEN) return Cat.LICHEN;
        if (block == Blocks.COBBLED_DEEPSLATE) return Cat.COBBLED;
        if (block == Blocks.DEEPSLATE) return Cat.DEEPSLATE;

        String p = Registries.BLOCK.getId(block).getPath();
        if (p.contains("torch") || p.equals("campfire") || p.equals("soul_campfire") || p.endsWith("candle")) return Cat.LIGHT;
        if (p.endsWith("lantern") && !p.equals("sea_lantern")) return Cat.LIGHT;
        return Cat.NONE;
    }

    private boolean interesting(BlockState state) {
        return classify(state.getBlock()) != Cat.NONE;
    }

    // ------------------------------------------------------------------ events

    @EventHandler
    private void onChunkData(ChunkDataEvent event) {
        enqueue(event.chunk().getPos().toLong());
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null || mc.player == null) return;
        if (overworldOnly.get() && PlayerUtils.getDimension() != Dimension.Overworld) {
            if (!hits.isEmpty()) clearAll();
            return;
        }

        // the server may send the lower sections later, so look at the nearby chunks again now and then
        int interval = rescanInterval.get();
        if (interval > 0 && ++tickCount % interval == 0) {
            int pcx = mc.player.getBlockX() >> 4, pcz = mc.player.getBlockZ() >> 4;
            int r = rescanRadius.get();
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (mc.world.getChunkManager().isChunkLoaded(pcx + dx, pcz + dz)) enqueue(ChunkPos.toLong(pcx + dx, pcz + dz));
                }
            }
        }

        for (int i = 0; i < chunksPerTick.get(); i++) {
            Long key = queue.poll();
            if (key == null) break;
            queued.remove(key);

            int cx = ChunkPos.getPackedX(key);
            int cz = ChunkPos.getPackedZ(key);
            if (!mc.world.getChunkManager().isChunkLoaded(cx, cz)) continue;
            scan(mc.world.getChunk(cx, cz));
        }

        // forget boxes of chunks that are gone
        if (tickCount % 100 == 0) {
            hits.keySet().removeIf(k -> !mc.world.getChunkManager().isChunkLoaded(ChunkPos.getPackedX(k), ChunkPos.getPackedZ(k)));
            announced.removeIf(k -> !hits.containsKey(k));
        }
    }

    // ------------------------------------------------------------------ scanning

    private boolean hasOldFloor(WorldChunk chunk) {
        int bottomY = mc.world.getBottomY();
        int lo = Math.max(floorMinY.get(), bottomY);
        int hi = floorMaxY.get();
        if (hi < lo) return false;

        ChunkSection[] sections = chunk.getSectionArray();
        int[] layer = new int[hi - lo + 3]; // one extra layer on each side for the stack check
        Block[] layerBlock = new Block[layer.length];

        for (int i = 0; i < layer.length; i++) {
            int y = lo - 1 + i;
            int idx = (y >> 4) - (bottomY >> 4);
            if (y < bottomY || idx < 0 || idx >= sections.length) continue;
            ChunkSection section = sections[idx];
            if (section == null || section.isEmpty()) continue;

            int ly = y & 15;
            if (bedrockOnly.get()) {
                int n = 0;
                for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) if (section.getBlockState(x, ly, z).isOf(Blocks.BEDROCK)) n++;
                layer[i] = n;
                layerBlock[i] = Blocks.BEDROCK;
            } else {
                // any single block that fills the layer
                Map<Block, Integer> counts = new HashMap<>();
                Block best = null;
                int bestN = 0;
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        Block b = section.getBlockState(x, ly, z).getBlock();
                        if (b == Blocks.AIR || b == Blocks.CAVE_AIR || b == Blocks.WATER || b == Blocks.LAVA) continue;
                        int n = counts.merge(b, 1, Integer::sum);
                        if (n > bestN) { bestN = n; best = b; }
                    }
                }
                layer[i] = bestN;
                layerBlock[i] = best;
            }
        }

        for (int i = 1; i < layer.length - 1; i++) {
            if (layer[i] < coverageMin.get()) continue;
            boolean below = layer[i - 1] >= stackMin.get() && layerBlock[i - 1] == layerBlock[i];
            boolean above = layer[i + 1] >= stackMin.get() && layerBlock[i + 1] == layerBlock[i];
            if (below || above) return true;
        }
        return false;
    }

    private void scan(WorldChunk chunk) {
        ChunkPos pos = chunk.getPos();
        int bottomY = mc.world.getBottomY();
        boolean old = hasOldFloor(chunk);

        int crops = 0, repeaters = 0, lights = 0, cobbled = 0, rotated = 0;
        double lichenX = 0, lichenZ = 0;
        boolean lichen = false;

        ChunkSection[] sections = chunk.getSectionArray();
        for (int i = 0; i < sections.length; i++) {
            ChunkSection section = sections[i];
            if (section == null || section.isEmpty() || !section.hasAny(this::interesting)) continue;

            int baseY = ((bottomY >> 4) + i) << 4;
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        BlockState state = section.getBlockState(x, y, z);
                        Block block = state.getBlock();
                        switch (classify(block)) {
                            case CROP -> {
                                if (crops < 1000 && block instanceof CropBlock crop && crop.isMature(state)) crops++;
                            }
                            case REPEATER -> {
                                if (repeaters < 3 && state.contains(Properties.POWERED) && state.get(Properties.POWERED)) repeaters++;
                            }
                            case LIGHT -> {
                                if (lights < 4) lights++;
                            }
                            case LICHEN -> {
                                if (!lichen) {
                                    lichen = true;
                                    lichenX = pos.getStartX() + x + 0.5;
                                    lichenZ = pos.getStartZ() + z + 0.5;
                                }
                            }
                            case COBBLED -> {
                                if (cobbled < 3) cobbled++;
                            }
                            case DEEPSLATE -> {
                                if (rotated < 3 && state.contains(Properties.AXIS) && state.get(Properties.AXIS) != Direction.Axis.Y) rotated++;
                            }
                            default -> {}
                        }
                    }
                }
            }
        }

        // block entities: how lived in the chunk is, and beehives with bees
        int blockEntities = 0;
        boolean bees = false;
        for (BlockEntity be : chunk.getBlockEntities().values()) {
            blockEntities++;
            if (be instanceof BeehiveBlockEntity hive && hive.getBeeCount() > 0) bees = true;
        }

        boolean lichenNear = false;
        if (lichen) {
            for (PlayerEntity other : mc.world.getPlayers()) {
                if (other == mc.player) continue;
                double dx = other.getX() - lichenX, dz = other.getZ() - lichenZ;
                if (dx * dx + dz * dz <= 112.0 * 112.0) { lichenNear = true; break; }
            }
        }

        // activity points
        int active = 0;
        List<String> reasons = new ArrayList<>();
        if (old) reasons.add("old bedrock floor");

        if (sigRepeaters.get() && repeaters > 0) { active += repeaters * 3; reasons.add("running repeaters x" + repeaters); }
        if (sigBees.get() && bees) { active += 4; reasons.add("beehive with bees"); }
        if (sigLichen.get() && lichenNear) { active += 4; reasons.add("glow lichen near a player"); }
        if (sigLights.get() && lights > 0) { active += lights; reasons.add("lights x" + lights); }
        if (sigDeepslate.get() && cobbled >= 3) { active += 2; reasons.add("cobbled deepslate"); }
        if (sigDeepslate.get() && rotated >= 3) { active += 3; reasons.add("sideways deepslate"); }

        boolean used = blockEntities >= usageMin.get();
        boolean grown = crops >= growthMin.get();
        boolean activeOk = active >= activeMin.get();
        if (used) reasons.add("block entities x" + blockEntities);
        if (grown) reasons.add("grown crops x" + crops);

        int tier = -1;
        if (old) {
            if (used || grown || activeOk) tier = TIER_OG_USED;
            else if (showPlainOg.get()) tier = TIER_OG;
        } else if (showActiveOnly.get()) {
            boolean heavy = blockEntities >= usageMin.get() * 2 || (used && grown) || active >= activeMin.get() * 2;
            if (heavy) tier = TIER_USED_NEW;
        }

        long key = pos.toLong();
        if (tier < 0) {
            hits.remove(key);
            announced.remove(key);
            return;
        }

        Hit hit = hits.computeIfAbsent(key, k -> new Hit(pos.x, pos.z));
        hit.tier = tier;
        hit.reasons = String.join(", ", reasons);

        if (announced.add(key) && chatAlerts.get()) {
            String label = tier == TIER_OG_USED ? "OG chunk (used)" : tier == TIER_OG ? "OG chunk" : "Heavily used chunk";
            info("%s at (highlight)%d, %d(default): %s", label, pos.x * 16 + 8, pos.z * 16 + 8, hit.reasons);
        }
    }

    // ------------------------------------------------------------------ render

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (hits.isEmpty() || mc.player == null) return;

        int pcx = mc.player.getBlockX() >> 4, pcz = mc.player.getBlockZ() >> 4;
        int radius = renderRadius.get();
        double t = System.nanoTime() / 1.0e9 * sheenSpeed.get();
        double y1 = boxMinY.get(), y2 = y1 + boxHeight.get();

        for (Hit h : hits.values()) {
            if (Math.max(Math.abs(h.cx - pcx), Math.abs(h.cz - pcz)) > radius) continue;

            SettingColor base = h.tier == TIER_OG_USED ? goldColor.get() : h.tier == TIER_OG ? paleColor.get() : usedColor.get();

            // the shimmer is a slow wave that travels across neighbouring chunks
            double wave = sheen.get() ? 0.65 + 0.35 * Math.sin(t * 2.0 + (h.cx + h.cz) * 0.6) : 1.0;
            side.set(base.r, base.g, base.b, (int) Math.min(255, base.a * wave * (h.tier == TIER_OG_USED ? 1.0 : 0.8)));
            line.set(base.r, base.g, base.b, (int) (255 * (0.7 + 0.3 * wave)));

            double x1 = h.cx * 16, z1 = h.cz * 16;
            event.renderer.box(x1, y1, z1, x1 + 16, y2, z1 + 16, side, line, shapeMode.get(), 0);

            if (tracers.get() && h.tier == TIER_OG_USED) {
                tracer.set(tracerColor.get());
                event.renderer.line(RenderUtils.center.x, RenderUtils.center.y, RenderUtils.center.z, x1 + 8, (y1 + y2) / 2, z1 + 8, tracer);
            }
        }
    }
}
