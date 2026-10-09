package com.blockoutlines.addon.gui;

import com.blockoutlines.addon.util.PixelGrid;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import java.util.function.Consumer;

/**
 * A pixel editor for the crosshair: a 32 x 32 grid where every cell is white, black or empty.
 * Left click paints with the chosen tool, right click erases. Escape or Cancel throws the changes away.
 */
public class CrosshairEditorScreen extends Screen {
    private static final int PANEL_WIDTH = 110;

    private final Screen parent;
    private final PixelGrid grid;
    private final Consumer<PixelGrid> onSave;

    private byte tool = PixelGrid.WHITE;
    private ButtonWidget whiteButton, blackButton, eraseButton;

    private int cell, gridX, gridY, panelX, panelY;

    private boolean dragging;
    private byte dragValue;
    private int lastX = -1, lastY = -1;

    public CrosshairEditorScreen(Screen parent, PixelGrid initial, Consumer<PixelGrid> onSave) {
        super(Text.literal("Draw crosshair"));
        this.parent = parent;
        this.grid = initial;
        this.onSave = onSave;
    }

    @Override
    protected void init() {
        int available = Math.max(PixelGrid.SIZE * 4, height - 44);
        int byHeight = available / PixelGrid.SIZE;
        int byWidth = (width - PANEL_WIDTH - 16 - 12) / PixelGrid.SIZE;
        cell = Math.max(4, Math.min(16, Math.min(byHeight, byWidth)));

        int gridPx = cell * PixelGrid.SIZE;
        int total = gridPx + 16 + PANEL_WIDTH;
        gridX = Math.max(6, (width - total) / 2);
        gridY = Math.max(18, (height - gridPx) / 2);
        panelX = gridX + gridPx + 16;
        panelY = Math.max(18, Math.min(gridY, height - 216));

        int y = panelY + 52; // below the preview
        whiteButton = addDrawableChild(button("White", y, () -> setTool(PixelGrid.WHITE)));
        blackButton = addDrawableChild(button("Black", y + 24, () -> setTool(PixelGrid.BLACK)));
        eraseButton = addDrawableChild(button("Erase", y + 48, () -> setTool(PixelGrid.EMPTY)));
        addDrawableChild(button("Clear all", y + 80, grid::clear));
        addDrawableChild(button("Save", y + 112, this::save));
        addDrawableChild(button("Cancel", y + 136, this::close));

        setTool(tool);
    }

    private ButtonWidget button(String label, int y, Runnable action) {
        return new ButtonWidget.Builder(Text.literal(label), b -> action.run())
            .dimensions(panelX, y, PANEL_WIDTH, 20)
            .build();
    }

    private void setTool(byte t) {
        tool = t;
        whiteButton.setMessage(Text.literal((t == PixelGrid.WHITE ? "> " : "") + "White"));
        blackButton.setMessage(Text.literal((t == PixelGrid.BLACK ? "> " : "") + "Black"));
        eraseButton.setMessage(Text.literal((t == PixelGrid.EMPTY ? "> " : "") + "Erase"));
    }

    private void save() {
        onSave.accept(grid);
        close();
    }

    @Override
    public void close() {
        client.setScreen(parent);
    }

    private int cellX(double mouseX) {
        return (int) Math.floor((mouseX - gridX) / cell);
    }

    private int cellY(double mouseY) {
        return (int) Math.floor((mouseY - gridY) / cell);
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (super.mouseClicked(click, doubled)) return true;

        int cx = cellX(click.x()), cy = cellY(click.y());
        if (!PixelGrid.inside(cx, cy)) return false;

        if (click.button() == 0) dragValue = tool;                 // left click: paint with the chosen tool
        else if (click.button() == 1) dragValue = PixelGrid.EMPTY; // right click: erase
        else return false;

        dragging = true;
        grid.set(cx, cy, dragValue);
        lastX = cx;
        lastY = cy;
        return true;
    }

    @Override
    public boolean mouseDragged(Click click, double offsetX, double offsetY) {
        if (!dragging) return super.mouseDragged(click, offsetX, offsetY);

        int cx = cellX(click.x()), cy = cellY(click.y());
        grid.line(lastX, lastY, cx, cy, dragValue); // also fills the cells skipped by a fast mouse move
        lastX = cx;
        lastY = cy;
        return true;
    }

    @Override
    public boolean mouseReleased(Click click) {
        dragging = false;
        lastX = lastY = -1;
        return super.mouseReleased(click);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta); // background and buttons

        int gridPx = cell * PixelGrid.SIZE;

        context.drawTextWithShadow(textRenderer, "Left click paints, right click erases", gridX, gridY - 13, 0xFFFFFFFF);

        // frame and cells (a 1 pixel gap after every cell shows the grid lines)
        context.fill(gridX - 2, gridY - 2, gridX + gridPx + 2, gridY + gridPx + 2, 0xFF1A1A1A);
        context.fill(gridX, gridY, gridX + gridPx, gridY + gridPx, 0xFF222222);
        for (int y = 0; y < PixelGrid.SIZE; y++) {
            for (int x = 0; x < PixelGrid.SIZE; x++) {
                byte v = grid.get(x, y);
                int color = v == PixelGrid.WHITE ? 0xFFFFFFFF
                    : v == PixelGrid.BLACK ? 0xFF000000
                    : (((x + y) & 1) == 0 ? 0xFF6B6B6B : 0xFF5E5E5E);
                int px = gridX + x * cell, py = gridY + y * cell;
                context.fill(px, py, px + cell - 1, py + cell - 1, color);
            }
        }

        // the crosshair centre lies between cell 15 and 16: mark it inside the grid gaps
        int mid = (PixelGrid.SIZE / 2) * cell - 1;
        context.fill(gridX + mid, gridY, gridX + mid + 1, gridY + gridPx, 0xFFB38F00);
        context.fill(gridX, gridY + mid, gridX + gridPx, gridY + mid + 1, 0xFFB38F00);

        // hovered cell
        int hx = cellX(mouseX), hy = cellY(mouseY);
        if (PixelGrid.inside(hx, hy)) {
            int px = gridX + hx * cell, py = gridY + hy * cell;
            outline(context, px, py, px + cell - 1, py + cell - 1, 0xFFFFD400);
        }

        context.drawTextWithShadow(textRenderer, "Set size = 32 in the module for 1:1", gridX, gridY + gridPx + 6, 0xFFB0B0B0);

        // real size preview
        context.drawTextWithShadow(textRenderer, "Preview (32 x 32)", panelX, panelY, 0xFFFFFFFF);
        int pvY = panelY + 12;
        context.fill(panelX, pvY, panelX + PixelGrid.SIZE, pvY + PixelGrid.SIZE, 0xFF707070);
        for (int y = 0; y < PixelGrid.SIZE; y++) {
            for (int x = 0; x < PixelGrid.SIZE; x++) {
                byte v = grid.get(x, y);
                if (v == PixelGrid.EMPTY) continue;
                context.fill(panelX + x, pvY + y, panelX + x + 1, pvY + y + 1, v == PixelGrid.WHITE ? 0xFFFFFFFF : 0xFF000000);
            }
        }
    }

    private static void outline(DrawContext c, int x1, int y1, int x2, int y2, int color) {
        c.fill(x1, y1, x2, y1 + 1, color);
        c.fill(x1, y2 - 1, x2, y2, color);
        c.fill(x1, y1, x1 + 1, y2, color);
        c.fill(x2 - 1, y1, x2, y2, color);
    }
}
