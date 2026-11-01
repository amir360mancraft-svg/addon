package com.blockoutlines.addon.gui;

import com.blockoutlines.addon.util.ChunkMap;
import com.blockoutlines.addon.util.MapTexture;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.Map;

/**
 * The big map: every chunk you have had loaded, seen from above. Drag with the left mouse button to move it,
 * scroll to zoom (it zooms toward the mouse), R puts you back in the middle, Esc closes it.
 */
public class ExploreMapScreen extends Screen {
    private double cx, cz, s = 2.0;
    private boolean dragging;
    private double lastX, lastY;
    private final MapTexture tex = new MapTexture("explore_map");
    private double drawnCx = Double.NaN, drawnCz, drawnS;
    private int drawnVersion = -1, drawnRes, drawnW, drawnH;

    public ExploreMapScreen() {
        super(Text.literal("Map"));
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null) {
            cx = mc.player.getX();
            cz = mc.player.getZ();
        }
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (verticalAmount == 0) return false;
        double wx = cx + (mouseX - width / 2.0) / s, wz = cz + (mouseY - height / 2.0) / s;
        s = Math.max(0.12, Math.min(24.0, s * (verticalAmount > 0 ? 1.25 : 1 / 1.25)));
        cx = wx - (mouseX - width / 2.0) / s;
        cz = wz - (mouseY - height / 2.0) / s;
        return true;
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        MinecraftClient mc = MinecraftClient.getInstance();
        ChunkMap.tick();

        // drag with the left button, R to centre on you
        long win = mc.getWindow().getHandle();
        double mx = mc.mouse.getX() * width / (double) mc.getWindow().getWidth();
        double my = mc.mouse.getY() * height / (double) mc.getWindow().getHeight();
        boolean down = GLFW.glfwGetMouseButton(win, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
        if (down) {
            if (dragging) {
                cx -= (mx - lastX) / s;
                cz -= (my - lastY) / s;
            }
            dragging = true;
            lastX = mx;
            lastY = my;
        } else dragging = false;
        if (GLFW.glfwGetKey(win, GLFW.GLFW_KEY_R) == GLFW.GLFW_PRESS && mc.player != null) {
            cx = mc.player.getX();
            cz = mc.player.getZ();
        }

        ctx.fill(0, 0, width, height, 0xFF0B0B10);

        Map<Long, ChunkMap.Chunk> map = ChunkMap.current();
        // the map is painted into one texture, and only again when you move / zoom or a chunk changed
        int res = (long) width * height > 600_000 ? 2 : 1;
        if (!tex.ready() || cx != drawnCx || cz != drawnCz || s != drawnS || ChunkMap.version != drawnVersion
            || res != drawnRes || width != drawnW || height != drawnH) {
            paint(map, res);
            drawnCx = cx;
            drawnCz = cz;
            drawnS = s;
            drawnVersion = ChunkMap.version;
            drawnRes = res;
            drawnW = width;
            drawnH = height;
        }
        if (tex.ready()) ctx.drawTexture(RenderPipelines.GUI_TEXTURED, tex.id(), 0, 0, 0f, 0f, width, height, tex.w, tex.h, tex.w, tex.h, 0xFFFFFFFF);

        // the area that is loaded right now
        if (mc.player != null) {
            int r = Math.max(2, Math.min(32, mc.options.getViewDistance().getValue()));
            int pcx = mc.player.getBlockX() >> 4, pcz = mc.player.getBlockZ() >> 4;
            int x0 = sx((pcx - r) * 16), x1 = sx((pcx + r + 1) * 16), y0 = sy((pcz - r) * 16), y1 = sy((pcz + r + 1) * 16);
            int line = 0x55FFFFFF;
            ctx.fill(x0, y0, x1, y0 + 1, line);
            ctx.fill(x0, y1 - 1, x1, y1, line);
            ctx.fill(x0, y0, x0 + 1, y1, line);
            ctx.fill(x1 - 1, y0, x1, y1, line);
        }

        // players
        if (mc.world != null) {
            for (Entity e : mc.world.getEntities()) {
                if (!(e instanceof PlayerEntity) || e == mc.player) continue;
                int px = sx(e.getX()), py = sy(e.getZ());
                if (px < -20 || py < -20 || px > width + 20 || py > height + 20) continue;
                ctx.fill(px - 3, py - 3, px + 3, py + 3, 0xFF000000);
                ctx.fill(px - 2, py - 2, px + 2, py + 2, 0xFFFFFFFF);
                String name = e.getName().getString();
                ctx.drawText(mc.textRenderer, name, px - mc.textRenderer.getWidth(name) / 2, py - 12, 0xFFFFFFFF, true);
            }
        }
        // you
        if (mc.player != null) {
            int px = sx(mc.player.getX()), py = sy(mc.player.getZ());
            double yaw = Math.toRadians(mc.player.getYaw());
            double fx = -Math.sin(yaw), fz = Math.cos(yaw);
            ctx.fill(px - 4, py - 4, px + 4, py + 4, 0xFF000000);
            ctx.fill(px - 3, py - 3, px + 3, py + 3, 0xFF1ED760);
            for (int k = 4; k <= 11; k++) {
                int tx = (int) Math.round(px + fx * k), ty = (int) Math.round(py + fz * k);
                ctx.fill(tx - 1, ty - 1, tx + 2, ty + 2, 0xFF1ED760);
            }
        }

        // texts
        ctx.fill(0, 0, width, 14, 0xAA000000);
        ctx.drawText(mc.textRenderer, "Drag: move    Scroll: zoom    R: back to you    Esc: close", 6, 3, 0xFFB4BCCB, true);
        String z = String.format("%.1f px/block   %d chunks explored", s, map.size());
        ctx.drawText(mc.textRenderer, z, width - 6 - mc.textRenderer.getWidth(z), 3, 0xFFFFC857, true);
        int hx = (int) Math.floor(cx + (mouseX - width / 2.0) / s), hz = (int) Math.floor(cz + (mouseY - height / 2.0) / s);
        String hover = "X " + hx + "   Z " + hz + "   chunk " + (hx >> 4) + ", " + (hz >> 4);
        ctx.fill(0, height - 14, mc.textRenderer.getWidth(hover) + 12, height, 0xAA000000);
        ctx.drawText(mc.textRenderer, hover, 6, height - 11, 0xFFFFFFFF, true);
    }

