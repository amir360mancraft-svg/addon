package com.blockoutlines.addon.modules;

import com.blockoutlines.addon.BlockOutlinesAddon;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
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
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.render.RenderUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.meteorclient.utils.world.Dimension;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket;
import net.minecraft.network.packet.s2c.play.LightData;
import net.minecraft.network.packet.s2c.play.LightUpdateS2CPacket;
import net.minecraft.util.math.ChunkPos;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Finds chunks with signs of players by reading the LIGHT data the server sends, not the blocks.
 *
 * Some servers hide the blocks of the deep chunk sections, but light is still sent for them. Light does not lie:
 *  - block light underground means something that glows is down there (torches, lanterns, lamps, glowstone, ...)
 *  - sky light that reaches deep sections means a shaft or tunnel is open to the sky
 * The module reads the light packets as they arrive, so it has to be ON when you join or move into new chunks.
 * Chunks that were loaded before you turned it on are not known until the server sends them again.
 *
 * Lava also glows, so by default sections near the lava layer are ignored (min-y). Natural structures with lights
 * (strongholds, ancient cities) can still show up.
 *
 * Suspicious chunks get a light beam: green for a mild sign, red for a strong one.
 */
public class LightChunkFinder extends Module {
    private static final int MAX_SECTIONS = 64;

    // how the light of one section looks in the packet
    private static final byte UNKNOWN = 0, ZERO = 1, FULL = 2, MIXED = 3;

    private final SettingGroup sgDetection = settings.createGroup("Detection");
    private final SettingGroup sgRender = settings.createGroup("Render");
    private final SettingGroup sgDebug = settings.createGroup("Debug");

