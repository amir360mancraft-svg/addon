package com.blockoutlines.addon.modules;

import com.blockoutlines.addon.BlockOutlinesAddon;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.BambooBlock;
import net.minecraft.block.AbstractPlantStemBlock;
import net.minecraft.block.BeehiveBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.CocoaBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Base Finder (formerly Chunk Finder) from Donut Client 1.6.1, ported to Meteor. It reads the chunks the client already
 * has and marks chunks where plants and amethyst show signs of player activity (grown amethyst, kelp / cave vines /
 * bamboo / cocoa in unnatural states, honey in bee nests, vines on built walls). The result is a heuristic, not proof.
 */
public class BaseFinder extends Module {
    public static final int MAX_TARGETS = 256;
    public static final int MAX_BLOCKS_PER_TICK = 4096;
    private static final int MAX_SECTIONS_PER_TICK = 64;
    private static final int MAX_CHUNKS_PER_TICK = 32;
    private static final int MAX_SCORE_PER_KIND = 24;
    private static final long WORK_NANOS_PER_TICK = 3_000_000L;

    public enum EvidenceKind { KELP, CAVE_VINES, VINES, AMETHYST, BAMBOO, COCOA, BEE_NEST }

    /** Inclusive bounds of a contiguous band of observed evidence blocks. */
    public record Layer(int minY, int maxY) {}

    /** A heuristic match, not proof of player activity. */
    public record FoundChunk(int chunkX, int chunkZ, int minY, int maxY, int score, Set<EvidenceKind> kinds, List<Layer> layers) {
        public FoundChunk {
            kinds = Set.copyOf(kinds);
            layers = List.copyOf(layers);
        }
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgBlocks = settings.createGroup("Evidence");
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Integer> simulationDistance = sgGeneral.add(new IntSetting.Builder()
        .name("simulation-distance").description("Scan radius in chunks. It does not change the server's simulation distance or load extra chunks.")
        .defaultValue(6).range(2, 16).sliderRange(2, 16).build());
    private final Setting<Integer> sensitivity = sgGeneral.add(new IntSetting.Builder()
        .name("sensitivity").description("1 = stricter, 5 = more candidates (5 also detects a single small, medium or large amethyst bud).")
        .defaultValue(3).range(1, 5).sliderRange(1, 5).build());
    private final Setting<Boolean> smart = sgGeneral.add(new BoolSetting.Builder()
        .name("smart-adjustment").description("Ignore bare amethyst / budding blocks and some common naturally generated plant states, keep mixed-stage growing geodes.")
        .defaultValue(true).build());

    private final Setting<Boolean> kelp = sgBlocks.add(new BoolSetting.Builder().name("kelp").defaultValue(true).build());
    private final Setting<Boolean> caveVines = sgBlocks.add(new BoolSetting.Builder().name("cave-vines").defaultValue(true).build());
    private final Setting<Boolean> vines = sgBlocks.add(new BoolSetting.Builder().name("vines").defaultValue(true).build());
    private final Setting<Boolean> amethyst = sgBlocks.add(new BoolSetting.Builder().name("amethyst").defaultValue(true).build());
    private final Setting<Boolean> bamboo = sgBlocks.add(new BoolSetting.Builder().name("bamboo").defaultValue(true).build());
    private final Setting<Boolean> cocoa = sgBlocks.add(new BoolSetting.Builder().name("cocoa").defaultValue(true).build());
    private final Setting<Boolean> beeNest = sgBlocks.add(new BoolSetting.Builder().name("bee-nest").defaultValue(true).build());

    private final Setting<Integer> alpha = sgRender.add(new IntSetting.Builder()
        .name("alpha").description("Overlay opacity (0-255).").defaultValue(80).range(0, 255).sliderRange(0, 255).build());
    private final Setting<SettingColor> color = sgRender.add(new ColorSetting.Builder()
        .name("color").description("Highlight color.").defaultValue(new SettingColor(0xC4, 0x47, 0xFF, 255)).build());

    // ---- scanner state
    private final Map<Long, Detection> detections = new HashMap<>();
    private final Set<Long> visitedThisPass = new HashSet<>();
    private List<FoundChunk> targets = List.of();
    private List<ChunkPos> scanOrder = List.of();
    private ClientWorld lastLevel;
    private ScanSettings lastSettings;
    private ScanWork active;
    private int centerX;
    private int centerZ;
    private int cursor;

