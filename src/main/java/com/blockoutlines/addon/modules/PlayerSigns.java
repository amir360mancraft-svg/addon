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
import net.minecraft.block.CropBlock;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Finds signs of players that can be seen without looking underground, for servers that only send the lower chunk
 * sections when you get close. It looks at what is actually loaded:
 *  - grown crops and farmland (someone planted and waited)
 *  - torches, lanterns, campfires and candles
 *  - building blocks (planks, glass, doors, fences, wool, concrete)
 *  - chests, barrels, shulker boxes, and machines (hoppers, furnaces, pistons, ...)
 *  - beds, signs, banners, bookshelves
 *  - entities: animal pens, villager groups, item frames and armor stands, dropped items, named mobs
 * Only the found blocks and entities are highlighted, not whole chunks. A chunk shows up once its signs add up to
 * the minimum score, so a single torch is ignored. Chunks near a village bell are skipped.
 */
public class PlayerSigns extends Module {
    private enum Cat { NONE, GROWN, FARM, LIGHT, BUILD, STORAGE, MACHINE, INTERIOR, BELL }

    private static final int CATS = Cat.values().length;
    private static final int LIST_CAP = 64;

    // points per counted sign, and how many signs count at most
    private static final int[] WEIGHT = new int[CATS];
    private static final int[] CAP = new int[CATS];

    static {
        set(Cat.GROWN, 1, 12);
        set(Cat.FARM, 1, 8);
        set(Cat.LIGHT, 2, 6);
        set(Cat.BUILD, 1, 10);
        set(Cat.STORAGE, 3, 4);
        set(Cat.MACHINE, 3, 4);
        set(Cat.INTERIOR, 2, 4);
    }

    private static void set(Cat c, int weight, int cap) {
        WEIGHT[c.ordinal()] = weight;
        CAP[c.ordinal()] = cap;
    }

    private static final Set<String> MACHINES = Set.of(
        "hopper", "furnace", "blast_furnace", "smoker", "crafting_table", "dispenser", "dropper", "observer",
        "piston", "sticky_piston", "comparator", "repeater", "redstone_lamp", "tnt", "anvil", "chipped_anvil",
        "damaged_anvil", "enchanting_table", "brewing_stand", "beacon", "smithing_table", "grindstone", "loom",
        "cartography_table", "fletching_table", "stonecutter", "composter", "lectern", "jukebox", "note_block"
    );

    private final SettingGroup sgDetection = settings.createGroup("Detection");
    private final SettingGroup sgBlocks = settings.createGroup("Block signs");
    private final SettingGroup sgEntities = settings.createGroup("Entity signs");
    private final SettingGroup sgRender = settings.createGroup("Render");
    private final SettingGroup sgPerformance = settings.createGroup("Performance");

