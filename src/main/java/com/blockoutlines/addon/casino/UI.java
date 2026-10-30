package com.blockoutlines.addon.casino;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
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

    public static void rect(DrawContext c, int x, int y, int w, int h, int col) {
        c.fill(x, y, x + w, y + h, col);
    }

    /** A rectangle with cut corners (looks rounded). */
    public static void round(DrawContext c, int x, int y, int w, int h, int col) {
        if (w < 6 || h < 6) {
            rect(c, x, y, w, h, col);
            return;
        }
        c.fill(x + 2, y, x + w - 2, y + h, col);
        c.fill(x, y + 2, x + w, y + h - 2, col);
        c.fill(x + 1, y + 1, x + w - 1, y + h - 1, col);
    }

    /** Vertical gradient. */
    public static void grad(DrawContext c, int x, int y, int w, int h, int top, int bottom) {
        int step = 2;
        for (int i = 0; i < h; i += step) {
            c.fill(x, y + i, x + w, y + Math.min(h, i + step), mix(top, bottom, h <= 1 ? 0 : i / (float) (h - 1)));
        }
    }

    public static void outline(DrawContext c, int x, int y, int w, int h, int col) {
        c.fill(x, y, x + w, y + 1, col);
        c.fill(x, y + h - 1, x + w, y + h, col);
        c.fill(x, y, x + 1, y + h, col);
        c.fill(x + w - 1, y, x + w, y + h, col);
    }

    /** A card with a soft shadow, a thin lighter edge and a gradient. */
    public static void panel(DrawContext c, int x, int y, int w, int h, int col) {
        round(c, x + 1, y + 2, w, h, 0x55000000);
        round(c, x, y, w, h, mix(col, 0xFFFFFFFF, 0.10f));
        if (w > 4 && h > 4) grad(c, x + 1, y + 1, w - 2, h - 2, mix(col, 0xFFFFFFFF, 0.04f), mix(col, 0xFF000000, 0.12f));
    }

    /** The table: a wooden rail and green felt that is darker near the edges. */
    public static void felt(DrawContext c, int x, int y, int w, int h) {
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
    public static void ellipse(DrawContext c, float cx, float cy, float rx, float ry, int col) {
        if (rx < 0.5f) rx = 0.5f;
        int r = (int) Math.ceil(ry);
        for (int dy = -r; dy <= r; dy++) {
            double k = 1.0 - (dy * dy) / (double) (ry * ry);
            if (k <= 0) continue;
            double half = rx * Math.sqrt(k);
            c.fill(Math.round((float) (cx - half)), Math.round(cy + dy), Math.round((float) (cx + half)), Math.round(cy + dy) + 1, col);
        }
    }

    public static void diamond(DrawContext c, float cx, float cy, int r, int col) {
        for (int dy = -r; dy <= r; dy++) {
            int half = r - Math.abs(dy);
            c.fill(Math.round(cx - half), Math.round(cy + dy), Math.round(cx + half) + 1, Math.round(cy + dy) + 1, col);
        }
    }

    /** A casino chip: a coloured disc with a ring and its value written on it. */
    public static void chip(DrawContext c, float cx, float cy, float r, int col, String label, boolean hover) {
        if (hover) r += 1;
        ellipse(c, cx + 1, cy + 2, r, r, 0x66000000);
        ellipse(c, cx, cy, r, r, mix(col, 0xFF000000, 0.35f));
        ellipse(c, cx, cy, r - 1, r - 1, col);
        ellipse(c, cx, cy, r - 3, r - 3, 0xDDFFFFFF);
        ellipse(c, cx, cy, r - 4.5f, r - 4.5f, col);
        int tc = luminance(col) > 150 ? 0xFF1A1A22 : 0xFFFFFFFF;
        plain(c, cx, cy + 0.5f, 0.72f, label, tc);
    }

    private static int luminance(int col) {
        return (((col >> 16) & 255) * 3 + ((col >> 8) & 255) * 6 + (col & 255)) / 10;
    }

    // ------------------------------------------------------------------ text and icons

    public static void text(DrawContext c, String s, int x, int y, int col) {
        c.drawTextWithShadow(tr(), s, x, y, col);
    }

    public static void center(DrawContext c, String s, int cx, int y, int col) {
        c.drawTextWithShadow(tr(), s, cx - tr().getWidth(s) / 2, y, col);
    }

    public static void right(DrawContext c, String s, int rx, int y, int col) {
        c.drawTextWithShadow(tr(), s, rx - tr().getWidth(s), y, col);
    }

    /** Text centered on (cx, cy) with its own scale in x and y. */
    public static void scaled(DrawContext c, float cx, float cy, float sx, float sy, String s, int col) {
        if (sx < 0.05f || sy < 0.05f) return;
        c.getMatrices().pushMatrix();
        c.getMatrices().translate(cx, cy);
        c.getMatrices().scale(sx, sy);
        c.drawTextWithShadow(tr(), s, -tr().getWidth(s) / 2, -4, col);
        c.getMatrices().popMatrix();
    }

    /** Text without a shadow, centered on (cx, cy) (for dark text on light cards). */
    public static void plain(DrawContext c, float cx, float cy, float sc, String s, int col) {
        if (sc < 0.05f) return;
        c.getMatrices().pushMatrix();
        c.getMatrices().translate(cx, cy);
        c.getMatrices().scale(sc, sc);
        c.drawText(tr(), s, -tr().getWidth(s) / 2, -4, col, false);
        c.getMatrices().popMatrix();
    }

    /** A real item icon centered on (cx, cy), stretched by sx / sy (16 px at scale 1). */
    public static void item(DrawContext c, ItemStack st, float cx, float cy, float sx, float sy) {
        if (sx < 0.08f || sy < 0.08f) return;
        c.getMatrices().pushMatrix();
        c.getMatrices().translate(cx, cy);
        c.getMatrices().scale(sx, sy);
        c.drawItem(st, -8, -8);
        c.getMatrices().popMatrix();
    }

    // ------------------------------------------------------------------ card suits

    private static void row(DrawContext c, float cx, float y, float half, int col) {
        if (half < 0) return;
        c.fill(Math.round(cx - half), Math.round(y), Math.round(cx + half) + 1, Math.round(y) + 1, col);
    }

    private static void vrect(DrawContext c, float x1, float y1, float x2, float y2, int col) {
        c.fill(Math.round(Math.min(x1, x2)), Math.round(Math.min(y1, y2)), Math.round(Math.max(x1, x2)), Math.round(Math.max(y1, y2)), col);
    }

    /** A card suit drawn from shapes: 0 spade, 1 heart, 2 diamond, 3 club. u is the size unit in pixels, inv turns it upside down. */
    public static void suit(DrawContext c, int suit, float cx, float cy, float u, int col, boolean inv) {
        float k = inv ? -1 : 1;
        switch (suit) {
            case 1 -> {
                ellipse(c, cx - 3 * u, cy - k * 2 * u, 3.3f * u, 3.3f * u, col);
                ellipse(c, cx + 3 * u, cy - k * 2 * u, 3.3f * u, 3.3f * u, col);
                int n = Math.max(1, Math.round(7.5f * u));
                for (int i = 0; i < n; i++) row(c, cx, cy + k * (-1.2f * u + i), 6.2f * u * (1 - i / (float) n), col);
            }
            case 0 -> {
                int n = Math.max(1, Math.round(7.5f * u));
                for (int i = 0; i < n; i++) row(c, cx, cy + k * (-6.5f * u + i), 6.2f * u * (i / (float) n), col);
                ellipse(c, cx - 3 * u, cy + k * 0.5f * u, 3.3f * u, 3.3f * u, col);
                ellipse(c, cx + 3 * u, cy + k * 0.5f * u, 3.3f * u, 3.3f * u, col);
                vrect(c, cx - u, cy + k * 2 * u, cx + u, cy + k * 6.5f * u, col);
            }
            case 2 -> {
                int m = Math.round(7 * u);
                for (int dy = -m; dy <= m; dy++) row(c, cx, cy + dy, 5.2f * u * (1 - Math.abs(dy) / (float) m), col);
            }
            default -> {
                ellipse(c, cx, cy - k * 3 * u, 3.1f * u, 3.1f * u, col);
                ellipse(c, cx - 3.3f * u, cy + k * 1.2f * u, 3.1f * u, 3.1f * u, col);
                ellipse(c, cx + 3.3f * u, cy + k * 1.2f * u, 3.1f * u, 3.1f * u, col);
                vrect(c, cx - u, cy, cx + u, cy + k * 6.5f * u, col);
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

        public void draw(DrawContext c, int mx, int my) {
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
