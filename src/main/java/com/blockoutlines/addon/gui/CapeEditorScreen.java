package com.blockoutlines.addon.gui;

import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.ArrayDeque;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Pixel editor for the cape texture (64 x 32). The coloured outlines show the parts the game really uses:
 * cape outside / inside and the elytra wing (front / back). Left click paints, right click erases.
 */
public class CapeEditorScreen extends Screen {
    public static final int W = 64, H = 32;
    private static final int PANEL_WIDTH = 110;

    private static final int[] PALETTE = {
        0xFFD63031, 0xFFF08020, 0xFFF5CD30, 0xFF3CAF50, 0xFF28BEC8, 0xFF3264DC, 0xFF8C46D2, 0xFFEE64AA,
        0xFFEBEBF0, 0xFF1C1C22, 0xFFB4B4BC, 0xFF5A5A64, 0xFF8B5A2B, 0xFFE0B040, 0xFF14285A, 0xFF1E6E3C
    };

    // face rectangles: x, y, w, h, outline colour, name
    private static final int[][] REGIONS = {
        {1, 1, 10, 16, 0xFFFFD400},   // cape outside
        {12, 1, 10, 16, 0xFFFF8A00},  // cape inside
        {24, 2, 10, 20, 0xFF00E5FF},  // elytra wing front
        {36, 2, 10, 20, 0xFF4D7CFF},  // elytra wing back
    };
    private static final String[] REGION_NAMES = {"Cape (outside)", "Cape (inside)", "Elytra (front)", "Elytra (back)"};

    private enum Tool { PENCIL, FILL, PICK }

    private final Screen parent;
    private final int[] pixels;
    private final Consumer<int[]> onSave;
    private final Supplier<int[]> presetSource;
    private final ArrayDeque<int[]> undo = new ArrayDeque<>();

    private Tool tool = Tool.PENCIL;
    private int color = 0xFF8C46D2;
    private ButtonWidget pencilBtn, fillBtn, pickBtn;
    private TextFieldWidget hex;
    private boolean updatingHex;

    private int cell, gridX, gridY, panelX, panelY, bh, step;
    private boolean dragging;
    private int dragValue;
    private int lastX = -1, lastY = -1;

    public CapeEditorScreen(Screen parent, int[] initial, Supplier<int[]> presetSource, Consumer<int[]> onSave) {
        super(Text.literal("Draw cape"));
        this.parent = parent;
        this.pixels = initial.clone();
        this.presetSource = presetSource;
        this.onSave = onSave;
    }

    @Override
    protected void init() {
        int previewBand = 54;
        int byHeight = (height - 40 - previewBand) / H;
        int byWidth = (width - PANEL_WIDTH - 16 - 12) / W;
        cell = Math.max(3, Math.min(14, Math.min(byHeight, byWidth)));

        int gridW = cell * W, gridH = cell * H;
        int total = gridW + 16 + PANEL_WIDTH;
        gridX = Math.max(6, (width - total) / 2);
        gridY = Math.max(18, (height - gridH - previewBand) / 2);
        panelX = gridX + gridW + 16;
        panelY = Math.max(18, gridY - 6);

        bh = height >= 300 ? 20 : 16;
        step = bh + 2;

        int y = panelY + 52;
        hex = new TextFieldWidget(textRenderer, panelX, y, PANEL_WIDTH, 16, Text.literal("Hex colour"));
        hex.setMaxLength(7);
        hex.setChangedListener(this::hexChanged);
        addDrawableChild(hex);
        setColor(color);

        y += 22;
        pencilBtn = addDrawableChild(button("Pencil", y, () -> setTool(Tool.PENCIL)));
        fillBtn = addDrawableChild(button("Fill", y + step, () -> setTool(Tool.FILL)));
        pickBtn = addDrawableChild(button("Pick colour", y + step * 2, () -> setTool(Tool.PICK)));
        y += step * 3 + 6;
        addDrawableChild(button("Undo", y, this::undo));
        addDrawableChild(button("Clear all", y + step, () -> { pushUndo(); java.util.Arrays.fill(pixels, 0); }));
        addDrawableChild(button("From preset", y + step * 2, () -> {
            pushUndo();
            int[] p = presetSource.get();
            System.arraycopy(p, 0, pixels, 0, Math.min(p.length, pixels.length));
        }));
        y += step * 3 + 6;
        addDrawableChild(button("Save", y, this::save));
        addDrawableChild(button("Cancel", y + step, this::close));
        setTool(tool);
    }

