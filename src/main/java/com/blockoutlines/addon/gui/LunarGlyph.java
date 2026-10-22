package com.blockoutlines.addon.gui;

import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.utils.render.color.Color;

import java.util.Locale;

/** Small flat icons for tabs and HUD elements, drawn from plain shapes. The key is a tab or element name. */
final class LunarGlyph {
    private LunarGlyph() {
    }

    private static boolean has(String key, String... words) {
        for (String w : words) if (key.contains(w)) return true;
        return false;
    }

    private static void box(GuiRenderer r, double cx, double cy, double u, double x, double y, double w, double h, double rad, Color c) {
        LunarDraw.round(r, cx + x * u, cy + y * u, w * u, h * u, rad * u, c);
    }

    private static void dot(GuiRenderer r, double cx, double cy, double u, double x, double y, double d, Color c) {
        LunarDraw.round(r, cx + (x - d / 2) * u, cy + (y - d / 2) * u, d * u, d * u, d * u / 2, c);
    }

    private static void person(GuiRenderer r, double cx, double cy, double u, double dx, double scale, Color c) {
        dot(r, cx, cy, u, dx, -2.2 * scale, 4 * scale, c);
        box(r, cx, cy, u, dx - 3.2 * scale, 0.8 * scale, 6.4 * scale, 4.2 * scale, 2 * scale, c);
    }

