package com.blockoutlines.addon.modules;

import com.blockoutlines.addon.BlockOutlinesAddon;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.BlockUpdateEvent;
import meteordevelopment.meteorclient.events.world.ChunkDataEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
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
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.ChunkRandom;
import net.minecraft.util.math.random.RandomSeed;
import net.minecraft.util.math.random.Xoroshiro128PlusPlusRandom;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.feature.PlacedFeature;
import net.minecraft.world.gen.feature.util.PlacedFeatureIndexer;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Seed based overworld ore finder for coal and emerald (both the deepslate version), or a custom block pair. Same
 * idea as the ancient debris finder: it works out roughly where a vein should be from the world seed, then looks at
 * what the server actually shows at those spots (still solid -> probably unmined, turned to air with rock around it
 * -> mined, the real ore block -> confirmed), so it also works against servers that hide ore from you (anti-xray).
 *
 * Accuracy, read before trusting it:
 *  - X/Z and how many tries per chunk now come from the game's own data: on world join it looks up where
 *    "ore_coal_upper"/"ore_coal_lower"/"ore_emerald" sit in the real per-biome feature order (the same trick the
 *    ancient debris finder uses for the Nether, just run over the overworld's biome list), so the random numbers
 *    drawn for position should line up with the server's.
 *  - Height range (the min/max Y each one spawns in) is typed in by hand from memory of the public ore distribution
 *    and could be off by a few blocks.
 *  - The shape of a vein (which nearby blocks also become ore) is an approximation, not the game's exact algorithm,
 *    so for Coal (which can try 20-30 times per chunk) later tries in the same chunk can drift off. Emerald places
 *    one block per try with no vein shape involved, so it should be the most reliable of the two.
 *  - This is NOT a full offline copy of Minecraft's world generator (that is a much bigger project, closer to tools
 *    like Amidst/Chunk Base, and not something I want to ship without being able to test it against a real server).
 *    What this module does instead is predict + then check the real blocks the server already sent you, which is
 *    why the "visible" and "mined" counts are a real cross-check of whether the prediction for your seed is right.
 */
public class OreFinder extends Module {
    public enum OreType { Coal, Emerald, Custom }
    private enum HeightMode { Uniform, Triangle }

    private static final class Rule {
        final int count, size, minY, maxY;
        final HeightMode mode;

        Rule(int count, int size, int minY, int maxY, HeightMode mode) {
            this.count = count;
            this.size = size;
            this.minY = minY;
            this.maxY = maxY;
            this.mode = mode;
        }
    }

    // Approximate height range / tries-per-chunk from memory of the public ore distribution (see class doc).
    private static final Map<OreType, Rule[]> PRESETS = new EnumMap<>(OreType.class);
    static {
        PRESETS.put(OreType.Coal, new Rule[]{
            new Rule(30, 17, 136, 320, HeightMode.Uniform),  // ore_coal_upper
            new Rule(20, 17, 0, 192, HeightMode.Triangle)    // ore_coal_lower
        });
        PRESETS.put(OreType.Emerald, new Rule[]{
            new Rule(3, 1, -16, 320, HeightMode.Uniform)     // ore_emerald (windswept hills only - see class doc)
        });
    }

    /** The real placed-feature ids in the game's own data, used to compute the real feature order on world join. */
    private static final Map<OreType, String[]> FEATURE_IDS = new EnumMap<>(OreType.class);
    static {
        FEATURE_IDS.put(OreType.Coal, new String[]{"ore_coal_upper", "ore_coal_lower"});
        FEATURE_IDS.put(OreType.Emerald, new String[]{"ore_emerald"});
    }

    private static final Map<OreType, Block[]> BLOCKS = new EnumMap<>(OreType.class);
    static {
        BLOCKS.put(OreType.Coal, new Block[]{Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE});
        BLOCKS.put(OreType.Emerald, new Block[]{Blocks.EMERALD_ORE, Blocks.DEEPSLATE_EMERALD_ORE});
    }

    /** Biomes that are not part of a normal overworld (used to build the biome list for the feature-order lookup). */
    private static final Set<String> NON_OVERWORLD_BIOMES = Set.of(
        "nether_wastes", "soul_sand_valley", "crimson_forest", "warped_forest", "basalt_deltas",
        "the_void", "the_end", "end_highlands", "end_midlands", "end_barrens", "small_end_islands"
    );

