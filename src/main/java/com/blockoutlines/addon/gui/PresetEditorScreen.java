package com.blockoutlines.addon.gui;

import com.blockoutlines.addon.modules.Presets;
import com.blockoutlines.addon.util.WheelIcons;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Editor for the presets. Left: the list of presets. Right: the name, then two tabs: "Icon" (every item of the game,
 * with a search) and "Modules" (click a module to cycle: ignore -> ON -> OFF).
 */
public class PresetEditorScreen extends Screen {
    private static final int CELL = 23, COLS = 12, MOD_ROW = 15, PANE_W = 288, LEFT_W = 140;

    private static List<String> allItemIds;

    private final Presets module;
    private int sel;
    private int tab; // 0 icon, 1 modules
    private TextFieldWidget nameField, searchField;
    private boolean updating;
    private final String[] search = {"", ""};
    private final int[] scroll = {0, 0};
    private List<String> itemsShown = new ArrayList<>();
    private List<Module> modsShown = new ArrayList<>();
    private ButtonWidget exclusiveBtn, tabIcon, tabMods;

    private int left, top, listX, listY, rightX, contentY, visRows, rowH = 20;

    public PresetEditorScreen(Presets module) {
        super(Text.literal("Presets"));
        this.module = module;
    }

    private static List<String> itemIds() {
        if (allItemIds == null) {
            List<String> l = new ArrayList<>();
            for (Item it : Registries.ITEM) {
                if (it == Items.AIR) continue;
                l.add(Registries.ITEM.getId(it).getPath());
            }
            l.sort(Comparator.naturalOrder());
            allItemIds = l;
        }
        return allItemIds;
    }

    @Override
    protected void init() {
        int total = LEFT_W + 10 + PANE_W;
        left = Math.max(4, (width - total) / 2);
        top = height < 330 ? 6 : (height - 310) / 2;
        listX = left;
        listY = top + 14;
        rightX = left + LEFT_W + 10;
        rowH = Math.max(15, Math.min(20, (height - top - 14 - 52) / 12));

        int by = listY + 12 * rowH + 4;
        addDrawableChild(ButtonWidget.builder(Text.literal("+ Add"), b -> add()).dimensions(listX, by, 68, 18).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Delete"), b -> remove()).dimensions(listX + 72, by, 68, 18).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Up"), b -> move(-1)).dimensions(listX, by + 20, 68, 18).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Down"), b -> move(1)).dimensions(listX + 72, by + 20, 68, 18).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> close()).dimensions(listX, by + 40, 140, 18).build());

        nameField = new TextFieldWidget(textRenderer, rightX, top + 24, PANE_W, 18, Text.literal("Name"));
        nameField.setMaxLength(16);
        nameField.setChangedListener(s -> { if (!updating && current() != null) { current().name = s; module.save(); } });
        addDrawableChild(nameField);

        tabIcon = ButtonWidget.builder(Text.literal("Icon"), b -> setTab(0)).dimensions(rightX, top + 48, 60, 18).build();
        tabMods = ButtonWidget.builder(Text.literal("Modules"), b -> setTab(1)).dimensions(rightX + 64, top + 48, 70, 18).build();
        addDrawableChild(tabIcon);
        addDrawableChild(tabMods);
        exclusiveBtn = ButtonWidget.builder(Text.literal(""), b -> toggleExclusive()).dimensions(rightX + 138, top + 48, PANE_W - 138, 18).build();
        addDrawableChild(exclusiveBtn);

        searchField = new TextFieldWidget(textRenderer, rightX + 46, top + 72, PANE_W - 46 - 90, 16, Text.literal("Search"));
        searchField.setMaxLength(32);
        searchField.setChangedListener(s -> { if (!updating) { search[tab] = s; scroll[tab] = 0; refilter(); } });
        addDrawableChild(searchField);
        addDrawableChild(ButtonWidget.builder(Text.literal("Use current"), b -> capture()).dimensions(rightX + PANE_W - 86, top + 71, 86, 18).build());

