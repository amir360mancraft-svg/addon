package com.blockoutlines.addon.casino;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;

/** Small drawing helpers shared by the casino games. */
public final class UI {
    public static final int BG = 0xFF14141D, PANEL = 0xFF1E1E2B, PANEL2 = 0xFF2A2A3B, LINE = 0xFF3A3A50, ACCENT = 0xFF7C5CFF,
        GOOD = 0xFF3DDC84, BAD = 0xFFFF5C5C, GOLD = 0xFFFFC857, TEXT = 0xFFFFFFFF, MUTED = 0xFFB8B8C8, FELT = 0xFF0F5A3C;

    private UI() {}

    public static TextRenderer tr() {
        return MinecraftClient.getInstance().textRenderer;
    }

    public static boolean in(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

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

    /** A clickable button that is drawn by hand. */
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
            int col = enabled ? color : 0xFF24242F;
            if (enabled && in(mx, my, x, y, w, h)) col = mix(col, 0xFFFFFFFF, 0.18f);
            round(c, x, y, w, h, col);
            center(c, label, x + w / 2, y + (h - 8) / 2, enabled ? TEXT : 0xFF6A6A7A);
        }
    }
}
