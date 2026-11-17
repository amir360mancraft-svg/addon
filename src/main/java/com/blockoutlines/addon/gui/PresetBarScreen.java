package com.blockoutlines.addon.gui;

import com.blockoutlines.addon.modules.Presets;
import com.blockoutlines.addon.util.WheelIcons;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * Like the round wheel, but the choices are rectangles side by side: hold the key, move to a card, let go (or click)
 * to apply the preset. The small dot on a card means the preset is active right now.
 */
public class PresetBarScreen extends Screen {
    private static final int CARD_W = 92, CARD_H = 84, GAP = 8;

    private final Presets module;
    private final Keybind hold;
    private final int accent;
    private long openedAt;
    private int hover = -1;

    public PresetBarScreen(Presets module, Keybind hold, int accentArgb) {
        super(Text.literal("Presets"));
        this.module = module;
        this.hold = hold;
        this.accent = accentArgb;
    }

    @Override
    protected void init() {
        openedAt = System.currentTimeMillis();
        addDrawableChild(ButtonWidget.builder(Text.literal("Edit"), b -> {
            client.setScreen(null);
            module.openEditor();
        }).dimensions(width / 2 - 30, height - 30, 60, 20).build());
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    private boolean held() {
        if (hold == null || !hold.isSet()) return false;
        long window = client.getWindow().getHandle();
        return hold.isKey() ? GLFW.glfwGetKey(window, hold.getValue()) == GLFW.GLFW_PRESS
            : GLFW.glfwGetMouseButton(window, hold.getValue()) == GLFW.GLFW_PRESS;
    }

    @Override
    public void tick() {
        super.tick();
        if (hold != null && System.currentTimeMillis() - openedAt > 200 && !held()) choose();
    }

    private void choose() {
        List<Presets.Preset> l = module.presets();
        Presets.Preset p = hover >= 0 && hover < l.size() ? l.get(hover) : null;
        client.setScreen(null);
        if (p != null) module.apply(p);
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (super.mouseClicked(click, doubled)) return true;
        if (click.button() == 0 && hover >= 0) {
            choose();
            return true;
        }
        return false;
    }

    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
        ctx.fill(0, 0, width, height, 0x88000000);
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        List<Presets.Preset> l = module.presets();
        int n = l.size();
        double t = Math.min(1.0, (System.currentTimeMillis() - openedAt) / 160.0);
        double ease = 1.0 - Math.pow(1.0 - t, 3.0);

        int perRow = Math.max(1, Math.min(n == 0 ? 1 : n, Math.min(8, (width - 24 + GAP) / (CARD_W + GAP))));
        int rows = n == 0 ? 1 : (n + perRow - 1) / perRow;
        int totalH = rows * CARD_H + (rows - 1) * GAP;
        int top = (height - totalH) / 2 + (int) ((1.0 - ease) * 14);

        hover = -1;
        if (n == 0) {
            ctx.drawCenteredTextWithShadow(textRenderer, "No presets yet", width / 2, height / 2 - 8, 0xFFFFFFFF);
            ctx.drawCenteredTextWithShadow(textRenderer, "Click Edit", width / 2, height / 2 + 4, 0xFFB8B8C8);
        }
        for (int i = 0; i < n; i++) {
            int row = i / perRow, col = i % perRow;
            int inRow = Math.min(perRow, n - row * perRow);
            int rowW = inRow * CARD_W + (inRow - 1) * GAP;
            int x = (width - rowW) / 2 + col * (CARD_W + GAP);
            int y = top + row * (CARD_H + GAP);
            boolean over = mouseX >= x && mouseX < x + CARD_W && mouseY >= y && mouseY < y + CARD_H;
            if (over) hover = i;
            Presets.Preset p = l.get(i);

            int border = over ? 0xFFFFFFFF : 0xFF4A4A5C;
            int fill = over ? ((0xDD << 24) | (accent & 0xFFFFFF)) : 0xCC1C1C26;
            ctx.fill(x, y, x + CARD_W, y + CARD_H, border);
            ctx.fill(x + 1, y + 1, x + CARD_W - 1, y + CARD_H - 1, fill);

            WheelIcons.draw(ctx, p.kind, p.value, x + CARD_W / 2, y + 26, over ? 36 : 32);
            String nm = textRenderer.trimToWidth(p.name, CARD_W - 8);
            ctx.drawCenteredTextWithShadow(textRenderer, nm, x + CARD_W / 2, y + 50, over ? 0xFFFFFFFF : 0xFFE0E0EC);
            String sub = p.on.size() + " on" + (p.exclusive ? " + only" : (p.off.isEmpty() ? "" : " / " + p.off.size() + " off"));
            ctx.drawCenteredTextWithShadow(textRenderer, textRenderer.trimToWidth(sub, CARD_W - 8), x + CARD_W / 2, y + 64, over ? 0xFFE8EEFF : 0xFF9090A4);
            if (module.matches(p)) ctx.fill(x + CARD_W - 9, y + 4, x + CARD_W - 4, y + 9, 0xFF3DDC84);
        }
        ctx.drawCenteredTextWithShadow(textRenderer, "Release to apply", width / 2, top + totalH + 12, 0xFF9090A4);
        ctx.drawCenteredTextWithShadow(textRenderer, "Presets", width / 2, top - 14, 0xFFB8B8C8);
    }
}
