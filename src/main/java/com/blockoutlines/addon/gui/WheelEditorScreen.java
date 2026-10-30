package com.blockoutlines.addon.gui;

import com.blockoutlines.addon.modules.CommandWheel;
import com.blockoutlines.addon.util.WheelIcons;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

/** Editor for the Command Wheel: the list of entries on the left, name / command / icon of the picked one on the right. */
public class WheelEditorScreen extends Screen {
    private static final int CELL = 24, COLS = 8;

    private final CommandWheel module;
    private int sel;
    private TextFieldWidget nameField, cmdField;
    private boolean updating;
    private int listX, listY, rightX, iconY, previewX;
    private int rowH = 22;

    public WheelEditorScreen(CommandWheel module) {
        super(Text.literal("Command wheel"));
        this.module = module;
    }

    @Override
    protected void init() {
        int total = 160 + 14 + COLS * CELL;
        int left = Math.max(6, (width - total) / 2);
        // the list has 12 rows and 2 rows of buttons under it: make sure all of it is on the screen
        rowH = Math.max(15, Math.min(22, (height - 62 - 4) / 12));
        int need = 62 + 12 * rowH;
        int top = Math.max(4, Math.min((height - 250) / 2, height - need));
        listX = left;
        listY = top + 14;
        rightX = left + 174;

        addDrawableChild(ButtonWidget.builder(Text.literal("+ Add"), b -> add()).dimensions(listX, listY + 12 * rowH + 4, 76, 18).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Delete"), b -> remove()).dimensions(listX + 80, listY + 12 * rowH + 4, 76, 18).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Up"), b -> move(-1)).dimensions(listX, listY + 12 * rowH + 24, 76, 18).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Down"), b -> move(1)).dimensions(listX + 80, listY + 12 * rowH + 24, 76, 18).build());

        nameField = new TextFieldWidget(textRenderer, rightX, top + 24, COLS * CELL, 18, Text.literal("Name"));
        nameField.setMaxLength(16);
        nameField.setChangedListener(s -> { if (!updating && current() != null) { current().label = s; module.save(); } });
        addDrawableChild(nameField);
        cmdField = new TextFieldWidget(textRenderer, rightX, top + 60, COLS * CELL, 18, Text.literal("Command"));
        cmdField.setMaxLength(100);
        cmdField.setChangedListener(s -> { if (!updating && current() != null) { current().command = s; module.save(); } });
        addDrawableChild(cmdField);

