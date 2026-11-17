package com.blockoutlines.addon.hud;

import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.utils.render.color.Color;

/**
 * Round icon badges drawn straight out of quads/triangles (the only drawing primitives HudRenderer gives us
 * that don't depend on a texture/sprite sheet) - a filled accent-colored circle with a simple glyph "cut out"
 * of it in the box's background color, which is the rounded/badge look the reference screenshot uses. Not a
 * pixel copy of any specific client's icon font, just the same idea done with plain shapes.
 */
final class HudIcons {
    private static final int SEGMENTS = 20;

    private HudIcons() {
    }

    /** Filled circle, used as the badge behind every glyph below. */
    static void circle(HudRenderer r, double cx, double cy, double radius, Color c) {
        double step = (Math.PI * 2) / SEGMENTS;
        for (int i = 0; i < SEGMENTS; i++) {
            double a1 = step * i, a2 = step * (i + 1);
            r.triangle(cx, cy,
                cx + Math.cos(a1) * radius, cy + Math.sin(a1) * radius,
                cx + Math.cos(a2) * radius, cy + Math.sin(a2) * radius, c);
        }
    }

    /** Rounded rectangle, used for the bar/box backgrounds (HudRenderer has no built-in round-rect). */
    static void roundRect(HudRenderer r, double x, double y, double w, double h, double rad, Color c) {
        if (w <= 0 || h <= 0) return;
        rad = Math.min(rad, Math.min(w, h) / 2.0);
        int rows = (int) Math.floor(rad);
        if (rows < 1) {
            r.quad(x, y, w, h, c);
            return;
        }
        for (int i = 0; i < rows; i++) {
            double d = rad - i - 0.5;
            double inset = rad - Math.sqrt(Math.max(0, rad * rad - d * d));
            r.quad(x + inset, y + i, w - inset * 2, 1, c);
            r.quad(x + inset, y + h - i - 1, w - inset * 2, 1, c);
        }
        r.quad(x, y + rows, w, h - rows * 2, c);
    }

    /** The addon's own brand mark - the first letter of the client name, as a little monogram badge. */
    static void brand(HudRenderer r, double cx, double cy, double radius, Color accent, Color bg, String letter, double scale) {
        circle(r, cx, cy, radius, accent);
        double letterScale = Math.max(0.1, scale * (radius / 11.0));
        double w = r.textWidth(letter, false, letterScale);
        double h = r.textHeight(false, letterScale);
        r.text(letter, cx - w / 2, cy - h / 2, bg, false, letterScale);
    }

    /** A little person cut out of the badge. */
    static void person(HudRenderer r, double cx, double cy, double radius, Color accent, Color bg) {
        circle(r, cx, cy, radius, accent);
        double hr = radius * 0.3;
        circle(r, cx, cy - radius * 0.28, hr, bg);
        r.triangle(cx - radius * 0.5, cy + radius * 0.65, cx + radius * 0.5, cy + radius * 0.65, cx, cy + radius * 0.05, bg);
    }

    /** A tiny server rack - three bars cut out of the badge. */
    static void server(HudRenderer r, double cx, double cy, double radius, Color accent, Color bg) {
        circle(r, cx, cy, radius, accent);
        double w = radius * 1.1, barH = radius * 0.28, gap = radius * 0.16;
        double top = cy - (barH * 3 + gap * 2) / 2;
        for (int i = 0; i < 3; i++) {
            double by = top + i * (barH + gap);
            r.quad(cx - w / 2, by, w, barH, bg);
            r.quad(cx - w / 2 + barH * 0.25, by + barH * 0.3, barH * 0.4, barH * 0.4, accent);
        }
    }

    /** A clock face with two hands, cut out of the badge. */
    static void clock(HudRenderer r, double cx, double cy, double radius, Color accent, Color bg) {
        circle(r, cx, cy, radius, accent);
        circle(r, cx, cy, radius * 0.72, bg);
        circle(r, cx, cy, radius * 0.6, accent);
        double mw = radius * 0.08;
        r.triangle(cx - mw, cy, cx + mw, cy, cx, cy - radius * 0.42, bg);
        r.triangle(cx, cy - mw, cx, cy + mw, cx + radius * 0.3, cy + radius * 0.14, bg);
        circle(r, cx, cy, radius * 0.08, bg);
    }

    /** An upward chevron cut out of the badge - used for FPS. */
    static void chevronUp(HudRenderer r, double cx, double cy, double radius, Color accent, Color bg) {
        circle(r, cx, cy, radius, accent);
        double w = radius * 0.8, h = radius * 0.7;
        r.triangle(cx, cy - h * 0.55, cx + w / 2, cy + h * 0.4, cx - w / 2, cy + h * 0.4, bg);
        r.quad(cx - w * 0.18, cy + h * 0.35, w * 0.36, h * 0.35, bg);
    }

    /** Three ascending signal bars cut out of the badge - used for ping. */
    static void signal(HudRenderer r, double cx, double cy, double radius, Color accent, Color bg) {
        circle(r, cx, cy, radius, accent);
        double barW = radius * 0.3, gap = radius * 0.16;
        double[] h = {radius * 0.45, radius * 0.75, radius * 1.05};
        double totalW = barW * 3 + gap * 2;
        double baseY = cy + radius * 0.55;
        double startX = cx - totalW / 2;
        for (int i = 0; i < 3; i++) {
            r.quad(startX + i * (barW + gap), baseY - h[i], barW, h[i], bg);
        }
    }

    /** A chip-ish outline with a dot cut out of the badge - used for TPS. */
    static void chip(HudRenderer r, double cx, double cy, double radius, Color accent, Color bg) {
        circle(r, cx, cy, radius, accent);
        double s = radius * 1.1, t = radius * 0.14;
        double x = cx - s / 2, y = cy - s / 2;
        r.quad(x, y, s, t, bg);
        r.quad(x, y + s - t, s, t, bg);
        r.quad(x, y, t, s, bg);
        r.quad(x + s - t, y, t, s, bg);
        circle(r, cx, cy, radius * 0.22, bg);
    }
}
