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
import meteordevelopment.meteorclient.settings.StringSetting;
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
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.ChunkRandom;
import net.minecraft.util.math.random.RandomSeed;
import net.minecraft.util.math.random.Xoroshiro128PlusPlusRandom;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.feature.OreFeatureConfig;
import net.minecraft.world.gen.feature.PlacedFeature;
import net.minecraft.world.gen.feature.util.PlacedFeatureIndexer;

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
 * Seed based ancient debris finder for the Nether. It does not look for the ore itself (anti-xray servers hide it),
 * it works out where the game placed ancient debris from the world seed and then looks at what the server shows at
 * those spots:
 *
 *  - stone (netherrack / basalt / blackstone) that touches no air  -> not mined, still there  -> drawn
 *  - air with solid rock around it                                   -> a hole someone dug  -> mined (optional color)
 *  - a real ancient debris block                                     -> confirmed (the seed is right)
 *
 * The generation rules are the game's: same decoration seed (ChunkRandom with Xoroshiro), same feature order (the
 * game's own PlacedFeatureIndexer run on the server's biome data), same placement ({@code in_square}, height range,
 * {@code scattered_ore} spread). Everything stays on your client, nothing is sent to the server.
 */
public class NetheriteFinder extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");
    private final SettingGroup sgPerformance = settings.createGroup("Performance");

    // -------- general
    private final Setting<String> seedText = sgGeneral.add(new StringSetting.Builder()
        .name("seed")
        .description("The world seed of the server (a number). Text is turned into a seed the way Minecraft does it.")
        .defaultValue("")
        .build()
    );

    private final Setting<Boolean> showMined = sgGeneral.add(new BoolSetting.Builder()
        .name("show-mined")
        .description("Also mark spots where ancient debris was generated but has been mined (empty hole inside the rock). Shows where players have been digging. Off by default: only buried, unmined spots are shown.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> minedMaxAir = sgGeneral.add(new IntSetting.Builder()
        .name("mined-max-air")
        .description("An empty spot only counts as mined when at most this many of the 26 blocks around it are air. A big cave around it means it was never rock.")
        .defaultValue(8)
        .range(0, 26)
        .sliderRange(0, 20)
        .visible(showMined::get)
        .build()
    );

    private final Setting<Boolean> showVisible = sgGeneral.add(new BoolSetting.Builder()
        .name("show-visible")
        .description("Also mark real ancient debris blocks the server already shows you. Off by default; the module still counts them as a check that the seed is right.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> debug = sgGeneral.add(new BoolSetting.Builder()
        .name("debug")
        .description("Print what the module is doing in chat every few seconds. Turn it off before streaming.")
        .defaultValue(false)
        .build()
    );

    // -------- render
    private final Setting<Boolean> render = sgRender.add(new BoolSetting.Builder()
        .name("render")
        .description("Draw boxes through walls.")
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

    private final Setting<SettingColor> unminedColor = sgRender.add(new ColorSetting.Builder()
        .name("unmined-color")
        .description("Ancient debris that should still be there.")
        .defaultValue(new SettingColor(255, 170, 60, 70))
        .visible(render::get)
        .build()
    );

    private final Setting<SettingColor> minedColor = sgRender.add(new ColorSetting.Builder()
        .name("mined-color")
        .description("Ancient debris that someone has mined.")
        .defaultValue(new SettingColor(255, 60, 60, 50))
        .visible(render::get)
        .build()
    );

    private final Setting<SettingColor> visibleColor = sgRender.add(new ColorSetting.Builder()
        .name("visible-color")
        .description("A real ancient debris block the server shows you (this confirms the seed is right).")
        .defaultValue(new SettingColor(80, 255, 120, 80))
        .visible(render::get)
        .build()
    );

    private final Setting<Integer> range = sgRender.add(new IntSetting.Builder()
        .name("range")
        .description("Only draw spots closer than this many blocks.")
        .defaultValue(128)
        .range(16, 512)
        .sliderRange(32, 256)
        .build()
    );

    private final Setting<Integer> maxBoxes = sgRender.add(new IntSetting.Builder()
        .name("max-boxes")
        .description("Maximum number of boxes drawn (the nearest ones).")
        .defaultValue(150)
        .range(10, 1000)
        .sliderRange(20, 400)
        .build()
    );

    private final Setting<Boolean> tracers = sgRender.add(new BoolSetting.Builder()
        .name("tracers")
        .description("Draw lines to unmined ancient debris.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> chatAlerts = sgRender.add(new BoolSetting.Builder()
        .name("chat-alerts")
        .description("Print the coordinates in chat when a new unmined spot comes close.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> alertRange = sgRender.add(new IntSetting.Builder()
        .name("alert-range")
        .description("How close (in blocks) a spot must be to be announced in chat.")
        .defaultValue(64)
        .range(8, 256)
        .sliderRange(16, 128)
        .visible(chatAlerts::get)
        .build()
    );

    // -------- performance
    private final Setting<Integer> chunksPerTick = sgPerformance.add(new IntSetting.Builder()
        .name("chunks-per-tick")
        .description("How many chunks are processed each tick. Lower it if you lose FPS.")
        .defaultValue(4)
        .range(1, 30)
        .sliderRange(1, 15)
        .build()
    );

    // -------- state
    private static final int UNMINED = 0, MINED = 1, VISIBLE = 2, PENDING = 3, IGNORE = 4;

    /** Placed features this module follows (vanilla 1.21.11 data): step 7 (underground decoration). */
    private static final int STEP = 7;
    private static final int FALLBACK_LARGE_INDEX = 21;
    private static final int FALLBACK_SMALL_INDEX = 22;

    private static final String[] NETHER_BIOMES = {"nether_wastes", "soul_sand_valley", "crimson_forest", "warped_forest", "basalt_deltas"};

    private static class Spot {
        final long pos;
        final boolean large;
        int state = PENDING;

        Spot(long pos, boolean large) {
            this.pos = pos;
            this.large = large;
        }
    }

    private final Map<Long, List<Spot>> spots = new HashMap<>();
    private final Set<Long> announced = new HashSet<>();

    private final Queue<Long> queue = new ConcurrentLinkedQueue<>();
    private final Set<Long> queued = ConcurrentHashMap.newKeySet();

    private Long seed;
    private boolean featuresReady;
    private int largeIndex = FALLBACK_LARGE_INDEX, smallIndex = FALLBACK_SMALL_INDEX, largeSize = 3, smallSize = 2;
    private String indexSource = "";
    private int debugTimer;

    private final BlockPos.Mutable mutable = new BlockPos.Mutable();
    private final BlockPos.Mutable mutable2 = new BlockPos.Mutable();
    private final Color side = new Color();
    private final Color line = new Color();
    private final Color tracer = new Color();

    public NetheriteFinder() {
        super(BlockOutlinesAddon.HUNTING, "netherite-finder", "Seed based ancient debris finder: works out where debris generated, then shows what is still unmined (works against anti-xray).");
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
        featuresReady = false;
    }

    private void clearAll() {
        spots.clear();
        announced.clear();
        queue.clear();
        queued.clear();
        seed = null;
    }

    private void enqueue(long key) {
        if (queued.add(key)) queue.add(key);
    }

    @Override
    public String getInfoString() {
        if (seed == null) return "no seed";
        int u = 0, m = 0, v = 0;
        for (List<Spot> l : spots.values()) {
            for (Spot s : l) {
                if (s.state == UNMINED) u++;
                else if (s.state == MINED) m++;
                else if (s.state == VISIBLE) v++;
            }
        }
        return u + "/" + m + "/" + v;
    }

    // ------------------------------------------------------------------ seed and features

    private static Long parseSeed(String text) {
        String s = text == null ? "" : text.trim();
        if (s.isEmpty()) return null;
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return (long) s.hashCode(); // what Minecraft does for a text seed
        }
    }

    /** Finds the position of the two debris features in the game's own feature order. */
    private void initFeatures() {
        featuresReady = true;
        largeIndex = FALLBACK_LARGE_INDEX;
        smallIndex = FALLBACK_SMALL_INDEX;
        indexSource = "built-in order";
        try {
            var registries = mc.world.getRegistryManager();
            Registry<Biome> biomes = registries.getOrThrow(RegistryKeys.BIOME);
            Registry<PlacedFeature> placed = registries.getOrThrow(RegistryKeys.PLACED_FEATURE);

            List<Biome> list = new ArrayList<>();
            for (String name : NETHER_BIOMES) {
                Biome b = biomes.get(RegistryKey.of(RegistryKeys.BIOME, Identifier.of("minecraft", name)));
                if (b != null) list.add(b);
            }
            PlacedFeature large = placed.get(RegistryKey.of(RegistryKeys.PLACED_FEATURE, Identifier.of("minecraft", "ore_ancient_debris_large")));
            PlacedFeature small = placed.get(RegistryKey.of(RegistryKeys.PLACED_FEATURE, Identifier.of("minecraft", "ore_debris_small")));
            if (list.isEmpty() || large == null || small == null) return;

            List<PlacedFeatureIndexer.IndexedFeatures> indexed =
                PlacedFeatureIndexer.collectIndexedFeatures(list, b -> b.getGenerationSettings().getFeatures(), false);
            if (indexed.size() <= STEP) return;

            List<PlacedFeature> step = indexed.get(STEP).features();
            int li = step.indexOf(large), si = step.indexOf(small);
            if (li < 0 || si < 0) return;

            largeIndex = li;
            smallIndex = si;
            indexSource = "game order";

            if (large.feature().value().config() instanceof OreFeatureConfig c) largeSize = c.size;
            if (small.feature().value().config() instanceof OreFeatureConfig c) smallSize = c.size;
        } catch (Throwable t) {
            BlockOutlinesAddon.LOG.warn("NetheriteFinder: could not read the feature order, using the built-in one: {}", t.toString());
        }
    }

    // ------------------------------------------------------------------ prediction

    private static int spread(net.minecraft.util.math.random.Random random, int spread) {
        return Math.round((random.nextFloat() - random.nextFloat()) * spread);
    }

    /** All ancient debris spots the game generated for the feature pass of chunk (cx, cz). */
    private List<Spot> predict(int cx, int cz) {
        List<Spot> out = new ArrayList<>(8);
        ChunkRandom random = new ChunkRandom(new Xoroshiro128PlusPlusRandom(RandomSeed.getSeed()));
        long population = random.setPopulationSeed(seed, cx * 16, cz * 16);

        // large veins: in_square, trapezoid height 8..24 (no plateau), scattered_ore size 3
        random.setDecoratorSeed(population, largeIndex, STEP);
        int x = random.nextInt(16) + cx * 16;
        int z = random.nextInt(16) + cz * 16;
        int y = 8 + random.nextInt(9) + random.nextInt(9);
        scatter(random, x, y, z, largeSize, true, out);

        // small veins: in_square, uniform height 8..119 (above bottom 8 .. below top 8 of the 128 high nether), size 2
        random.setDecoratorSeed(population, smallIndex, STEP);
        x = random.nextInt(16) + cx * 16;
        z = random.nextInt(16) + cz * 16;
        y = random.nextInt(119 - 8 + 1) + 8;
        scatter(random, x, y, z, smallSize, false, out);

        return out;
    }

    private void scatter(net.minecraft.util.math.random.Random random, int x, int y, int z, int size, boolean large, List<Spot> out) {
        int count = random.nextInt(size + 1);
        for (int j = 0; j < count; j++) {
            int sp = Math.min(j, 7);
            int dx = spread(random, sp);
            int dy = spread(random, sp);
            int dz = spread(random, sp);
            int py = y + dy;
            if (py < 0 || py > 127) continue;
            long packed = BlockPos.asLong(x + dx, py, z + dz);
            boolean dup = false;
            for (Spot s : out) if (s.pos == packed) { dup = true; break; }
            if (!dup) out.add(new Spot(packed, large));
        }
    }

    // ------------------------------------------------------------------ looking at the real world

    private boolean loaded(int x, int z) {
        return mc.world.getChunkManager().isChunkLoaded(x >> 4, z >> 4);
    }

    private static boolean isAirState(BlockState s) {
        return s.isAir();
    }

    /** A solid block that fills its space, with no fluid in it. */
    private static boolean buried(BlockState s) {
        return s.isOpaque() && s.getFluidState().isEmpty();
    }

    private static boolean baseStone(Block b) {
        return b == Blocks.NETHERRACK || b == Blocks.BASALT || b == Blocks.BLACKSTONE;
    }

    private int classify(Spot spot) {
        int x = BlockPos.unpackLongX(spot.pos), y = BlockPos.unpackLongY(spot.pos), z = BlockPos.unpackLongZ(spot.pos);

        // everything we look at must be loaded, otherwise "air" would only mean "unknown"
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) if (!loaded(x + dx, z + dz)) return PENDING;

        BlockState state = mc.world.getBlockState(mutable.set(x, y, z));
        Block block = state.getBlock();

        if (block == Blocks.ANCIENT_DEBRIS) return VISIBLE;

        if (baseStone(block)) {
            // An anti-xray server shows a hidden block as plain stone only while it is buried: every side is a solid
            // block. If any side touches air, lava or water the server sends the real block, so there is no hidden
            // debris here (and the game never generates debris next to air anyway).
            for (Direction d : Direction.values()) {
                if (!buried(mc.world.getBlockState(mutable2.set(x + d.getOffsetX(), y + d.getOffsetY(), z + d.getOffsetZ())))) return IGNORE;
            }
            return UNMINED;
        }

        if (isAirState(state)) {
            int air = 0;
            for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dy == 0 && dz == 0) continue;
                if (isAirState(mc.world.getBlockState(mutable2.set(x + dx, y + dy, z + dz)))) air++;
            }
            return air <= minedMaxAir.get() ? MINED : IGNORE;
        }

        return IGNORE;
    }

    private void reclassify(List<Spot> list) {
        for (Spot s : list) {
            s.state = classify(s);
            if (s.state == UNMINED && chatAlerts.get() && mc.player != null && announced.add(s.pos)) {
                int x = BlockPos.unpackLongX(s.pos), y = BlockPos.unpackLongY(s.pos), z = BlockPos.unpackLongZ(s.pos);
                double dx = x + 0.5 - mc.player.getX(), dy = y + 0.5 - mc.player.getY(), dz = z + 0.5 - mc.player.getZ();
                int ar = alertRange.get();
                if (dx * dx + dy * dy + dz * dz <= ar * (double) ar) {
                    info("Unmined ancient debris (%s) at (highlight)%d %d %d(default).", s.large ? "large vein" : "small vein", x, y, z);
                }
            }
        }
    }

    // ------------------------------------------------------------------ events

    @EventHandler
    private void onChunkData(ChunkDataEvent event) {
        // a chunk's veins can reach into its neighbors, so the neighbors are looked at again too
        ChunkPos p = event.chunk().getPos();
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) enqueue(new ChunkPos(p.x + dx, p.z + dz).toLong());
    }

    @EventHandler
    private void onBlockUpdate(BlockUpdateEvent event) {
        if (seed == null) return;
        // only when the changed block is right next to one of the predicted spots
        BlockPos bp = event.pos;
        ChunkPos cp = new ChunkPos(bp);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                long key = new ChunkPos(cp.x + dx, cp.z + dz).toLong();
                List<Spot> list = spots.get(key);
                if (list == null) continue;
                for (Spot s : list) {
                    if (Math.abs(BlockPos.unpackLongX(s.pos) - bp.getX()) <= 2
                        && Math.abs(BlockPos.unpackLongY(s.pos) - bp.getY()) <= 2
                        && Math.abs(BlockPos.unpackLongZ(s.pos) - bp.getZ()) <= 2) {
                        enqueue(key);
                        break;
                    }
                }
            }
        }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null || mc.player == null) return;
        if (PlayerUtils.getDimension() != Dimension.Nether) return;

        Long parsed = parseSeed(seedText.get());
        if (parsed == null) {
            if (seed != null) { spots.clear(); announced.clear(); }
            seed = null;
            return;
        }
        if (!parsed.equals(seed)) {
            seed = parsed;
            spots.clear();
            announced.clear();
            for (Chunk chunk : Utils.chunks()) enqueue(chunk.getPos().toLong());
        }
        if (!featuresReady) initFeatures();

        for (int i = 0; i < chunksPerTick.get(); i++) {
            Long key = queue.poll();
            if (key == null) break;
            queued.remove(key);

            int cx = ChunkPos.getPackedX(key);
            int cz = ChunkPos.getPackedZ(key);
            // the prediction only needs the seed, but there is no point in judging chunks the server has not sent
            if (!mc.world.getChunkManager().isChunkLoaded(cx, cz)) continue;

            List<Spot> list = spots.get(key);
            if (list == null) {
                list = predict(cx, cz);
                spots.put(key, list);
            }
            reclassify(list);
            // spots that could not be judged yet (neighbor chunk missing) are tried again soon
            for (Spot s : list) if (s.state == PENDING) { enqueue(key); break; }
        }

        if (debug.get() && ++debugTimer >= 100) {
            debugTimer = 0;
            int total = 0, u = 0, m = 0, v = 0, p = 0;
            for (List<Spot> l : spots.values()) for (Spot s : l) {
                total++;
                if (s.state == UNMINED) u++;
                else if (s.state == MINED) m++;
                else if (s.state == VISIBLE) v++;
                else if (s.state == PENDING) p++;
            }
            info("seed %d | feature order: %s (large %d, small %d) | spots %d: unmined %d, mined %d, visible %d, pending %d", seed, indexSource, largeIndex, smallIndex, total, u, m, v, p);
        }
    }

    // ------------------------------------------------------------------ render

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (seed == null || spots.isEmpty() || mc.player == null) return;
        if (PlayerUtils.getDimension() != Dimension.Nether) return;
        if (!render.get() && !tracers.get()) return;

        double px = mc.player.getX(), py = mc.player.getY(), pz = mc.player.getZ();
        double r2 = range.get() * (double) range.get();

        List<Spot> near = new ArrayList<>();
        List<Double> dist = new ArrayList<>();
        for (List<Spot> list : spots.values()) {
            for (Spot s : list) {
                if (s.state != UNMINED && !(s.state == VISIBLE && showVisible.get()) && !(s.state == MINED && showMined.get())) continue;
                double dx = BlockPos.unpackLongX(s.pos) + 0.5 - px, dy = BlockPos.unpackLongY(s.pos) + 0.5 - py, dz = BlockPos.unpackLongZ(s.pos) + 0.5 - pz;
                double d = dx * dx + dy * dy + dz * dz;
                if (d > r2) continue;
                near.add(s);
                dist.add(d);
            }
        }

        // nearest first, cut at max-boxes
        Integer[] order = new Integer[near.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        java.util.Arrays.sort(order, (a, b) -> Double.compare(dist.get(a), dist.get(b)));

        int shown = 0;
        for (int idx : order) {
            if (shown++ >= maxBoxes.get()) break;
            Spot s = near.get(idx);
            SettingColor c = s.state == UNMINED ? unminedColor.get() : s.state == VISIBLE ? visibleColor.get() : minedColor.get();

            int x = BlockPos.unpackLongX(s.pos), y = BlockPos.unpackLongY(s.pos), z = BlockPos.unpackLongZ(s.pos);
            if (render.get()) {
                side.set(c.r, c.g, c.b, c.a);
                line.set(c.r, c.g, c.b, (int) Math.min(255, c.a * 3.2));
                event.renderer.box(x, y, z, x + 1, y + 1, z + 1, side, line, shapeMode.get(), 0);
            }
            if (tracers.get() && s.state == UNMINED) {
                tracer.set(c.r, c.g, c.b, 220);
                event.renderer.line(RenderUtils.center.x, RenderUtils.center.y, RenderUtils.center.z, x + 0.5, y + 0.5, z + 0.5, tracer);
            }
        }
    }
}