    // -------- detection
    private final Setting<Boolean> overworldOnly = sgDetection.add(new BoolSetting.Builder()
        .name("overworld-only")
        .description("Only look in the overworld.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> minScore = sgDetection.add(new IntSetting.Builder()
        .name("min-score")
        .description("Points a chunk needs before its signs are shown. Higher means fewer, surer results.")
        .defaultValue(10)
        .range(1, 100)
        .sliderRange(3, 40)
        .build()
    );

    private final Setting<Boolean> ignoreVillages = sgDetection.add(new BoolSetting.Builder()
        .name("ignore-villages")
        .description("Skip chunks close to a village bell, since villages have farms, beds and torches by themselves.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> villageRadius = sgDetection.add(new IntSetting.Builder()
        .name("village-radius")
        .description("How many chunks around a bell count as village.")
        .defaultValue(5)
        .range(1, 12)
        .sliderRange(1, 10)
        .visible(ignoreVillages::get)
        .build()
    );

    // -------- block signs
    private final Setting<Boolean> sigGrown = sgBlocks.add(new BoolSetting.Builder()
        .name("grown-crops")
        .description("Fully grown wheat, carrots, potatoes, beetroot and similar.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> sigFarm = sgBlocks.add(new BoolSetting.Builder()
        .name("farmland")
        .description("Farmland and young crops.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> sigLight = sgBlocks.add(new BoolSetting.Builder()
        .name("lights")
        .description("Torches, lanterns, campfires and candles.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> sigBuild = sgBlocks.add(new BoolSetting.Builder()
        .name("building-blocks")
        .description("Planks, fences, doors, trapdoors, glass, wool, concrete and bricks.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> sigStorage = sgBlocks.add(new BoolSetting.Builder()
        .name("storage")
        .description("Chests, barrels, ender chests and shulker boxes.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> sigMachine = sgBlocks.add(new BoolSetting.Builder()
        .name("machines")
        .description("Hoppers, furnaces, pistons, dispensers, crafting tables and similar.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> sigInterior = sgBlocks.add(new BoolSetting.Builder()
        .name("interior")
        .description("Beds, signs, banners, bookshelves and ladders.")
        .defaultValue(true)
        .build()
    );

    // -------- entity signs
    private final Setting<Boolean> sigAnimals = sgEntities.add(new BoolSetting.Builder()
        .name("animal-pens")
        .description("Six or more animals in one chunk.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> sigVillagers = sgEntities.add(new BoolSetting.Builder()
        .name("villagers")
        .description("Three or more villagers in one chunk (trading halls).")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> sigFrames = sgEntities.add(new BoolSetting.Builder()
        .name("frames-and-stands")
        .description("Two or more item frames and armor stands in one chunk.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> sigItems = sgEntities.add(new BoolSetting.Builder()
        .name("dropped-items")
        .description("Five or more dropped items in one chunk.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> sigNamed = sgEntities.add(new BoolSetting.Builder()
        .name("named-mobs")
        .description("Mobs with a name tag.")
        .defaultValue(true)
        .build()
    );

    // -------- render
    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How the highlights are drawn.")
        .defaultValue(ShapeMode.Both)
        .build()
    );

    private final Setting<SettingColor> grownColor = sgRender.add(new ColorSetting.Builder()
        .name("grown-color")
        .description("Color of grown crops.")
        .defaultValue(new SettingColor(60, 220, 60, 70))
        .build()
    );

    private final Setting<SettingColor> buildingColor = sgRender.add(new ColorSetting.Builder()
        .name("building-color")
        .description("Color of farmland, lights, building blocks and interior blocks.")
        .defaultValue(new SettingColor(255, 150, 0, 60))
        .build()
    );

    private final Setting<SettingColor> storageColor = sgRender.add(new ColorSetting.Builder()
        .name("storage-color")
        .description("Color of storage and machines.")
        .defaultValue(new SettingColor(255, 230, 0, 80))
        .build()
    );

    private final Setting<SettingColor> entityColor = sgRender.add(new ColorSetting.Builder()
        .name("entity-color")
        .description("Color of entities.")
        .defaultValue(new SettingColor(0, 200, 255, 60))
        .build()
    );

    private final Setting<Integer> maxBlocks = sgRender.add(new IntSetting.Builder()
        .name("max-blocks")
        .description("Most block highlights drawn at once (the nearest ones).")
        .defaultValue(400)
        .range(10, 3000)
        .sliderRange(50, 1000)
        .build()
    );

    private final Setting<Integer> maxEntities = sgRender.add(new IntSetting.Builder()
        .name("max-entities")
        .description("Most entity highlights drawn at once.")
        .defaultValue(100)
        .range(5, 500)
        .sliderRange(10, 200)
        .build()
    );

    private final Setting<Boolean> tracers = sgRender.add(new BoolSetting.Builder()
        .name("tracers")
        .description("Draw a line to the middle of each flagged chunk's signs.")
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

    // -------- state
    private static class LongList {
        long[] a = new long[8];
        int size;

        void add(long v) {
            if (size >= LIST_CAP) return;
            if (size == a.length) a = java.util.Arrays.copyOf(a, size * 2);
            a[size++] = v;
        }
    }

    private static class ChunkData {
        final int[] counts = new int[CATS];
        final LongList[] lists = new LongList[CATS];

        void add(Cat c, long pos) {
            int i = c.ordinal();
            counts[i]++;
            if (lists[i] == null) lists[i] = new LongList();
            lists[i].add(pos);
        }
    }

    private enum Ent { ANIMAL, VILLAGER, FRAME, ITEM, NAMED }

    private static class EntityData {
        final int[] counts = new int[Ent.values().length];
        @SuppressWarnings("unchecked")
        final List<Entity>[] lists = new List[Ent.values().length];

        void add(Ent t, Entity e) {
            int i = t.ordinal();
            counts[i]++;
            if (lists[i] == null) lists[i] = new ArrayList<>();
            lists[i].add(e);
        }
    }

    private record BlockSign(BlockPos pos, Cat cat) {}
    private record EntitySign(Entity entity) {}

    private final Map<Block, Cat> catCache = new ConcurrentHashMap<>();
    private final Map<Long, ChunkData> data = new HashMap<>();
    private final Set<Long> bells = new HashSet<>();
    private final Set<Long> announced = new HashSet<>();

    // the chunk data event comes from the network thread; scanning stays on the game thread
    private final Queue<Long> queue = new ConcurrentLinkedQueue<>();
    private final Set<Long> queued = ConcurrentHashMap.newKeySet();

    private List<BlockSign> blockSigns = new ArrayList<>();
    private List<EntitySign> entitySigns = new ArrayList<>();
    private List<Vec3d> anchors = new ArrayList<>();
    private int flaggedCount;
    private int tickCount;

    private final Color side = new Color();
    private final Color line = new Color();
    private final Color tracer = new Color();

    public PlayerSigns() {
        super(BlockOutlinesAddon.HUNTING, "player-signs", "Highlights signs of players that are visible without digging: grown crops, torches, builds, storage, animal pens and more.");
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
        data.clear();
        bells.clear();
        announced.clear();
        queue.clear();
        queued.clear();
        blockSigns = new ArrayList<>();
        entitySigns = new ArrayList<>();
        anchors = new ArrayList<>();
        flaggedCount = 0;
    }

    private void enqueue(long key) {
        if (queued.add(key)) queue.add(key);
    }

    @Override
    public String getInfoString() {
        return flaggedCount == 0 ? null : Integer.toString(flaggedCount);
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
        String p = Registries.BLOCK.getId(block).getPath();

        if (p.equals("bell")) return Cat.BELL;
        if (block instanceof CropBlock || p.equals("farmland")) return Cat.FARM;

        if (p.equals("chest") || p.equals("trapped_chest") || p.equals("barrel") || p.equals("ender_chest") || p.endsWith("shulker_box")) return Cat.STORAGE;
        if (MACHINES.contains(p)) return Cat.MACHINE;

        if (p.contains("torch") || p.equals("campfire") || p.equals("soul_campfire") || p.endsWith("candle")) return Cat.LIGHT;
        if (p.endsWith("lantern") && !p.equals("sea_lantern")) return Cat.LIGHT;

        if (p.endsWith("_bed") || p.endsWith("_sign") || p.endsWith("_banner") || p.contains("bookshelf") || p.equals("ladder") || p.equals("scaffolding")) return Cat.INTERIOR;

        if (p.endsWith("_planks") || p.endsWith("_fence") || p.endsWith("_fence_gate") || p.endsWith("_door") || p.endsWith("_trapdoor")
            || p.endsWith("_wool") || p.endsWith("_concrete") || p.endsWith("_glazed_terracotta") || p.contains("glass") || p.equals("bricks")) return Cat.BUILD;

        return Cat.NONE;
    }

    private boolean interesting(BlockState state) {
        return classify(state.getBlock()) != Cat.NONE;
    }

    private boolean enabled(Cat c) {
        return switch (c) {
            case GROWN -> sigGrown.get();
            case FARM -> sigFarm.get();
            case LIGHT -> sigLight.get();
            case BUILD -> sigBuild.get();
            case STORAGE -> sigStorage.get();
            case MACHINE -> sigMachine.get();
            case INTERIOR -> sigInterior.get();
            default -> false;
        };
    }

    // ------------------------------------------------------------------ events

    @EventHandler
    private void onChunkData(ChunkDataEvent event) {
        enqueue(event.chunk().getPos().toLong());
    }

    @EventHandler
    private void onBlockUpdate(BlockUpdateEvent event) {
        if (classify(event.oldState.getBlock()) == Cat.NONE && classify(event.newState.getBlock()) == Cat.NONE) return;
        enqueue(new ChunkPos(event.pos).toLong());
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null || mc.player == null) return;
        if (overworldOnly.get() && PlayerUtils.getDimension() != Dimension.Overworld) {
            if (flaggedCount > 0 || !data.isEmpty()) clearAll();
            return;
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

        if (++tickCount % 10 == 0) sweep();
    }

    // ------------------------------------------------------------------ scanning

    private void scan(WorldChunk chunk) {
        ChunkPos pos = chunk.getPos();
        int startX = pos.getStartX(), startZ = pos.getStartZ();
        int bottomY = mc.world.getBottomY();
        ChunkData d = new ChunkData();

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
                        Cat c = classify(block);
                        if (c == Cat.NONE) continue;

                        if (block instanceof CropBlock crop && crop.isMature(state)) c = Cat.GROWN;
                        d.add(c, BlockPos.asLong(startX + x, baseY + y, startZ + z));
                    }
                }
            }
        }

        long key = pos.toLong();
        if (d.counts[Cat.BELL.ordinal()] > 0) bells.add(key);
        else bells.remove(key);
        data.put(key, d);
    }

    private boolean nearVillage(long key) {
        if (!ignoreVillages.get() || bells.isEmpty()) return false;
        int cx = ChunkPos.getPackedX(key), cz = ChunkPos.getPackedZ(key);
        int r = villageRadius.get();
        for (long b : bells) {
            if (Math.abs(ChunkPos.getPackedX(b) - cx) <= r && Math.abs(ChunkPos.getPackedZ(b) - cz) <= r) return true;
        }
        return false;
    }

    private Ent entityType(Entity e) {
        if (e instanceof PlayerEntity) return null;
        if (e instanceof ItemEntity) return Ent.ITEM;
        if (e instanceof ItemFrameEntity || e instanceof ArmorStandEntity) return Ent.FRAME;
        if (e instanceof VillagerEntity) return Ent.VILLAGER;
        if (e instanceof AnimalEntity) return Ent.ANIMAL;
        return null;
    }

    /** Re-reads the entities, scores every chunk and rebuilds the lists that are drawn. */
    private void sweep() {
        // forget chunks that are no longer loaded
        data.keySet().removeIf(k -> !mc.world.getChunkManager().isChunkLoaded(ChunkPos.getPackedX(k), ChunkPos.getPackedZ(k)));
        bells.removeIf(k -> !data.containsKey(k));
        announced.removeIf(k -> !data.containsKey(k));

        Map<Long, EntityData> ents = new HashMap<>();
        for (Entity e : mc.world.getEntities()) {
            if (e instanceof PlayerEntity) continue;
            long key = e.getChunkPos().toLong();
            Ent t = entityType(e);
            if (t != null) ents.computeIfAbsent(key, k -> new EntityData()).add(t, e);
            if (e.hasCustomName()) ents.computeIfAbsent(key, k -> new EntityData()).add(Ent.NAMED, e);
        }

        Set<Long> keys = new HashSet<>(data.keySet());
        keys.addAll(ents.keySet());

        double px = mc.player.getX(), py = mc.player.getY(), pz = mc.player.getZ();
        List<BlockSign> blocks = new ArrayList<>();
        List<EntitySign> entities = new ArrayList<>();
        List<Vec3d> anchorList = new ArrayList<>();
        int flagged = 0;

        for (long key : keys) {
            if (nearVillage(key)) continue;

            ChunkData cd = data.get(key);
            EntityData ed = ents.get(key);

            int score = 0;
            List<String> reasons = new ArrayList<>();

            if (cd != null) {
                for (Cat c : Cat.values()) {
                    if (!enabled(c)) continue;
                    int n = cd.counts[c.ordinal()];
                    if (n == 0) continue;
                    score += Math.min(n, CAP[c.ordinal()]) * WEIGHT[c.ordinal()];
                    reasons.add(label(c) + " x" + n);
                }
            }

            boolean showAnimals = false, showVillagers = false, showFrames = false, showItems = false, showNamed = false;
            if (ed != null) {
                int animals = ed.counts[Ent.ANIMAL.ordinal()];
                int villagers = ed.counts[Ent.VILLAGER.ordinal()];
                int frames = ed.counts[Ent.FRAME.ordinal()];
                int items = ed.counts[Ent.ITEM.ordinal()];
                int named = ed.counts[Ent.NAMED.ordinal()];

                if (sigAnimals.get() && animals >= 6) { showAnimals = true; score += 4; reasons.add("animals x" + animals); }
                if (sigVillagers.get() && villagers >= 3) { showVillagers = true; score += 5; reasons.add("villagers x" + villagers); }
                if (sigFrames.get() && frames >= 2) { showFrames = true; score += 3; reasons.add("frames/stands x" + frames); }
                if (sigItems.get() && items >= 5) { showItems = true; score += 2; reasons.add("dropped items x" + items); }
                if (sigNamed.get() && named >= 1) { showNamed = true; score += 3; reasons.add("named mobs x" + named); }
            }

            if (score < minScore.get()) {
                announced.remove(key);
                continue;
            }

            flagged++;
            int cx = ChunkPos.getPackedX(key), cz = ChunkPos.getPackedZ(key);
            if (announced.add(key) && chatAlerts.get()) {
                info("Player signs at (highlight)%d, %d(default) (score %d): %s", cx * 16 + 8, cz * 16 + 8, score, String.join(", ", reasons));
            }

            double sx = 0, sy = 0, sz = 0;
            int bn = 0;

            if (cd != null) {
                for (Cat c : Cat.values()) {
                    if (!enabled(c) || cd.lists[c.ordinal()] == null) continue;
                    LongList l = cd.lists[c.ordinal()];
                    for (int i = 0; i < l.size; i++) {
                        BlockPos bp = BlockPos.fromLong(l.a[i]);
                        blocks.add(new BlockSign(bp, c));
                        sx += bp.getX() + 0.5;
                        sy += bp.getY() + 0.5;
                        sz += bp.getZ() + 0.5;
                        bn++;
                    }
                }
            }

            if (ed != null) {
                if (showAnimals) addEntities(entities, ed.lists[Ent.ANIMAL.ordinal()]);
                if (showVillagers) addEntities(entities, ed.lists[Ent.VILLAGER.ordinal()]);
                if (showFrames) addEntities(entities, ed.lists[Ent.FRAME.ordinal()]);
                if (showItems) addEntities(entities, ed.lists[Ent.ITEM.ordinal()]);
                if (showNamed) addEntities(entities, ed.lists[Ent.NAMED.ordinal()]);
            }

            // the anchor is roughly the middle of the chunk's block signs, only used for tracers
            anchorList.add(bn > 0 ? new Vec3d(sx / bn, sy / bn, sz / bn) : new Vec3d(cx * 16 + 8, py, cz * 16 + 8));
        }

        // nearest first, then cap
        blocks.sort(Comparator.comparingDouble(b -> dist2(b.pos().getX() + 0.5, b.pos().getY() + 0.5, b.pos().getZ() + 0.5, px, py, pz)));
        entities.sort(Comparator.comparingDouble(s -> dist2(s.entity().getX(), s.entity().getY(), s.entity().getZ(), px, py, pz)));

        blockSigns = blocks.size() > maxBlocks.get() ? new ArrayList<>(blocks.subList(0, maxBlocks.get())) : blocks;
        entitySigns = entities.size() > maxEntities.get() ? new ArrayList<>(entities.subList(0, maxEntities.get())) : entities;
        anchors = anchorList;
        flaggedCount = flagged;
    }

    private static void addEntities(List<EntitySign> out, List<Entity> list) {
        if (list == null) return;
        for (Entity e : list) out.add(new EntitySign(e));
    }

    private static double dist2(double x, double y, double z, double px, double py, double pz) {
        double dx = x - px, dy = y - py, dz = z - pz;
        return dx * dx + dy * dy + dz * dz;
    }

    private static String label(Cat c) {
        return switch (c) {
            case GROWN -> "grown crops";
            case FARM -> "farmland";
            case LIGHT -> "lights";
            case BUILD -> "building blocks";
            case STORAGE -> "storage";
            case MACHINE -> "machines";
            case INTERIOR -> "interior";
            default -> c.name().toLowerCase();
        };
    }

    // ------------------------------------------------------------------ render

    private Color colorFor(Cat c) {
        return switch (c) {
            case GROWN -> grownColor.get();
            case STORAGE, MACHINE -> storageColor.get();
            default -> buildingColor.get();
        };
    }

    private void setColors(Color base) {
        side.set(base);
        line.set(base.r, base.g, base.b, 255);
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (mc.player == null) return;

        for (BlockSign s : blockSigns) {
            setColors(colorFor(s.cat()));
            event.renderer.box(s.pos(), side, line, shapeMode.get(), 0);
        }

        if (!entitySigns.isEmpty()) {
            setColors(entityColor.get());
            for (EntitySign s : entitySigns) {
                Entity e = s.entity();
                if (e.isRemoved()) continue;
                Vec3d p = e.getLerpedPos(event.tickDelta);
                Box box = e.getBoundingBox().offset(p.x - e.getX(), p.y - e.getY(), p.z - e.getZ());
                event.renderer.box(box, side, line, shapeMode.get(), 0);
            }
        }

        if (tracers.get()) {
            tracer.set(tracerColor.get());
            for (Vec3d a : anchors) {
                event.renderer.line(RenderUtils.center.x, RenderUtils.center.y, RenderUtils.center.z, a.x, a.y, a.z, tracer);
            }
        }
    }
}
