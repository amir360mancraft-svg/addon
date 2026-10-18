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
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.util.Arm;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A small dark panel next to the hotbar: an icon per item with the number you carry under it
 * (totems, crystals, obsidian, pearls ...). Zero counts are dimmed, low counts turn red.
 * "Separate" mode gives every item its own little panel that you can drag anywhere (open the chat and drag).
 */
public class ItemCounterHud extends Module {
    public enum Place { RightOfHotbar, LeftOfHotbar, Custom }
    public enum Layout { Horizontal, Vertical }
    public enum Mode { Together, Separate }

    private final SettingGroup sg = settings.getDefaultGroup();
    private final SettingGroup sgLook = settings.createGroup("Look");
    private final SettingGroup sgSlot = settings.createGroup("Boxes behind items");

    private final Setting<List<Item>> items = sg.add(new ItemListSetting.Builder()
        .name("items").description("The items to count (in this order).")
        .defaultValue(List.of(Items.TOTEM_OF_UNDYING, Items.END_CRYSTAL, Items.OBSIDIAN, Items.RESPAWN_ANCHOR,
            Items.GLOWSTONE, Items.ENDER_PEARL, Items.EXPERIENCE_BOTTLE))
        .build());
    private final Setting<Mode> mode = sg.add(new EnumSetting.Builder<Mode>()
        .name("mode").description("Together: one panel. Separate: every item has its own panel that you can place anywhere (drag it while the chat is open).")
        .defaultValue(Mode.Together).build());
    private final Setting<Place> place = sg.add(new EnumSetting.Builder<Place>()
        .name("position").description("Where the panel is drawn (in Separate mode: where the panels start before you move them).")
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
    private final Setting<Boolean> resetPositions = sg.add(new BoolSetting.Builder()
        .name("reset-positions").description("Turn on to put all separate panels back to their start places.")
        .defaultValue(false).onChanged(v -> { if (v) { this.positions.clear(); this.saved.set(""); this.lastSaved = ""; this.resetPositions.set(false); } })
        .visible(() -> mode.get() == Mode.Separate).build());
    private final Setting<String> saved = sg.add(new StringSetting.Builder()
        .name("saved-positions").description("Where you dragged the panels (stored automatically).")
        .defaultValue("").visible(() -> false).build());

    private final Setting<Double> scale = sgLook.add(new DoubleSetting.Builder()
        .name("scale").description("Size of the whole panel.")
        .defaultValue(1.0).range(0.3, 4.0).sliderRange(0.3, 3.0).build());
    private final Setting<Integer> rounding = sgLook.add(new IntSetting.Builder()
        .name("rounding").description("How round the corners are.")
        .defaultValue(6).range(0, 30).sliderRange(0, 30).build());
    private final Setting<SettingColor> background = sgLook.add(new ColorSetting.Builder()
        .name("background").description("Colour of the panel.")
        .defaultValue(new SettingColor(14, 16, 22, 255)).build());
    private final Setting<Integer> backgroundAlpha = sgLook.add(new IntSetting.Builder()
        .name("background-alpha").description("Opacity of the panel (0 = invisible, 255 = solid).")
        .defaultValue(210).range(0, 255).sliderRange(0, 255).build());
    private final Setting<Boolean> outline = sgLook.add(new BoolSetting.Builder()
        .name("outline").description("Border around the panel.")
        .defaultValue(true).build());
    private final Setting<Integer> outlineThickness = sgLook.add(new IntSetting.Builder()
        .name("outline-thickness").description("How thick the border is.")
        .defaultValue(1).range(1, 8).sliderRange(1, 6).visible(outline::get).build());
    private final Setting<SettingColor> outlineColor = sgLook.add(new ColorSetting.Builder()
        .name("outline-color").description("Colour of the border (alpha = opacity).")
        .defaultValue(new SettingColor(255, 255, 255, 60)).visible(outline::get).build());
    private final Setting<SettingColor> countColor = sgLook.add(new ColorSetting.Builder()
        .name("count-color").description("Colour of the numbers.")
        .defaultValue(new SettingColor(225, 228, 240, 255)).build());
    private final Setting<SettingColor> zeroColor = sgLook.add(new ColorSetting.Builder()
        .name("zero-color").description("Colour of a zero count.")
        .defaultValue(new SettingColor(110, 114, 128, 255)).build());
    private final Setting<SettingColor> lowColor = sgLook.add(new ColorSetting.Builder()
        .name("low-color").description("Colour of a low count.")
        .defaultValue(new SettingColor(255, 95, 95, 255)).build());
    private final Setting<Boolean> shadow = sgLook.add(new BoolSetting.Builder()
        .name("text-shadow").description("Shadow behind the numbers.")
        .defaultValue(false).build());

    private final Setting<Boolean> slotBox = sgSlot.add(new BoolSetting.Builder()
        .name("box-behind-items").description("Draw a small box behind every icon.")
        .defaultValue(false).build());
    private final Setting<SettingColor> slotColor = sgSlot.add(new ColorSetting.Builder()
        .name("box-color").description("Colour of the box behind the icons.")
        .defaultValue(new SettingColor(0, 0, 0, 255)).visible(slotBox::get).build());
    private final Setting<Integer> slotAlpha = sgSlot.add(new IntSetting.Builder()
        .name("box-alpha").description("Opacity of the box behind the icons.")
        .defaultValue(90).range(0, 255).sliderRange(0, 255).visible(slotBox::get).build());
    private final Setting<Boolean> dimZero = sgSlot.add(new BoolSetting.Builder()
        .name("dim-empty").description("Fade the icon of an item you have none of.")
        .defaultValue(true).build());
    private final Setting<SettingColor> dimColor = sgSlot.add(new ColorSetting.Builder()
        .name("dim-color").description("Colour laid over an empty item's icon.")
        .defaultValue(new SettingColor(14, 16, 22, 255)).visible(dimZero::get).build());
    private final Setting<Integer> dimAlpha = sgSlot.add(new IntSetting.Builder()
        .name("dim-alpha").description("How strongly an empty icon is faded (0 = not at all, 255 = hidden).")
        .defaultValue(150).range(0, 255).sliderRange(0, 255).visible(dimZero::get).build());

    // sizes in "panel units"; the whole thing is drawn under one scale matrix so nothing can drift apart
    private static final int ICON = 16, TEXT_H = 7, GAP_ICON_TEXT = 2, CELL_W = 22, ITEM_H = ICON + GAP_ICON_TEXT + TEXT_H;
    private static final int PAD = 5, PAD_SEP = 4, GAP = 4;
    private static final float TEXT_SCALE = 0.8f;

    private final Map<String, int[]> positions = new HashMap<>();
    private String lastSaved = "";
    private String dragging;
    private double grabX, grabY;

    public ItemCounterHud() {
        super(BlockOutlinesAddon.CATEGORY, "item-counter-hud", "A panel next to the hotbar showing how many totems, crystals, obsidian ... you carry.");
    }

    @Override
    public void onActivate() {
        lastSaved = "";
        dragging = null;
    }

    private static final class Entry {
        final Item item;
        final int count;
        int x, y, w, h; // screen pixels (GUI scaled)
        Entry(Item item, int count) { this.item = item; this.count = count; }
    }

    @EventHandler
    private void onRender2D(Render2DEvent event) {
        if (mc.player == null || mc.getWindow() == null || mc.options.hudHidden) return;
        loadPositions();

        List<Entry> list = new ArrayList<>();
        PlayerInventory inv = mc.player.getInventory();
        for (Item item : items.get()) {
            int n = count(inv, item);
            if (hideEmpty.get() && n == 0) continue;
            list.add(new Entry(item, n));
        }
        if (list.isEmpty()) return;

        float s = scale.get().floatValue();
        int sw = mc.getWindow().getScaledWidth();
        int sh = mc.getWindow().getScaledHeight();
        boolean horizontal = layout.get() == Layout.Horizontal;
        boolean separate = mode.get() == Mode.Separate;
        int n = list.size();

        // size of one panel (unit) and of the whole group
        int unitPad = separate ? PAD_SEP : PAD;
        int unitW = CELL_W + unitPad * 2;
        int unitH = ITEM_H + unitPad * 2;
        int groupW, groupH;
        if (separate) {
            groupW = horizontal ? n * unitW + (n - 1) * GAP : unitW;
            groupH = horizontal ? unitH : n * unitH + (n - 1) * GAP;
        } else {
            groupW = horizontal ? PAD * 2 + n * CELL_W : PAD * 2 + CELL_W;
            groupH = horizontal ? PAD * 2 + ITEM_H : PAD * 2 + n * ITEM_H + (n - 1) * GAP;
        }
        int gw = Math.round(groupW * s), gh = Math.round(groupH * s);

        int gx, gy;
        switch (place.get()) {
            case RightOfHotbar -> {
                boolean leftHanded = mc.options.getMainArm().getValue() == Arm.LEFT;
                gx = sw / 2 + 91 + (leftHanded ? 29 : 0) + 8 + offsetX.get();
                gy = sh - 2 - gh + offsetY.get();
            }
            case LeftOfHotbar -> {
                boolean leftHanded = mc.options.getMainArm().getValue() == Arm.LEFT;
                gx = sw / 2 - 91 - (leftHanded ? 0 : 29) - 8 - gw + offsetX.get();
                gy = sh - 2 - gh + offsetY.get();
            }
            default -> {
                gx = offsetX.get();
                gy = offsetY.get();
            }
        }
        gx = Math.max(0, Math.min(sw - gw, gx));
        gy = Math.max(0, Math.min(sh - gh, gy));

        // positions of every panel on the screen
        int uw = Math.round(unitW * s), uh = Math.round(unitH * s), step = Math.round(GAP * s);
        if (separate) {
            for (int i = 0; i < n; i++) {
                Entry e = list.get(i);
                e.w = uw;
                e.h = uh;
                int[] custom = positions.get(id(e.item));
                if (custom != null) {
                    e.x = custom[0];
                    e.y = custom[1];
                } else {
                    e.x = gx + (horizontal ? i * (uw + step) : 0);
                    e.y = gy + (horizontal ? 0 : i * (uh + step));
                }
                e.x = Math.max(0, Math.min(sw - uw, e.x));
                e.y = Math.max(0, Math.min(sh - uh, e.y));
            }
            handleDrag(list, sw, sh);
        }

        DrawContext ctx = event.drawContext;
        if (!separate) {
            ctx.getMatrices().pushMatrix();
            ctx.getMatrices().translate(gx, gy);
            ctx.getMatrices().scale(s, s);
            panel(ctx, groupW, groupH);
            for (int i = 0; i < n; i++) {
                int cx = PAD + (horizontal ? i * CELL_W : 0);
                int cy = PAD + (horizontal ? 0 : i * (ITEM_H + GAP));
                cell(ctx, list.get(i), cx, cy, CELL_W);
            }
            ctx.getMatrices().popMatrix();
        } else {
            for (Entry e : list) {
                ctx.getMatrices().pushMatrix();
                ctx.getMatrices().translate(e.x, e.y);
                ctx.getMatrices().scale(s, s);
                panel(ctx, unitW, unitH);
                cell(ctx, e, PAD_SEP, PAD_SEP, CELL_W);
                ctx.getMatrices().popMatrix();
            }
        }
    }

    /** Background and border of a panel, in panel units (the scale matrix is already applied). */
    private void panel(DrawContext ctx, int w, int h) {
        int radius = Math.min(rounding.get(), Math.min(w, h) / 2);
        RoundRect.draw(ctx, 0, 0, w, h, radius, withAlpha(background.get(), backgroundAlpha.get()));
        if (outline.get()) ring(ctx, w, h, radius, outlineThickness.get(), argb(outlineColor.get()));
    }

    /** One icon with its number centred under it. (x, y) = top-left corner of the cell content. */
    private void cell(DrawContext ctx, Entry e, int x, int y, int width) {
        int ix = x + (width - ICON) / 2;

        if (slotBox.get() && slotAlpha.get() > 0) {
            RoundRect.draw(ctx, ix - 1, y - 1, ICON + 2, ICON + 2, Math.max(2, rounding.get() / 2), withAlpha(slotColor.get(), slotAlpha.get()));
        }

        ctx.drawItem(new ItemStack(e.item), ix, y);

        if (e.count == 0 && dimZero.get() && dimAlpha.get() > 0) {
            RoundRect.draw(ctx, ix, y, ICON, ICON, 2, withAlpha(dimColor.get(), dimAlpha.get()));
        }

        String text = String.valueOf(e.count);
        int color = e.count == 0 ? argb(zeroColor.get()) : (e.count <= lowAt.get() ? argb(lowColor.get()) : argb(countColor.get()));
        float tw = mc.textRenderer.getWidth(text) * TEXT_SCALE;
        ctx.getMatrices().pushMatrix();
        ctx.getMatrices().translate(x + (width - tw) / 2f, y + ICON + GAP_ICON_TEXT);
        ctx.getMatrices().scale(TEXT_SCALE, TEXT_SCALE);
        ctx.drawText(mc.textRenderer, text, 0, 0, color, shadow.get());
        ctx.getMatrices().popMatrix();
    }

    // ------------------------------------------------------------------ border drawn as a ring (so a transparent panel stays clean)

    private static int inset(int row, int h, int r) {
        if (r <= 0) return 0;
        int d = row < h / 2 ? row : h - 1 - row;
        if (d >= r) return 0;
        double dy = r - d - 0.5;
        return (int) Math.round(r - Math.sqrt(Math.max(0.0, (double) r * r - dy * dy)));
    }

    private void ring(DrawContext ctx, int w, int h, int r, int t, int argb) {
        t = Math.max(1, Math.min(t, Math.min(w, h) / 2));
        int ir = Math.max(0, r - t);
        for (int row = 0; row < h; row++) {
            int oi = inset(row, h, r);
            if (row < t || row >= h - t) {
                ctx.fill(oi, row, w - oi, row + 1, argb);
            } else {
                int ii = Math.max(oi, t + inset(row - t, h - 2 * t, ir));
                if (ii > oi) {
                    ctx.fill(oi, row, ii, row + 1, argb);
                    ctx.fill(w - ii, row, w - oi, row + 1, argb);
                }
            }
        }
    }

    // ------------------------------------------------------------------ dragging (chat open)

    private void handleDrag(List<Entry> list, int sw, int sh) {
        if (!(mc.currentScreen instanceof ChatScreen)) {
            if (dragging != null) save();
            dragging = null;
            return;
        }
        double mx = mc.mouse.getX() * sw / (double) mc.getWindow().getWidth();
        double my = mc.mouse.getY() * sh / (double) mc.getWindow().getHeight();
        boolean down = GLFW.glfwGetMouseButton(mc.getWindow().getHandle(), GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
        if (!down) {
            if (dragging != null) save();
            dragging = null;
            return;
        }
        if (dragging == null) {
            for (int i = list.size() - 1; i >= 0; i--) {
                Entry e = list.get(i);
                if (mx >= e.x && mx <= e.x + e.w && my >= e.y && my <= e.y + e.h) {
                    dragging = id(e.item);
                    grabX = mx - e.x;
                    grabY = my - e.y;
                    break;
                }
            }
            if (dragging == null) return;
        }
        for (Entry e : list) {
            if (id(e.item).equals(dragging)) {
                int nx = Math.max(0, Math.min(sw - e.w, (int) (mx - grabX)));
                int ny = Math.max(0, Math.min(sh - e.h, (int) (my - grabY)));
                positions.put(dragging, new int[]{nx, ny});
                e.x = nx;
                e.y = ny;
                break;
            }
        }
    }

    private void save() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, int[]> en : positions.entrySet()) {
            if (sb.length() > 0) sb.append(';');
            sb.append(en.getKey()).append('=').append(en.getValue()[0]).append(',').append(en.getValue()[1]);
        }
        lastSaved = sb.toString();
        saved.set(lastSaved);
    }

    private void loadPositions() {
        String s = saved.get();
        if (s == null || s.equals(lastSaved)) return;
        lastSaved = s;
        positions.clear();
        for (String part : s.split(";")) {
            int eq = part.indexOf('=');
            if (eq <= 0) continue;
            String[] xy = part.substring(eq + 1).split(",");
            if (xy.length != 2) continue;
            try {
                positions.put(part.substring(0, eq), new int[]{Integer.parseInt(xy[0].trim()), Integer.parseInt(xy[1].trim())});
            } catch (NumberFormatException ignored) {
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private static String id(Item item) {
        return Registries.ITEM.getId(item).toString();
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

    private static int withAlpha(SettingColor c, int alpha) {
        return (Math.max(0, Math.min(255, alpha)) << 24) | (c.r << 16) | (c.g << 8) | c.b;
    }
}
