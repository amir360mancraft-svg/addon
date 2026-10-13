package com.blockoutlines.addon.util;

import net.minecraft.client.gui.DrawContext;

/** Small rounded-rectangle helper for flat HUD panels (scanline fills, no textures). */
public final class RoundRect {
    private RoundRect() {}

    public static void draw(DrawContext ctx, int x, int y, int w, int h, int radius, int argb) {
        if (w <= 0 || h <= 0) return;
        int r = Math.max(0, Math.min(radius, Math.min(w, h) / 2));
        if (r == 0) {
            ctx.fill(x, y, x + w, y + h, argb);
            return;
        }
        // straight middle part in one call
        ctx.fill(x, y + r, x + w, y + h - r, argb);
        for (int row = 0; row < r; row++) {
            double dy = r - row - 0.5;
            int inset = (int) Math.round(r - Math.sqrt(Math.max(0.0, r * r - dy * dy)));
            ctx.fill(x + inset, y + row, x + w - inset, y + row + 1, argb);
            ctx.fill(x + inset, y + h - row - 1, x + w - inset, y + h - row, argb);
        }
    }

    /** One pixel outline: outer shape in the border colour, then the caller draws the fill on top (inset by 1). */
    public static void outlined(DrawContext ctx, int x, int y, int w, int h, int radius, int fill, int border) {
        draw(ctx, x, y, w, h, radius, border);
        draw(ctx, x + 1, y + 1, w - 2, h - 2, Math.max(0, radius - 1), fill);
    }
}
