package com.blockoutlines.addon.modules;

import com.blockoutlines.addon.BlockOutlinesAddon;
import com.blockoutlines.addon.util.RoundRect;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.ItemListSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

import java.util.ArrayList;
import java.util.List;

/**
 * A small dark panel next to the hotbar: an icon per item with the number you carry under it
 * (totems, crystals, obsidian, pearls ...). Zero counts are dimmed, low counts turn red.
 */
public class ItemCounterHud extends Module {
    public enum Place { RightOfHotbar, LeftOfHotbar, Custom }
    public enum Layout { Horizontal, Vertical }

    private final SettingGroup sg = settings.getDefaultGroup();
    private final SettingGroup sgLook = settings.createGroup("Look");

    private final Setting<List<Item>> items = sg.add(new ItemListSetting.Builder()
        .name("items").description("The items to count (in this order).")
        .defaultValue(List.of(Items.TOTEM_OF_UNDYING, Items.END_CRYSTAL, Items.OBSIDIAN, Items.RESPAWN_ANCHOR,
            Items.GLOWSTONE, Items.ENDER_PEARL, Items.EXPERIENCE_BOTTLE))
        .build());
    private final Setting<Place> place = sg.add(new EnumSetting.Builder<Place>()
        .name("position").description("Where the panel is drawn.")
        .defaultValue(Place.RightOfHotbar).build());
    private final Setting<Layout> layout = sg.add(new EnumSetting.Builder<Layout>()
        .name("layout").description("Items side by side, or one under another.")
        .defaultValue(Layout.Horizontal).build());
    private final Setting<Integer> offsetX = sg.add(new IntSetting.Builder()
        .name("offset-x").description("Move the panel left / right (Custom: distance from the left edge).")
        .defaultValue(0).range(-600, 2000).sliderRange(-200, 800).build());
    private final Setting<Integer> offsetY = sg.add(new IntSetting.Builder()
        .name("offset-y").description("Move the panel up / down (Custom: distance from the top edge).")
        .defaultValue(0).range(-600, 2000).sliderRange(-200, 600).build());
    private final Setting<Boolean> hideEmpty = sg.add(new BoolSetting.Builder()
        .name("hide-empty").description("Do not show items you have none of.")
        .defaultValue(false).build());
    private final Setting<Boolean> countOffhand = sg.add(new BoolSetting.Builder()
        .name("count-offhand-and-armor").description("Also count the offhand and armor slots.")
        .defaultValue(true).build());
    private final Setting<Integer> lowAt = sg.add(new IntSetting.Builder()
        .name("low-warning").description("Counts at or below this turn red (0 = only the dimmed zero).")
        .defaultValue(2).range(0, 64).sliderRange(0, 16).build());

    private final Setting<Double> scale = sgLook.add(new DoubleSetting.Builder()
        .name("scale").description("Size of the whole panel.")
        .defaultValue(1.0).range(0.5, 3.0).sliderRange(0.5, 2.5).build());
    private final Setting<Integer> rounding = sgLook.add(new IntSetting.Builder()
        .name("rounding").description("Corner radius of the panel.")
        .defaultValue(6).range(0, 16).sliderRange(0, 16).build());
    private final Setting<SettingColor> background = sgLook.add(new ColorSetting.Builder()
        .name("background").description("Panel colour (alpha = transparency).")
        .defaultValue(new SettingColor(14, 16, 22, 210)).build());
    private final Setting<Boolean> outline = sgLook.add(new BoolSetting.Builder()
        .name("outline").description("Thin border around the panel.")
        .defaultValue(true).build());
    private final Setting<SettingColor> outlineColor = sgLook.add(new ColorSetting.Builder()
        .name("outline-color").description("Colour of the border.")
        .defaultValue(new SettingColor(255, 255, 255, 38) ).visible(outline::get).build());
    private final Setting<SettingColor> countColor = sgLook.add(new ColorSetting.Builder()
        .name("count-color").description("Colour of the numbers.")
        .defaultValue(new SettingColor(225, 228, 240, 255)).build());
    private final Setting<SettingColor> zeroColor = sgLook.add(new ColorSetting.Builder()
        .name("zero-color").description("Colour of a zero count (the icon is dimmed too).")
        .defaultValue(new SettingColor(110, 114, 128, 255)).build());
    private final Setting<SettingColor> lowColor = sgLook.add(new ColorSetting.Builder()
        .name("low-color").description("Colour of a low count.")
        .defaultValue(new SettingColor(255, 95, 95, 255)).build());
    private final Setting<Boolean> shadow = sgLook.add(new BoolSetting.Builder()
        .name("text-shadow").description("Shadow behind the numbers.")
        .defaultValue(false).build());

