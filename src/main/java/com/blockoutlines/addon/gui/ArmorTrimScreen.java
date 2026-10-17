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
 * Preview screen like minetrim.com: your own character on the left wearing the picked trim, pieces of armor under it,
 * and the patterns (smithing templates) and materials to click on the right.
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

    private final Screen parent;
    private final ArmorTrimChanger module;
    private int set;

    private int dollX1, dollY1, dollX2, dollY2;
    private int patX, patY, matX, matY, iconY;
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
        int top = Math.max(16, (height - 200) / 2);

        dollX1 = left; dollY1 = top + 10; dollX2 = left + 150; dollY2 = top + 150;
        iconY = dollY2 + 6;
        patX = left + 150 + 16; patY = top + 12;
        matX = patX; matY = patY + CELL * 3 + 22;

        int by = matY + CELL * 2 + 12;
        addDrawableChild(ButtonWidget.builder(Text.literal("Armor: " + SET_NAMES[set]), b -> {
            set = (set + 1) % SETS.length;
            b.setMessage(Text.literal("Armor: " + SET_NAMES[set]));
        }).dimensions(patX, by, CELL * 3 - 2, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> close())
            .dimensions(patX + CELL * 3 + 2, by, CELL * 3 - 2, 20).build());
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
        if (click.button() != 0) return false;
        int i = hit(click.x(), click.y(), patX, patY, 6, Pattern.values().length);
        if (i >= 0) { module.pattern.set(Pattern.values()[i]); return true; }
        i = hit(click.x(), click.y(), matX, matY, 6, Material.values().length);
        if (i >= 0) { module.material.set(Material.values()[i]); return true; }
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

        Pattern curP = module.pattern.get();
        Material curM = module.material.get();

        // character
        ctx.fill(dollX1, dollY1, dollX2, dollY2, 0xFF6DB8F5);
        ctx.drawTextWithShadow(textRenderer, "Preview", dollX1, dollY1 - 11, 0xFFFFFFFF);
        if (client != null && client.player != null) {
            try {
                InventoryScreen.drawEntity(ctx, dollX1, dollY1, dollX2, dollY2, 62, 0.0625f, mouseX, mouseY, client.player);
            } catch (Throwable ignored) {
            }
            if (client.player.getEquippedStack(net.minecraft.entity.EquipmentSlot.CHEST).isEmpty()
                && client.player.getEquippedStack(net.minecraft.entity.EquipmentSlot.HEAD).isEmpty()
                && client.player.getEquippedStack(net.minecraft.entity.EquipmentSlot.LEGS).isEmpty()
                && client.player.getEquippedStack(net.minecraft.entity.EquipmentSlot.FEET).isEmpty()) {
                ctx.drawCenteredTextWithShadow(textRenderer, "Wear armor to see it on you", (dollX1 + dollX2) / 2, dollY2 - 12, 0xFFFFFFFF);
            }
        }

        // armor pieces with the picked trim (always visible, even without armor on)
        int ix = dollX1 + (150 - 4 * 34) / 2;
        for (int i = 0; i < 4; i++) {
            ItemStack s = module.icon(SETS[set][i], curP, curM);
            ctx.getMatrices().pushMatrix();
            ctx.getMatrices().translate(ix + i * 34, iconY);
            ctx.getMatrices().scale(2f, 2f);
            ctx.drawItem(s, 0, 0);
            ctx.getMatrices().popMatrix();
        }

        // patterns
        ctx.drawTextWithShadow(textRenderer, "Pattern: " + curP.name(), patX, patY - 11, 0xFFFFFFFF);
        grid(ctx, patIcons, patX, patY, 6, curP.ordinal(), mouseX, mouseY, Pattern.values());

        // materials
        ctx.drawTextWithShadow(textRenderer, "Material: " + curM.name(), matX, matY - 11, 0xFFFFFFFF);
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