    // -------- detection
    private final Setting<Boolean> overworldOnly = sgDetection.add(new BoolSetting.Builder()
        .name("overworld-only")
        .description("Only look in the overworld.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> minY = sgDetection.add(new IntSetting.Builder()
        .name("min-y")
        .description("Lowest Y that counts. Lava glows too, so keep this above the lava layer (about -54).")
        .defaultValue(-52)
        .range(-64, 320)
        .sliderRange(-64, 64)
        .build()
    );

    private final Setting<Integer> maxY = sgDetection.add(new IntSetting.Builder()
        .name("max-y")
        .description("Highest Y that counts.")
        .defaultValue(16)
        .range(-64, 320)
        .sliderRange(-64, 128)
        .build()
    );

    private final Setting<Integer> sourceLevel = sgDetection.add(new IntSetting.Builder()
        .name("source-level")
        .description("A block light value at or above this counts as a light source nearby (torch 14, lantern and lamp 15).")
        .defaultValue(12)
        .range(1, 15)
        .sliderRange(8, 15)
        .build()
    );

    private final Setting<Boolean> skyLeak = sgDetection.add(new BoolSetting.Builder()
        .name("sky-light-shafts")
        .description("Also count sky light that reaches several deep sections in a row (a shaft or tunnel open to the sky). Adds a little to the score.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> minScore = sgDetection.add(new IntSetting.Builder()
        .name("min-score")
        .description("Score needed for a green light.")
        .defaultValue(6)
        .range(1, 60)
        .sliderRange(2, 30)
        .build()
    );

    private final Setting<Integer> redScore = sgDetection.add(new IntSetting.Builder()
        .name("red-score")
        .description("Score needed for a red light (a strong sign).")
        .defaultValue(14)
        .range(2, 100)
        .sliderRange(6, 50)
        .build()
    );

    private final Setting<Integer> forgetDistance = sgDetection.add(new IntSetting.Builder()
        .name("forget-distance")
        .description("Forget chunks further away than this many chunks.")
        .defaultValue(40)
        .range(8, 256)
        .sliderRange(8, 128)
        .build()
    );

    // -------- render
    private final Setting<SettingColor> greenColor = sgRender.add(new ColorSetting.Builder()
        .name("green-color")
        .description("Color of a mild sign.")
        .defaultValue(new SettingColor(60, 255, 90, 80))
        .build()
    );

    private final Setting<SettingColor> redColor = sgRender.add(new ColorSetting.Builder()
        .name("red-color")
        .description("Color of a strong sign.")
        .defaultValue(new SettingColor(255, 50, 50, 90))
        .build()
    );

    private final Setting<Boolean> beam = sgRender.add(new BoolSetting.Builder()
        .name("beam")
        .description("Draw a vertical light beam in the middle of the chunk.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> beamWidth = sgRender.add(new DoubleSetting.Builder()
        .name("beam-width")
        .description("Width of the beam in blocks.")
        .defaultValue(2.0)
        .range(0.5, 8.0)
        .sliderRange(0.5, 6.0)
        .visible(beam::get)
        .build()
    );

    private final Setting<Double> beamMinY = sgRender.add(new DoubleSetting.Builder()
        .name("beam-min-y")
        .description("Bottom of the beam.")
        .defaultValue(-64)
        .range(-64, 320)
        .sliderRange(-64, 320)
        .visible(beam::get)
        .build()
    );

    private final Setting<Double> beamMaxY = sgRender.add(new DoubleSetting.Builder()
        .name("beam-max-y")
        .description("Top of the beam.")
        .defaultValue(200)
        .range(-64, 320)
        .sliderRange(-64, 320)
        .visible(beam::get)
        .build()
    );

    private final Setting<Boolean> chunkOutline = sgRender.add(new BoolSetting.Builder()
        .name("chunk-outline")
        .description("Also draw a flat outline of the chunk.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> outlineY = sgRender.add(new DoubleSetting.Builder()
        .name("outline-y")
        .description("Y of the flat chunk outline.")
        .defaultValue(0)
        .range(-64, 320)
        .sliderRange(-64, 320)
        .visible(chunkOutline::get)
        .build()
    );

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How the beam is drawn.")
        .defaultValue(ShapeMode.Both)
        .build()
    );

    private final Setting<Boolean> pulse = sgRender.add(new BoolSetting.Builder()
        .name("pulse")
        .description("Let the light slowly pulse.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> renderRadius = sgRender.add(new IntSetting.Builder()
        .name("render-radius")
        .description("How many chunks away lights are still drawn.")
        .defaultValue(32)
        .range(4, 128)
        .sliderRange(4, 64)
        .build()
    );

    private final Setting<Boolean> tracers = sgRender.add(new BoolSetting.Builder()
        .name("tracers")
        .description("Draw a line to each red chunk.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> chatAlerts = sgRender.add(new BoolSetting.Builder()
        .name("chat-alerts")
        .description("Print a message in chat the first time a chunk is flagged.")
        .defaultValue(true)
        .build()
    );

    // -------- debug
    private final Setting<Boolean> debug = sgDebug.add(new BoolSetting.Builder()
        .name("debug")
        .description("Print how much light data the server sends, every few seconds. Use it to check that the server sends light for the deep sections.")
        .defaultValue(false)
        .build()
    );

    // -------- state
    /** Light profile of one chunk. Never changed after it is stored, a new one replaces it. */
    private static class ChunkLight {
        final byte[] sky = new byte[MAX_SECTIONS];
        final byte[] block = new byte[MAX_SECTIONS];
        final short[] blockPeak = new short[MAX_SECTIONS]; // cells at or above the source level
        final short[] blockCells = new short[MAX_SECTIONS]; // cells above zero

        ChunkLight copy() {
            ChunkLight c = new ChunkLight();
            System.arraycopy(sky, 0, c.sky, 0, MAX_SECTIONS);
            System.arraycopy(block, 0, c.block, 0, MAX_SECTIONS);
            System.arraycopy(blockPeak, 0, c.blockPeak, 0, MAX_SECTIONS);
            System.arraycopy(blockCells, 0, c.blockCells, 0, MAX_SECTIONS);
            return c;
        }
    }

    private static class Hit {
        final int cx, cz;
        int score;
        String reasons;

        Hit(int cx, int cz) {
            this.cx = cx;
            this.cz = cz;
        }
    }

    // written by the network thread, read by the game thread
    private final Map<Long, ChunkLight> lights = new ConcurrentHashMap<>();

    private final Map<Long, Hit> hits = new HashMap<>();
    private final Set<Long> announced = new HashSet<>();

    private volatile long packets;
    private int tickCount;

    private final Color side = new Color();
    private final Color line = new Color();
    private final Color tracer = new Color();

    public LightChunkFinder() {
        super(BlockOutlinesAddon.HUNTING, "light-chunk-finder", "Reads the light data the server sends to find chunks with lights or shafts underground. Shows a green or red light on suspicious chunks.");
    }

    @Override
    public void onActivate() {
        lights.clear();
        hits.clear();
        announced.clear();
        packets = 0;
    }

    @Override
    public void onDeactivate() {
        lights.clear();
        hits.clear();
        announced.clear();
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        lights.clear();
        hits.clear();
        announced.clear();
    }

    @Override
    public String getInfoString() {
        return hits.isEmpty() ? null : Integer.toString(hits.size());
    }

    // ------------------------------------------------------------------ packets (network thread)

    @EventHandler
    private void onPacket(PacketEvent.Receive event) {
        if (event.packet instanceof ChunkDataS2CPacket p) {
            ingest(p.getChunkX(), p.getChunkZ(), p.getLightData());
        } else if (event.packet instanceof LightUpdateS2CPacket p) {
            ingest(p.getChunkX(), p.getChunkZ(), p.getData());
        }
    }

    private void ingest(int cx, int cz, LightData data) {
        if (data == null) return;
        packets++;

        long key = ChunkPos.toLong(cx, cz);
        ChunkLight old = lights.get(key);
        ChunkLight cl = old == null ? new ChunkLight() : old.copy();

        // block light: peaks and cell counts are measured, sky light only needs its kind
        track(data.getInitedBlock(), data.getUninitedBlock(), data.getBlockNibbles(), cl, true);
        track(data.getInitedSky(), data.getUninitedSky(), data.getSkyNibbles(), cl, false);

        lights.put(key, cl);
    }

    private void track(BitSet inited, BitSet uninited, List<byte[]> nibbles, ChunkLight cl, boolean block) {
        byte[] kinds = block ? cl.block : cl.sky;
        int level = sourceLevel.get();

        // sections that are known to be completely dark
        for (int i = uninited.nextSetBit(0); i >= 0 && i < MAX_SECTIONS; i = uninited.nextSetBit(i + 1)) {
            kinds[i] = ZERO;
            if (block) {
                cl.blockPeak[i] = 0;
                cl.blockCells[i] = 0;
            }
        }

        // sections that came with a light array, in order
        int idx = 0;
        for (int i = inited.nextSetBit(0); i >= 0; i = inited.nextSetBit(i + 1)) {
            if (idx >= nibbles.size()) break;
            byte[] arr = nibbles.get(idx++);
            if (i >= MAX_SECTIONS || arr == null || arr.length != 2048) continue;

            boolean allZero = true, allFull = true;
            int cells = 0, peak = 0;
            for (byte b : arr) {
                int lo = b & 15, hi = (b >> 4) & 15;
                if (b != 0) allZero = false;
                if (b != (byte) 0xFF) allFull = false;
                if (block) {
                    if (lo > 0) cells++;
                    if (hi > 0) cells++;
                    if (lo >= level) peak++;
                    if (hi >= level) peak++;
                }
            }

            kinds[i] = allZero ? ZERO : allFull ? FULL : MIXED;
            if (block) {
                cl.blockPeak[i] = (short) Math.min(peak, Short.MAX_VALUE);
                cl.blockCells[i] = (short) Math.min(cells, Short.MAX_VALUE);
            }
        }
    }

    // ------------------------------------------------------------------ scoring (game thread)

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null || mc.player == null) return;
        if (overworldOnly.get() && PlayerUtils.getDimension() != Dimension.Overworld) {
            if (!hits.isEmpty()) hits.clear();
            return;
        }

        if (++tickCount % 20 != 0) return;
        sweep();

        if (debug.get() && tickCount % 100 == 0) printDebug();
    }

    private void sweep() {
        int bottomCoord = mc.world.getBottomY() >> 4;
        int pcx = mc.player.getBlockX() >> 4, pcz = mc.player.getBlockZ() >> 4;
        int forget = forgetDistance.get();

        // forget far chunks
        lights.keySet().removeIf(k -> Math.max(Math.abs(ChunkPos.getPackedX(k) - pcx), Math.abs(ChunkPos.getPackedZ(k) - pcz)) > forget);

        Set<Long> seen = new HashSet<>();
        for (Map.Entry<Long, ChunkLight> e : lights.entrySet()) {
            long key = e.getKey();
            ChunkLight cl = e.getValue();
            seen.add(key);

            int blockSections = 0, sourcePoints = 0, skySections = 0;
            for (int i = 0; i < MAX_SECTIONS; i++) {
                // light section 0 is one section below the world
                int sectionY = bottomCoord - 1 + i;
                int lo = sectionY * 16, hi = lo + 15;
                if (hi < minY.get() || lo > maxY.get()) continue;

                if (cl.block[i] == MIXED && cl.blockPeak[i] > 0) {
                    blockSections++;
                    sourcePoints += Math.min(cl.blockPeak[i], 8);
                }
                if (skyLeak.get() && cl.sky[i] == MIXED) skySections++;
            }

            List<String> reasons = new ArrayList<>();
            int score = 0;
            if (blockSections > 0) {
                score += Math.min(blockSections, 5) * 2 + Math.min(sourcePoints, 20);
                reasons.add("light sources in " + blockSections + " section(s)");
            }
            if (skySections >= 2) {
                score += Math.min(skySections, 4);
                reasons.add("sky light in " + skySections + " deep sections");
            }

            if (score < minScore.get()) {
                hits.remove(key);
                announced.remove(key);
                continue;
            }

            Hit hit = hits.computeIfAbsent(key, k -> new Hit(ChunkPos.getPackedX(k), ChunkPos.getPackedZ(k)));
            hit.score = score;
            hit.reasons = String.join(", ", reasons);

            if (announced.add(key) && chatAlerts.get()) {
                info("%s light at (highlight)%d, %d(default) with score %d: %s", score >= redScore.get() ? "Red" : "Green", hit.cx * 16 + 8, hit.cz * 16 + 8, score, hit.reasons);
            }
        }

        hits.keySet().removeIf(k -> !seen.contains(k));
        announced.removeIf(k -> !hits.containsKey(k));
    }

    private void printDebug() {
        int bottomCoord = mc.world.getBottomY() >> 4;
        int chunks = 0, deepBlock = 0, deepSky = 0, deepKnown = 0;
        for (ChunkLight cl : lights.values()) {
            chunks++;
            for (int i = 0; i < MAX_SECTIONS; i++) {
                int hi = (bottomCoord - 1 + i) * 16 + 15;
                if (hi >= minY.get() && hi - 15 <= maxY.get()) {
                    if (cl.block[i] != UNKNOWN || cl.sky[i] != UNKNOWN) deepKnown++;
                    if (cl.block[i] == MIXED) deepBlock++;
                    if (cl.sky[i] == MIXED) deepSky++;
                }
            }
        }
        info("Light data: %d packets, %d chunks, %d sections in range with data, %d with block light, %d with sky light mix, %d flagged.", packets, chunks, deepKnown, deepBlock, deepSky, hits.size());
    }

    // ------------------------------------------------------------------ render

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (hits.isEmpty() || mc.player == null) return;

        int pcx = mc.player.getBlockX() >> 4, pcz = mc.player.getBlockZ() >> 4;
        int radius = renderRadius.get();
        double t = System.nanoTime() / 1.0e9;
        double half = beamWidth.get() / 2;

        for (Hit h : hits.values()) {
            if (Math.max(Math.abs(h.cx - pcx), Math.abs(h.cz - pcz)) > radius) continue;

            boolean red = h.score >= redScore.get();
            SettingColor base = red ? redColor.get() : greenColor.get();
            double wave = pulse.get() ? 0.7 + 0.3 * Math.sin(t * 2.5 + (h.cx + h.cz) * 0.5) : 1.0;
            side.set(base.r, base.g, base.b, (int) Math.min(255, base.a * wave));
            line.set(base.r, base.g, base.b, (int) (255 * (0.75 + 0.25 * wave)));

            double cxm = h.cx * 16 + 8, czm = h.cz * 16 + 8;

            if (beam.get()) {
                event.renderer.box(cxm - half, beamMinY.get(), czm - half, cxm + half, beamMaxY.get(), czm + half, side, line, shapeMode.get(), 0);
            }

            if (chunkOutline.get()) {
                double x1 = h.cx * 16, z1 = h.cz * 16, y = outlineY.get();
                event.renderer.box(x1, y, z1, x1 + 16, y + 0.1, z1 + 16, side, line, ShapeMode.Both, 0);
            }

            if (tracers.get() && red) {
                tracer.set(base.r, base.g, base.b, 220);
                event.renderer.line(RenderUtils.center.x, RenderUtils.center.y, RenderUtils.center.z, cxm, (beamMinY.get() + beamMaxY.get()) / 2, czm, tracer);
            }
        }
    }
}