    /** Draws the icon for {@code key} centered at cx, cy inside a box of size s. {@code bg} is the color behind it (for cut-outs). */
    static void draw(GuiRenderer r, String key, double cx, double cy, double s, Color c, Color bg) {
        key = key.toLowerCase(Locale.ROOT);
        double u = s / 10.0;

        if (has(key, "mods", "module-info", "inventory")) {
            for (double dx : new double[]{-2.8, 0.3})
                for (double dy : new double[]{-2.8, 0.3}) box(r, cx, cy, u, dx, dy, 2.5, 2.5, 0.8, c);
            if (!has(key, "inventory", "info")) {
                box(r, cx, cy, u, -2.8, -2.8, 2.5, 2.5, 0.8, c);
            }
        } else if (has(key, "huds", "hud")) {
            box(r, cx, cy, u, -5, -4, 10, 8, 1.2, c);
            box(r, cx, cy, u, -3.8, -2.8, 7.6, 5.6, 0.6, bg);
            box(r, cx, cy, u, -3, -2, 3, 1.6, 0.5, c);
            box(r, cx, cy, u, 0, 0.6, 3, 1.6, 0.5, c);
        } else if (has(key, "config", "gear", "setting")) {
            for (int i = 0; i < 8; i++) {
                double a = Math.toRadians(i * 45);
                LunarDraw.seg(r, cx + Math.cos(a) * 2.5 * u, cy + Math.sin(a) * 2.5 * u, cx + Math.cos(a) * 4.6 * u, cy + Math.sin(a) * 4.6 * u, 1.9 * u, c);
            }
            dot(r, cx, cy, u, 0, 0, 7, c);
            dot(r, cx, cy, u, 0, 0, 3, bg);
        } else if (has(key, "friend")) {
            person(r, cx, cy, u, -2.2, 0.8, c);
            person(r, cx, cy, u, 2.4, 0.9, c);
        } else if (has(key, "profile", "player-model", "player")) {
            person(r, cx, cy, u, 0, 1.0, c);
        } else if (has(key, "macro")) {
            box(r, cx, cy, u, -5, -4, 10, 8, 1.6, c);
            LunarDraw.tri(r, cx - 1.6 * u, cy - 2.2 * u, cx - 1.6 * u, cy + 2.2 * u, cx + 2.6 * u, cy, bg);
        } else if (has(key, "lunar", "star")) {
            LunarDraw.star(r, cx, cy, 4, 5 * u, 1.6 * u, 0, c);
        } else if (has(key, "path", "baritone")) {
            LunarDraw.seg(r, cx - 4 * u, cy + 3 * u, cx + 2 * u, cy - 3 * u, 1.6 * u, c);
            LunarDraw.tri(r, cx + 4.6 * u, cy - 4.6 * u, cx - 0.4 * u, cy - 3.2 * u, cx + 3.2 * u, cy + 0.4 * u, c);
        } else if (has(key, "armor")) {
            box(r, cx, cy, u, -3.6, -4.4, 7.2, 8.8, 1.6, c);
            dot(r, cx, cy, u, 0, -4.4, 3.4, bg);
        } else if (has(key, "combat")) {
            LunarDraw.seg(r, cx - 4 * u, cy + 4 * u, cx + 4 * u, cy - 4 * u, 1.5 * u, c);
            LunarDraw.seg(r, cx + 4 * u, cy + 4 * u, cx - 4 * u, cy - 4 * u, 1.5 * u, c);
            LunarDraw.seg(r, cx - 4.4 * u, cy + 1.2 * u, cx - 1.2 * u, cy + 4.4 * u, 1.3 * u, c);
        } else if (has(key, "compass")) {
            dot(r, cx, cy, u, 0, 0, 9.4, c);
            dot(r, cx, cy, u, 0, 0, 7, bg);
            LunarDraw.tri(r, cx, cy - 3.4 * u, cx - 1.5 * u, cy + 0.4 * u, cx + 1.5 * u, cy + 0.4 * u, c);
            LunarDraw.tri(r, cx, cy + 3.4 * u, cx - 1.5 * u, cy - 0.4 * u, cx + 1.5 * u, cy - 0.4 * u, c);
        } else if (has(key, "hole")) {
            box(r, cx, cy, u, -4.5, -4.5, 9, 9, 1.4, c);
            box(r, cx, cy, u, -2.6, -2.6, 5.2, 5.2, 0.8, bg);
        } else if (has(key, "item")) {
            box(r, cx, cy, u, -4, -4, 8, 8, 1.4, c);
            box(r, cx, cy, u, -2, -2, 4, 4, 0.6, bg);
            box(r, cx, cy, u, -0.6, -0.6, 1.2, 1.2, 0.3, c);
        } else if (has(key, "keyboard")) {
            box(r, cx, cy, u, -5, -3.4, 10, 6.8, 1.4, c);
            for (int i = 0; i < 3; i++) box(r, cx, cy, u, -3.6 + i * 2.6, -1.8, 1.8, 1.8, 0.4, bg);
            box(r, cx, cy, u, -3.6, 0.8, 7.2, 1.4, 0.4, bg);
        } else if (has(key, "lag")) {
            LunarDraw.tri(r, cx, cy - 4.8 * u, cx - 5 * u, cy + 4 * u, cx + 5 * u, cy + 4 * u, c);
            box(r, cx, cy, u, -0.5, -1.6, 1, 3.2, 0.4, bg);
            dot(r, cx, cy, u, 0, 2.5, 1.2, bg);
        } else if (has(key, "map")) {
            box(r, cx, cy, u, -4.6, -4, 9.2, 8, 1.2, c);
            LunarDraw.seg(r, cx - 1.5 * u, cy - 3.6 * u, cx - 1.5 * u, cy + 3.6 * u, 0.9 * u, bg);
            LunarDraw.seg(r, cx + 1.5 * u, cy - 3.6 * u, cx + 1.5 * u, cy + 3.6 * u, 0.9 * u, bg);
        } else if (has(key, "info")) {
            dot(r, cx, cy, u, 0, 0, 9.4, c);
            dot(r, cx, cy, u, 0, -2.4, 1.4, bg);
            box(r, cx, cy, u, -0.7, -0.8, 1.4, 4, 0.5, bg);
        } else if (has(key, "radar")) {
            dot(r, cx, cy, u, 0, 0, 9.4, c);
            dot(r, cx, cy, u, 0, 0, 7.4, bg);
            dot(r, cx, cy, u, 0, 0, 4.6, c);
            dot(r, cx, cy, u, 0, 0, 3.2, bg);
            dot(r, cx, cy, u, 0, 0, 1.4, c);
        } else if (has(key, "potion")) {
            box(r, cx, cy, u, -1.2, -4.6, 2.4, 3.2, 0.6, c);
            LunarDraw.tri(r, cx - 1.2 * u, cy - 1.4 * u, cx + 1.2 * u, cy - 1.4 * u, cx - 4 * u, cy + 4.4 * u, c);
            LunarDraw.tri(r, cx + 1.2 * u, cy - 1.4 * u, cx + 4 * u, cy + 4.4 * u, cx - 4 * u, cy + 4.4 * u, c);
        } else if (has(key, "text")) {
            box(r, cx, cy, u, -4, -4, 8, 1.8, 0.5, c);
            box(r, cx, cy, u, -0.9, -4, 1.8, 8.4, 0.5, c);
        } else if (has(key, "active")) {
            box(r, cx, cy, u, -4.5, -3.8, 9, 1.8, 0.8, c);
            box(r, cx, cy, u, -4.5, -0.9, 6.4, 1.8, 0.8, c);
            box(r, cx, cy, u, -4.5, 2, 3.6, 1.8, 0.8, c);
        } else {
            box(r, cx, cy, u, -4, -4, 8, 8, 2, c);
            box(r, cx, cy, u, -2, -2, 4, 4, 1, bg);
        }
    }
}
