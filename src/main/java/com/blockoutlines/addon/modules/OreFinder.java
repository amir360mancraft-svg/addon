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
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.ChunkRandom;
import net.minecraft.util.math.random.RandomSeed;
import net.minecraft.util.math.random.Xoroshiro128PlusPlusRandom;
import net.minecraft.world.chunk.Chunk;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Seed based overworld ore finder: same idea as the ancient debris finder, generalized to coal, iron, copper, gold,
 * redstone, lapis, diamond and emerald (or a custom block pair). It works out roughly where a vein should be from
 * the world seed, then looks at what the server actually shows at those spots (still solid -> probably unmined,
 * turned to air with rock around it -> mined, the real ore block -> confirmed) so it also works against anti-xray.
 *
 * Read this before trusting it: unlike ancient debris (which generates from a handful of Nether biomes, so the
 * exact feature order can be computed), a normal overworld ore has dozens of possible biomes and several placement
 * passes. This module does NOT replicate the game's exact placement algorithm move for move - it uses the publicly
 * known, approximate Minecraft ore distribution (height range, how many tries per chunk, roughly how big a vein is)
 * for each ore, from memory. The height ranges especially may be off by some blocks for some ores, and the "large"
 * sub-variants (diamond's big vein, gold's bottom-of-the-world bonus, etc.) are only roughly covered. Use "Custom"
 * and the Minecraft Wiki's ore distribution page to fix numbers that look off for your server's version, and treat
 * highlighted spots as "worth digging toward", not a guaranteed exact block. Emerald only naturally generates in
 * Windswept Hills type biomes; this module does not check the biome, so expect extra highlighted spots elsewhere.
 */
public class OreFinder extends Module {
    public enum OreType { Coal, Iron, Copper, Gold, Redstone, Lapis, Diamond, Emerald, Custom }
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

    // Approximate 1.18+ vanilla distribution (two main tiers per ore where it has them). From memory - see class doc.
    private static final Map<OreType, Rule[]> PRESETS = new EnumMap<>(OreType.class);
    static {
        PRESETS.put(OreType.Coal, new Rule[]{
            new Rule(30, 17, 136, 320, HeightMode.Uniform),
            new Rule(20, 17, 0, 192, HeightMode.Triangle)
        });
        PRESETS.put(OreType.Iron, new Rule[]{
            new Rule(90, 9, 80, 320, HeightMode.Triangle),
            new Rule(10, 4, -24, 56, HeightMode.Uniform)
        });
        PRESETS.put(OreType.Copper, new Rule[]{
            new Rule(16, 10, -16, 112, HeightMode.Triangle),
            new Rule(16, 20, -16, 48, HeightMode.Uniform)
        });
        PRESETS.put(OreType.Gold, new Rule[]{
            new Rule(4, 9, -64, 32, HeightMode.Triangle),
            new Rule(50, 9, -64, -48, HeightMode.Uniform)
        });
        PRESETS.put(OreType.Redstone, new Rule[]{
            new Rule(8, 8, -64, 16, HeightMode.Triangle),
            new Rule(4, 8, -64, -32, HeightMode.Uniform)
        });
        PRESETS.put(OreType.Lapis, new Rule[]{
            new Rule(2, 7, -32, 32, HeightMode.Triangle),
            new Rule(4, 7, -64, 64, HeightMode.Uniform)
        });
        PRESETS.put(OreType.Diamond, new Rule[]{
            new Rule(7, 8, -64, 16, HeightMode.Triangle),
            new Rule(1, 12, -64, -4, HeightMode.Uniform)
        });
        PRESETS.put(OreType.Emerald, new Rule[]{
            new Rule(3, 1, -16, 320, HeightMode.Uniform)
        });
    }

