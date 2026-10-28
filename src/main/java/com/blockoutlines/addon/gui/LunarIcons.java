package com.blockoutlines.addon.gui;

import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.utils.render.color.Color;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Small flat icons for this addon's own modules, drawn from a few simple shapes (circle, rectangle, triangle,
 * line, star). Coordinates are in "half sizes": 1.0 is the edge of the icon, y points down.
 *
 * One command per entry, separated by |:  c x y d  (circle)   e x y w h  (ellipse)   q x y w h  (rectangle)
 * t x1 y1 x2 y2 x3 y3  (triangle)   s x1 y1 x2 y2 thickness  (line)   p x y points outer inner rotation  (star)
 * and the last letter is the color: f = icon color, h = faded icon color, b = card background, x/y/z/w = red/green/blue/yellow.
 */
final class LunarIcons {
    private LunarIcons() {
    }

    private static final String DATA = """
ring|c 0 0 1.9 f|c 0 0 1.15 b
crosshair|c 0 0 1.7 f|c 0 0 1.3 b|q -0.1 -1 0.2 0.55 f|q -0.1 0.45 0.2 0.55 f|q -1 -0.1 0.55 0.2 f|q 0.45 -0.1 0.55 0.2 f|c 0 0 0.32 f
bolt|t 0.35 -1 -0.5 0.2 0.05 -0.25 f|t 0.05 -0.25 -0.5 0.2 0 0.2 f|t 0.05 -0.25 0 0.2 0.55 -0.25 f|t 0 0.2 -0.3 1 0.55 -0.25 f
sparkle|p 0 0 4 1 0.22 0 f|p 0.62 -0.62 4 0.38 0.1 0 f
burst|p 0 0 8 1 0.5 0 f|c 0 0 0.45 b
ghost|c 0 -0.3 1.7 f|q -0.85 -0.3 1.7 1.0 f|t -0.85 0.7 -0.28 0.7 -0.57 1.0 f|t -0.28 0.7 0.28 0.7 0 1.0 f|t 0.28 0.7 0.85 0.7 0.57 1.0 f|c -0.3 -0.35 0.34 b|c 0.3 -0.35 0.34 b
eye|e 0 0 2 1.3 f|e 0 0 1.55 0.85 b|c 0 0 0.9 f|c 0 0 0.36 b
shield|q -0.8 -0.9 1.6 1.0 f|t -0.8 0.1 0.8 0.1 0 1.0 f|q -0.5 -0.62 1.0 0.62 b|t -0.5 0 0.5 0 0 0.62 b
gem|q -0.55 -0.8 1.1 0.6 f|t -0.55 -0.8 -0.9 -0.2 -0.55 -0.2 f|t 0.55 -0.8 0.9 -0.2 0.55 -0.2 f|t -0.9 -0.2 0.9 -0.2 0 0.95 f|s -0.55 -0.2 0 0.95 0.08 b|s 0.55 -0.2 0 0.95 0.08 b|s -0.9 -0.2 0.9 -0.2 0.08 b
grid|q -0.95 -0.95 0.55 0.55 f|q -0.275 -0.95 0.55 0.55 f|q 0.4 -0.95 0.55 0.55 f|q -0.95 -0.275 0.55 0.55 f|q -0.275 -0.275 0.55 0.55 f|q 0.4 -0.275 0.55 0.55 f|q -0.95 0.4 0.55 0.55 f|q -0.275 0.4 0.55 0.55 f|q 0.4 0.4 0.55 0.55 f
chunk|q -0.95 -0.95 0.55 0.55 f|q -0.275 -0.95 0.55 0.55 h|q 0.4 -0.95 0.55 0.55 h|q -0.95 -0.275 0.55 0.55 h|q -0.275 -0.275 0.55 0.55 f|q 0.4 -0.275 0.55 0.55 h|q -0.95 0.4 0.55 0.55 h|q -0.275 0.4 0.55 0.55 h|q 0.4 0.4 0.55 0.55 f
bulb|c 0 -0.25 1.4 f|q -0.32 0.35 0.64 0.3 f|q -0.2 0.72 0.4 0.15 f|s -0.2 0.1 0 -0.3 0.09 b|s 0 -0.3 0.2 0.1 0.09 b|s 0.98 -0.25 0.8 -0.25 0.12 f|s -0.98 -0.25 -0.8 -0.25 0.12 f|s 0.72 -0.95 0.58 -0.8 0.12 f|s -0.72 -0.95 -0.58 -0.8 0.12 f
house|t -1 -0.1 1 -0.1 0 -0.95 f|q -0.7 -0.1 1.4 1.0 f|q -0.2 0.35 0.4 0.55 b
cloud|c -0.5 0.1 0.95 f|c 0.05 -0.2 1.25 f|c 0.55 0.15 0.85 f|q -0.5 0.1 1.05 0.475 f
trident|q -0.08 -0.55 0.16 1.6 f|q -0.75 -0.15 1.5 0.16 f|q -0.75 -0.7 0.14 0.6 f|q 0.61 -0.7 0.14 0.6 f|t -0.75 -0.7 -0.61 -0.7 -0.68 -1.0 f|t 0.61 -0.7 0.75 -0.7 0.68 -1.0 f|t -0.16 -0.55 0.16 -0.55 0 -1.0 f
person|c 0 -0.4 0.95 f|t -0.45 0.2 0.45 0.2 0.85 0.95 f|t -0.45 0.2 0.85 0.95 -0.85 0.95 f
cube|q -0.9 -0.5 1.4 1.4 f|q -0.7 -0.3 1.0 1.0 b|s -0.9 -0.5 -0.5 -0.9 0.14 f|s 0.5 -0.5 0.9 -0.9 0.14 f|s -0.5 -0.9 0.9 -0.9 0.14 f|s 0.9 -0.9 0.9 0.5 0.14 f|s 0.5 0.9 0.9 0.5 0.14 f
box|q -0.9 -0.3 1.3 1.2 f|t -0.9 -0.3 -0.5 -0.9 0.9 -0.9 h|t -0.9 -0.3 0.9 -0.9 0.4 -0.3 h|t 0.4 -0.3 0.9 -0.9 0.9 0.3 h|t 0.4 -0.3 0.9 0.3 0.4 0.9 h
cone|t 0 -0.95 1.0 0.45 -1.0 0.45 f|e 0 0.5 2.2 0.4 f
palette|c -0.4 -0.25 1.1 x|c 0.4 -0.25 1.1 y|c 0 0.45 1.1 z
cape|t -0.5 -0.9 0.5 -0.9 0.9 0.95 f|t -0.5 -0.9 0.9 0.95 -0.9 0.95 f|s -0.5 -0.75 0.5 -0.75 0.14 b
pill|q -0.55 -0.45 1.1 0.9 f|c -0.55 0 0.9 f|c 0.55 0 0.9 f|c 0.5 0 0.3 b
wings|t -0.1 -0.4 -1 -0.95 -0.75 0.1 f|t -0.1 -0.05 -0.95 -0.15 -0.6 0.6 f|t -0.1 0.25 -0.7 0.35 -0.35 0.95 f|t 0.1 -0.4 1 -0.95 0.75 0.1 f|t 0.1 -0.05 0.95 -0.15 0.6 0.6 f|t 0.1 0.25 0.7 0.35 0.35 0.95 f
picture|q -0.95 -0.8 1.9 1.6 f|q -0.8 -0.65 1.6 1.3 b|t -0.8 0.65 -0.15 -0.2 0.5 0.65 f|t 0.05 0.65 0.5 0.1 0.8 0.65 f|c 0.45 -0.35 0.4 f
nodrop|t 0 -0.95 0.55 0.05 -0.55 0.05 f|c 0 0.35 1.15 f|s -0.9 0.9 0.9 -0.9 0.34 b|s -0.9 0.9 0.9 -0.9 0.14 x
paw|c 0 0.4 1.15 f|c -0.8 -0.1 0.5 f|c -0.3 -0.6 0.5 f|c 0.3 -0.6 0.5 f|c 0.8 -0.1 0.5 f
sign|q -0.95 -0.85 1.9 1.1 f|q -0.1 0.2 0.2 0.8 f|s -0.6 -0.5 0.6 -0.5 0.12 b|s -0.6 -0.15 0.3 -0.15 0.12 b
trail|c 0.7 -0.6 0.9 f|c 0.2 -0.2 0.7 f|c -0.25 0.2 0.5 h|c -0.7 0.6 0.32 h
apple|c -0.3 0.2 1.35 f|c 0.3 0.2 1.35 f|t 0.05 -0.45 0.75 -0.95 0.5 -0.35 y
sword|s -0.75 0.75 0.7 -0.7 0.26 f|s -0.7 0.15 -0.15 0.7 0.2 f|s -0.9 0.9 -0.5 0.5 0.2 h
firefly|c 0 0 1.85 h|c 0 0 1.05 f|c 0 0 0.55 w|c 0.95 -0.8 0.38 f|c -0.95 0.75 0.3 f|c 0.8 0.85 0.22 h
beam|q -0.42 -1 0.84 1.55 h|q -0.16 -1 0.32 1.55 f|e 0 0.72 2.0 0.5 f|e 0 0.72 1.2 0.24 b|p 0 0.72 4 0.34 0.12 0 w
hud|q -0.95 -0.8 1.9 1.6 f|q -0.8 -0.65 1.6 1.3 b|c -0.45 -0.2 0.6 f|q -0.05 -0.4 0.8 0.15 f|q -0.05 -0.05 0.55 0.15 h|q -0.6 0.3 1.2 0.2 f
""";