    private int sx(double wx) {
        return (int) Math.round(width / 2.0 + (wx - cx) * s);
    }

    private int sy(double wz) {
        return (int) Math.round(height / 2.0 + (wz - cz) * s);
    }

    @Override
    public void removed() {
        tex.free();
        super.removed();
    }

    /** Paints every visible chunk into an image the size of the screen (divided by res). */
    private void paint(Map<Long, ChunkMap.Chunk> map, int res) {
        int tw = Math.max(1, width / res), th = Math.max(1, height / res);
        NativeImage img = new NativeImage(tw, th, false);
        for (int y = 0; y < th; y++) for (int x = 0; x < tw; x++) img.setColorArgb(x, y, 0xFF0B0B10);
        double ps = s / res;                                  // image pixels per block
        double halfW = tw / 2.0 / ps, halfH = th / 2.0 / ps;
        int minCx = (int) Math.floor((cx - halfW) / 16) - 1, maxCx = (int) Math.floor((cx + halfW) / 16) + 1;
        int minCz = (int) Math.floor((cz - halfH) / 16) - 1, maxCz = (int) Math.floor((cz + halfH) / 16) + 1;
        long cells = (long) (maxCx - minCx + 1) * (maxCz - minCz + 1);
        boolean avgOnly = ps * 16 < 5;                        // far away: one colour per chunk
        if (cells <= map.size()) {
            for (int x = minCx; x <= maxCx; x++) {
                for (int z = minCz; z <= maxCz; z++) {
                    ChunkMap.Chunk c = map.get(ChunkMap.key(x, z));
                    if (c != null) paintChunk(img, c, x, z, tw, th, ps, avgOnly);
                }
            }
        } else {
            for (Map.Entry<Long, ChunkMap.Chunk> e : map.entrySet()) {
                int x = (int) (e.getKey() >> 32), z = (int) (long) e.getKey();
                if (x < minCx || x > maxCx || z < minCz || z > maxCz) continue;
                paintChunk(img, e.getValue(), x, z, tw, th, ps, avgOnly);
            }
        }
        tex.commit(img, tw, th);
    }

    private void paintChunk(NativeImage img, ChunkMap.Chunk c, int chx, int chz, int tw, int th, double ps, boolean avgOnly) {
        double x0 = tw / 2.0 + (chx * 16 - cx) * ps, y0 = th / 2.0 + (chz * 16 - cz) * ps;
        int px0 = Math.max(0, (int) Math.floor(x0)), px1 = Math.min(tw, (int) Math.ceil(x0 + 16 * ps));
        int py0 = Math.max(0, (int) Math.floor(y0)), py1 = Math.min(th, (int) Math.ceil(y0 + 16 * ps));
        for (int py = py0; py < py1; py++) {
            int lz = Math.min(15, Math.max(0, (int) Math.floor((py + 0.5 - y0) / ps)));
            for (int px = px0; px < px1; px++) {
                int lx = Math.min(15, Math.max(0, (int) Math.floor((px + 0.5 - x0) / ps)));
                img.setColorArgb(px, py, avgOnly ? c.avg : c.colors[lz * 16 + lx]);
            }
        }
    }
}