    private static final Map<OreType, Block[]> BLOCKS = new EnumMap<>(OreType.class);
    static {
        BLOCKS.put(OreType.Coal, new Block[]{Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE});
        BLOCKS.put(OreType.Iron, new Block[]{Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE});
        BLOCKS.put(OreType.Copper, new Block[]{Blocks.COPPER_ORE, Blocks.DEEPSLATE_COPPER_ORE});
        BLOCKS.put(OreType.Gold, new Block[]{Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE});
        BLOCKS.put(OreType.Redstone, new Block[]{Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE});
        BLOCKS.put(OreType.Lapis, new Block[]{Blocks.LAPIS_ORE, Blocks.DEEPSLATE_LAPIS_ORE});
        BLOCKS.put(OreType.Diamond, new Block[]{Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE});
        BLOCKS.put(OreType.Emerald, new Block[]{Blocks.EMERALD_ORE, Blocks.DEEPSLATE_EMERALD_ORE});
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgCustom = settings.createGroup("Custom ore");
    private final SettingGroup sgRender = settings.createGroup("Render");
    private final SettingGroup sgPerformance = settings.createGroup("Performance");

    private final Setting<String> seedText = sgGeneral.add(new StringSetting.Builder()
        .name("seed").description("The world seed of the server (a number). Text is turned into a seed the way Minecraft does it.")
        .defaultValue("").build());

    private final Setting<OreType> ore = sgGeneral.add(new EnumSetting.Builder<OreType>()
        .name("ore").description("Which ore to look for. Custom lets you type your own block ids and range.")
        .defaultValue(OreType.Diamond).build());

    private final Setting<Boolean> showMined = sgGeneral.add(new BoolSetting.Builder()
        .name("show-mined").description("Also mark spots where the ore generated but the block is gone now (empty hole in solid rock).")
        .defaultValue(false).build());

    private final Setting<Integer> minedMaxAir = sgGeneral.add(new IntSetting.Builder()
        .name("mined-max-air").description("An empty spot only counts as mined when at most this many of the 26 blocks around it are air.")
        .defaultValue(8).range(0, 26).sliderRange(0, 20).visible(showMined::get).build());

    private final Setting<Boolean> showVisible = sgGeneral.add(new BoolSetting.Builder()
        .name("show-visible").description("Also mark real ore blocks the server already shows you (also confirms the seed/numbers are right).")
        .defaultValue(true).build());

    private final Setting<Boolean> debug = sgGeneral.add(new BoolSetting.Builder()
        .name("debug").description("Print what the module is doing in chat every few seconds.")
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
        .name("height-mode").description("Uniform = evenly spread. Triangle = more common in the middle of the range (most ores are like this).")
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
    private final BlockPos.Mutable mutable = new BlockPos.Mutable();
    private final BlockPos.Mutable mutable2 = new BlockPos.Mutable();
    private final Color side = new Color();
    private final Color line = new Color();
    private final Color tracer = new Color();
    private int debugTimer;

    public OreFinder() {
        super(BlockOutlinesAddon.HUNTING, "ore-finder", "Seed based ore finder for the overworld (coal, iron, copper, gold, redstone, lapis, diamond, emerald, or a custom block). Approximate - read the module description.");
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
        if (ore.get() != OreType.Custom) return BLOCKS.get(ore.get());
        Block n = parseBlock(customNormal.get());
        Block d = parseBlock(customDeepslate.get());
        if (n == null && d == null) return new Block[]{Blocks.STONE}; // never matches, avoids a crash on a bad id
        if (n == null) return new Block[]{d};
        if (d == null) return new Block[]{n};
        return new Block[]{n, d};
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

    // ------------------------------------------------------------------ prediction

    private static float triangle(net.minecraft.util.math.random.Random random) {
        return (random.nextFloat() - random.nextFloat() + 1.0F) / 2.0F; // 0..1, peaked at 0.5
    }

    private static int spread(net.minecraft.util.math.random.Random random, int spread) {
        return Math.round((random.nextFloat() - random.nextFloat()) * spread);
    }

    private List<Spot> predict(int cx, int cz) {
        List<Spot> out = new ArrayList<>(16);
        Rule[] rl = rules();
        ChunkRandom random = new ChunkRandom(new Xoroshiro128PlusPlusRandom(RandomSeed.getSeed()));
        long population = random.setPopulationSeed(seed, cx * 16, cz * 16);

        for (int ri = 0; ri < rl.length; ri++) {
            Rule r = rl[ri];
            // one attempt per decorator seed, like the game does, but the feature index is a guess here (see the
            // class doc): we derive a distinct-but-made-up index per ore+rule so different ores/rules at least do
            // not all draw from the exact same random stream.
            int fakeIndex = 4000 + ore.get().ordinal() * 8 + ri;
            for (int attempt = 0; attempt < Math.max(1, r.count / Math.max(1, r.size)) + 1; attempt++) {
                random.setDecoratorSeed(population, fakeIndex * 97 + attempt, 7);
                int x = random.nextInt(16) + cx * 16;
                int z = random.nextInt(16) + cz * 16;
                int y = r.mode == HeightMode.Triangle
                    ? r.minY + Math.round(triangle(random) * (r.maxY - r.minY))
                    : r.minY + random.nextInt(Math.max(1, r.maxY - r.minY + 1));
                scatter(random, x, y, z, r.size, out);
            }
        }
        return out;
    }

    private void scatter(net.minecraft.util.math.random.Random random, int x, int y, int z, int size, List<Spot> out) {
        int count = random.nextInt(Math.max(1, size)) + 1;
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

    private static boolean hostRock(BlockState s) {
        return s.isIn(BlockTags.STONE_ORE_REPLACEABLES) || s.isIn(BlockTags.DEEPSLATE_ORE_REPLACEABLES);
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
            info("seed %d | ore %s | spots %d: unmined %d, mined %d, visible %d, pending %d", seed, ore.get(), total, u, m, v, p);
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