    private static final String[][] MAP = {
        {"amethyst-finder", "gem"}, {"seed-netherite-finder", "gem"}, {"og-chunk-finder", "chunk"}, {"light-chunk-finder", "bulb"},
        {"sus-chunk", "eye"}, {"base-finder", "house"}, {"better-region-map", "grid"}, {"animated-sky", "cloud"},
        {"starry-sky", "sparkle"}, {"armor-trim-changer", "shield"}, {"auto-trident", "trident"}, {"better-admin-hud", "person"},
        {"better-outline", "cube"}, {"china-hat", "cone"}, {"color-grading", "palette"}, {"custom-cape", "cape"},
        {"custom-crosshair", "crosshair"}, {"target-marker", "crosshair"}, {"custom-glint", "sparkle"}, {"death-echo", "ghost"},
        {"dynamic-island", "pill"}, {"fake-elytra", "wings"}, {"wings", "wings"}, {"hit-effect", "burst"},
        {"kill-effect", "bolt"}, {"item-counter-hud", "box"}, {"item-drop-physics", "box"}, {"jump-circle", "ring"},
        {"menu-background", "picture"}, {"no-water", "nodrop"}, {"pets", "paw"}, {"player-signs", "sign"},
        {"player-trail", "trail"}, {"projectile-trails", "trail"}, {"saturation", "apple"}, {"swing-speed", "sword"},
        {"target-hud", "hud"}, {"fireflies", "firefly"}, {"loot-beams", "beam"}, {"afk-clicker", "sword"}, {"anti-vanish", "eye"}, {"floating-hands", "ghost"}, {"aurora-sky", "cloud"}, {"shader-hand", "palette"}, {"penguin-companion", "paw"}, {"weather-plus", "cloud"}, {"sword-trails", "sword"}, {"anchor-bomb", "sword"}, {"better-name-protector", "eye"}, {"command-wheel", "sword"}, {"emote-wheel", "ghost"},
    };

