package com.blockoutlines.addon.util;

import meteordevelopment.meteorclient.renderer.Renderer3D;
import meteordevelopment.meteorclient.utils.render.color.Color;

import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Text in the world: a line is drawn with java.awt into a mask and then built from coloured quads. */
public final class WorldText {
    private static final int FONT_PX = 40, PAD = 4;
    private static final double[] LEVEL_ALPHA = {0, 0.40, 0.75, 1.0};

    private static final class Raster {
        int w, h;
        List<int[]> runs;
    }

    private static final Map<String, Raster> CACHE = new HashMap<>();
    private static Font font;

    private WorldText() {}

    private static Font font() {
        if (font == null) {
            Font f = new Font("Segoe UI", Font.BOLD, FONT_PX);
            if (!f.getFamily().equalsIgnoreCase("Segoe UI")) f = new Font(Font.SANS_SERIF, Font.BOLD, FONT_PX);
            font = f;
        }
        return font;
    }

    private static int level(int alpha) {
        if (alpha < 40) return 0;
        if (alpha < 100) return 1;
        if (alpha < 180) return 2;
        return 3;
    }

    private static Raster raster(String text) {
        Raster cached = CACHE.get(text);
        if (cached != null) return cached;
        if (CACHE.size() > 160) CACHE.clear();

        BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D pg = probe.createGraphics();
        pg.setFont(font());
        FontMetrics fm = pg.getFontMetrics();
        int tw = Math.max(1, fm.stringWidth(text)), th = fm.getHeight(), asc = fm.getAscent();
        pg.dispose();
        int w = tw + PAD * 2, h = th + PAD * 2;

        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setFont(font());
        g.setColor(java.awt.Color.WHITE);
        g.drawString(text, PAD, PAD + asc);
        g.dispose();
        int[] px = img.getRGB(0, 0, w, h, null, 0, w);

        // merge dots of the same strength into rectangles: first across, then down
        List<int[]> out = new ArrayList<>();
        Map<Long, int[]> open = new HashMap<>();
        for (int y = 0; y < h; y++) {
            int x = 0;
            while (x < w) {
                int lvl = level(px[y * w + x] >>> 24);
                if (lvl == 0) {
                    x++;
                    continue;
                }
                int x2 = x + 1;
                while (x2 < w && level(px[y * w + x2] >>> 24) == lvl) x2++;
                long key = ((long) x << 40) | ((long) (x2 - x) << 20) | lvl;
                int[] prev = open.get(key);
                if (prev != null && prev[1] + prev[3] == y) prev[3]++;
                else {
                    int[] r = {x, y, x2 - x, 1, lvl};
                    out.add(r);
                    open.put(key, r);
                }
                x = x2;
            }
        }
        Raster r = new Raster();
        r.w = w;
        r.h = h;
        r.runs = out;
        CACHE.put(text, r);
        return r;
    }

    /** The size that makes the text fit in maxWidth blocks (never bigger than size). */
    public static double fit(String text, double size, double maxWidth) {
        double w = width(text, size);
        return w > maxWidth && w > 0 ? size * maxWidth / w : size;
    }

    /** The width in blocks that the text would have at this size (size is about the height of the letters). */
    public static double width(String text, double size) {
        if (text == null || text.isEmpty()) return 0;
        return raster(text).w * (size / FONT_PX);
    }

    /** Like draw, but the text is made smaller if it would be wider than maxWidth. */
    public static void drawFit(Renderer3D r, String text, double wx, double wy, double wz, double rx, double rz, double size, double maxWidth, int argb) {
        double w = width(text, size);
        if (w > maxWidth && w > 0) size *= maxWidth / w;
        draw(r, text, wx, wy, wz, rx, rz, size, argb);
    }

    /** Draws the text centered on the world point; it runs along (rx, rz), the viewer's right. size is about the letter height in blocks. */
    public static void draw(Renderer3D r, String text, double wx, double wy, double wz, double rx, double rz, double size, int argb) {
        drawOn(r, text, wx, wy, wz, rx, 0, rz, 0, 1, 0, size, argb);
    }

    /** Text on any flat plane: it runs along the right vector R and its letters stand along the up vector U (both unit length). */
    public static void drawOn(Renderer3D r, String text, double wx, double wy, double wz,
                              double rx, double ry, double rz, double ux, double uy, double uz, double size, int argb) {
        if (text == null || text.isEmpty()) return;
        Raster ras = raster(text);
        double k = size / FONT_PX;
        int a = (argb >>> 24) & 255, cr = (argb >> 16) & 255, cg = (argb >> 8) & 255, cb = argb & 255;
        Color[] cols = new Color[4];
        for (int i = 1; i < 4; i++) cols[i] = new Color(cr, cg, cb, (int) Math.round(a * LEVEL_ALPHA[i]));
        double hw = ras.w * k / 2, hh = ras.h * k / 2;
        double px = wx - rx * hw + ux * hh, py = wy - ry * hw + uy * hh, pz = wz - rz * hw + uz * hh;   // top-left corner
        for (int[] q : ras.runs) {
            Color c = cols[q[4]];
            if (c.a <= 2) continue;
            double x0 = q[0] * k, x1 = (q[0] + q[2]) * k;
            double y0 = q[1] * k, y1 = (q[1] + q[3]) * k;
            r.quad(px + rx * x0 - ux * y0, py + ry * x0 - uy * y0, pz + rz * x0 - uz * y0,
                px + rx * x1 - ux * y0, py + ry * x1 - uy * y0, pz + rz * x1 - uz * y0,
                px + rx * x1 - ux * y1, py + ry * x1 - uy * y1, pz + rz * x1 - uz * y1,
                px + rx * x0 - ux * y1, py + ry * x0 - uy * y1, pz + rz * x0 - uz * y1, c);
        }
    }
}