    private final Color fillColor = new Color();
    private final Color lineColor = new Color();

    public BaseFinder() {
        super(BlockOutlinesAddon.HUNTING, "base-finder", "Marks chunks where grown amethyst, kelp, vines, bamboo, cocoa or honey suggest a player base (Donut Client's Base Finder). A heuristic, not proof.");
    }

    @Override
    public void onDeactivate() {
        clear();
    }

    @Override
    public String getInfoString() {
        return targets.isEmpty() ? null : Integer.toString(targets.size());
    }

    // ------------------------------------------------------------------ scanner (ported 1:1)

    public static int thresholdForSensitivity(int sensitivity) {
        return 14 - 2 * Math.clamp(sensitivity, 1, 5);
    }

    private void clear() {
        detections.clear();
        visitedThisPass.clear();
        targets = List.of();
        scanOrder = List.of();
        lastLevel = null;
        lastSettings = null;
        active = null;
        cursor = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        MinecraftClient client = mc;
        if (client.world == null || client.player == null) {
            clear();
            return;
        }
        ScanSettings settings = new ScanSettings(Math.clamp(simulationDistance.get(), 2, 16),
            Math.clamp(sensitivity.get(), 1, 5), smart.get(), kelp.get(), caveVines.get(), vines.get(),
            amethyst.get(), bamboo.get(), cocoa.get(), beeNest.get());
        if (!settings.hasFilters()) {
            clear();
            return;
        }
        int playerX = client.player.getChunkPos().x;
        int playerZ = client.player.getChunkPos().z;
        if (client.world != lastLevel || !settings.equals(lastSettings)) {
            clear();
            lastLevel = client.world;
            lastSettings = settings;
            rebuildOrder(playerX, playerZ, settings.radius());
        } else if (playerX != centerX || playerZ != centerZ) {
            rebuildOrder(playerX, playerZ, settings.radius());
        }

        boolean dirty = detections.entrySet().removeIf(entry -> {
            Detection detection = entry.getValue();
            FoundChunk result = detection.result();
            return !inRange(result.chunkX(), result.chunkZ(), settings.radius())
                || loadedChunk(client.world, result.chunkX(), result.chunkZ()) != detection.chunk();
        });
        if (active != null && loadedChunk(client.world, active.pos.x, active.pos.z) != active.chunk) {
            active = null;
        }

        int blocksLeft = MAX_BLOCKS_PER_TICK;
        int sectionsLeft = MAX_SECTIONS_PER_TICK;
        int chunksLeft = MAX_CHUNKS_PER_TICK;
        long deadline = System.nanoTime() + WORK_NANOS_PER_TICK;
        while (blocksLeft > 0 && sectionsLeft > 0 && chunksLeft > 0) {
            if (active == null) {
                if (cursor >= scanOrder.size()) {
                    visitedThisPass.clear();
                    rebuildOrder(centerX, centerZ, settings.radius());
                    break;
                }
                ChunkPos next = scanOrder.get(cursor++);
                visitedThisPass.add(next.toLong());
                chunksLeft--;
                WorldChunk chunk = loadedChunk(client.world, next.x, next.z);
                if (chunk == null) continue;
                active = new ScanWork(chunk, next);
            }
            ChunkSection[] sections = active.chunk.getSectionArray();
            if (active.sectionIndex >= sections.length) {
                dirty |= finishScan(settings);
                active = null;
                continue;
            }
            ChunkSection section = sections[active.sectionIndex];
            if (!active.sectionStarted) {
                sectionsLeft--;
                if (section.isEmpty() || !section.hasAny(settings::isCandidate)) {
                    active.sectionIndex++;
                    if (System.nanoTime() >= deadline) break;
                    continue;
                }
                active.sectionStarted = true;
            }
            int start = active.blockIndex;
            int end = start + Math.min(blocksLeft, 4096 - start);
            while (active.blockIndex < end) {
                int index = active.blockIndex;
                int x = index & 15;
                int z = (index >>> 4) & 15;
                int y = (index >>> 8) & 15;
                BlockState state = section.getBlockState(x, y, z);
                if (settings.isCandidate(state)) {
                    int worldY = active.chunk.getBottomY() + active.sectionIndex * 16 + y;
                    active.observe(state, x, worldY, z, settings);
                }
                active.blockIndex++;
                if ((active.blockIndex & 127) == 0 && System.nanoTime() >= deadline) break;
            }
            blocksLeft -= active.blockIndex - start;
            if (active.blockIndex == 4096) {
                active.sectionIndex++;
                active.blockIndex = 0;
                active.sectionStarted = false;
            }
            if (System.nanoTime() >= deadline) break;
        }
        if (dirty) publish();
    }