        contentY = top + 104;
        int avail = height - contentY - 6;
        visRows = Math.max(3, avail / (tab == 0 ? CELL : MOD_ROW));
        refreshFields();
    }

    private Presets.Preset current() {
        List<Presets.Preset> l = module.presets();
        return sel >= 0 && sel < l.size() ? l.get(sel) : null;
    }

    private void setTab(int t) {
        tab = t;
        updating = true;
        searchField.setText(search[tab]);
        updating = false;
        int avail = height - contentY - 6;
        visRows = Math.max(3, avail / (tab == 0 ? CELL : MOD_ROW));
        refilter();
    }

    private void refilter() {
        String q = search[tab].toLowerCase(Locale.ROOT).trim();
        if (tab == 0) {
            itemsShown = new ArrayList<>();
            for (String id : itemIds()) if (q.isEmpty() || id.contains(q)) itemsShown.add(id);
        } else {
            modsShown = new ArrayList<>();
            for (Module m : Modules.get().getAll()) {
                if (m == null) continue;
                if (q.isEmpty() || m.title.toLowerCase(Locale.ROOT).contains(q) || m.name.contains(q)) modsShown.add(m);
            }
            modsShown.sort(Comparator.comparing(m -> m.title.toLowerCase(Locale.ROOT)));
        }
        clampScroll();
    }

    private int rowsTotal() {
        return tab == 0 ? (itemsShown.size() + COLS - 1) / COLS : modsShown.size();
    }

    private void clampScroll() {
        int max = Math.max(0, rowsTotal() - visRows);
        scroll[tab] = Math.max(0, Math.min(max, scroll[tab]));
    }

    private void refreshFields() {
        updating = true;
        Presets.Preset p = current();
        nameField.setText(p == null ? "" : p.name);
        nameField.setEditable(p != null);
        searchField.setText(search[tab]);
        updating = false;
        refilter();
        updateExclusiveLabel();
    }

    private void updateExclusiveLabel() {
        Presets.Preset p = current();
        exclusiveBtn.setMessage(Text.literal("Turn off the rest: " + (p != null && p.exclusive ? "YES" : "NO")));
    }

    private void toggleExclusive() {
        Presets.Preset p = current();
        if (p == null) return;
        p.exclusive = !p.exclusive;
        module.save();
        updateExclusiveLabel();
    }

    private void capture() {
        Presets.Preset p = current();
        if (p == null) return;
        module.capture(p);
    }

    private void add() {
        if (module.presets().size() >= 16) return;
        module.presets().add(new Presets.Preset("New preset", "paper", false));
        sel = module.presets().size() - 1;
        module.save();
        refreshFields();
    }

    private void remove() {
        if (current() == null) return;
        module.presets().remove(sel);
        if (sel >= module.presets().size()) sel = module.presets().size() - 1;
        module.save();
        refreshFields();
    }

    private void move(int d) {
        int j = sel + d;
        List<Presets.Preset> l = module.presets();
        if (current() == null || j < 0 || j >= l.size()) return;
        Presets.Preset a = l.get(sel);
        l.set(sel, l.get(j));
        l.set(j, a);
        sel = j;
        module.save();
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
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (mouseX >= rightX && mouseY >= contentY) {
            scroll[tab] -= (int) Math.signum(verticalAmount) * (tab == 0 ? 2 : 3);
            clampScroll();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (super.mouseClicked(click, doubled)) return true;
        if (click.button() != 0) return false;
        double mx = click.x(), my = click.y();
        List<Presets.Preset> l = module.presets();

        for (int i = 0; i < l.size() && i < 12; i++) {
            int y = listY + i * rowH;
            if (mx >= listX && mx < listX + LEFT_W && my >= y && my < y + rowH - 2) {
                sel = i;
                refreshFields();
                return true;
            }
        }
        Presets.Preset p = current();
        if (p == null || mx < rightX || my < contentY) return false;

        if (tab == 0) {
            int cx = (int) Math.floor((mx - rightX) / CELL), cy = (int) Math.floor((my - contentY) / CELL);
            if (cx >= 0 && cx < COLS && cy >= 0 && cy < visRows) {
                int i = (cy + scroll[0]) * COLS + cx;
                if (i < itemsShown.size()) {
                    p.kind = "item";
                    p.value = itemsShown.get(i);
                    module.save();
                    return true;
                }
            }
        } else {
            int row = (int) Math.floor((my - contentY) / (double) MOD_ROW);
            if (row >= 0 && row < visRows && mx < rightX + PANE_W) {
                int i = row + scroll[1];
                if (i < modsShown.size()) {
                    cycle(p, modsShown.get(i).name);
                    module.save();
                    return true;
                }
            }
        }
        return false;
    }

    /** ignore -> ON -> OFF -> ignore */
    private void cycle(Presets.Preset p, String name) {
        if (p.on.contains(name)) {
            p.on.remove(name);
            p.off.add(name);
        } else if (p.off.contains(name)) {
            p.off.remove(name);
        } else {
            p.on.add(name);
        }
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        List<Presets.Preset> l = module.presets();
        ctx.drawTextWithShadow(textRenderer, "Presets (" + l.size() + "/16)  -  key: hold, pick, release", listX, top, 0xFFFFFFFF);

        for (int i = 0; i < l.size() && i < 12; i++) {
            int y = listY + i * rowH;
            Presets.Preset p = l.get(i);
            ctx.fill(listX, y, listX + LEFT_W, y + rowH - 2, i == sel ? 0xFF4A4A62 : 0xFF2C2C3A);
            WheelIcons.draw(ctx, p.kind, p.value, listX + 11, y + (rowH - 2) / 2, 16);
            ctx.drawTextWithShadow(textRenderer, textRenderer.trimToWidth(p.name, LEFT_W - 40), listX + 24, y + (rowH - 2 - 8) / 2 + 1, 0xFFFFFFFF);
            if (module.matches(p)) ctx.fill(listX + LEFT_W - 8, y + 4, listX + LEFT_W - 3, y + 9, 0xFF3DDC84);
        }

        ctx.drawTextWithShadow(textRenderer, "Name", rightX, top + 14, 0xFFB8B8C8);
        ctx.drawTextWithShadow(textRenderer, "Search", rightX, top + 76, 0xFFB8B8C8);
        // the active tab
        ctx.fill(rightX + (tab == 0 ? 0 : 64), top + 66, rightX + (tab == 0 ? 60 : 134), top + 68, 0xFF5A78C8);

        Presets.Preset p = current();
        if (p == null) {
            ctx.drawTextWithShadow(textRenderer, "Add a preset with + Add", rightX, contentY + 4, 0xFFB8B8C8);
            return;
        }

        if (tab == 0) {
            String hoverName = null;
            for (int r = 0; r < visRows; r++) {
                for (int c = 0; c < COLS; c++) {
                    int i = (r + scroll[0]) * COLS + c;
                    if (i >= itemsShown.size()) break;
                    int x = rightX + c * CELL, y = contentY + r * CELL;
                    String id = itemsShown.get(i);
                    boolean on = "item".equals(p.kind) && id.equals(p.value);
                    boolean over = mouseX >= x && mouseX < x + CELL - 2 && mouseY >= y && mouseY < y + CELL - 2;
                    ctx.fill(x, y, x + CELL - 2, y + CELL - 2, on ? 0xFF5A78C8 : over ? 0xFF44445A : 0xFF2C2C3A);
                    WheelIcons.draw(ctx, "item", id, x + (CELL - 2) / 2, y + (CELL - 2) / 2, 16);
                    if (over) hoverName = WheelIcons.stack(id).getName().getString();
                }
            }
            String info = itemsShown.size() + " items" + (hoverName != null ? "  -  " + hoverName : "  -  scroll for more, click to choose");
            ctx.drawTextWithShadow(textRenderer, textRenderer.trimToWidth(info, PANE_W), rightX, top + 92, 0xFF8888A0);
        } else {
            for (int r = 0; r < visRows; r++) {
                int i = r + scroll[1];
                if (i >= modsShown.size()) break;
                Module m = modsShown.get(i);
                int y = contentY + r * MOD_ROW;
                boolean on = p.on.contains(m.name), off = p.off.contains(m.name);
                ctx.fill(rightX, y, rightX + PANE_W, y + MOD_ROW - 1, 0xFF2C2C3A);
                ctx.drawTextWithShadow(textRenderer, textRenderer.trimToWidth(m.title, PANE_W - 70), rightX + 4, y + 3, m.isActive() ? 0xFFFFFFFF : 0xFFB0B0C0);
                String label = on ? "ON" : off ? "OFF" : "-";
                int col = on ? 0xFF3DDC84 : off ? 0xFFE5534B : 0xFF70708A;
                ctx.fill(rightX + PANE_W - 46, y + 1, rightX + PANE_W - 2, y + MOD_ROW - 2, on ? 0xFF1E4D36 : off ? 0xFF55231F : 0xFF22222E);
                ctx.drawCenteredTextWithShadow(textRenderer, label, rightX + PANE_W - 24, y + 3, col);
            }
            ctx.drawTextWithShadow(textRenderer, textRenderer.trimToWidth(p.on.size() + " on, " + p.off.size() + " off  -  click a module: - / ON / OFF", PANE_W), rightX, top + 92, 0xFF8888A0);
        }
    }
}
