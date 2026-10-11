package com.blockoutlines.addon.modules;

import com.blockoutlines.addon.BlockOutlinesAddon;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.BlockUpdateEvent;
import meteordevelopment.meteorclient.events.world.ChunkDataEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
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
import net.minecraft.block.BedBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.block.entity.BarrelBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.block.entity.EnderChestBlockEntity;
import net.minecraft.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Base hunting: flags loaded chunks that contain signs of player activity. Everything is read from chunk data the
 * server already sent, so nothing is sent to the server.
 *
 * Signals (each one scores points once it passes its own count threshold):
 *  - rotated deepslate: natural deepslate always faces up, a sideways pillar was placed by a player
 *  - cobbled deepslate and end stone: not generated in the overworld terrain
 *  - obsidian, buried chests / barrels / shulkers, and man-made blocks (hoppers, furnaces, pistons, ...)
 *
 * Chunks that hold a natural structure marker (ancient city, trial chamber, stronghold) ignore the signals that
 * those structures also produce, so they do not light up by themselves.
 */
public class SusChunk extends Module {
    private final SettingGroup sgDetection = settings.createGroup("Detection");
    private final SettingGroup sgSignals = settings.createGroup("Signals");
    private final SettingGroup sgRender = settings.createGroup("Render");
    private final SettingGroup sgPerformance = settings.createGroup("Performance");

