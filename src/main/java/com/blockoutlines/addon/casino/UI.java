package com.blockoutlines.addon.casino;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

import java.util.HashMap;
import java.util.Map;

/** Small drawing helpers shared by the casino: panels, buttons, chips, item icons, card suits. */
public final class UI {
    public static final int BG = 0xFF11111A, PANEL = 0xFF1B1B28, PANEL2 = 0xFF2A2A3D, LINE = 0xFF3A3A52, ACCENT = 0xFF7C5CFF,
        GOOD = 0xFF3DDC84, BAD = 0xFFFF5C5C, GOLD = 0xFFFFC857, TEXT = 0xFFFFFFFF, MUTED = 0xFFB4B4C8, FELT = 0xFF11643F;

    private UI() {}

    private static final Map<Item, ItemStack> STACKS = new HashMap<>();

    public static ItemStack stack(Item item) {
        return STACKS.computeIfAbsent(item, ItemStack::new);
    }

    public static ItemStack diamond() {
        return stack(Items.DIAMOND);
    }

    public static ItemStack emerald() {
        return stack(Items.EMERALD);
    }

    public static ItemStack tnt() {
        return stack(Items.TNT);
    }

    public static TextRenderer tr() {
        return MinecraftClient.getInstance().textRenderer;
    }

