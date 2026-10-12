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
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BarrelBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.block.entity.EnderChestBlockEntity;
import net.minecraft.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
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
 * Base hunting: finds amethyst geodes and tells apart the ones that someone has been near for a long time.
 *
 * Why it works: budding amethyst only grows its crystals while the chunk is loaded and ticking, and on a server
 * like Donut SMP a chunk is only loaded while a player is close. A geode whose buds have all grown into fully grown
 * clusters therefore means a player stayed around for hours (an AFK spot, a base, a farm). The module also looks for
 * player-made blocks and storage next to the geode.
 *
 * Everything is read from chunk data the server already sent, nothing is sent to the server.
 *
 * Tiers: 0 = natural geode, 1 = crystals grown beyond what worldgen places, 2 = very likely player activity.
 */
public class AmethystFinder extends Module {
    private final SettingGroup sgDetection = settings.createGroup("Detection");
    private final SettingGroup sgRender = settings.createGroup("Render");
    private final SettingGroup sgPerformance = settings.createGroup("Performance");

    // -------- detection
    private final Setting<Boolean> overworldOnly = sgDetection.add(new BoolSetting.Builder()
        .name("overworld-only")
        .description("Only scan in the overworld (geodes only generate there).")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showNatural = sgDetection.add(new BoolSetting.Builder()
        .name("show-all-geodes")
        .description("Also mark geodes that look untouched (in their own color). Turn off to see only grown or used ones.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> minBudding = sgDetection.add(new IntSetting.Builder()
        .name("min-budding")
        .description("Minimum budding amethyst blocks in the chunk. Budding amethyst cannot be collected, so it always means a real geode.")
        .defaultValue(1)
        .range(1, 50)
        .sliderRange(1, 20)
        .build()
    );

    private final Setting<Integer> minSize = sgDetection.add(new IntSetting.Builder()
        .name("min-size")
        .description("Minimum amethyst blocks (including budding) in the chunk to count as a geode.")
        .defaultValue(10)
        .range(1, 500)
        .sliderRange(5, 100)
        .build()
    );

    private final Setting<Integer> grownPercent = sgDetection.add(new IntSetting.Builder()
        .name("grown-percent")
        .description("Tier 1: this share (in %) of the open faces of budding amethyst already holds a fully grown cluster. Normal worldgen leaves roughly 10%.")
        .defaultValue(35)
        .range(5, 100)
        .sliderRange(15, 80)
        .build()
    );

    private final Setting<Integer> strongPercent = sgDetection.add(new IntSetting.Builder()
        .name("strong-percent")
        .description("Tier 2: this share (in %) or more is fully grown. That takes hours of the chunk being loaded.")
        .defaultValue(60)
        .range(10, 100)
        .sliderRange(30, 95)
        .build()
    );

    private final Setting<Boolean> sigBlocks = sgDetection.add(new BoolSetting.Builder()
        .name("player-blocks")
        .description("Look for player-made blocks near the geode (cobblestone, ladders, scaffolding, furnaces, crafting tables, glass, wool, concrete, beds...).")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> sigStorage = sgDetection.add(new BoolSetting.Builder()
        .name("storage")
        .description("Look for chests, barrels, ender chests and shulker boxes near the geode.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> nearRange = sgDetection.add(new IntSetting.Builder()
        .name("search-margin")
        .description("How many blocks above and below the geode the player-block search reaches.")
        .defaultValue(10)
        .range(2, 40)
        .sliderRange(4, 24)
        .build()
    );

    // -------- render
    private final Setting<Boolean> render = sgRender.add(new BoolSetting.Builder()
        .name("render")
        .description("Draw a box around chunks with a geode.")
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

    private final Setting<SettingColor> naturalColor = sgRender.add(new ColorSetting.Builder()
        .name("natural-color")
        .description("Geode that looks untouched.")
        .defaultValue(new SettingColor(190, 140, 255, 70))
        .visible(render::get)
        .build()
    );

    private final Setting<SettingColor> grownColor = sgRender.add(new ColorSetting.Builder()
        .name("grown-color")
        .description("Crystals have grown more than worldgen explains (someone was around).")
        .defaultValue(new SettingColor(255, 150, 20, 90))
        .visible(render::get)
        .build()
    );

    private final Setting<SettingColor> activeColor = sgRender.add(new ColorSetting.Builder()
        .name("active-color")
        .description("Very likely player activity: almost fully grown, or player blocks and storage next to it.")
        .defaultValue(new SettingColor(255, 40, 60, 100))
        .visible(render::get)
        .build()
    );

    private final Setting<Boolean> fullHeight = sgRender.add(new BoolSetting.Builder()
        .name("full-height")
        .description("Draw the box over the whole world height instead of only around the geode.")
        .defaultValue(false)
        .visible(render::get)
        .build()
    );

    private final Setting<Boolean> markClusters = sgRender.add(new BoolSetting.Builder()
        .name("mark-grown-clusters")
        .description("Outline every fully grown amethyst cluster in flagged geodes.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> clusterRange = sgRender.add(new IntSetting.Builder()
        .name("cluster-range")
        .description("Only outline clusters closer than this many blocks.")
        .defaultValue(96)
        .range(16, 256)
        .sliderRange(32, 192)
        .visible(markClusters::get)
        .build()
    );

    private final Setting<Integer> maxRender = sgRender.add(new IntSetting.Builder()
        .name("max-rendered")
        .description("Maximum number of chunks drawn (the nearest ones).")
        .defaultValue(40)
        .range(1, 200)
        .sliderRange(5, 100)
        .visible(render::get)
        .build()
    );

    private final Setting<Boolean> tracers = sgRender.add(new BoolSetting.Builder()
        .name("tracers")
        .description("Draw a line from the crosshair to tier 1 and tier 2 geodes.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> chatAlerts = sgRender.add(new BoolSetting.Builder()
        .name("chat-alerts")
        .description("Print a message in chat when a grown or active geode is found.")
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
        .description("Forget geodes further away than this many chunks. 0 keeps them until you leave the world.")
        .defaultValue(0)
        .range(0, 512)
        .sliderRange(0, 128)
        .build()
    );

    // -------- state
    private static final int MAX_CLUSTERS_PER_HIT = 250;

    private static class Hit {
        final int chunkX, chunkZ;
        int tier, minY, maxY;
        int budding, grown, openFaces, percent, playerBlocks, storage;
        List<Long> clusters = new ArrayList<>();
        String reasons;

        Hit(int chunkX, int chunkZ) {
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
        }

        double centerX() { return chunkX * 16 + 8; }
        double centerZ() { return chunkZ * 16 + 8; }
    }

    private final Map<Long, Hit> hits = new HashMap<>();
    private final Map<Long, Integer> announcedTier = new HashMap<>();

    private final Queue<Long> queue = new ConcurrentLinkedQueue<>();
    private final Set<Long> queued = ConcurrentHashMap.newKeySet();

    private static volatile Set<Block> playerBlocks;

    private final Color side = new Color();
    private final Color line = new Color();
    private final Color tracer = new Color();

    public AmethystFinder() {
        super(BlockOutlinesAddon.HUNTING, "amethyst-finder", "Finds amethyst geodes and flags the ones with fully grown crystals or player blocks nearby (signs of someone being around).");
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
        announcedTier.clear();
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

    // ------------------------------------------------------------------ block sets

    private static boolean isAmethystFamily(Block b) {
        return b == Blocks.BUDDING_AMETHYST || b == Blocks.AMETHYST_BLOCK || b == Blocks.AMETHYST_CLUSTER
            || b == Blocks.LARGE_AMETHYST_BUD || b == Blocks.MEDIUM_AMETHYST_BUD || b == Blocks.SMALL_AMETHYST_BUD;
    }

    private static boolean amethystState(BlockState s) {
        return isAmethystFamily(s.getBlock());
    }

    /** Blocks that normal terrain and the usual underground structures do not produce. Built once from the block names. */
    private static Set<Block> playerBlocks() {
        Set<Block> set = playerBlocks;
        if (set != null) return set;

        set = new HashSet<>();
        String[] parts = {
            "cobblestone", "ladder", "scaffolding", "furnace", "smoker", "crafting_table", "hopper", "barrel",
            "shulker_box", "_bed", "concrete", "glass", "wool", "netherrack", "tnt", "_sign", "_door", "campfire",
            "anvil", "enchanting_table", "brewing_stand", "dispenser", "dropper", "repeater", "comparator",
            "piston", "observer", "redstone_lamp", "beacon", "end_stone"
        };
        for (Block b : Registries.BLOCK) {
            String path = Registries.BLOCK.getId(b).getPath();
            if (path.startsWith("mossy_") || path.startsWith("infested_") || path.startsWith("cracked_") || path.contains("stone_bricks")) continue;
            for (String p : parts) {
                if (path.contains(p)) { set.add(b); break; }
            }
        }
        playerBlocks = set;
        return set;
    }

    private static boolean playerState(BlockState s) {
        return playerBlocks().contains(s.getBlock());
    }

    // ------------------------------------------------------------------ events

    @EventHandler
    private void onChunkData(ChunkDataEvent event) {
        enqueue(event.chunk().getPos().toLong());
    }

    @EventHandler
    private void onBlockUpdate(BlockUpdateEvent event) {
        boolean a = isAmethystFamily(event.oldState.getBlock()) || isAmethystFamily(event.newState.getBlock());
        if (!a) {
            // a player block only matters next to a known geode chunk
            long key = new ChunkPos(event.pos).toLong();
            if (!hits.containsKey(key) || !(playerState(event.oldState) || playerState(event.newState))) return;
        }
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
                announcedTier.remove(e.getKey());
                it.remove();
            }
        }
    }

    // ------------------------------------------------------------------ scanning

    private void scan(WorldChunk chunk) {
        ChunkPos pos = chunk.getPos();
        long key = pos.toLong();
        int bottomY = mc.world.getBottomY();

        // pass 1: find the amethyst of this chunk (cheap: sections without any are skipped)
        int budding = 0, amethyst = 0;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        List<int[]> buddingPos = new ArrayList<>();

        ChunkSection[] sections = chunk.getSectionArray();
        for (int i = 0; i < sections.length; i++) {
            ChunkSection section = sections[i];
            if (section == null || section.isEmpty() || !section.hasAny(AmethystFinder::amethystState)) continue;

            int baseY = ((bottomY >> 4) + i) << 4;
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        Block block = section.getBlockState(x, y, z).getBlock();
                        if (!isAmethystFamily(block)) continue;

                        int wy = baseY + y;
                        if (wy < minY) minY = wy;
                        if (wy > maxY) maxY = wy;

                        if (block == Blocks.BUDDING_AMETHYST) {
                            budding++;
                            amethyst++;
                            buddingPos.add(new int[]{pos.getStartX() + x, wy, pos.getStartZ() + z});
                        } else if (block == Blocks.AMETHYST_BLOCK) {
                            amethyst++;
                        }
                    }
                }
            }
        }

        if (budding < minBudding.get() || amethyst < minSize.get()) {
            hits.remove(key);
            announcedTier.remove(key);
            return;
        }

        // pass 2: how far have the crystals grown? Look at every open face of every budding block.
        int grown = 0, large = 0, medium = 0, small = 0, empty = 0;
        Set<Long> seen = new HashSet<>();
        List<Long> clusters = new ArrayList<>();
        BlockPos.Mutable m = new BlockPos.Mutable();
        for (int[] b : buddingPos) {
            for (Direction d : Direction.values()) {
                m.set(b[0] + d.getOffsetX(), b[1] + d.getOffsetY(), b[2] + d.getOffsetZ());
                if (!seen.add(m.asLong())) continue;

                Block nb = mc.world.getBlockState(m).getBlock();
                if (nb == Blocks.AMETHYST_CLUSTER) {
                    grown++;
                    if (clusters.size() < MAX_CLUSTERS_PER_HIT) clusters.add(m.asLong());
                } else if (nb == Blocks.LARGE_AMETHYST_BUD) large++;
                else if (nb == Blocks.MEDIUM_AMETHYST_BUD) medium++;
                else if (nb == Blocks.SMALL_AMETHYST_BUD) small++;
                else if (nb == Blocks.AIR || nb == Blocks.CAVE_AIR || nb == Blocks.WATER) empty++;
            }
        }
        int open = grown + large + medium + small + empty;
        int percent = open >= 6 ? (int) Math.round(100.0 * grown / open) : 0;

        // pass 3: player-made blocks and storage close to the geode (only for chunks that really have one)
        int lo = minY - nearRange.get(), hi = maxY + nearRange.get();
        int manMade = 0, storage = 0;
        if (sigBlocks.get()) {
            for (int i = 0; i < sections.length; i++) {
                ChunkSection section = sections[i];
                if (section == null || section.isEmpty()) continue;
                int baseY = ((bottomY >> 4) + i) << 4;
                if (baseY + 15 < lo || baseY > hi) continue;
                if (!section.hasAny(AmethystFinder::playerState)) continue;

                for (int y = 0; y < 16; y++) {
                    int wy = baseY + y;
                    if (wy < lo || wy > hi) continue;
                    for (int z = 0; z < 16; z++) {
                        for (int x = 0; x < 16; x++) {
                            if (playerState(section.getBlockState(x, y, z))) manMade++;
                        }
                    }
                }
            }
        }
        if (sigStorage.get()) {
            for (BlockEntity be : chunk.getBlockEntities().values()) {
                int weight;
                if (be instanceof ShulkerBoxBlockEntity) weight = 3;
                else if (be instanceof ChestBlockEntity || be instanceof BarrelBlockEntity || be instanceof EnderChestBlockEntity) weight = 1;
                else continue;
                int by = be.getPos().getY();
                if (by < lo || by > hi) continue;
                storage += weight;
            }
        }

        // decide the tier
        List<String> reasons = new ArrayList<>();
        int tier = 0;
        if (percent >= grownPercent.get()) {
            tier = 1;
            reasons.add(percent + "% of the crystals fully grown (" + grown + "/" + open + ")");
        }
        if (percent >= strongPercent.get()) {
            tier = 2;
        }
        if (manMade >= 3) {
            reasons.add("player blocks x" + manMade);
            if (manMade >= 8) tier = Math.max(tier, 2); else tier = Math.max(tier, 1);
        }
        if (storage >= 1) {
            reasons.add("storage x" + storage);
            tier = 2;
        }

        if (tier == 0 && !showNatural.get()) {
            hits.remove(key);
            announcedTier.remove(key);
            return;
        }

        Hit hit = hits.computeIfAbsent(key, k -> new Hit(pos.x, pos.z));
        hit.tier = tier;
        hit.minY = minY;
        hit.maxY = maxY;
        hit.budding = budding;
        hit.grown = grown;
        hit.openFaces = open;
        hit.percent = percent;
        hit.playerBlocks = manMade;
        hit.storage = storage;
        hit.clusters = clusters;
        hit.reasons = reasons.isEmpty() ? "natural geode (" + percent + "% grown)" : String.join(", ", reasons);

        if (tier >= 1 && chatAlerts.get()) {
            Integer before = announcedTier.get(key);
            if (before == null || before < tier) {
                announcedTier.put(key, tier);
                info("%s geode at (highlight)%d, %d(default): %s", tier == 2 ? "Active" : "Grown", (int) hit.centerX(), (int) hit.centerZ(), hit.reasons);
            }
        } else if (tier == 0) {
            announcedTier.remove(key);
        }
    }

    // ------------------------------------------------------------------ render

    private SettingColor colorFor(int tier) {
        return tier >= 2 ? activeColor.get() : tier == 1 ? grownColor.get() : naturalColor.get();
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (hits.isEmpty() || mc.player == null) return;
        if (!render.get() && !tracers.get() && !markClusters.get()) return;

        double px = mc.player.getX(), py = mc.player.getY(), pz = mc.player.getZ();
        List<Hit> list = new ArrayList<>(hits.values());
        list.sort(Comparator.comparingDouble(h -> {
            double dx = h.centerX() - px, dz = h.centerZ() - pz;
            return dx * dx + dz * dz;
        }));

        double range2 = clusterRange.get() * (double) clusterRange.get();
        int shown = 0;
        BlockPos.Mutable bp = new BlockPos.Mutable();

        for (Hit h : list) {
            if (shown++ >= maxRender.get()) break;

            SettingColor c = colorFor(h.tier);
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
                side.set(c.r, c.g, c.b, c.a);
                line.set(c.r, c.g, c.b, (int) Math.min(255, c.a * 2.8));
                event.renderer.box(x1, y1, z1, x1 + 16, y2, z1 + 16, side, line, shapeMode.get(), 0);
            }

            if (tracers.get() && h.tier >= 1) {
                tracer.set(c.r, c.g, c.b, 220);
                event.renderer.line(RenderUtils.center.x, RenderUtils.center.y, RenderUtils.center.z,
                    h.centerX(), (y1 + y2) / 2, h.centerZ(), tracer);
            }

            if (markClusters.get() && h.tier >= 1) {
                side.set(c.r, c.g, c.b, 60);
                line.set(c.r, c.g, c.b, 255);
                for (long packed : h.clusters) {
                    bp.set(packed);
                    double dx = bp.getX() + 0.5 - px, dy = bp.getY() + 0.5 - py, dz = bp.getZ() + 0.5 - pz;
                    if (dx * dx + dy * dy + dz * dz > range2) continue;
                    event.renderer.box(bp, side, line, ShapeMode.Both, 0);
                }
            }
        }
    }
}
