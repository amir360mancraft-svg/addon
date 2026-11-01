package com.blockoutlines.addon.modules;

import com.blockoutlines.addon.BlockOutlinesAddon;
import com.blockoutlines.addon.gui.ExploreMapScreen;
import com.blockoutlines.addon.util.ChunkMap;
import com.blockoutlines.addon.util.HudHook;
import com.blockoutlines.addon.util.MapTexture;
import com.blockoutlines.addon.util.RoundRect;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.KeybindSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.BlockState;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;
import org.lwjgl.glfw.GLFW;

/**
 * A minimap. On the surface it is a top-down map of the terrain (like a vanilla map, with light and shadow on the hills).
 * Below a Y level you choose (or in the Nether) it switches to CAVE mode: a slice at your height, so you see the cave
 * you are standing in (walls are dark, the floor is lit and gets darker the deeper it is). Players, mobs and items are dots.
 * Drag it while the chat is open.
 */
public class BetterMinimap extends Module {
    public enum Mode { Auto, Surface, Cave }
    public enum Shape { Square, Circle }

    private static final int UNLOADED = 0xFF15151C, CHASM = 0xFF050508;

    private final SettingGroup sg = settings.getDefaultGroup();
    private final SettingGroup sgEnt = settings.createGroup("Dots");
    private final SettingGroup sgHud = settings.createGroup("HUD");

    private final Setting<Mode> mode = sg.add(new EnumSetting.Builder<Mode>().name("mode")
        .description("Auto: surface map, and the cave view when you are below the cave level (always in the Nether).")
        .defaultValue(Mode.Auto).build());
    private final Setting<Integer> caveBelow = sg.add(new IntSetting.Builder().name("cave-below-y")
        .description("Auto mode switches to the cave view when your Y is below this.").defaultValue(50).range(-64, 320).sliderRange(-64, 120).build());
    private final Setting<Integer> range = sg.add(new IntSetting.Builder().name("range")
        .description("How many blocks the map shows around you.").defaultValue(32).range(16, 96).sliderRange(16, 96).build());
    private final Setting<Boolean> rotate = sg.add(new BoolSetting.Builder().name("rotate-with-you")
        .description("The top of the map is where you look. Off: north is always up.").defaultValue(true).build());
    private final Setting<Shape> shape = sg.add(new EnumSetting.Builder<Shape>().name("shape")
        .description("Square or round map.").defaultValue(Shape.Square).build());
    private final Setting<Keybind> mapKey = sg.add(new KeybindSetting.Builder().name("open-map-key")
        .description("Opens the big map with every chunk you have loaded (drag to move, scroll to zoom). The module must be on to remember chunks.")
        .defaultValue(Keybind.none()).action(this::openMap).build());
    private final Setting<Boolean> coords = sg.add(new BoolSetting.Builder().name("coordinates")
        .description("Your X / Y / Z and the current view under the map.").defaultValue(true).build());

    private final Setting<Boolean> players = sgEnt.add(new BoolSetting.Builder().name("players").defaultValue(true).build());
    private final Setting<Boolean> hostiles = sgEnt.add(new BoolSetting.Builder().name("hostile-mobs").defaultValue(true).build());
    private final Setting<Boolean> animals = sgEnt.add(new BoolSetting.Builder().name("animals").defaultValue(false).build());
    private final Setting<Boolean> items = sgEnt.add(new BoolSetting.Builder().name("dropped-items").defaultValue(false).build());

    private final Setting<Integer> size = sgHud.add(new IntSetting.Builder().name("size")
        .description("Size of the map in pixels.").defaultValue(120).range(70, 300).sliderRange(70, 260).build());
    private final Setting<Integer> hudX = sgHud.add(new IntSetting.Builder().name("hud-x")
        .description("X. -1 parks it at the top right. Drag it while the chat is open.").defaultValue(-1).min(-1).sliderRange(-1, 4000).build());
    private final Setting<Integer> hudY = sgHud.add(new IntSetting.Builder().name("hud-y")
        .description("Y. -1 parks it at the top.").defaultValue(-1).min(-1).sliderRange(-1, 4000).build());

    // the colours of the terrain around you, in world coordinates; the map is drawn (and turned) from this
    private int[] cache, back, backH;
    private int cn, cx0, cz0, lastBx, lastBz, lastBy;
    private long lastBuild, lastTex;
    private boolean lastCave;
    // a new copy of the terrain colours is built a few rows per frame (no lag spike) and then swapped in
    private boolean building, bCave;
    private int bn, bx0, bz0, bY, brow;
    private final MapTexture tex = new MapTexture("minimap_view");
    private int texPx;
    private boolean dragging;
    private double grabX, grabY;