        iconY = top + 98;
        int gridRows = (WheelIcons.TEMPLATES.length + COLS - 1) / COLS;
        int by = iconY + gridRows * CELL + 6;
        addDrawableChild(ButtonWidget.builder(Text.literal("Upload picture..."), b -> upload()).dimensions(rightX, by, 120, 18).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> close()).dimensions(rightX, by + 24, 80, 18).build());
        previewX = rightX + 126;

        refreshFields();
    }

    private CommandWheel.Item current() {
        List<CommandWheel.Item> l = module.items();
        return sel >= 0 && sel < l.size() ? l.get(sel) : null;
    }

    private void refreshFields() {
        updating = true;
        CommandWheel.Item it = current();
        nameField.setText(it == null ? "" : it.label);
        cmdField.setText(it == null ? "" : it.command);
        nameField.setEditable(it != null);
        cmdField.setEditable(it != null);
        updating = false;
    }

    private void add() {
        if (module.items().size() >= 12) return;
        module.items().add(new CommandWheel.Item("New", "/", "item", "paper"));
        sel = module.items().size() - 1;
        module.save();
        refreshFields();
    }

    private void remove() {
        if (current() == null) return;
        module.items().remove(sel);
        if (sel >= module.items().size()) sel = module.items().size() - 1;
        module.save();
        refreshFields();
    }

    private void move(int d) {
        int j = sel + d;
        List<CommandWheel.Item> l = module.items();
        if (current() == null || j < 0 || j >= l.size()) return;
        CommandWheel.Item a = l.get(sel);
        l.set(sel, l.get(j));
        l.set(j, a);
        sel = j;
        module.save();
    }

    private void upload() {
        if (current() == null) return;
        try {
            PointerBuffer filters = org.lwjgl.BufferUtils.createPointerBuffer(1);
            filters.put(MemoryUtil.memASCII("*.png"));
            filters.rewind();
            String path = TinyFileDialogs.tinyfd_openFileDialog("Select an icon (PNG)", null, filters, "PNG pictures", false);
            if (path != null) setFileIcon(new File(path));
        } catch (Throwable t) {
            // the file dialog is not available: drop a picture on this window instead
        }
    }

    private void setFileIcon(File f) {
        try {
            String name = WheelIcons.importFile(f);
            if (current() != null) {
                current().kind = "file";
                current().value = name;
                module.save();
            }
        } catch (Exception ignored) {
        }
    }

    // a .png dropped on the window
    public void onFilesDropped(List<Path> paths) {
        for (Path p : paths) {
            if (p.toString().toLowerCase().endsWith(".png")) {
                setFileIcon(p.toFile());
                return;
            }
        }
    }

    @Override
    public void close() {
        module.save();
        client.setScreen(null);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (super.mouseClicked(click, doubled)) return true;
        if (click.button() != 0) return false;
        double mx = click.x(), my = click.y();

        // list
        List<CommandWheel.Item> l = module.items();
        for (int i = 0; i < l.size(); i++) {
            int y = listY + i * rowH;
            if (mx >= listX && mx < listX + 156 && my >= y && my < y + rowH - 2) {
                sel = i;
                refreshFields();
                return true;
            }
        }
        // template icons
        if (current() != null) {
            int cx = (int) Math.floor((mx - rightX) / CELL), cy = (int) Math.floor((my - iconY) / CELL);
            if (mx >= rightX && my >= iconY && cx >= 0 && cx < COLS && cy >= 0) {
                int i = cy * COLS + cx;
                if (i < WheelIcons.TEMPLATES.length) {
                    current().kind = "item";
                    current().value = WheelIcons.TEMPLATES[i];
                    module.save();
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        int top = listY - 14;
        ctx.drawTextWithShadow(textRenderer, "Entries (" + module.items().size() + "/12)", listX, top, 0xFFFFFFFF);

        List<CommandWheel.Item> l = module.items();
        for (int i = 0; i < l.size(); i++) {
            int y = listY + i * rowH;
            CommandWheel.Item it = l.get(i);
            ctx.fill(listX, y, listX + 156, y + rowH - 2, i == sel ? 0xFF4A4A62 : 0xFF2C2C3A);
            WheelIcons.draw(ctx, it.kind, it.value, listX + 11, y + (rowH - 2) / 2, 16);
            ctx.drawTextWithShadow(textRenderer, it.label, listX + 24, y + (rowH - 2 - 8) / 2 + 1, 0xFFFFFFFF);
        }

        ctx.drawTextWithShadow(textRenderer, "Name", rightX, top + 14, 0xFFB8B8C8);
        ctx.drawTextWithShadow(textRenderer, "Command (starts with / to run a command, otherwise it is sent as chat)", rightX, top + 50, 0xFFB8B8C8);
        ctx.drawTextWithShadow(textRenderer, "Icon: click one, or upload / drop a PNG", rightX, iconY - 11, 0xFFB8B8C8);

        CommandWheel.Item cur = current();
        for (int i = 0; i < WheelIcons.TEMPLATES.length; i++) {
            int x = rightX + (i % COLS) * CELL, y = iconY + (i / COLS) * CELL;
            boolean on = cur != null && "item".equals(cur.kind) && WheelIcons.TEMPLATES[i].equals(cur.value);
            ctx.fill(x, y, x + CELL - 2, y + CELL - 2, on ? 0xFF5A78C8 : 0xFF2C2C3A);
            WheelIcons.draw(ctx, "item", WheelIcons.TEMPLATES[i], x + (CELL - 2) / 2, y + (CELL - 2) / 2, 16);
        }
        if (cur != null) {
            int gridRows = (WheelIcons.TEMPLATES.length + COLS - 1) / COLS;
            ctx.drawTextWithShadow(textRenderer, "Now:", previewX, iconY + gridRows * CELL + 11, 0xFFB8B8C8);
            WheelIcons.draw(ctx, cur.kind, cur.value, previewX + 40, iconY + gridRows * CELL + 15, 18);
        }
    }
}
