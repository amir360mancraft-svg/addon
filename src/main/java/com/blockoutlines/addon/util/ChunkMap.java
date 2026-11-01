package com.blockoutlines.addon.util;

import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Remembers what every chunk you have had loaded looks like from above (one colour per block, per dimension),
 * so the big map can show everything you have explored, not only what is loaded right now. Kept while the game runs.
 */
public final class ChunkMap {
    private ChunkMap() {}

    public static final class Chunk {
        public int[] colors;        // 16 x 16 colours, row by row (z * 16 + x)
        public int avg;
        public long time;
    }

    private static final Map<String, Map<Long, Chunk>> DIMS = new HashMap<>();
    private static final int MAX_CHUNKS = 60000;
    private static List<int[]> spiral = new ArrayList<>();
    private static int spiralRadius = -1;
    private static long lastTick;
    /** Goes up whenever a chunk is added or refreshed (the big map repaints then). */
    public static int version;

    public static long key(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    public static Map<Long, Chunk> current() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null) return new HashMap<>();
        return DIMS.computeIfAbsent(mc.world.getRegistryKey().getValue().toString(), k -> new HashMap<>());
    }

    public static void clearCurrent() {
        current().clear();
    }

    private static void buildSpiral(int radius) {
        spiral = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) spiral.add(new int[]{dx, dz});
        spiral.sort(Comparator.comparingInt(o -> o[0] * o[0] + o[1] * o[1]));
        spiralRadius = radius;
    }

    /** Called every frame; looks at a few loaded chunks each time (new ones first, old ones again after a while). */
    public static void tick() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null || mc.player == null) return;
        long now = System.currentTimeMillis();
        if (now - lastTick < 100) return;
        lastTick = now;

        int radius = Math.max(2, Math.min(32, mc.options.getViewDistance().getValue()));
        if (radius != spiralRadius) buildSpiral(radius);
        Map<Long, Chunk> map = current();
        int pcx = mc.player.getBlockX() >> 4, pcz = mc.player.getBlockZ() >> 4;
        int budget = 3;
        for (int[] o : spiral) {
            int cx = pcx + o[0], cz = pcz + o[1];
            if (!mc.world.getChunkManager().isChunkLoaded(cx, cz)) continue;
            Chunk c = map.get(key(cx, cz));
            int dist = Math.max(Math.abs(o[0]), Math.abs(o[1]));
            long need = dist <= 3 ? 4000 : dist <= 8 ? 15000 : 60000;
            if (c != null && now - c.time < need) continue;
            if (c == null && map.size() >= MAX_CHUNKS) continue;
            map.put(key(cx, cz), scan(mc, cx, cz, now));
            version++;
            if (--budget <= 0) break;
        }
    }

    private static int base(MinecraftClient mc, BlockState st, BlockPos pos) {
        int c = st.getMapColor(mc.world, pos).color;
        return c == 0 ? 0x484848 : c;
    }

    private static int shade(int rgb, float f) {
        int r = Math.min(255, Math.max(0, Math.round(((rgb >> 16) & 255) * f)));
        int g = Math.min(255, Math.max(0, Math.round(((rgb >> 8) & 255) * f)));
        int b = Math.min(255, Math.max(0, Math.round((rgb & 255) * f)));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static Chunk scan(MinecraftClient mc, int cx, int cz, long now) {
        boolean ceiling = mc.world.getDimension().hasCeiling();       // the Nether: a slice at your height instead of the roof
        int y0 = mc.player.getBlockY() + 1;
        int[] col = new int[256], hts = new int[256];
        BlockPos.Mutable pos = new BlockPos.Mutable();
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                int wx = cx * 16 + lx, wz = cz * 16 + lz, i = lz * 16 + lx;
                if (ceiling) {
                    col[i] = slice(mc, wx, y0, wz, pos);
                } else {
                    int top = mc.world.getTopY(Heightmap.Type.WORLD_SURFACE, wx, wz);
                    hts[i] = top;
                    pos.set(wx, top - 1, wz);
                    col[i] = 0xFF000000 | base(mc, mc.world.getBlockState(pos), pos);
                }
            }
        }
        if (!ceiling) {
            for (int lz = 0; lz < 16; lz++) {
                for (int lx = 0; lx < 16; lx++) {
                    int i = lz * 16 + lx, north;
                    if (lz > 0) north = hts[i - 16];
                    else {
                        int nz = cz * 16 - 1, nx = cx * 16 + lx;
                        north = mc.world.getChunkManager().isChunkLoaded(nx >> 4, nz >> 4) ? mc.world.getTopY(Heightmap.Type.WORLD_SURFACE, nx, nz) : hts[i];
                    }
                    int d = Math.max(-3, Math.min(3, hts[i] - north));
                    col[i] = shade(col[i], 1.0f + d * 0.08f);
                }
            }
        }
        long r = 0, g = 0, b = 0;
        for (int c : col) {
            r += (c >> 16) & 255;
            g += (c >> 8) & 255;
            b += c & 255;
        }
        Chunk ch = new Chunk();
        ch.colors = col;
        ch.avg = 0xFF000000 | ((int) (r / 256) << 16) | ((int) (g / 256) << 8) | (int) (b / 256);
        ch.time = now;
        return ch;
    }

    private static int slice(MinecraftClient mc, int x, int y0, int z, BlockPos.Mutable pos) {
        pos.set(x, y0, z);
        BlockState at = mc.world.getBlockState(pos);
        if (!at.isAir() && !at.getCollisionShape(mc.world, pos).isEmpty()) return shade(base(mc, at, pos), 0.30f);
        for (int dy = 1; dy <= 14; dy++) {
            pos.set(x, y0 - dy, z);
            BlockState st = mc.world.getBlockState(pos);
            if (st.isAir()) continue;
            return shade(base(mc, st, pos), Math.max(0.5f, 1.0f - (dy - 1) * 0.035f) * 1.05f);
        }
        return 0xFF050508;
    }
}