    private static final int ICON = 16, CELL_W = 24, CELL_H = 29, PAD = 5;

    public ItemCounterHud() {
        super(BlockOutlinesAddon.COSMETICS, "item-counter-hud", "A panel next to the hotbar showing how many totems, crystals, obsidian ... you carry.");
    }

    @EventHandler
    private void onRender2D(Render2DEvent event) {
        if (mc.player == null || mc.getWindow() == null || mc.options.hudHidden) return;

        List<Item> list = new ArrayList<>();
        List<Integer> counts = new ArrayList<>();
        PlayerInventory inv = mc.player.getInventory();
        for (Item item : items.get()) {
            int n = count(inv, item);
            if (hideEmpty.get() && n == 0) continue;
            list.add(item);
            counts.add(n);
        }
        if (list.isEmpty()) return;

        float s = scale.get().floatValue();
        int sw = mc.getWindow().getScaledWidth();
        int sh = mc.getWindow().getScaledHeight();
        boolean horizontal = layout.get() == Layout.Horizontal;

        int cols = horizontal ? list.size() : 1;
        int rows = horizontal ? 1 : list.size();
        int w = Math.round((PAD * 2 + cols * CELL_W) * s);
        int h = Math.round((PAD * 2 + rows * CELL_H - 2) * s);

        int x, y;
        switch (place.get()) {
            case RightOfHotbar -> {
                boolean leftHanded = mc.options.getMainArm().getValue() == net.minecraft.util.Arm.LEFT;
                x = sw / 2 + 91 + (leftHanded ? 29 : 0) + 8 + offsetX.get();
                y = sh - 2 - h + offsetY.get();
            }
            case LeftOfHotbar -> {
                boolean leftHanded = mc.options.getMainArm().getValue() == net.minecraft.util.Arm.LEFT;
                x = sw / 2 - 91 - (leftHanded ? 0 : 29) - 8 - w + offsetX.get();
                y = sh - 2 - h + offsetY.get();
            }
            default -> {
                x = offsetX.get();
                y = offsetY.get();
            }
        }
        x = Math.max(0, Math.min(sw - w, x));
        y = Math.max(0, Math.min(sh - h, y));

        DrawContext ctx = event.drawContext;
        int bg = argb(background.get());
        int radius = Math.max(0, Math.round(rounding.get() * s));
        if (outline.get()) RoundRect.outlined(ctx, x, y, w, h, radius, bg, argb(outlineColor.get()));
        else RoundRect.draw(ctx, x, y, w, h, radius, bg);

        for (int i = 0; i < list.size(); i++) {
            int col = horizontal ? i : 0;
            int row = horizontal ? 0 : i;
            float cx = x + (PAD + col * CELL_W) * s;
            float cy = y + (PAD - 1 + row * CELL_H) * s;
            int n = counts.get(i);

            // icon (dimmed when you have none)
            ctx.getMatrices().pushMatrix();
            ctx.getMatrices().translate(cx + (CELL_W - ICON) / 2f * s, cy);
            ctx.getMatrices().scale(s, s);
            ctx.drawItem(new ItemStack(list.get(i)), 0, 0);
            ctx.getMatrices().popMatrix();
            if (n == 0) {
                int ix = Math.round(cx + (CELL_W - ICON) / 2f * s);
                int iy = Math.round(cy);
                ctx.fill(ix, iy, ix + Math.round(ICON * s), iy + Math.round(ICON * s), (bg & 0x00FFFFFF) | 0xAA000000);
            }

            // number under the icon
            String text = String.valueOf(n);
            int color = n == 0 ? argb(zeroColor.get()) : (n <= lowAt.get() ? argb(lowColor.get()) : argb(countColor.get()));
            float ts = 0.85f * s;
            float tw = mc.textRenderer.getWidth(text) * ts;
            ctx.getMatrices().pushMatrix();
            ctx.getMatrices().translate(cx + (CELL_W * s - tw) / 2f, cy + (ICON + 3) * s);
            ctx.getMatrices().scale(ts, ts);
            ctx.drawText(mc.textRenderer, text, 0, 0, color, shadow.get());
            ctx.getMatrices().popMatrix();
        }
    }

    private int count(PlayerInventory inv, Item item) {
        int total = 0;
        int main = 36;
        int size = inv.size();
        for (int i = 0; i < size; i++) {
            if (i >= main && !countOffhand.get()) break;
            ItemStack stack = inv.getStack(i);
            if (!stack.isEmpty() && stack.getItem() == item) total += stack.getCount();
        }
        return total;
    }

    private static int argb(SettingColor c) {
        return (c.a << 24) | (c.r << 16) | (c.g << 8) | c.b;
    }
}