    private static WorldChunk loadedChunk(ClientWorld level, int x, int z) {
        Chunk chunk = level.getChunkManager().getChunk(x, z, ChunkStatus.FULL, false);
        return chunk instanceof WorldChunk worldChunk ? worldChunk : null;
    }

    private void rebuildOrder(int x, int z, int radius) {
        centerX = x;
        centerZ = z;
        cursor = 0;
        if (active != null && !inRange(active.pos.x, active.pos.z, radius)) active = null;
        visitedThisPass.removeIf(key -> !inRange(ChunkPos.getPackedX(key), ChunkPos.getPackedZ(key), radius));
        List<ChunkPos> order = new ArrayList<>((radius * 2 + 1) * (radius * 2 + 1));
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                ChunkPos pos = new ChunkPos(x + dx, z + dz);
                if (!visitedThisPass.contains(pos.toLong())) order.add(pos);
            }
        }
        order.sort(Comparator.comparingLong(pos -> distanceSquared(pos.x, pos.z)));
        scanOrder = List.copyOf(order);
        publish();
    }

    private boolean inRange(int x, int z, int radius) {
        return Math.abs((long) x - centerX) <= radius && Math.abs((long) z - centerZ) <= radius;
    }

    private long distanceSquared(int x, int z) {
        long dx = (long) x - centerX;
        long dz = (long) z - centerZ;
        return dx * dx + dz * dz;
    }

    private boolean finishScan(ScanSettings settings) {
        long key = active.pos.toLong();
        FoundChunk found = active.result(settings);
        if (found.score() < thresholdForSensitivity(settings.sensitivity())) {
            return detections.remove(key) != null;
        }
        if (!detections.containsKey(key) && detections.size() >= MAX_TARGETS) {
            var farthest = detections.entrySet().stream().max(Comparator.comparingLong(entry -> {
                FoundChunk result = entry.getValue().result();
                return distanceSquared(result.chunkX(), result.chunkZ());
            })).orElseThrow();
            FoundChunk previous = farthest.getValue().result();
            if (distanceSquared(found.chunkX(), found.chunkZ())
                >= distanceSquared(previous.chunkX(), previous.chunkZ())) return false;
            detections.remove(farthest.getKey());
        }
        Detection previous = detections.put(key, new Detection(active.chunk, found));
        return previous == null || !found.equals(previous.result());
    }

    private void publish() {
        List<FoundChunk> found = new ArrayList<>(detections.size());
        for (Detection detection : detections.values()) found.add(detection.result());
        found.sort(Comparator.comparingLong((FoundChunk result) -> distanceSquared(result.chunkX(), result.chunkZ()))
            .thenComparing(Comparator.comparingInt(FoundChunk::score).reversed())
            .thenComparingInt(FoundChunk::chunkX).thenComparingInt(FoundChunk::chunkZ));
        targets = List.copyOf(found);
    }

    // ------------------------------------------------------------------ render

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (mc.world == null || alpha.get() <= 0 || targets.isEmpty()) return;
        SettingColor base = color.get();
        double opacity = alpha.get() / 255.0;
        fillColor.set(base.r, base.g, base.b, (int) Math.round(Math.clamp(opacity * 0.06, 0.0, 1.0) * 255.0));
        lineColor.set(base.r, base.g, base.b, (int) Math.round(Math.clamp(opacity, 0.0, 1.0) * 255.0));

        for (FoundChunk chunk : targets) {
            if (!mc.world.getChunkManager().isChunkLoaded(chunk.chunkX(), chunk.chunkZ())) continue;
            double x = chunk.chunkX() * 16.0, z = chunk.chunkZ() * 16.0;
            for (Layer layer : chunk.layers()) {
                event.renderer.box(x, layer.minY() - .05, z, x + 16, layer.maxY() + 1.05, z + 16, fillColor, lineColor, ShapeMode.Both, 0);
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private record Detection(WorldChunk chunk, FoundChunk result) {}

    private record ScanSettings(int radius, int sensitivity, boolean smart, boolean kelp,
                                boolean caveVines, boolean vines, boolean amethyst,
                                boolean bamboo, boolean cocoa, boolean beeNest) {
        boolean hasFilters() { return kelp || caveVines || vines || amethyst || bamboo || cocoa || beeNest; }

        boolean isCandidate(BlockState state) {
            return (kelp && state.isOf(Blocks.KELP))
                || (caveVines && state.isOf(Blocks.CAVE_VINES))
                || (vines && state.isOf(Blocks.VINE))
                || (amethyst && (state.isOf(Blocks.AMETHYST_BLOCK) || state.isOf(Blocks.BUDDING_AMETHYST)
                    || state.isOf(Blocks.SMALL_AMETHYST_BUD) || state.isOf(Blocks.MEDIUM_AMETHYST_BUD)
                    || state.isOf(Blocks.LARGE_AMETHYST_BUD) || state.isOf(Blocks.AMETHYST_CLUSTER)))
                || (bamboo && state.isOf(Blocks.BAMBOO))
                || (cocoa && state.isOf(Blocks.COCOA))
                || (beeNest && state.isOf(Blocks.BEE_NEST));
        }
    }

    private static final class ScanWork {
        final WorldChunk chunk;
        final ChunkPos pos;
        final int[] points = new int[EvidenceKind.values().length];
        final int[] lowest = new int[EvidenceKind.values().length];
        final int[] highest = new int[EvidenceKind.values().length];
        final BitSet evidenceHeights = new BitSet();
        final BlockPos.Mutable neighbor = new BlockPos.Mutable();
        int sectionIndex;
        int blockIndex;
        boolean sectionStarted;
        boolean amethystGrowth;

        ScanWork(WorldChunk chunk, ChunkPos pos) { this.chunk = chunk; this.pos = pos; }

        FoundChunk result(ScanSettings settings) {
            int total = 0;
            int minY = Integer.MAX_VALUE;
            int maxY = Integer.MIN_VALUE;
            EnumSet<EvidenceKind> kinds = EnumSet.noneOf(EvidenceKind.class);
            for (EvidenceKind kind : EvidenceKind.values()) {
                int index = kind.ordinal();
                if (points[index] == 0) continue;
                int score = points[index];
                // Maximum sensitivity also accepts the first growth stage of a lone bud.
                if (kind == EvidenceKind.AMETHYST && settings.sensitivity() == 5 && amethystGrowth) {
                    score = Math.max(score, thresholdForSensitivity(5));
                }
                total += score;
                minY = Math.min(minY, lowest[index]);
                maxY = Math.max(maxY, highest[index]);
                kinds.add(kind);
            }
            List<Layer> layers = new ArrayList<>();
            int chunkMinY = chunk.getBottomY();
            for (int start = evidenceHeights.nextSetBit(0); start >= 0;) {
                int end = evidenceHeights.nextClearBit(start) - 1;
                layers.add(new Layer(chunkMinY + start, chunkMinY + end));
                start = evidenceHeights.nextSetBit(end + 1);
            }
            return new FoundChunk(pos.x, pos.z, minY, maxY, total, kinds, layers);
        }

        void observe(BlockState state, int x, int y, int z, ScanSettings settings) {
            EvidenceKind kind;
            int strength;
            if (state.isOf(Blocks.KELP)) {
                kind = EvidenceKind.KELP;
                int age = state.get(AbstractPlantStemBlock.AGE);
                // Generated kelp tips start at ages 20..23.
                strength = age < 20 || age == 25 ? 3 : age == 24 ? 1 : settings.smart() ? 0 : 1;
            } else if (state.isOf(Blocks.CAVE_VINES)) {
                kind = EvidenceKind.CAVE_VINES;
                // Ages 23..25 and berries both occur during lush-cave generation.
                strength = state.get(AbstractPlantStemBlock.AGE) < 23 ? 3 : settings.smart() ? 0 : 1;
            } else if (state.isOf(Blocks.VINE)) {
                kind = EvidenceKind.VINES;
                strength = hasConstructedSupport(x, y, z) ? 3 : settings.smart() ? 0 : 1;
            } else if (state.isOf(Blocks.BAMBOO)) {
                kind = EvidenceKind.BAMBOO;
                strength = state.get(BambooBlock.AGE) == 0 ? 3 : settings.smart() ? 0 : 1;
            } else if (state.isOf(Blocks.COCOA)) {
                kind = EvidenceKind.COCOA;
                int age = state.get(CocoaBlock.AGE);
                strength = age == 2 ? 3 : age == 1 ? 1 : settings.smart() ? 0 : 1;
            } else if (state.isOf(Blocks.BEE_NEST)) {
                kind = EvidenceKind.BEE_NEST;
                int honey = state.get(BeehiveBlock.HONEY_LEVEL);
                strength = honey > 0 ? honey + 1 : settings.smart() ? 0 : 1;
            } else {
                kind = EvidenceKind.AMETHYST;
                if (!state.isOf(Blocks.AMETHYST_BLOCK) && !state.isOf(Blocks.BUDDING_AMETHYST)) amethystGrowth = true;
                strength = state.isOf(Blocks.AMETHYST_CLUSTER) ? 4
                    : state.isOf(Blocks.LARGE_AMETHYST_BUD) ? 3
                    : state.isOf(Blocks.MEDIUM_AMETHYST_BUD) ? 2
                    : state.isOf(Blocks.SMALL_AMETHYST_BUD) ? 1 : settings.smart() ? 0 : 1;
            }
            if (strength == 0) return;
            evidenceHeights.set(y - chunk.getBottomY());
            int index = kind.ordinal();
            if (points[index] == 0) {
                lowest[index] = y;
                highest[index] = y;
            }
            points[index] = Math.min(MAX_SCORE_PER_KIND, points[index] + strength);
            lowest[index] = Math.min(lowest[index], y);
            highest[index] = Math.max(highest[index], y);
        }

        private boolean hasConstructedSupport(int x, int y, int z) {
            // Read only this chunk; unknown support across an unloaded boundary is not evidence.
            return (x > 0 && constructed(chunk.getBlockState(neighbor.set(pos.getStartX() + x - 1, y, pos.getStartZ() + z))))
                || (x < 15 && constructed(chunk.getBlockState(neighbor.set(pos.getStartX() + x + 1, y, pos.getStartZ() + z))))
                || (z > 0 && constructed(chunk.getBlockState(neighbor.set(pos.getStartX() + x, y, pos.getStartZ() + z - 1))))
                || (z < 15 && constructed(chunk.getBlockState(neighbor.set(pos.getStartX() + x, y, pos.getStartZ() + z + 1))));
        }

        private static boolean constructed(BlockState state) {
            return isPlanks(state) || state.isOf(Blocks.BRICKS) || state.isOf(Blocks.STONE_BRICKS)
                || state.isOf(Blocks.GLASS) || state.isOf(Blocks.SMOOTH_STONE)
                || state.isOf(Blocks.POLISHED_ANDESITE) || state.isOf(Blocks.POLISHED_DIORITE)
                || state.isOf(Blocks.POLISHED_GRANITE);
        }

        /** Same blocks as the vanilla planks tag. */
        private static boolean isPlanks(BlockState state) {
            return state.isOf(Blocks.OAK_PLANKS) || state.isOf(Blocks.SPRUCE_PLANKS) || state.isOf(Blocks.BIRCH_PLANKS)
                || state.isOf(Blocks.JUNGLE_PLANKS) || state.isOf(Blocks.ACACIA_PLANKS) || state.isOf(Blocks.DARK_OAK_PLANKS)
                || state.isOf(Blocks.PALE_OAK_PLANKS) || state.isOf(Blocks.MANGROVE_PLANKS) || state.isOf(Blocks.CHERRY_PLANKS)
                || state.isOf(Blocks.BAMBOO_PLANKS) || state.isOf(Blocks.CRIMSON_PLANKS) || state.isOf(Blocks.WARPED_PLANKS);
        }
    }
}
