package com.blockoutlines.addon.casino;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.ItemStack;

/** Drawing on the normal 2D screen. */
public final class GuiGfx extends Gfx {
    private final DrawContext c;

    public GuiGfx(DrawContext c) {
        this.c = c;
    }

    @Override
    public void fill(int x1, int y1, int x2, int y2, int argb) {
        c.fill(x1, y1, x2, y2, argb);
    }

    @Override
    public void push() {
        c.getMatrices().pushMatrix();
    }

    @Override
    public void pop() {
        c.getMatrices().popMatrix();
    }

    @Override
    public void translate(float x, float y) {
        c.getMatrices().translate(x, y);
    }

    @Override
    public void scale(float x, float y) {
        c.getMatrices().scale(x, y);
    }

    @Override
    public void text(String s, int x, int y, int argb, boolean shadow) {
        if (shadow) c.drawTextWithShadow(UI.tr(), s, x, y, argb);
        else c.drawText(UI.tr(), s, x, y, argb, false);
    }

    @Override
    public void item(ItemStack st, int x, int y) {
        c.drawItem(st, x, y);
    }
}
