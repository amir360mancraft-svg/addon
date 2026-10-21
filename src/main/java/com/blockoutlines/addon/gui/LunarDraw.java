package com.blockoutlines.addon.gui;

import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.utils.render.color.Color;

/**
 * Shapes made only of plain rectangles, so they are drawn in the same pass and the same order as everything else
 * (round corners, triangles and stars that always show up, whatever the face direction of a triangle is).
 */
final class LunarDraw {
    private LunarDraw() {
    }

    static Color col(int r, int g, int b, double a) {
        return new Color(r, g, b, (int) Math.max(0, Math.min(255, Math.round(a))));
    }

    /** A rectangle with round corners, drawn as one-pixel rows at the top and bottom. */
    static void round(GuiRenderer r, double x, double y, double w, double h, double rad, Color c) {
        if (w <= 0 || h <= 0) return;
        rad = Math.min(rad, Math.min(w, h) / 2.0);
        int rows = (int) Math.floor(rad);
        if (rows < 1) { r.quad(x, y, w, h, c); return; }
        for (int i = 0; i < rows; i++) {
            double d = rad - i - 0.5;
            double inset = rad - Math.sqrt(Math.max(0, rad * rad - d * d));
            r.quad(x + inset, y + i, w - inset * 2, 1, c);
            r.quad(x + inset, y + h - i - 1, w - inset * 2, 1, c);
        }
        r.quad(x, y + rows, w, h - rows * 2, c);
    }

    /** A filled triangle, drawn row by row. */
    static void tri(GuiRenderer r, double x1, double y1, double x2, double y2, double x3, double y3, Color c) {
        double minY = Math.min(y1, Math.min(y2, y3)), maxY = Math.max(y1, Math.max(y2, y3));
        int y0 = (int) Math.floor(minY), y9 = (int) Math.ceil(maxY);
        double[] xs = {x1, x2, x3}, ys = {y1, y2, y3};
        for (int yy = y0; yy < y9; yy++) {
            double yc = yy + 0.5;
            double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
            for (int i = 0; i < 3; i++) {
                int j = (i + 1) % 3;
                double ya = ys[i], yb = ys[j];
                if (ya == yb) continue;
                if ((yc >= ya && yc <= yb) || (yc >= yb && yc <= ya)) {
                    double x = xs[i] + (yc - ya) * (xs[j] - xs[i]) / (yb - ya);
                    lo = Math.min(lo, x);
                    hi = Math.max(hi, x);
                }
            }
            if (hi > lo) r.quad(lo, yy, hi - lo, 1, c);
        }
    }

    /** A line with thickness: a rectangle along the two points, made of two triangles. */
    static void seg(GuiRenderer r, double x1, double y1, double x2, double y2, double th, Color c) {
        double dx = x2 - x1, dy = y2 - y1;
        double len = Math.sqrt(dx * dx + dy * dy);
        if (len < 1e-6) return;
        double nx = -dy / len * th / 2, ny = dx / len * th / 2;
        tri(r, x1 + nx, y1 + ny, x2 + nx, y2 + ny, x2 - nx, y2 - ny, c);
        tri(r, x1 + nx, y1 + ny, x2 - nx, y2 - ny, x1 - nx, y1 - ny, c);
    }

    /** A star with the given number of points; rot is in degrees. */
    static void star(GuiRenderer r, double cx, double cy, int points, double outer, double inner, double rotDeg, Color c) {
        double rot = Math.toRadians(rotDeg) - Math.PI / 2;
        int n = points * 2;
        double[] px = new double[n], py = new double[n];
        for (int i = 0; i < n; i++) {
            double a = rot + i * Math.PI / points;
            double rr = (i % 2 == 0) ? outer : inner;
            px[i] = cx + Math.cos(a) * rr;
            py[i] = cy + Math.sin(a) * rr;
        }
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            tri(r, cx, cy, px[i], py[i], px[j], py[j], c);
        }
    }
}