    public BetterMinimap() {
        super(BlockOutlinesAddon.CATEGORY, "better-minimap", "A minimap: terrain from above, and a cave view when you are underground.");
    }

    @Override
    public void onDeactivate() {
        dragging = false;
        cache = null;
        back = null;
        building = false;
        tex.free();
    }

    private void openMap() {
        if (mc.player == null || mc.world == null || mc.currentScreen != null) return;
        mc.setScreen(new ExploreMapScreen());
    }

    private boolean caveView() {
        if (mc.world == null || mc.player == null) return false;
        return switch (mode.get()) {
            case Cave -> true;
            case Surface -> false;
            case Auto -> mc.world.getDimension().hasCeiling() || mc.player.getY() < caveBelow.get();
        };
    }

    // ------------------------------------------------------------------ the colours

    private int base(BlockState st, BlockPos pos) {
        int c = st.getMapColor(mc.world, pos).color;
        return c == 0 ? 0x484848 : c;
    }

    private static int shade(int rgb, float f) {
        int r = Math.min(255, Math.max(0, Math.round(((rgb >> 16) & 255) * f)));
        int g = Math.min(255, Math.max(0, Math.round(((rgb >> 8) & 255) * f)));
        int b = Math.min(255, Math.max(0, Math.round((rgb & 255) * f)));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /** What you see at (x, z) when you cut the world at your height: rock around you is dark, the floor is lit. */
    private int caveColor(int x, int y0, int z, BlockPos.Mutable pos) {
        pos.set(x, y0, z);
        BlockState at = mc.world.getBlockState(pos);
        if (!at.isAir() && !at.getCollisionShape(mc.world, pos).isEmpty()) return shade(base(at, pos), 0.30f);   // wall
        for (int dy = 1; dy <= 14; dy++) {
            pos.set(x, y0 - dy, z);
            BlockState st = mc.world.getBlockState(pos);
            if (st.isAir()) continue;
            float f = Math.max(0.5f, 1.0f - (dy - 1) * 0.035f);
            return shade(base(st, pos), f * 1.05f);
        }
        return CHASM;
    }

    private void startBuild(boolean cave) {
        int half = (int) Math.ceil(range.get() * 1.42) + 5;
        bn = half * 2;
        if (back == null || back.length != bn * bn) back = new int[bn * bn];
        if (backH == null || backH.length != bn * bn) backH = new int[bn * bn];
        bx0 = mc.player.getBlockX() - half;
        bz0 = mc.player.getBlockZ() - half;
        bY = mc.player.getBlockY() + 1;
        bCave = cave;
        brow = 0;
        building = true;
        lastBx = mc.player.getBlockX();
        lastBz = mc.player.getBlockZ();
    }

    /** Works on a few rows of the new copy; when it is complete it replaces the old one. */
    private void stepBuild() {
        BlockPos.Mutable pos = new BlockPos.Mutable();
        int rows = 0;
        while (brow < bn && rows < 10) {
            int iz = brow, wz = bz0 + iz, lastCh = Integer.MIN_VALUE;
            boolean loaded = false;
            for (int ix = 0; ix < bn; ix++) {
                int wx = bx0 + ix, i = iz * bn + ix;
                if ((wx >> 4) != lastCh) {
                    lastCh = wx >> 4;
                    loaded = mc.world.getChunkManager().isChunkLoaded(wx >> 4, wz >> 4);
                }
                if (!loaded) {
                    back[i] = UNLOADED;
                    continue;
                }
                if (bCave) {
                    back[i] = caveColor(wx, bY, wz, pos);
                } else {
                    int top = mc.world.getTopY(Heightmap.Type.WORLD_SURFACE, wx, wz);
                    backH[i] = top;
                    pos.set(wx, top - 1, wz);
                    back[i] = 0xFF000000 | base(mc.world.getBlockState(pos), pos);
                }
            }
            brow++;
            rows++;
        }
        if (brow < bn) return;
        if (!bCave) {                                         // light and shadow from the height difference to the block to the north
            for (int iz = 1; iz < bn; iz++) {
                for (int ix = 0; ix < bn; ix++) {
                    int i = iz * bn + ix;
                    if (back[i] == UNLOADED || back[i - bn] == UNLOADED) continue;
                    int d = Math.max(-3, Math.min(3, backH[i] - backH[i - bn]));
                    back[i] = shade(back[i], 1.0f + d * 0.08f);
                }
            }
        }
        int[] old = cache;
        cache = back;
        back = old != null && old.length == cache.length ? old : null;
        cn = bn;
        cx0 = bx0;
        cz0 = bz0;
        lastCave = bCave;
        lastBy = bY;
        lastBuild = System.currentTimeMillis();
        building = false;
    }

    /** Paints the map (turned the way you look) into the texture, one pixel per screen pixel. */
    private void paintTexture(int mapSize, double rx, double rz, double fx, double fz, boolean round) {
        int k = Math.max(1, (int) Math.round(mc.getWindow().getScaleFactor()));
        k = Math.max(1, Math.min(k, 360 / mapSize));
        int tp = mapSize * k;
        int r = range.get();
        double ppb = tp / (2.0 * r);                          // texture pixels per block
        double px = mc.player.getX(), pz = mc.player.getZ();
        NativeImage img = new NativeImage(tp, tp, false);
        for (int y = 0; y < tp; y++) {
            double sy = (tp / 2.0 - y - 0.5) / ppb;
            for (int x = 0; x < tp; x++) {
                double sx = (x + 0.5 - tp / 2.0) / ppb;
                int col = 0;
                if (!round || sx * sx + sy * sy <= r * r) {
                    int ix = (int) Math.floor(px + rx * sx + fx * sy) - cx0;
                    int iz = (int) Math.floor(pz + rz * sx + fz * sy) - cz0;
                    col = ix >= 0 && iz >= 0 && ix < cn && iz < cn ? cache[iz * cn + ix] : UNLOADED;
                }
                img.setColorArgb(x, y, col);
            }
        }
        tex.commit(img, tp, tp);
        texPx = tp;
        lastTex = System.currentTimeMillis();
    }

    // ------------------------------------------------------------------ drawing

    @EventHandler
    private void onRender2D(Render2DEvent event) {
        if (mc.player == null || mc.world == null || mc.getWindow() == null) return;
        ChunkMap.tick();                                      // remembers the chunks for the big map
        if (mc.options.hudHidden) return;

        int sw = mc.getWindow().getScaledWidth(), sh = mc.getWindow().getScaledHeight();
        int pad = 3, mapSize = size.get(), info = coords.get() ? 12 : 0;
        int w = mapSize + pad * 2, h = mapSize + pad * 2 + info;
        int x = resolve(hudX.get(), sw, w, true), y = resolve(hudY.get(), sh, h, false);
        handleDrag(x, y, w, h, sw, sh);
        x = resolve(hudX.get(), sw, w, true);
        y = resolve(hudY.get(), sh, h, false);
        HudHook.mark("Minimap", x, y, w, h);

        DrawContext ctx = event.drawContext;
        RoundRect.outlined(ctx, x, y, w, h, 6, 0xF00A0A0E, 0x33FFFFFF);

        boolean cave = caveView();
        long now = System.currentTimeMillis();
        if (building) {
            if (bCave != cave) startBuild(cave);
            stepBuild();
        } else if (cache == null || cave != lastCave || now - lastBuild > 400
            || Math.abs(mc.player.getBlockX() - lastBx) >= 3 || Math.abs(mc.player.getBlockZ() - lastBz) >= 3
            || (cave && mc.player.getBlockY() + 1 != lastBy)) {
            startBuild(cave);
            stepBuild();
        }

        int n = range.get() * 2, r = range.get();
        double yaw = Math.toRadians(mc.player.getYaw());
        double fx = rotate.get() ? -Math.sin(yaw) : 0, fz = rotate.get() ? Math.cos(yaw) : -1;   // where the top of the map points
        double rx = -fz, rz = fx;                                                                   // and its right
        double px = mc.player.getX(), pz = mc.player.getZ();
        int mx = x + pad, my = y + pad;
        float sc = mapSize / (float) n;
        boolean round = shape.get() == Shape.Circle;

        if (cache != null) {
            if (!tex.ready() || now - lastTex >= 50) paintTexture(mapSize, rx, rz, fx, fz, round);
            if (tex.ready()) ctx.drawTexture(RenderPipelines.GUI_TEXTURED, tex.id(), mx, my, 0f, 0f, mapSize, mapSize, texPx, texPx, texPx, texPx, 0xFFFFFFFF);
        }

        // dots
        double cxp = mx + mapSize / 2.0, cyp = my + mapSize / 2.0;
        if (players.get() || hostiles.get() || animals.get() || items.get()) {
            for (Entity e : mc.world.getEntities()) {
                if (e == mc.player) continue;
                int col;
                if (e instanceof PlayerEntity) {
                    if (!players.get()) continue;
                    col = 0xFFFFFFFF;
                } else if (e instanceof HostileEntity) {
                    if (!hostiles.get()) continue;
                    col = 0xFFFF4444;
                } else if (e instanceof AnimalEntity) {
                    if (!animals.get()) continue;
                    col = 0xFF44DD66;
                } else if (e instanceof ItemEntity) {
                    if (!items.get()) continue;
                    col = 0xFFFFD25A;
                } else continue;
                double dx = e.getX() - px, dz = e.getZ() - pz;
                if (cave && Math.abs(e.getY() - mc.player.getY()) > 20) continue;
                double sx = dx * rx + dz * rz, sy = dx * fx + dz * fz;
                if (round ? sx * sx + sy * sy > r * r : Math.abs(sx) > r || Math.abs(sy) > r) continue;
                int ex = (int) Math.round(cxp + sx * sc), ey = (int) Math.round(cyp - sy * sc);
                ctx.fill(ex - 2, ey - 2, ex + 2, ey + 2, 0xFF000000);
                ctx.fill(ex - 1, ey - 1, ex + 1, ey + 1, col);
            }
        }

        // you: a small arrow (always up when the map turns, otherwise it points where you look)
        double ax = rotate.get() ? 0 : -Math.sin(yaw), ay = rotate.get() ? 1 : -Math.cos(yaw);
        int cx = (int) Math.round(cxp), cy = (int) Math.round(cyp);
        ctx.fill(cx - 2, cy - 2, cx + 2, cy + 2, 0xFF000000);
        ctx.fill(cx - 1, cy - 1, cx + 1, cy + 1, 0xFF1ED760);
        for (int k = 2; k <= 5; k++) {
            int tx = (int) Math.round(cxp + ax * k), ty = (int) Math.round(cyp - ay * k);
            ctx.fill(tx - 1, ty - 1, tx + 1, ty + 1, 0xFF1ED760);
        }

        // N: where north is
        double nsx = 0 * rx + (-1) * rz, nsy = 0 * fx + (-1) * fz;
        double edge = mapSize / 2.0 - 6;
        double k = round ? edge : edge / Math.max(Math.abs(nsx), Math.abs(nsy));
        int nx = (int) Math.round(cxp + nsx * k), ny = (int) Math.round(cyp - nsy * k);
        small(ctx, "N", nx, ny - 3, 0xFFFF6A6A, 0.9f, true);

        if (coords.get()) {
            String left = "X " + mc.player.getBlockX() + "  Y " + mc.player.getBlockY() + "  Z " + mc.player.getBlockZ();
            small(ctx, left, x + pad + 1, y + h - 11, 0xFFB4BCCB, 0.8f, false);
            String tag = cave ? "CAVE" : "SURFACE";
            int tw = (int) (mc.textRenderer.getWidth(tag) * 0.8f);
            small(ctx, tag, x + w - pad - 1 - tw, y + h - 11, cave ? 0xFFFFC857 : 0xFF1ED760, 0.8f, false);
        }
    }

    private void small(DrawContext ctx, String s, int x, int y, int argb, float sc, boolean centered) {
        ctx.getMatrices().pushMatrix();
        ctx.getMatrices().translate(x, y);
        ctx.getMatrices().scale(sc, sc);
        ctx.drawText(mc.textRenderer, s, centered ? -mc.textRenderer.getWidth(s) / 2 : 0, 0, argb, true);
        ctx.getMatrices().popMatrix();
    }

    private void handleDrag(int x, int y, int w, int h, int sw, int sh) {
        if (!HudHook.editing()) {
            dragging = false;
            return;
        }
        double mx = mc.mouse.getX() * sw / (double) mc.getWindow().getWidth();
        double my = mc.mouse.getY() * sh / (double) mc.getWindow().getHeight();
        boolean down = GLFW.glfwGetMouseButton(mc.getWindow().getHandle(), GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
        if (!down) {
            dragging = false;
            return;
        }
        if (!dragging) {
            if (mx < x || mx > x + w || my < y || my > y + h) return;
            dragging = true;
            grabX = mx - x;
            grabY = my - y;
        }
        hudX.set(Math.max(0, Math.min(sw - w, (int) (mx - grabX))));
        hudY.set(Math.max(0, Math.min(sh - h, (int) (my - grabY))));
    }

    private static int resolve(int value, int screen, int size, boolean horizontal) {
        if (value < 0) return horizontal ? Math.max(0, screen - size - 4) : 4;
        return Math.max(0, Math.min(screen - size, value));
    }
}