    // -------- detection
    private final Setting<Boolean> overworldOnly = sgDetection.add(new BoolSetting.Builder()
        .name("overworld-only")
        .description("Only scan in the overworld. The signals are tuned for overworld terrain.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> undergroundOnly = sgDetection.add(new BoolSetting.Builder()
        .name("underground-only")
        .description("Ignore everything on or near the surface (villages, houses, trees).")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> minDepth = sgDetection.add(new IntSetting.Builder()
        .name("min-depth")
        .description("How many blocks below the surface a block must be to count as underground.")
        .defaultValue(6)
        .range(0, 64)
        .sliderRange(0, 32)
        .visible(undergroundOnly::get)
        .build()
    );

    private final Setting<Boolean> ignoreStructures = sgDetection.add(new BoolSetting.Builder()
        .name("ignore-structures")
        .description("In chunks with an ancient city, trial chamber or stronghold, ignore the signals those structures create naturally.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> minScore = sgDetection.add(new IntSetting.Builder()
        .name("min-score")
        .description("Score a chunk needs before it is flagged. Higher means fewer, surer results.")
        .defaultValue(12)
        .range(1, 100)
        .sliderRange(4, 40)
        .build()
    );

    // -------- signals
    private final Setting<Boolean> sigRotated = sgSignals.add(new BoolSetting.Builder()
        .name("rotated-deepslate")
        .description("Deepslate pillars lying sideways (placed by a player).")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> sigCobbled = sgSignals.add(new BoolSetting.Builder()
        .name("cobbled-deepslate")
        .description("Cobbled deepslate, which is not part of the normal terrain.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> sigEndStone = sgSignals.add(new BoolSetting.Builder()
        .name("end-stone")
        .description("End stone in the overworld.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> sigObsidian = sgSignals.add(new BoolSetting.Builder()
        .name("obsidian")
        .description("Clusters of obsidian.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> sigStorage = sgSignals.add(new BoolSetting.Builder()
        .name("storage")
        .description("Chests, barrels, ender chests and shulker boxes.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> sigManMade = sgSignals.add(new BoolSetting.Builder()
        .name("man-made-blocks")
        .description("Hoppers, furnaces, crafting tables, beds, redstone parts and similar.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> sigPistons = sgSignals.add(new BoolSetting.Builder()
        .name("pistons")
        .description("Pistons and observers (farms, doors, trap parts).")
        .defaultValue(true)
        .build()
    );

    // -------- render
    private final Setting<Boolean> render = sgRender.add(new BoolSetting.Builder()
        .name("render")
        .description("Draw a box around flagged chunks.")
        .defaultValue(true)
        .build()
    );

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How the boxes are drawn.")
        .defaultValue(ShapeMode.Both)
        .visible(render::get)
        .build()
    );

    private final Setting<SettingColor> lowColor = sgRender.add(new ColorSetting.Builder()
        .name("low-score-color")
        .description("Color of chunks that only just pass the minimum score.")
        .defaultValue(new SettingColor(255, 200, 0, 90))
        .visible(render::get)
        .build()
    );

    private final Setting<SettingColor> highColor = sgRender.add(new ColorSetting.Builder()
        .name("high-score-color")
        .description("Color of chunks with a very high score.")
        .defaultValue(new SettingColor(255, 40, 40, 90))
        .visible(render::get)
        .build()
    );

    private final Setting<Boolean> fullHeight = sgRender.add(new BoolSetting.Builder()
        .name("full-height")
        .description("Draw the box over the whole world height instead of only around the found blocks.")
        .defaultValue(false)
        .visible(render::get)
        .build()
    );

    private final Setting<Integer> maxRender = sgRender.add(new IntSetting.Builder()
        .name("max-rendered")
        .description("Maximum number of chunks drawn (the nearest ones).")
        .defaultValue(30)
        .range(1, 200)
        .sliderRange(5, 100)
        .visible(render::get)
        .build()
    );

    private final Setting<Boolean> tracers = sgRender.add(new BoolSetting.Builder()
        .name("tracers")
        .description("Draw a line from the crosshair to each flagged chunk.")
        .defaultValue(false)
        .build()
    );

    private final Setting<SettingColor> tracerColor = sgRender.add(new ColorSetting.Builder()
        .name("tracer-color")
        .description("Color of the tracers.")
        .defaultValue(new SettingColor(255, 120, 0, 200))
        .visible(tracers::get)
        .build()
    );

    private final Setting<Boolean> chatAlerts = sgRender.add(new BoolSetting.Builder()
        .name("chat-alerts")
        .description("Print a message in chat the first time a chunk is flagged.")
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

    private final Setting<Integer> forgetDistance = sgPerformance.add(new IntSetting.Builder()
        .name("forget-distance")
        .description("Forget flagged chunks further away than this many chunks. 0 keeps them until you leave the world.")
        .defaultValue(0)
        .range(0, 512)
        .sliderRange(0, 128)
        .build()
    );

    // -------- state
    private static final Set<Block> MAN_MADE = Set.of(
        Blocks.HOPPER, Blocks.FURNACE, Blocks.BLAST_FURNACE, Blocks.SMOKER, Blocks.CRAFTING_TABLE,
        Blocks.DISPENSER, Blocks.DROPPER, Blocks.BEACON, Blocks.ANVIL, Blocks.ENCHANTING_TABLE,
        Blocks.BREWING_STAND, Blocks.TNT, Blocks.LECTERN, Blocks.SMITHING_TABLE, Blocks.GRINDSTONE,
        Blocks.COMPARATOR, Blocks.REPEATER, Blocks.REDSTONE_LAMP
    );

    /** Blocks that mean "this chunk holds a natural structure". */
    private static final Set<Block> STRUCTURE_MARKERS = Set.of(
        Blocks.SCULK_SHRIEKER, Blocks.REINFORCED_DEEPSLATE, Blocks.TRIAL_SPAWNER, Blocks.VAULT, Blocks.END_PORTAL_FRAME
    );

    private static class Hit {
        final int chunkX, chunkZ;
        int score, minY, maxY;
        String reasons;

        Hit(int chunkX, int chunkZ) {
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
        }

        double centerX() { return chunkX * 16 + 8; }
        double centerZ() { return chunkZ * 16 + 8; }
    }

    private final Map<Long, Hit> hits = new HashMap<>();
    private final Set<Long> announced = new HashSet<>();

    // the chunk data event comes from the network thread, so the queue is concurrent; scanning stays on the game thread
    private final Queue<Long> queue = new ConcurrentLinkedQueue<>();
    private final Set<Long> queued = ConcurrentHashMap.newKeySet();

    private final Color side = new Color();
    private final Color line = new Color();
    private final Color tracer = new Color();

    public SusChunk() {
        super(BlockOutlinesAddon.HUNTING, "sus-chunk", "Flags chunks that show signs of a player base: rotated deepslate, buried storage, man-made blocks and more.");
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

    // ------------------------------------------------------------------ events

    @EventHandler
    private void onChunkData(ChunkDataEvent event) {
        enqueue(event.chunk().getPos().toLong());
    }

    @EventHandler
    private void onBlockUpdate(BlockUpdateEvent event) {
        // only rescan when a block that matters appeared or disappeared
        if (!relevant(event.oldState) && !relevant(event.newState)) return;
        enqueue(new ChunkPos(event.pos).toLong());
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null || mc.player == null) return;
        if (overworldOnly.get() && PlayerUtils.getDimension() != Dimension.Overworld) return;

        forgetFar();

        for (int i = 0; i < chunksPerTick.get(); i++) {
            Long key = queue.poll();
            if (key == null) break;
            queued.remove(key);

            int cx = ChunkPos.getPackedX(key);
            int cz = ChunkPos.getPackedZ(key);
            if (!mc.world.getChunkManager().isChunkLoaded(cx, cz)) continue;
            scan(mc.world.getChunk(cx, cz));
        }
    }

    private void forgetFar() {
        int limit = forgetDistance.get();
        if (limit <= 0 || hits.isEmpty()) return;
        double px = mc.player.getX(), pz = mc.player.getZ();
        double max = limit * 16.0;
        Iterator<Map.Entry<Long, Hit>> it = hits.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, Hit> e = it.next();
            if (Math.hypot(e.getValue().centerX() - px, e.getValue().centerZ() - pz) > max) {
                announced.remove(e.getKey());
                it.remove();
            }
        }
    }

    // ------------------------------------------------------------------ scanning

    private boolean relevant(BlockState s) {
        Block b = s.getBlock();
        return b == Blocks.DEEPSLATE || b == Blocks.COBBLED_DEEPSLATE || b == Blocks.END_STONE || b == Blocks.OBSIDIAN
            || b == Blocks.PISTON || b == Blocks.STICKY_PISTON || b == Blocks.OBSERVER
            || b == Blocks.CHEST || b == Blocks.TRAPPED_CHEST || b == Blocks.BARREL || b == Blocks.ENDER_CHEST
            || isManMade(b) || STRUCTURE_MARKERS.contains(b);
    }

    private static boolean isManMade(Block b) {
        return MAN_MADE.contains(b) || b instanceof BedBlock || b instanceof ShulkerBoxBlock;
    }

    private static boolean isPiston(Block b) {
        return b == Blocks.PISTON || b == Blocks.STICKY_PISTON || b == Blocks.OBSERVER;
    }

    private boolean interesting(BlockState s) {
        Block b = s.getBlock();
        return b == Blocks.DEEPSLATE || b == Blocks.COBBLED_DEEPSLATE || b == Blocks.END_STONE || b == Blocks.OBSIDIAN
            || isPiston(b) || isManMade(b) || STRUCTURE_MARKERS.contains(b);
    }

    private void scan(WorldChunk chunk) {
        ChunkPos pos = chunk.getPos();
        int startX = pos.getStartX(), startZ = pos.getStartZ();
        int bottomY = mc.world.getBottomY();
        int depth = undergroundOnly.get() ? minDepth.get() : Integer.MIN_VALUE;

        // surface height of each column, used for the underground test
        int[] surface = new int[256];
        Heightmap heightmap = chunk.getHeightmap(Heightmap.Type.WORLD_SURFACE);
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) surface[x * 16 + z] = heightmap.get(x, z);

        int rotated = 0, cobbled = 0, endStone = 0, obsidian = 0, storage = 0, manMade = 0, pistons = 0;
        boolean structure = false;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;

        ChunkSection[] sections = chunk.getSectionArray();
        for (int i = 0; i < sections.length; i++) {
            ChunkSection section = sections[i];
            if (section == null || section.isEmpty() || !section.hasAny(this::interesting)) continue;

            int baseY = ((bottomY >> 4) + i) << 4;
            for (int y = 0; y < 16; y++) {
                int wy = baseY + y;
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        BlockState state = section.getBlockState(x, y, z);
                        Block block = state.getBlock();
                        if (block == Blocks.AIR || !interesting(state)) continue;

                        if (STRUCTURE_MARKERS.contains(block)) {
                            structure = true;
                            continue;
                        }

                        if (wy >= surface[x * 16 + z] - depth && depth != Integer.MIN_VALUE) continue;

                        boolean hit = false;
                        if (block == Blocks.DEEPSLATE) {
                            if (state.contains(Properties.AXIS) && state.get(Properties.AXIS) != Direction.Axis.Y) {
                                rotated++;
                                hit = true;
                            }
                        } else if (block == Blocks.COBBLED_DEEPSLATE) {
                            cobbled++;
                            hit = true;
                        } else if (block == Blocks.END_STONE) {
                            endStone++;
                            hit = true;
                        } else if (block == Blocks.OBSIDIAN) {
                            obsidian++;
                            hit = true;
                        } else if (isPiston(block)) {
                            pistons++;
                            hit = true;
                        } else if (isManMade(block)) {
                            manMade++;
                            hit = true;
                        }

                        if (hit) {
                            if (wy < minY) minY = wy;
                            if (wy > maxY) maxY = wy;
                        }
                    }
                }
            }
        }

        // storage blocks are block entities, so they are read separately
        for (BlockEntity be : chunk.getBlockEntities().values()) {
            int weight;
            if (be instanceof ShulkerBoxBlockEntity) weight = 3;
            else if (be instanceof ChestBlockEntity || be instanceof BarrelBlockEntity || be instanceof EnderChestBlockEntity) weight = 1;
            else continue;

            BlockPos bp = be.getPos();
            if (depth != Integer.MIN_VALUE && bp.getY() >= surface[(bp.getX() & 15) * 16 + (bp.getZ() & 15)] - depth) continue;

            storage += weight;
            if (bp.getY() < minY) minY = bp.getY();
            if (bp.getY() > maxY) maxY = bp.getY();
        }

        // natural structures also make these signals, so they are not counted there
        if (structure && ignoreStructures.get()) {
            rotated = 0;
            obsidian = 0;
            storage = 0;
            // trial chambers contain dispensers, and ancient cities contain a lot of loot
            manMade = 0;
        }

        List<String> reasons = new ArrayList<>();
        int score = 0;
        if (sigRotated.get() && rotated >= 3) score += add(reasons, "rotated deepslate x" + rotated, 4 + Math.min(10, rotated / 3));
        if (sigCobbled.get() && cobbled >= 4) score += add(reasons, "cobbled deepslate x" + cobbled, 3 + Math.min(5, cobbled / 4));
        if (sigEndStone.get() && endStone >= 1) score += add(reasons, "end stone x" + endStone, 8 + Math.min(6, endStone / 2));
        if (sigObsidian.get() && obsidian >= 6) score += add(reasons, "obsidian x" + obsidian, 4 + Math.min(8, obsidian / 4));
        if (sigStorage.get() && storage >= 2) score += add(reasons, "storage x" + storage, 3 + Math.min(17, storage * 2));
        if (sigManMade.get() && manMade >= 3) score += add(reasons, "man-made blocks x" + manMade, 3 + Math.min(15, manMade));
        if (sigPistons.get() && pistons >= 3) score += add(reasons, "pistons x" + pistons, 4 + Math.min(8, pistons));

        long key = pos.toLong();
        if (score >= minScore.get()) {
            Hit hit = hits.computeIfAbsent(key, k -> new Hit(pos.x, pos.z));
            hit.score = score;
            hit.minY = minY;
            hit.maxY = maxY;
            hit.reasons = String.join(", ", reasons);

            if (announced.add(key) && chatAlerts.get()) {
                info("Sus chunk at (highlight)%d, %d(default) with score %d: %s", (int) hit.centerX(), (int) hit.centerZ(), score, hit.reasons);
            }
        } else {
            hits.remove(key);
            announced.remove(key);
        }
    }

    private static int add(List<String> reasons, String text, int points) {
        reasons.add(text);
        return points;
    }

    // ------------------------------------------------------------------ render

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (hits.isEmpty() || mc.player == null) return;
        if (!render.get() && !tracers.get()) return;

        double px = mc.player.getX(), pz = mc.player.getZ();
        List<Hit> list = new ArrayList<>(hits.values());
        list.sort(Comparator.comparingDouble(h -> {
            double dx = h.centerX() - px, dz = h.centerZ() - pz;
            return dx * dx + dz * dz;
        }));

        int min = minScore.get();
        int shown = 0;
        for (Hit h : list) {
            if (shown++ >= maxRender.get()) break;

            // 0 at the minimum score, 1 at three times the minimum
            double t = Math.max(0, Math.min(1, (h.score - min) / (double) (min * 2)));

            double x1 = h.chunkX * 16, z1 = h.chunkZ * 16;
            double y1, y2;
            if (fullHeight.get()) {
                y1 = mc.world.getBottomY();
                y2 = mc.world.getBottomY() + mc.world.getDimension().height();
            } else {
                y1 = h.minY - 1;
                y2 = h.maxY + 2;
            }

            if (render.get()) {
                mix(side, lowColor.get(), highColor.get(), t, 1.0);
                mix(line, lowColor.get(), highColor.get(), t, 2.8); // outline is stronger than the fill
                event.renderer.box(x1, y1, z1, x1 + 16, y2, z1 + 16, side, line, shapeMode.get(), 0);
            }

            if (tracers.get()) {
                tracer.set(tracerColor.get());
                event.renderer.line(RenderUtils.center.x, RenderUtils.center.y, RenderUtils.center.z,
                    h.centerX(), (y1 + y2) / 2, h.centerZ(), tracer);
            }
        }
    }

    private static void mix(Color out, Color a, Color b, double t, double alphaScale) {
        int r = (int) (a.r + (b.r - a.r) * t);
        int g = (int) (a.g + (b.g - a.g) * t);
        int bl = (int) (a.b + (b.b - a.b) * t);
        int al = (int) Math.min(255, (a.a + (b.a - a.a) * t) * alphaScale);
        out.set(r, g, bl, al);
    }
}