    private ButtonWidget button(String label, int y, Runnable action) {
        return new ButtonWidget.Builder(Text.literal(label), b -> action.run())
            .dimensions(panelX, y, PANEL_WIDTH, bh)
            .build();
    }

    private void setTool(Tool t) {
        tool = t;
        pencilBtn.setMessage(Text.literal((t == Tool.PENCIL ? "> " : "") + "Pencil"));
        fillBtn.setMessage(Text.literal((t == Tool.FILL ? "> " : "") + "Fill"));
        pickBtn.setMessage(Text.literal((t == Tool.PICK ? "> " : "") + "Pick colour"));
    }

    private void setColor(int argb) {
        color = 0xFF000000 | (argb & 0xFFFFFF);
        if (hex != null) {
            updatingHex = true;
            hex.setText(String.format("#%06X", color & 0xFFFFFF));
            updatingHex = false;
        }
    }

    private void hexChanged(String text) {
        if (updatingHex) return;
        String t = text.startsWith("#") ? text.substring(1) : text;
        if (t.length() != 6) return;
        try {
            color = 0xFF000000 | Integer.parseInt(t, 16);
        } catch (NumberFormatException ignored) {
        }
    }

    private void pushUndo() {
        undo.push(pixels.clone());
        while (undo.size() > 30) undo.removeLast();
    }

    private void undo() {
        if (undo.isEmpty()) return;
        System.arraycopy(undo.pop(), 0, pixels, 0, pixels.length);
    }

    private void save() {
        onSave.accept(pixels);
        close();
    }

    @Override
    public void close() {
        client.setScreen(parent);
    }

    private static boolean inside(int x, int y) {
        return x >= 0 && y >= 0 && x < W && y < H;
    }

    private int cellX(double mx) { return (int) Math.floor((mx - gridX) / cell); }
    private int cellY(double my) { return (int) Math.floor((my - gridY) / cell); }

    private void paint(int x, int y, int value) {
        if (inside(x, y)) pixels[y * W + x] = value;
    }

    private void line(int x0, int y0, int x1, int y1, int value) {
        int dx = Math.abs(x1 - x0), dy = Math.abs(y1 - y0);
        int sx = x0 < x1 ? 1 : -1, sy = y0 < y1 ? 1 : -1;
        int err = dx - dy;
        while (true) {
            paint(x0, y0, value);
            if (x0 == x1 && y0 == y1) break;
            int e2 = 2 * err;
            if (e2 > -dy) { err -= dy; x0 += sx; }
            if (e2 < dx) { err += dx; y0 += sy; }
        }
    }

    private void flood(int sx, int sy, int value) {
        int target = pixels[sy * W + sx];
        if (target == value) return;
        ArrayDeque<int[]> stack = new ArrayDeque<>();
        stack.push(new int[]{sx, sy});
        while (!stack.isEmpty()) {
            int[] p = stack.pop();
            int x = p[0], y = p[1];
            if (!inside(x, y) || pixels[y * W + x] != target) continue;
            pixels[y * W + x] = value;
            stack.push(new int[]{x + 1, y});
            stack.push(new int[]{x - 1, y});
            stack.push(new int[]{x, y + 1});
            stack.push(new int[]{x, y - 1});
        }
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (super.mouseClicked(click, doubled)) return true;

        // palette swatches
        int px = (int) click.x(), py = (int) click.y();
        int palY = panelY + 12;
        if (px >= panelX && px < panelX + PANEL_WIDTH && py >= palY && py < palY + 2 * 13 && click.button() == 0) {
            int i = ((py - palY) / 13) * 8 + (px - panelX) / 13;
            if (i >= 0 && i < PALETTE.length) { setColor(PALETTE[i]); return true; }
        }

        int cx = cellX(click.x()), cy = cellY(click.y());
        if (!inside(cx, cy)) return false;

        if (click.button() == 1) {
            pushUndo();
            dragValue = 0;
        } else if (click.button() == 0) {
            if (tool == Tool.PICK) {
                int v = pixels[cy * W + cx];
                if ((v >>> 24) != 0) setColor(v);
                return true;
            }
            pushUndo();
            if (tool == Tool.FILL) {
                flood(cx, cy, color);
                return true;
            }
            dragValue = color;
        } else return false;

        dragging = true;
        paint(cx, cy, dragValue);
        lastX = cx;
        lastY = cy;
        return true;
    }

