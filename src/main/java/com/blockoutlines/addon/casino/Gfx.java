package com.blockoutlines.addon.casino;

import net.minecraft.item.ItemStack;

/** What the casino games draw on: the 2D screen (GuiGfx) or a flat panel standing in the world (WorldGfx). Units are screen pixels. */
public abstract class Gfx {
    public abstract void fill(int x1, int y1, int x2, int y2, int argb);

    public abstract void push();

    public abstract void pop();

    public abstract void translate(float x, float y);

    public abstract void scale(float x, float y);

    /** Text with its top-left corner at (x, y); the letters are 8 px high. */
    public abstract void text(String s, int x, int y, int argb, boolean shadow);

    /** An item icon (16 px) with its top-left corner at (x, y). */
    public abstract void item(ItemStack st, int x, int y);
}