    /** Vanilla data: underground ores are decoration step 6 (ancient debris, in the Nether finder, is step 7). */
    private static final int STEP = 6;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgCustom = settings.createGroup("Custom ore");
    private final SettingGroup sgRender = settings.createGroup("Render");
    private final SettingGroup sgPerformance = settings.createGroup("Performance");

    private final Setting<String> seedText = sgGeneral.add(new StringSetting.Builder()
        .name("seed").description("The world seed of the server (a number). Text is turned into a seed the way Minecraft does it.")
        .defaultValue("").build());

    private final Setting<OreType> ore = sgGeneral.add(new EnumSetting.Builder<OreType>()
        .name("ore").description("Which ore to look for. Custom lets you type your own block ids and range.")
        .defaultValue(OreType.Emerald).build());

    private final Setting<Boolean> deepslateOnly = sgGeneral.add(new BoolSetting.Builder()
        .name("deepslate-only").description("Only count/show the deepslate version of the ore (ignore the normal-stone version).")
        .defaultValue(true).build());

    private final Setting<Boolean> showMined = sgGeneral.add(new BoolSetting.Builder()
        .name("show-mined").description("Also mark spots where the ore generated but the block is gone now (empty hole in solid rock).")
        .defaultValue(false).build());

    private final Setting<Integer> minedMaxAir = sgGeneral.add(new IntSetting.Builder()
        .name("mined-max-air").description("An empty spot only counts as mined when at most this many of the 26 blocks around it are air.")
        .defaultValue(8).range(0, 26).sliderRange(0, 20).visible(showMined::get).build());

    private final Setting<Boolean> showVisible = sgGeneral.add(new BoolSetting.Builder()
        .name("show-visible").description("Also mark real ore blocks the server already shows you (also confirms the prediction is on track).")
        .defaultValue(true).build());

    private final Setting<Boolean> debug = sgGeneral.add(new BoolSetting.Builder()
        .name("debug").description("Print what the module is doing in chat every few seconds, including whether the real feature order was found.")
        .defaultValue(false).build());

    // -------- custom ore
    private final Setting<String> customNormal = sgCustom.add(new StringSetting.Builder()
        .name("normal-block").description("Block id for the normal-stone version, e.g. minecraft:copper_ore.")
        .defaultValue("minecraft:diamond_ore").visible(() -> ore.get() == OreType.Custom).build());

    private final Setting<String> customDeepslate = sgCustom.add(new StringSetting.Builder()
        .name("deepslate-block").description("Block id for the deepslate version, e.g. minecraft:deepslate_copper_ore.")
        .defaultValue("minecraft:deepslate_diamond_ore").visible(() -> ore.get() == OreType.Custom).build());

    private final Setting<Integer> customMinY = sgCustom.add(new IntSetting.Builder()
        .name("min-y").defaultValue(-64).range(-64, 320).sliderRange(-64, 320).visible(() -> ore.get() == OreType.Custom).build());

    private final Setting<Integer> customMaxY = sgCustom.add(new IntSetting.Builder()
        .name("max-y").defaultValue(16).range(-64, 320).sliderRange(-64, 320).visible(() -> ore.get() == OreType.Custom).build());

    private final Setting<HeightMode> customMode = sgCustom.add(new EnumSetting.Builder<HeightMode>()
        .name("height-mode").description("Uniform = evenly spread. Triangle = more common in the middle of the range.")
        .defaultValue(HeightMode.Triangle).visible(() -> ore.get() == OreType.Custom).build());

    private final Setting<Integer> customCount = sgCustom.add(new IntSetting.Builder()
        .name("tries-per-chunk").defaultValue(8).range(1, 100).sliderRange(1, 50).visible(() -> ore.get() == OreType.Custom).build());

    private final Setting<Integer> customSize = sgCustom.add(new IntSetting.Builder()
        .name("vein-size").defaultValue(8).range(1, 32).sliderRange(1, 24).visible(() -> ore.get() == OreType.Custom).build());