    @Override
    public boolean mouseDragged(Click click, double offsetX, double offsetY) {
        if (!dragging) return super.mouseDragged(click, offsetX, offsetY);
        int cx = cellX(click.x()), cy = cellY(click.y());
        line(lastX, lastY, cx, cy, dragValue);
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
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);

        int gridW = cell * W, gridH = cell * H;
        ctx.drawTextWithShadow(textRenderer, "Left click paints, right click erases. Outlines = the parts the game uses.", gridX, gridY - 13, 0xFFFFFFFF);

        ctx.fill(gridX - 2, gridY - 2, gridX + gridW + 2, gridY + gridH + 2, 0xFF1A1A1A);
        int gap = cell >= 6 ? 1 : 0;
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int v = pixels[y * W + x];
                int bg = (((x + y) & 1) == 0) ? 0xFF6B6B6B : 0xFF5E5E5E;
                int px = gridX + x * cell, py = gridY + y * cell;
                ctx.fill(px, py, px + cell - gap, py + cell - gap, (v >>> 24) == 0 ? bg : (0xFF000000 | v));
            }
        }
        for (int[] r : REGIONS) {
            outline(ctx, gridX + r[0] * cell, gridY + r[1] * cell, gridX + (r[0] + r[2]) * cell, gridY + (r[1] + r[3]) * cell, r[4]);
        }

        int hx = cellX(mouseX), hy = cellY(mouseY);
        if (inside(hx, hy)) {
            int px = gridX + hx * cell, py = gridY + hy * cell;
            outline(ctx, px, py, px + cell, py + cell, 0xFFFFFFFF);
        }

        // legend under the grid, previews next to it
        int ly = gridY + gridH + 6;
        for (int i = 0; i < REGIONS.length; i++) {
            int lx = gridX + (i % 2) * 120;
            int yy = ly + (i / 2) * 11;
            ctx.fill(lx, yy + 1, lx + 7, yy + 8, REGIONS[i][4]);
            ctx.drawTextWithShadow(textRenderer, REGION_NAMES[i], lx + 11, yy, 0xFFE0E0E0);
        }
        int pvX = gridX + 250;
        drawFace(ctx, pvX, ly, 1, 1, 10, 16, 2, false);
        drawFace(ctx, pvX + 36, ly, 24, 2, 10, 20, 2, false);
        drawFace(ctx, pvX + 36 + 22, ly, 24, 2, 10, 20, 2, true);
        ctx.drawTextWithShadow(textRenderer, "Preview", pvX + 100, ly + 12, 0xFFB0B0B0);

        // panel
        ctx.drawTextWithShadow(textRenderer, "Colours", panelX, panelY, 0xFFFFFFFF);
        for (int i = 0; i < PALETTE.length; i++) {
            int sx = panelX + (i % 8) * 13, sy = panelY + 12 + (i / 8) * 13;
            ctx.fill(sx - 1, sy - 1, sx + 11, sy + 11, PALETTE[i] == color ? 0xFFFFFFFF : 0xFF000000);
            ctx.fill(sx, sy, sx + 10, sy + 10, PALETTE[i]);
        }
        ctx.drawTextWithShadow(textRenderer, "Hex", panelX, panelY + 41, 0xFFB0B0B0);
        ctx.fill(panelX + PANEL_WIDTH - 22, panelY + 38, panelX + PANEL_WIDTH, panelY + 50, 0xFFFFFFFF);
        ctx.fill(panelX + PANEL_WIDTH - 21, panelY + 39, panelX + PANEL_WIDTH - 1, panelY + 49, color);
    }

    private void drawFace(DrawContext ctx, int x, int y, int fx, int fy, int fw, int fh, int scale, boolean mirror) {
        ctx.fill(x - 1, y - 1, x + fw * scale + 1, y + fh * scale + 1, 0xFF3A3A3A);
        for (int v = 0; v < fh; v++) {
            for (int u = 0; u < fw; u++) {
                int su = mirror ? fw - 1 - u : u;
                int c = pixels[(fy + v) * W + fx + su];
                if ((c >>> 24) == 0) continue;
                ctx.fill(x + u * scale, y + v * scale, x + (u + 1) * scale, y + (v + 1) * scale, 0xFF000000 | c);
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
