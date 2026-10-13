package com.blockoutlines.addon.modules;

import com.blockoutlines.addon.BlockOutlinesAddon;
import com.blockoutlines.addon.util.RoundRect;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ChatScreen;
import org.lwjgl.glfw.GLFW;

/**
 * DonutSMP region map. The world is a 9x9 grid of 50 000 x 50 000 block regions centred on 0,0.
 * Shown as a dark rounded panel (Spotify-HUD style). Drag it while the chat is open.
 */
public class RegionMap extends Module {
    private static final int GRID = 9;
    private static final double REGION = 50000.0;
    private static final double OFFSET = 225000.0;

    // Region numbers, row by row (row = Z, column = X; top-left is -X / -Z).
    private static final int[] ID = {
        82, 100, 101, 102, 103, 104, 105, 106, 91,
        83, 44, 75, 42, 41, 40, 39, 38, 92,
        84, 45, 14, 13, 12, 11, 10, 37, 93,
        85, 46, 74, 3, 2, 1, 25, 36, 94,
        86, 47, 72, 71, 5, 4, 24, 35, 95,
        87, 51, 17, 9, 8, 7, 23, 34, 96,
        88, 54, 18, 61, 62, 21, 22, 33, 97,
        89, 26, 27, 28, 29, 30, 59, 32, 98,
        90, 107, 108, 109, 110, 111, 112, 113, 99,
    };
    private static final int[] TYPE = {
        5, 3, 3, 3, 2, 2, 2, 2, 2,
        5, 3, 3, 3, 2, 2, 2, 2, 2,
        5, 3, 3, 3, 2, 2, 2, 2, 2,
        5, 5, 5, 3, 2, 2, 2, 2, 2,
        4, 4, 4, 4, 2, 2, 2, 2, 2,
        4, 1, 1, 0, 0, 0, 0, 0, 2,
        4, 1, 1, 0, 0, 0, 0, 0, 0,
        0, 1, 0, 0, 0, 0, 0, 0, 0,
        0, 1, 1, 1, 1, 1, 1, 1, 0,
    };
    private static final String[] NAMES = {"EU Central", "EU West", "NA East", "NA West", "Asia", "Oceania"};
    private static final int[] COLORS = {
        0xFF63CE9F, 0xFF6366FF, 0xFF4FADEA, 0xFF2F6EBA, 0xFFF5C242, 0xFFFC8803,
    };

    private static final int PAD = 8;
    private static final int HEADER = 40;
    private static final int LEGEND_ROW = 11;

    private static final int BACKGROUND = 0xF00A0A0E;
    private static final int BORDER = 0x33FFFFFF;
    private static final int GREEN = 0xFF1ED760;
    private static final int TITLE = 0xFFF4F6FA;
    private static final int SUBTITLE = 0xFF8B95A7;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgHud = settings.createGroup("HUD");

    private final Setting<Boolean> showIds = sgGeneral.add(new BoolSetting.Builder()
        .name("region-numbers").description("Print the region number inside every cell.").defaultValue(true).build());
    private final Setting<Boolean> showCoords = sgGeneral.add(new BoolSetting.Builder()
        .name("coordinates").description("Show your X / Z under the region name.").defaultValue(true).build());
    private final Setting<Boolean> showLegend = sgGeneral.add(new BoolSetting.Builder()
        .name("legend").description("Show the colour legend (EU, NA, Asia, Oceania).").defaultValue(true).build());
    private final Setting<Boolean> showPlayer = sgGeneral.add(new BoolSetting.Builder()
        .name("player-marker").description("Show where you are on the map.").defaultValue(true).build());

    private final Setting<Integer> panelWidth = sgHud.add(new IntSetting.Builder()
        .name("width").description("Panel width.").defaultValue(230).range(160, 420).sliderRange(160, 420).build());
    private final Setting<Integer> scale = sgHud.add(new IntSetting.Builder()
        .name("scale").description("Size of the panel and text in percent.").defaultValue(100).range(70, 150).sliderRange(70, 150).build());
    private final Setting<Integer> hudX = sgHud.add(new IntSetting.Builder()
        .name("hud-x").description("Panel X. -1 parks it at the top right. Drag it while the chat is open.").defaultValue(-1).min(-1).sliderRange(-1, 4000).build());
    private final Setting<Integer> hudY = sgHud.add(new IntSetting.Builder()
        .name("hud-y").description("Panel Y. -1 parks it at the top.").defaultValue(-1).min(-1).sliderRange(-1, 4000).build());

    private boolean dragging;
    private double grabX, grabY;

    public RegionMap() {
        super(BlockOutlinesAddon.HUNTING, "region-map", "Map of the DonutSMP regions (EU / NA / Asia / Oceania) with your position.");
    }

    @Override
    public void onDeactivate() {
        dragging = false;
    }

