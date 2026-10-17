package com.blockoutlines.addon.gui;

import com.blockoutlines.addon.modules.ArmorTrimChanger;
import com.blockoutlines.addon.modules.ArmorTrimChanger.Material;
import com.blockoutlines.addon.modules.ArmorTrimChanger.Pattern;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/**
 * Preview screen like minetrim.com: your own character on the left wearing the picked trims, the four armor pieces under
 * it (click one to edit it), and the patterns (smithing templates) and materials to click on the right.
 * Every piece of armor has its own pattern and material.
 */
public class ArmorTrimScreen extends Screen {
    private static final int CELL = 22;
    private static final Item[][] SETS = {
        {Items.DIAMOND_HELMET, Items.DIAMOND_CHESTPLATE, Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS},
        {Items.NETHERITE_HELMET, Items.NETHERITE_CHESTPLATE, Items.NETHERITE_LEGGINGS, Items.NETHERITE_BOOTS},
        {Items.IRON_HELMET, Items.IRON_CHESTPLATE, Items.IRON_LEGGINGS, Items.IRON_BOOTS},
        {Items.GOLDEN_HELMET, Items.GOLDEN_CHESTPLATE, Items.GOLDEN_LEGGINGS, Items.GOLDEN_BOOTS},
        {Items.CHAINMAIL_HELMET, Items.CHAINMAIL_CHESTPLATE, Items.CHAINMAIL_LEGGINGS, Items.CHAINMAIL_BOOTS},
    };
    private static final String[] SET_NAMES = {"Diamond", "Netherite", "Iron", "Gold", "Chainmail"};
    private static final String[] TAB_NAMES = {"Helmet", "Chest", "Legs", "Boots"};

    private final Screen parent;
    private final ArmorTrimChanger module;
    private int set;
    private int slot = 1; // piece being edited: chestplate first

    private int dollX1, dollY1, dollX2, dollY2;
    private int patX, patY, matX, matY, iconX, iconY;
    private ButtonWidget[] tabs;
    private final ItemStack[] patIcons = new ItemStack[Pattern.values().length];
    private final ItemStack[] matIcons = new ItemStack[Material.values().length];

    public ArmorTrimScreen(Screen parent, ArmorTrimChanger module) {
        super(Text.literal("Armor trim preview"));
        this.parent = parent;
        this.module = module;
        module.previewOpen = true;
        for (Pattern p : Pattern.values()) patIcons[p.ordinal()] = stackOf(p.templateItem());
        for (Material m : Material.values()) matIcons[m.ordinal()] = stackOf(m.item);
    }

    private static ItemStack stackOf(String id) {
        Item item = Registries.ITEM.get(Identifier.ofVanilla(id));
        return new ItemStack(item == Items.AIR ? Items.BARRIER : item);
    }