    // -------- render
    private final Setting<Boolean> render = sgRender.add(new BoolSetting.Builder().name("render").defaultValue(true).build());
    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>().name("shape-mode").defaultValue(ShapeMode.Both).visible(render::get).build());
    private final Setting<SettingColor> unminedColor = sgRender.add(new ColorSetting.Builder().name("unmined-color").defaultValue(new SettingColor(80, 255, 255, 70)).visible(render::get).build());
    private final Setting<SettingColor> minedColor = sgRender.add(new ColorSetting.Builder().name("mined-color").defaultValue(new SettingColor(255, 60, 60, 50)).visible(render::get).build());
    private final Setting<SettingColor> visibleColor = sgRender.add(new ColorSetting.Builder().name("visible-color").defaultValue(new SettingColor(80, 255, 120, 80)).visible(render::get).build());
    private final Setting<Integer> range = sgRender.add(new IntSetting.Builder().name("range").defaultValue(96).range(16, 512).sliderRange(32, 256).build());
    private final Setting<Integer> maxBoxes = sgRender.add(new IntSetting.Builder().name("max-boxes").defaultValue(200).range(10, 1500).sliderRange(20, 500).build());
    private final Setting<Boolean> tracers = sgRender.add(new BoolSetting.Builder().name("tracers").defaultValue(false).build());
    private final Setting<Boolean> chatAlerts = sgRender.add(new BoolSetting.Builder().name("chat-alerts").defaultValue(true).build());
    private final Setting<Integer> alertRange = sgRender.add(new IntSetting.Builder().name("alert-range").defaultValue(48).range(8, 256).sliderRange(16, 128).visible(chatAlerts::get).build());

    // -------- performance
    private final Setting<Integer> chunksPerTick = sgPerformance.add(new IntSetting.Builder().name("chunks-per-tick").defaultValue(3).range(1, 30).sliderRange(1, 15).build());

    private static final int UNMINED = 0, MINED = 1, VISIBLE = 2, PENDING = 3, IGNORE = 4;

    private static class Spot {
        final long pos;
        int state = PENDING;

        Spot(long pos) {
            this.pos = pos;
        }
    }

    private final Map<Long, List<Spot>> spots = new HashMap<>();
    private final Set<Long> announced = new HashSet<>();
    private final Queue<Long> queue = new ConcurrentLinkedQueue<>();
    private final Set<Long> queued = ConcurrentHashMap.newKeySet();

    private Long seed;
    private OreType builtFor;
    private boolean featuresReady;
    private final Map<OreType, int[]> featureIndex = new EnumMap<>(OreType.class);
    private String indexSource = "";
    private final BlockPos.Mutable mutable = new BlockPos.Mutable();
    private final BlockPos.Mutable mutable2 = new BlockPos.Mutable();
    private final Color side = new Color();
    private final Color line = new Color();
    private final Color tracer = new Color();
    private int debugTimer;

    public OreFinder() {
        super(BlockOutlinesAddon.HUNTING, "ore-finder", "Seed based ore finder for coal and emerald (deepslate), or a custom block. Approximate - read the module description.");
    }

    @Override
    public void onActivate() {
        clearAll();
        featuresReady = false;
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
        if (seed == null) return ore.get().toString();
        int u = 0, m = 0, v = 0;
        for (List<Spot> l : spots.values()) for (Spot s : l) {
            if (s.state == UNMINED) u++;
            else if (s.state == MINED) m++;
            else if (s.state == VISIBLE) v++;
        }
        return ore.get() + " " + u + "/" + m + "/" + v;
    }

    private static Long parseSeed(String text) {
        String s = text == null ? "" : text.trim();
        if (s.isEmpty()) return null;
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return (long) s.hashCode();
        }
    }

    private Rule[] rules() {
        if (ore.get() == OreType.Custom) {
            return new Rule[]{new Rule(customCount.get(), customSize.get(), customMinY.get(), customMaxY.get(), customMode.get())};
        }
        return PRESETS.get(ore.get());
    }

    private Block[] blocks() {
        Block[] b;
        if (ore.get() != OreType.Custom) {
            b = BLOCKS.get(ore.get());
        } else {
            Block n = parseBlock(customNormal.get());
            Block d = parseBlock(customDeepslate.get());
            if (n == null && d == null) return new Block[]{Blocks.STONE}; // never matches, avoids a crash on a bad id
            b = (n == null) ? new Block[]{d} : (d == null) ? new Block[]{n} : new Block[]{n, d};
        }
        if (!deepslateOnly.get() || b.length < 2) return b;
        return new Block[]{b[1]}; // [normal, deepslate] -> just deepslate
    }

    private static Block parseBlock(String id) {
        try {
            Identifier i = Identifier.of(id.trim());
            Block b = Registries.BLOCK.get(i);
            return b == Blocks.AIR ? null : b;
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ real feature order (for accurate X/Z)

    /** Finds where each ore's placed feature(s) sit in the game's real, per-biome underground-ores order. */
    private void initFeatures() {
        featuresReady = true;
        featureIndex.clear();
        indexSource = "not found yet - using an approximate order";
        try {
            var registries = mc.world.getRegistryManager();
            Registry<Biome> biomes = registries.getOrThrow(RegistryKeys.BIOME);
            Registry<PlacedFeature> placed = registries.getOrThrow(RegistryKeys.PLACED_FEATURE);

            List<Biome> list = new ArrayList<>();
            for (var entry : biomes.getEntrySet()) {
                if (!NON_OVERWORLD_BIOMES.contains(entry.getKey().getValue().getPath())) list.add(entry.getValue());
            }
            if (list.isEmpty()) return;

            List<PlacedFeatureIndexer.IndexedFeatures> indexed =
                PlacedFeatureIndexer.collectIndexedFeatures(list, b -> b.getGenerationSettings().getFeatures(), false);
            if (indexed.size() <= STEP) return;
            List<PlacedFeature> step = indexed.get(STEP).features();

            for (var e : FEATURE_IDS.entrySet()) {
                String[] ids = e.getValue();
                int[] idx = new int[ids.length];
                boolean ok = true;
                for (int i = 0; i < ids.length; i++) {
                    PlacedFeature f = placed.get(RegistryKey.of(RegistryKeys.PLACED_FEATURE, Identifier.of("minecraft", ids[i])));
                    int pos = f == null ? -1 : step.indexOf(f);
                    if (pos < 0) { ok = false; break; }
                    idx[i] = pos;
                }
                if (ok) featureIndex.put(e.getKey(), idx);
            }
            indexSource = featureIndex.size() + "/" + FEATURE_IDS.size() + " ores matched to the real order";
        } catch (Throwable t) {
            BlockOutlinesAddon.LOG.warn("OreFinder: could not read the feature order, falling back to an approximate one: {}", t.toString());
        }
    }

    // ------------------------------------------------------------------ prediction

    /** Close to the game's own TrapezoidHeightProvider with no plateau: two nextInt calls, peaked at the middle. */
    private static int triangleY(net.minecraft.util.math.random.Random random, int min, int max) {
        int span = Math.max(0, max - min);
        int k = span / 2;
        int l = span - k;
        return min + random.nextInt(l + 1) + random.nextInt(k + 1);
    }

    private static int spread(net.minecraft.util.math.random.Random random, int spread) {
        return Math.round((random.nextFloat() - random.nextFloat()) * spread);
    }

    private List<Spot> predict(int cx, int cz) {
        List<Spot> out = new ArrayList<>(16);
        Rule[] rl = rules();
        ChunkRandom random = new ChunkRandom(new Xoroshiro128PlusPlusRandom(RandomSeed.getSeed()));
        long population = random.setPopulationSeed(seed, cx * 16, cz * 16);
        int[] real = featureIndex.get(ore.get());

        for (int ri = 0; ri < rl.length; ri++) {
            Rule r = rl[ri];
            // one decorator seed per feature (like the game), then every try for that feature shares the same
            // continuing random stream - a made-up index is used only when the real one could not be found.
            int decoratorIndex = (real != null && ri < real.length) ? real[ri] : 4000 + ore.get().ordinal() * 8 + ri;
            random.setDecoratorSeed(population, decoratorIndex, STEP);

            for (int attempt = 0; attempt < r.count; attempt++) {
                int x = random.nextInt(16) + cx * 16;
                int z = random.nextInt(16) + cz * 16;
                int y = r.mode == HeightMode.Triangle ? triangleY(random, r.minY, r.maxY) : r.minY + random.nextInt(Math.max(1, r.maxY - r.minY + 1));
                scatter(random, x, y, z, r.size, out);
            }
        }
        return out;
    }

    private void scatter(net.minecraft.util.math.random.Random random, int x, int y, int z, int size, List<Spot> out) {
        if (size <= 1) {
            long packed = BlockPos.asLong(x, y, z);
            boolean dup = false;
            for (Spot s : out) if (s.pos == packed) { dup = true; break; }
            if (!dup) out.add(new Spot(packed));
            return;
        }
        int count = random.nextInt(size) + 1;
        for (int j = 0; j < count; j++) {
            int sp = Math.min(j, 7);
            int dx = spread(random, sp);
            int dy = spread(random, sp) / 2;
            int dz = spread(random, sp);
            int py = y + dy;
            if (py < -64 || py > 320) continue;
            long packed = BlockPos.asLong(x + dx, py, z + dz);
            boolean dup = false;
            for (Spot s : out) if (s.pos == packed) { dup = true; break; }
            if (!dup) out.add(new Spot(packed));
        }
    }

    // ------------------------------------------------------------------ looking at the real world

    private boolean loaded(int x, int z) {
        return mc.world.getChunkManager().isChunkLoaded(x >> 4, z >> 4);
    }

    private static boolean isAirState(BlockState s) {
        return s.isAir();
    }

    private static boolean buried(BlockState s) {
        return s.isOpaque() && s.getFluidState().isEmpty();
    }

    private boolean hostRock(BlockState s) {
        boolean deepslate = s.isIn(BlockTags.DEEPSLATE_ORE_REPLACEABLES);
        if (deepslateOnly.get()) return deepslate;
        return deepslate || s.isIn(BlockTags.STONE_ORE_REPLACEABLES);
    }

    private int classify(Spot spot) {
        int x = BlockPos.unpackLongX(spot.pos), y = BlockPos.unpackLongY(spot.pos), z = BlockPos.unpackLongZ(spot.pos);
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) if (!loaded(x + dx, z + dz)) return PENDING;

        BlockState state = mc.world.getBlockState(mutable.set(x, y, z));
        Block block = state.getBlock();

        for (Block b : blocks()) if (block == b) return VISIBLE;

        if (hostRock(state)) {
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
                    info("Possible %s at (highlight)%d %d %d(default).", ore.get(), x, y, z);
                }
            }
        }
    }

    // ------------------------------------------------------------------ events

    @EventHandler
    private void onChunkData(ChunkDataEvent event) {
        ChunkPos p = event.chunk().getPos();
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) enqueue(new ChunkPos(p.x + dx, p.z + dz).toLong());
    }

    @EventHandler
    private void onBlockUpdate(BlockUpdateEvent event) {
        if (seed == null) return;
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
        if (PlayerUtils.getDimension() != Dimension.Overworld) return;
        if (!featuresReady) initFeatures();

        Long parsed = parseSeed(seedText.get());
        boolean oreChanged = builtFor != ore.get();
        if (parsed == null) {
            if (seed != null) { spots.clear(); announced.clear(); }
            seed = null;
            return;
        }
        if (!parsed.equals(seed) || oreChanged) {
            seed = parsed;
            builtFor = ore.get();
            spots.clear();
            announced.clear();
            for (Chunk chunk : Utils.chunks()) enqueue(chunk.getPos().toLong());
        }

        for (int i = 0; i < chunksPerTick.get(); i++) {
            Long key = queue.poll();
            if (key == null) break;
            queued.remove(key);

            int cx = ChunkPos.getPackedX(key);
            int cz = ChunkPos.getPackedZ(key);
            if (!mc.world.getChunkManager().isChunkLoaded(cx, cz)) continue;

            List<Spot> list = spots.get(key);
            if (list == null) {
                list = predict(cx, cz);
                spots.put(key, list);
            }
            reclassify(list);
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
            info("seed %d | ore %s | feature order: %s | spots %d: unmined %d, mined %d, visible %d, pending %d", seed, ore.get(), indexSource, total, u, m, v, p);
        }
    }

    // ------------------------------------------------------------------ render

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (seed == null || spots.isEmpty() || mc.player == null) return;
        if (PlayerUtils.getDimension() != Dimension.Overworld) return;
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

        Integer[] order = new Integer[near.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> Double.compare(dist.get(a), dist.get(b)));

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