    @EventHandler
    private void onRender2D(Render2DEvent event) {
        if (mc.player == null || mc.getWindow() == null || mc.options.hudHidden) return;

        float s = scale.get() / 100.0f;
        int sw = mc.getWindow().getScaledWidth();
        int sh = mc.getWindow().getScaledHeight();

        int width = px(panelWidth.get(), s);
        int pad = px(PAD, s);
        int gridSize = width - pad * 2;
        int legendRows = showLegend.get() ? 2 : 0;
        int height = pad + px(HEADER, s) + gridSize + (legendRows > 0 ? px(6 + LEGEND_ROW * legendRows, s) : 0) + pad;

        int x = resolve(hudX.get(), sw, width, true);
        int y = resolve(hudY.get(), sh, height, false);

        handleDrag(x, y, width, height, sw, sh);
        x = resolve(hudX.get(), sw, width, true);
        y = resolve(hudY.get(), sh, height, false);

        DrawContext ctx = event.drawContext;
        RoundRect.outlined(ctx, x, y, width, height, Math.max(3, px(10, s)), BACKGROUND, BORDER);

        double ppx = mc.player.getX();
        double ppz = mc.player.getZ();
        int col = (int) Math.floor((ppx + OFFSET) / REGION);
        int row = (int) Math.floor((ppz + OFFSET) / REGION);
        boolean inside = col >= 0 && col < GRID && row >= 0 && row < GRID;
        int idx = inside ? row * GRID + col : -1;

        // header
        text(ctx, "REGION MAP", x + pad, y + pad + px(1, s), GREEN, s * 0.85f);
        String name = inside ? NAMES[TYPE[idx]] : "Outside the map";
        text(ctx, name, x + pad, y + pad + px(14, s), TITLE, s * 1.15f);
        if (showCoords.get()) {
            String sub = (inside ? "Region " + ID[idx] + "   " : "") + "X " + (int) Math.floor(ppx) + "  Z " + (int) Math.floor(ppz);
            text(ctx, sub, x + pad, y + pad + px(28, s), SUBTITLE, s * 0.9f);
        }

        // grid
        int gx = x + pad;
        int gy = y + pad + px(HEADER, s);
        int cell = gridSize / GRID;
        int used = cell * GRID;
        gx += (gridSize - used) / 2;

        for (int r = 0; r < GRID; r++) {
            for (int c = 0; c < GRID; c++) {
                int i = r * GRID + c;
                int cx = gx + c * cell;
                int cy = gy + r * cell;
                int color = COLORS[TYPE[i]];
                boolean here = i == idx;
                int alpha = here ? 0xE6 : 0x73;
                ctx.fill(cx + 1, cy + 1, cx + cell, cy + cell, (alpha << 24) | (color & 0xFFFFFF));
                if (here) {
                    ctx.fill(cx, cy, cx + cell + 1, cy + 1, 0xFFFFFFFF);
                    ctx.fill(cx, cy + cell, cx + cell + 1, cy + cell + 1, 0xFFFFFFFF);
                    ctx.fill(cx, cy, cx + 1, cy + cell + 1, 0xFFFFFFFF);
                    ctx.fill(cx + cell, cy, cx + cell + 1, cy + cell + 1, 0xFFFFFFFF);
                }
                if (showIds.get() && cell >= 14) {
                    String label = Integer.toString(ID[i]);
                    float ls = s * Math.min(0.75f, cell / 30.0f + 0.2f);
                    int lw = (int) (mc.textRenderer.getWidth(label) * ls);
                    int lh = (int) (8 * ls);
                    text(ctx, label, cx + (cell - lw) / 2 + 1, cy + (cell - lh) / 2 + 1, here ? 0xFFFFFFFF : 0xCCFFFFFF, ls);
                }
            }
        }

        // player marker
        if (showPlayer.get()) {
            double fx = Math.max(0.0, Math.min(GRID, (ppx + OFFSET) / REGION));
            double fz = Math.max(0.0, Math.min(GRID, (ppz + OFFSET) / REGION));
            int mx = gx + (int) Math.round(fx * cell);
            int my = gy + (int) Math.round(fz * cell);
            int d = Math.max(2, px(2, s));
            ctx.fill(mx - d - 1, my - d - 1, mx + d + 1, my + d + 1, 0xFF000000);
            ctx.fill(mx - d, my - d, mx + d, my + d, 0xFFFFFFFF);
            // short heading tick
            double yaw = Math.toRadians(mc.player.getYaw());
            int tx = mx + (int) Math.round(-Math.sin(yaw) * (d + px(5, s)));
            int ty = my + (int) Math.round(Math.cos(yaw) * (d + px(5, s)));
            ctx.fill(tx - 1, ty - 1, tx + 1, ty + 1, GREEN);
        }

        // legend: 3 columns x 2 rows
        if (showLegend.get()) {
            int ly = gy + cell * GRID + px(6, s);
            int colW = (width - pad * 2) / 3;
            for (int i = 0; i < NAMES.length; i++) {
                int lx = x + pad + (i % 3) * colW;
                int rowY = ly + (i / 3) * px(LEGEND_ROW, s);
                int sq = Math.max(3, px(5, s));
                ctx.fill(lx, rowY + 1, lx + sq, rowY + 1 + sq, COLORS[i]);
                text(ctx, NAMES[i], lx + sq + px(3, s), rowY, SUBTITLE, s * 0.8f);
            }
        }
    }

    private void handleDrag(int x, int y, int w, int h, int sw, int sh) {
        if (!(mc.currentScreen instanceof ChatScreen)) {
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

    private void text(DrawContext ctx, String value, int x, int y, int argb, float s) {
        if (value == null || value.isEmpty()) return;
        ctx.getMatrices().pushMatrix();
        ctx.getMatrices().translate(x, y);
        ctx.getMatrices().scale(s, s);
        ctx.drawText(mc.textRenderer, value, 0, 0, argb, true);
        ctx.getMatrices().popMatrix();
    }

    private static int resolve(int value, int screen, int size, boolean horizontal) {
        if (value < 0) return horizontal ? Math.max(0, screen - size - 4) : 4;
        return Math.max(0, Math.min(screen - size, value));
    }

    private static int px(int value, float s) {
        return Math.max(1, Math.round(value * s));
    }
}