    private record Cmd(char kind, double[] v, char color) {}

    private static Map<String, List<Cmd>> icons;
    private static final Map<String, String> byModule = new HashMap<>();

    private static void load() {
        icons = new HashMap<>();
        for (String line : DATA.split("\\R")) {
            line = line.trim();
            if (line.isEmpty()) continue;
            String[] parts = line.split("\\|");
            List<Cmd> cmds = new ArrayList<>();
            for (int i = 1; i < parts.length; i++) {
                String[] t = parts[i].trim().split("\\s+");
                double[] v = new double[t.length - 2];
                for (int k = 0; k < v.length; k++) v[k] = Double.parseDouble(t[k + 1]);
                cmds.add(new Cmd(t[0].charAt(0), v, t[t.length - 1].charAt(0)));
            }
            icons.put(parts[0], cmds);
        }
        for (String[] m : MAP) byModule.put(m[0], m[1]);
    }

    static boolean has(String moduleName) {
        if (icons == null) load();
        return byModule.containsKey(moduleName);
    }

    /** Draws the icon of that module; h is the half size in pixels. Returns false when it has none. */
    static boolean draw(GuiRenderer r, String moduleName, double cx, double cy, double h, int[] fg, double alpha, int[] bg) {
        if (icons == null) load();
        String key = byModule.get(moduleName);
        if (key == null) return false;
        List<Cmd> cmds = icons.get(key);
        if (cmds == null) return false;

        Color cf = LunarDraw.col(fg[0], fg[1], fg[2], alpha);
        Color ch = LunarDraw.col(fg[0], fg[1], fg[2], alpha * 0.45);
        Color cb = LunarDraw.col(bg[0], bg[1], bg[2], alpha);
        Color cx_ = LunarDraw.col(240, 70, 80, alpha);
        Color cy_ = LunarDraw.col(70, 210, 120, alpha);
        Color cz = LunarDraw.col(80, 140, 255, alpha);
        Color cw = LunarDraw.col(255, 210, 70, alpha);

        for (Cmd c : cmds) {
            Color col = switch (c.color) {
                case 'h' -> ch;
                case 'b' -> cb;
                case 'x' -> cx_;
                case 'y' -> cy_;
                case 'z' -> cz;
                case 'w' -> cw;
                default -> cf;
            };
            double[] v = c.v;
            switch (c.kind) {
                case 'c' -> LunarDraw.round(r, cx + (v[0] - v[2] / 2) * h, cy + (v[1] - v[2] / 2) * h, v[2] * h, v[2] * h, v[2] * h / 2, col);
                case 'e' -> LunarDraw.round(r, cx + (v[0] - v[2] / 2) * h, cy + (v[1] - v[3] / 2) * h, v[2] * h, v[3] * h, v[3] * h / 2, col);
                case 'q' -> r.quad(cx + v[0] * h, cy + v[1] * h, v[2] * h, v[3] * h, col);
                case 't' -> LunarDraw.tri(r, cx + v[0] * h, cy + v[1] * h, cx + v[2] * h, cy + v[3] * h, cx + v[4] * h, cy + v[5] * h, col);
                case 's' -> LunarDraw.seg(r, cx + v[0] * h, cy + v[1] * h, cx + v[2] * h, cy + v[3] * h, v[4] * h, col);
                case 'p' -> LunarDraw.star(r, cx + v[0] * h, cy + v[1] * h, (int) v[2], v[3] * h, v[4] * h, v[5], col);
                default -> { }
            }
        }
        return true;
    }
}