    public static boolean in(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    // ------------------------------------------------------------------ shapes

    public static void rect(Gfx c, int x, int y, int w, int h, int col) {
        c.fill(x, y, x + w, y + h, col);
    }

    /** A rectangle with cut corners (looks rounded). */
    public static void round(Gfx c, int x, int y, int w, int h, int col) {
        if (w < 6 || h < 6) {
            rect(c, x, y, w, h, col);
            return;
        }
        c.fill(x + 2, y, x + w - 2, y + h, col);
        c.fill(x, y + 2, x + w, y + h - 2, col);
        c.fill(x + 1, y + 1, x + w - 1, y + h - 1, col);
    }

    /** Vertical gradient. */
    public static void grad(Gfx c, int x, int y, int w, int h, int top, int bottom) {
        int step = 2;
        for (int i = 0; i < h; i += step) {
            c.fill(x, y + i, x + w, y + Math.min(h, i + step), mix(top, bottom, h <= 1 ? 0 : i / (float) (h - 1)));
        }
    }

    public static void outline(Gfx c, int x, int y, int w, int h, int col) {
        c.fill(x, y, x + w, y + 1, col);
        c.fill(x, y + h - 1, x + w, y + h, col);
        c.fill(x, y, x + 1, y + h, col);
        c.fill(x + w - 1, y, x + w, y + h, col);
    }

    /** A card with a soft shadow, a thin lighter edge and a gradient. */
    public static void panel(Gfx c, int x, int y, int w, int h, int col) {
        round(c, x + 1, y + 2, w, h, 0x55000000);
        round(c, x, y, w, h, mix(col, 0xFFFFFFFF, 0.10f));
        if (w > 4 && h > 4) grad(c, x + 1, y + 1, w - 2, h - 2, mix(col, 0xFFFFFFFF, 0.04f), mix(col, 0xFF000000, 0.12f));
    }

    /** The table: a wooden rail and green felt that is darker near the edges. */
    public static void felt(Gfx c, int x, int y, int w, int h) {
        round(c, x + 1, y + 2, w, h, 0x55000000);
        round(c, x, y, w, h, 0xFF4A2F1B);
        round(c, x + 2, y + 2, w - 4, h - 4, 0xFF68442A);
        int in = 6;
        UI.rect(c, x + in, y + in, w - in * 2, h - in * 2, FELT);
        for (int i = 0; i < 12; i++) {
            float a = 0.20f * (1 - i / 12f);
            int col = alpha(0xFF000000, a);
            int o = in + i;
            c.fill(x + o, y + o, x + w - o, y + o + 1, col);
            c.fill(x + o, y + h - o - 1, x + w - o, y + h - o, col);
            c.fill(x + o, y + o, x + o + 1, y + h - o, col);
            c.fill(x + w - o - 1, y + o, x + w - o, y + h - o, col);
        }
        outline(c, x + in + 5, y + in + 5, w - 2 * (in + 5), h - 2 * (in + 5), alpha(GOLD, 0.16f));
    }

    /** Filled ellipse. */
    public static void ellipse(Gfx c, float cx, float cy, float rx, float ry, int col) {
        if (rx < 0.5f) rx = 0.5f;
        int r = (int) Math.ceil(ry);
        for (int dy = -r; dy <= r; dy++) {
            double k = 1.0 - (dy * dy) / (double) (ry * ry);
            if (k <= 0) continue;
            double half = rx * Math.sqrt(k);
            c.fill(Math.round((float) (cx - half)), Math.round(cy + dy), Math.round((float) (cx + half)), Math.round(cy + dy) + 1, col);
        }
    }

    public static void diamond(Gfx c, float cx, float cy, int r, int col) {
        for (int dy = -r; dy <= r; dy++) {
            int half = r - Math.abs(dy);
            c.fill(Math.round(cx - half), Math.round(cy + dy), Math.round(cx + half) + 1, Math.round(cy + dy) + 1, col);
        }
    }

    /** A casino chip: a coloured disc with a light ring and its value written on it. */
    public static void chip(Gfx c, float cx, float cy, float r, int col, String label, boolean hover) {
        if (hover) r += 1;
        ellipse(c, cx + 1, cy + 2, r, r, 0x66000000);
        ellipse(c, cx, cy, r, r, 0xFFEFEFF6);
        ellipse(c, cx, cy, r - 2, r - 2, col);
        int tc = luminance(col) > 150 ? 0xFF1A1A22 : 0xFFFFFFFF;
        plain(c, cx, cy + 0.5f, 0.6f, label, tc);
    }

    private static final long[] DEN = {1_000_000L, 100_000L, 10_000L, 1_000L, 100L, 1L};
    private static final int[] DEN_COL = {0xFF8A4DFF, 0xFF2A2A35, 0xFF3F6BE0, 0xFFD94A4A, 0xFFE4E4EC, 0xFF8C8CA0};

    /** The bet as stacks of chips: bigger chips first, centered on cx, standing on baseY. */
    public static void chipStack(Gfx c, float cx, float baseY, long amount) {
        if (amount <= 0) return;
        int[] kinds = new int[4];
        int[] counts = new int[4];
        int n = 0;
        long rem = amount;
        for (int k = 0; k < DEN.length && n < 4; k++) {
            long cnt = rem / DEN[k];
            if (cnt <= 0) continue;
            kinds[n] = k;
            counts[n] = (int) Math.min(cnt, k == 0 ? 12 : 8);
            rem -= cnt * DEN[k];
            n++;
        }
        for (int i = 0; i < n; i++) {
            float sx = cx + (i - (n - 1) / 2f) * 26;
            int col = DEN_COL[kinds[i]];
            for (int j = 0; j < counts[i]; j++) {
                float y = baseY - j * 3;
                ellipse(c, sx, y + 2, 12, 5, 0x55000000);
                ellipse(c, sx, y + 1, 11, 5, mix(col, 0xFF000000, 0.45f));
                ellipse(c, sx, y, 11, 4.5f, col);
                ellipse(c, sx, y, 7.5f, 3f, mix(col, 0xFFFFFFFF, 0.5f));
                ellipse(c, sx, y, 5.5f, 2f, col);
            }
        }
    }

    /** A pulsing coloured frame around a part of the table (used to show who won). */
    public static void glow(Gfx c, int x, int y, int w, int h, int col, long since) {
        long age = System.currentTimeMillis() - since;
        if (age < 0 || age > 4000) return;
        float k = (float) (0.5 + 0.5 * Math.sin(age / 140.0));
        float fade = age > 3200 ? 1f - (age - 3200) / 800f : 1f;
        for (int i = 0; i < 3; i++) outline(c, x - i, y - i, w + 2 * i, h + 2 * i, alpha(col, (0.35f + 0.5f * k) * fade / (1 + i)));
    }

    private static int luminance(int col) {
        return (((col >> 16) & 255) * 3 + ((col >> 8) & 255) * 6 + (col & 255)) / 10;
    }

    // ------------------------------------------------------------------ text and icons

    public static void text(Gfx c, String s, int x, int y, int col) {
        c.text(s, x, y, col, true);
    }

    public static void center(Gfx c, String s, int cx, int y, int col) {
        c.text(s, cx - tr().getWidth(s) / 2, y, col, true);
    }

    public static void right(Gfx c, String s, int rx, int y, int col) {
        c.text(s, rx - tr().getWidth(s), y, col, true);
    }

    /** Text centered on (cx, cy) with its own scale in x and y. */
    public static void scaled(Gfx c, float cx, float cy, float sx, float sy, String s, int col) {
        if (sx < 0.05f || sy < 0.05f) return;
        c.push();
        c.translate(cx, cy);
        c.scale(sx, sy);
        c.text(s, -tr().getWidth(s) / 2, -4, col, true);
        c.pop();
    }

    /** Text without a shadow, centered on (cx, cy) (for dark text on light cards). */
    public static void plain(Gfx c, float cx, float cy, float sc, String s, int col) {
        if (sc < 0.05f) return;
        c.push();
        c.translate(cx, cy);
        c.scale(sc, sc);
        c.text(s, -tr().getWidth(s) / 2, -4, col, false);
        c.pop();
    }

    /** A real item icon centered on (cx, cy), stretched by sx / sy (16 px at scale 1). */
    public static void item(Gfx c, ItemStack st, float cx, float cy, float sx, float sy) {
        if (sx < 0.08f || sy < 0.08f) return;
        c.push();
        c.translate(cx, cy);
        c.scale(sx, sy);
        c.item(st, -8, -8);
        c.pop();
    }

    // ------------------------------------------------------------------ card suits

    private static final int GW = 9, GH = 10;
    private static final boolean[][][] MASK = new boolean[4][GH][GW];

    static {
        for (int y = 0; y < GH; y++) {
            for (int x = 0; x < GW; x++) {
                double px = x + 0.5, py = y + 0.5;
                MASK[1][y][x] = heart(px, py);
                MASK[0][y][x] = heart(px, 9.2 - py + 0.4) && py < 8.2 || (py >= 7.2 && Math.abs(px - 4.5) <= (py >= 9 ? 2.2 : 1.0));
                MASK[2][y][x] = Math.abs(px - 4.5) / 4.5 + Math.abs(py - 5.0) / 5.0 <= 1.0;
                MASK[3][y][x] = circle(px, py, 4.5, 2.6, 2.5) || circle(px, py, 2.4, 5.6, 2.5) || circle(px, py, 6.6, 5.6, 2.5)
                    || (py >= 5.5 && Math.abs(px - 4.5) <= (py >= 8.6 ? 2.3 : 0.95));
            }
        }
    }

    private static boolean circle(double x, double y, double cx, double cy, double r) {
        return (x - cx) * (x - cx) + (y - cy) * (y - cy) <= r * r;
    }

    private static boolean heart(double x, double y) {
        if (circle(x, y, 2.6, 2.7, 2.65) || circle(x, y, 6.4, 2.7, 2.65)) return true;
        return y >= 2.7 && y <= 9.4 && Math.abs(x - 4.5) <= 4.9 * (9.4 - y) / 6.7;
    }

    /** A card suit from a small pixel mask (clean at any whole size): 0 spade, 1 heart, 2 diamond, 3 club. One mask cell is p pixels. */
    public static void pix(Gfx c, int suit, float cx, float cy, int p, int col) {
        int x0 = Math.round(cx - GW * p / 2f), y0 = Math.round(cy - GH * p / 2f);
        boolean[][] m = MASK[suit];
        for (int y = 0; y < GH; y++) {
            int x = 0;
            while (x < GW) {
                if (!m[y][x]) {
                    x++;
                    continue;
                }
                int e = x;
                while (e < GW && m[y][e]) e++;
                c.fill(x0 + x * p, y0 + y * p, x0 + e * p, y0 + (y + 1) * p, col);
                x = e;
            }
        }
    }

    // 0 spade, 1 heart, 2 diamond, 3 club. BIG are about 11 px, SMALL about 7 px.
    private static final String[][] BIG = {
        {".....#.....", "....###....", "...#####...", "..#######..", ".#########.", "###########", "###########", "###########", ".####.####.", "....###....", "...#####..."},
        {"..###.###..", ".#########.", "###########", "###########", "###########", ".#########.", "..#######..", "...#####...", "....###....", ".....#....."},
        {"....#....", "...###...", "..#####..", ".#######.", "#########", "#########", ".#######.", "..#####..", "...###...", "....#...."},
        {"....###....", "...#####...", "...#####...", ".##.###.##.", "####.#.####", "###########", "###########", ".###.#.###.", ".....#.....", "....###....", "...#####..."},
    };
    private static final String[][] SMALL = {
        {"...#...", "..###..", ".#####.", "#######", "#######", "...#...", "..###.."},
        {".##.##.", "#######", "#######", ".#####.", "..###..", "...#..."},
        {"...#...", "..###..", ".#####.", "#######", ".#####.", "..###..", "...#..."},
        {"..###..", "..###..", "##.#.##", "#######", "##.#.##", "...#...", "..###.."},
    };

    /** A card suit as a small pixel picture, centered on (cx, cy). cell = pixels per dot, inv = upside down. */
    public static void suit(Gfx c, int suit, float cx, float cy, int cell, boolean big, int col, boolean inv) {
        String[] rows = (big ? BIG : SMALL)[Math.max(0, Math.min(3, suit))];
        int h = rows.length, w = rows[0].length();
        int x0 = Math.round(cx - w * cell / 2f), y0 = Math.round(cy - h * cell / 2f);
        for (int r = 0; r < h; r++) {
            String line = rows[inv ? h - 1 - r : r];
            int start = -1;
            for (int i = 0; i <= w; i++) {
                boolean on = i < w && line.charAt(i) == '#';
                if (on && start < 0) start = i;
                if (!on && start >= 0) {
                    c.fill(x0 + start * cell, y0 + r * cell, x0 + i * cell, y0 + (r + 1) * cell, col);
                    start = -1;
                }
            }
        }
    }

    // ------------------------------------------------------------------ animation maths

    public static float easeOut(float t) {
        t = Math.max(0, Math.min(1, t));
        return 1 - (1 - t) * (1 - t) * (1 - t);
    }

    public static float easeBack(float t) {
        t = Math.max(0, Math.min(1, t));
        float s = 1.70158f;
        t -= 1;
        return t * t * ((s + 1) * t + s) + 1;
    }

    public static int alpha(int col, float a) {
        int al = Math.max(0, Math.min(255, (int) (((col >>> 24) & 0xFF) * a)));
        return (al << 24) | (col & 0xFFFFFF);
    }

    public static int mix(int a, int b, float t) {
        int r = (int) (((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
        int g = (int) (((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
        int bl = (int) ((a & 255) * (1 - t) + (b & 255) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    /** A clickable button that is drawn by hand, with a lit top edge and a darker bottom edge. */
    public static final class Btn {
        public int x, y, w, h;
        public String label;
        public int color = PANEL2;
        public boolean enabled = true;

        public Btn(String label, int color) {
            this.label = label;
            this.color = color;
        }

        public Btn at(int x, int y, int w, int h) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            return this;
        }

        public boolean hit(int mx, int my) {
            return enabled && in(mx, my, x, y, w, h);
        }

        public void draw(Gfx c, int mx, int my) {
            boolean hover = enabled && in(mx, my, x, y, w, h);
            int col = enabled ? color : 0xFF22222C;
            if (hover) col = mix(col, 0xFFFFFFFF, 0.16f);
            round(c, x, y + 1, w, h, mix(col, 0xFF000000, 0.45f));
            round(c, x, y, w, h, col);
            if (enabled) c.fill(x + 2, y + 1, x + w - 2, y + 2, mix(col, 0xFFFFFFFF, 0.28f));
            center(c, label, x + w / 2, y + (h - 8) / 2 + 1, enabled ? TEXT : 0xFF6A6A7A);
        }
    }
}
