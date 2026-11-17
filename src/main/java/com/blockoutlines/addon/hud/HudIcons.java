package com.blockoutlines.addon.hud;

import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.utils.render.color.Color;

/**
 * Small vector icons drawn straight out of quads/triangles/lines (the only drawing primitives HudRenderer gives
 * us that don't depend on a texture/sprite sheet), so they render on the real HUD without needing any extra
 * assets. They're deliberately simple shapes, not a pixel copy of any specific client's icon font.
 */
final class HudIcons {
    private HudIcons() {
    }

    /** The addon's own brand mark - a filled diamond. */
    static void brand(HudRenderer r, double x, double y, double s, Color c) {
        double cx = x + s / 2, cy = y + s / 2, rad = s * 0.42;
        r.triangle(cx, cy - rad, cx + rad, cy, cx, cy + rad, c);
        r.triangle(cx, cy - rad, cx - rad, cy, cx, cy + rad, c);
    }

    /** A little person - a square head and a triangular body. */
    static void person(HudRenderer r, double x, double y, double s, Color c) {
        r.quad(x + s * 0.32, y + s * 0.06, s * 0.36, s * 0.34, c);
        r.triangle(x + s * 0.1, y + s * 0.95, x + s * 0.9, y + s * 0.95, x + s * 0.5, y + s * 0.46, c);
    }

    /** A tiny server rack - three stacked bars with an LED dot each. */
    static void server(HudRenderer r, double x, double y, double s, Color c, Color led) {
        double barH = s * 0.22, gap = s * 0.1;
        for (int i = 0; i < 3; i++) {
            double by = y + i * (barH + gap);
            r.quad(x, by, s, barH, c);
            r.quad(x + s * 0.08, by + barH * 0.3, barH * 0.4, barH * 0.4, led);
        }
    }

    /** A clock - a diamond outline with two hands. */
    static void clock(HudRenderer r, double x, double y, double s, Color c) {
        double cx = x + s / 2, cy = y + s / 2, rad = s * 0.45;
        r.line(cx - rad, cy, cx, cy - rad, c);
        r.line(cx, cy - rad, cx + rad, cy, c);
        r.line(cx + rad, cy, cx, cy + rad, c);
        r.line(cx, cy + rad, cx - rad, cy, c);
        r.line(cx, cy, cx, cy - rad * 0.6, c);
        r.line(cx, cy, cx + rad * 0.45, cy + rad * 0.1, c);
    }

    /** An upward chevron with a stem underneath it - used for FPS. */
    static void chevronUp(HudRenderer r, double x, double y, double s, Color c) {
        r.triangle(x + s * 0.5, y + s * 0.05, x + s * 0.9, y + s * 0.7, x + s * 0.1, y + s * 0.7, c);
        r.quad(x + s * 0.4, y + s * 0.72, s * 0.2, s * 0.23, c);
    }

    /** Three ascending signal bars - used for ping. */
    static void signal(HudRenderer r, double x, double y, double s, Color c) {
        double barW = s * 0.22, gap = s * 0.1;
        double[] h = {s * 0.35, s * 0.62, s * 0.9};
        for (int i = 0; i < 3; i++) {
            r.quad(x + i * (barW + gap), y + s - h[i], barW, h[i], c);
        }
    }

    /** A chip-ish square outline with a dot in the middle - used for TPS. */
    static void chip(HudRenderer r, double x, double y, double s, Color c) {
        r.quad(x, y, s, s * 0.08, c);
        r.quad(x, y + s - s * 0.08, s, s * 0.08, c);
        r.quad(x, y, s * 0.08, s, c);
        r.quad(x + s - s * 0.08, y, s * 0.08, s, c);
        r.quad(x + s * 0.4, y + s * 0.4, s * 0.2, s * 0.2, c);
    }
}