    @Override
    protected void init() {
        int totalW = 150 + 16 + CELL * 6;
        int left = Math.max(6, (width - totalW) / 2);
        int top = Math.max(16, (height - 215) / 2);

        dollX1 = left; dollY1 = top + 10; dollX2 = left + 150; dollY2 = top + 150;
        iconX = dollX1 + (150 - 4 * 34) / 2;
        iconY = dollY2 + 6;

        patX = left + 150 + 16;
        int tabW = (CELL * 6 - 3 * 2) / 4;
        tabs = new ButtonWidget[4];
        for (int i = 0; i < 4; i++) {
            final int idx = i;
            tabs[i] = addDrawableChild(ButtonWidget.builder(Text.literal(tabLabel(i)), b -> selectSlot(idx))
                .dimensions(patX + i * (tabW + 2), top, tabW, 18).build());
        }

        patY = top + 38;
        matX = patX; matY = patY + CELL * 3 + 22;

        int by = matY + CELL * 2 + 12;
        addDrawableChild(ButtonWidget.builder(Text.literal("Armor: " + SET_NAMES[set]), b -> {
            set = (set + 1) % SETS.length;
            b.setMessage(Text.literal("Armor: " + SET_NAMES[set]));
        }).dimensions(patX, by, CELL * 2 + 14, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Copy to all"), b -> copyToAll())
            .dimensions(patX + CELL * 2 + 16, by, CELL * 2 + 14, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> close())
            .dimensions(patX + CELL * 4 + 32, by, CELL * 2 - 4, 20).build());
    }

    private String tabLabel(int i) {
        return (i == slot ? "> " : "") + TAB_NAMES[i] + (module.enabled(i).get() ? "" : " (off)");
    }

    private void selectSlot(int i) {
        slot = i;
        refreshTabs();
    }

    private void refreshTabs() {
        if (tabs == null) return;
        for (int i = 0; i < 4; i++) tabs[i].setMessage(Text.literal(tabLabel(i)));
    }

    private void copyToAll() {
        Pattern p = module.pattern(slot).get();
        Material m = module.material(slot).get();
        for (int i = 0; i < 4; i++) {
            module.pattern(i).set(p);
            module.material(i).set(m);
        }
    }

    @Override
    public void close() {
        module.previewOpen = false;
        client.setScreen(parent);
    }

    @Override
    public void removed() {
        module.previewOpen = false;
        super.removed();
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (super.mouseClicked(click, doubled)) return true;
        double mx = click.x(), my = click.y();

        // click an armor piece under the character to edit it, right click switches that piece on / off
        for (int i = 0; i < 4; i++) {
            int x = iconX + i * 34, y = iconY;
            if (mx >= x && mx < x + 32 && my >= y && my < y + 32) {
                if (click.button() == 1) {
                    module.enabled(i).set(!module.enabled(i).get());
                    slot = i;
                } else if (click.button() == 0) {
                    slot = i;
                }
                refreshTabs();
                return true;
            }
        }

        if (click.button() != 0) return false;
        int i = hit(mx, my, patX, patY, 6, Pattern.values().length);
        if (i >= 0) { module.pattern(slot).set(Pattern.values()[i]); return true; }
        i = hit(mx, my, matX, matY, 6, Material.values().length);
        if (i >= 0) { module.material(slot).set(Material.values()[i]); return true; }
        return false;
    }

    private static int hit(double mx, double my, int x0, int y0, int cols, int count) {
        int cx = (int) Math.floor((mx - x0) / CELL), cy = (int) Math.floor((my - y0) / CELL);
        if (mx < x0 || my < y0 || cx < 0 || cx >= cols || cy < 0) return -1;
        int i = cy * cols + cx;
        return i < count ? i : -1;
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);

        Pattern curP = module.pattern(slot).get();
        Material curM = module.material(slot).get();

        // character
        ctx.fill(dollX1, dollY1, dollX2, dollY2, 0xFF6DB8F5);
        ctx.drawTextWithShadow(textRenderer, "Preview", dollX1, dollY1 - 11, 0xFFFFFFFF);
        if (client != null && client.player != null) {
            try {
                InventoryScreen.drawEntity(ctx, dollX1, dollY1, dollX2, dollY2, 62, 0.0625f, mouseX, mouseY, client.player);
            } catch (Throwable ignored) {
            }
            boolean bare = true;
            for (net.minecraft.entity.EquipmentSlot s : ArmorTrimChanger.SLOTS) {
                if (!client.player.getEquippedStack(s).isEmpty()) bare = false;
            }
            if (bare) {
                ctx.drawCenteredTextWithShadow(textRenderer, "Wear armor to see it on you", (dollX1 + dollX2) / 2, dollY2 - 12, 0xFFFFFFFF);
            }
        }

        // the four armor pieces, each with its own trim (always visible, even without armor on)
        for (int i = 0; i < 4; i++) {
            int x = iconX + i * 34;
            boolean sel = i == slot;
            ctx.fill(x - 1, iconY - 1, x + 33, iconY + 33, sel ? 0xFFFFFFFF : 0xFF2C2C3A);
            ctx.fill(x, iconY, x + 32, iconY + 32, 0xFF3A3A4C);
            ItemStack s = module.icon(SETS[set][i], module.pattern(i).get(), module.material(i).get());
            ctx.getMatrices().pushMatrix();
            ctx.getMatrices().translate(x, iconY);
            ctx.getMatrices().scale(2f, 2f);
            ctx.drawItem(s, 0, 0);
            ctx.getMatrices().popMatrix();
            if (!module.enabled(i).get()) {
                ctx.fill(x, iconY, x + 32, iconY + 32, 0xAA000000);
                ctx.drawCenteredTextWithShadow(textRenderer, "off", x + 16, iconY + 12, 0xFFFF8080);
            }
        }
        ctx.drawTextWithShadow(textRenderer, "Click a piece to edit it, right click = on / off", dollX1 - 2, iconY + 37, 0xFFB0B0B0);

        // patterns
        ctx.drawTextWithShadow(textRenderer, ArmorTrimChanger.SLOT_NAMES[slot] + " pattern: " + curP.name(), patX, patY - 11, 0xFFFFFFFF);
        grid(ctx, patIcons, patX, patY, 6, curP.ordinal(), mouseX, mouseY, Pattern.values());

        // materials
        ctx.drawTextWithShadow(textRenderer, ArmorTrimChanger.SLOT_NAMES[slot] + " material: " + curM.name(), matX, matY - 11, 0xFFFFFFFF);
        grid(ctx, matIcons, matX, matY, 6, curM.ordinal(), mouseX, mouseY, Material.values());
    }

    private void grid(DrawContext ctx, ItemStack[] icons, int x0, int y0, int cols, int selected, int mouseX, int mouseY, Enum<?>[] names) {
        int hover = hit(mouseX, mouseY, x0, y0, cols, icons.length);
        for (int i = 0; i < icons.length; i++) {
            int x = x0 + (i % cols) * CELL, y = y0 + (i / cols) * CELL;
            ctx.fill(x, y, x + CELL - 2, y + CELL - 2, i == hover ? 0xFF4A4A62 : 0xFF2C2C3A);
            if (i == selected) {
                ctx.fill(x - 1, y - 1, x + CELL - 1, y, 0xFFFFFFFF);
                ctx.fill(x - 1, y + CELL - 2, x + CELL - 1, y + CELL - 1, 0xFFFFFFFF);
                ctx.fill(x - 1, y, x, y + CELL - 2, 0xFFFFFFFF);
                ctx.fill(x + CELL - 2, y, x + CELL - 1, y + CELL - 2, 0xFFFFFFFF);
            }
            ctx.drawItem(icons[i], x + 2, y + 2);
        }
        if (hover >= 0) ctx.drawTooltip(textRenderer, Text.literal(names[hover].name()), mouseX, mouseY);
    }
}
