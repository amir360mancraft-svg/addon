package com.blockoutlines.addon.gui;

import meteordevelopment.meteorclient.utils.misc.Keybind;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * A round menu like the weapon wheel in GTA: hold a key, move the mouse towards a slice, let go of the key (or click)
 * to pick it. Used by Command Wheel and Emote Wheel.
 */
public class RadialWheelScreen extends Screen {
    public interface Icon {
        void draw(DrawContext ctx, int cx, int cy, int size);
    }

    public static final class Entry {
        public final String label, sub;
        public final Icon icon;
        public final Runnable action;

        public Entry(String label, String sub, Icon icon, Runnable action) {
            this.label = label;
            this.sub = sub;
            this.icon = icon;
            this.action = action;
        }
    }

    private final List<Entry> entries;
    private final Keybind hold;
    private final Runnable onEdit;
    private final int outer, accent;
    private final String hint;
    private long openedAt;
    private int hover = -1;

    public RadialWheelScreen(String title, List<Entry> entries, Keybind hold, Runnable onEdit, int outerRadius, int accentArgb, String hint) {
        super(Text.literal(title));
        this.entries = entries;
        this.hold = hold;
        this.onEdit = onEdit;
        this.outer = outerRadius;
        this.accent = accentArgb;
        this.hint = hint;
    }

    @Override
    protected void init() {
        openedAt = System.currentTimeMillis();
        if (onEdit != null) {
            addDrawableChild(ButtonWidget.builder(Text.literal("Edit"), b -> {
                client.setScreen(null);
                onEdit.run();
            }).dimensions(width / 2 - 30, height - 30, 60, 20).build());
        }
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    /** The key (or mouse button) of the setting is still held down. */
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
        if (hover >= 0 && hover < entries.size()) {
            Entry e = entries.get(hover);
            client.setScreen(null);
            e.action.run();
        } else {
            client.setScreen(null);
        }
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

    // dim the game a little instead of the blurred background
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
        ctx.fill(0, 0, width, height, 0x88000000);
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta); // background and the Edit button
        int cx = width / 2, cy = height / 2;
        int n = entries.size();
        double t = Math.min(1.0, (System.currentTimeMillis() - openedAt) / 160.0);
        double ease = 1.0 - Math.pow(1.0 - t, 3.0);
        int R = (int) (outer * (0.6 + 0.4 * ease));
        int r0 = (int) (R * 0.42);
        double step = n > 0 ? Math.PI * 2.0 / n : Math.PI * 2.0;

        // which slice the mouse is in
        double dx = mouseX - cx, dy = mouseY - cy;
        double dist = Math.hypot(dx, dy);
        hover = -1;
        if (n > 0 && dist > r0 * 0.6) {
            double ang = Math.atan2(dx, -dy);
            if (ang < 0) ang += Math.PI * 2.0;
            hover = (int) Math.floor((ang + step / 2.0) / step) % n;
        }

        // the ring, drawn in small squares
        final int B = 3;
        int accentSoft = (0xDD << 24) | (accent & 0xFFFFFF);
        for (int by = -R; by < R; by += B) {
            for (int bx = -R; bx < R; bx += B) {
                double px = bx + B / 2.0, py = by + B / 2.0;
                double r = Math.hypot(px, py);
                if (r < r0 || r > R) continue;
                double ang = Math.atan2(px, -py);
                if (ang < 0) ang += Math.PI * 2.0;
                int idx = 0;
                if (n > 1) {
                    double a = ang + step / 2.0;
                    idx = (int) Math.floor(a / step) % n;
                    double tt = a % step;
                    if (Math.min(tt, step - tt) * r < 1.7) continue; // gap between slices
                }
                int color;
                if (idx == hover && n > 0) color = r > R - B ? 0xFFFFFFFF : accentSoft;
                else color = r > R - B ? 0xCC4A4A5C : 0xCC1C1C26;
                ctx.fill(cx + bx, cy + by, cx + bx + B, cy + by + B, color);
            }
        }

        // icons and names
        int rm = (r0 + R) / 2;
        for (int i = 0; i < n; i++) {
            double a = i * step;
            int ix = cx + (int) Math.round(Math.sin(a) * rm), iy = cy - (int) Math.round(Math.cos(a) * rm);
            int size = i == hover ? 28 : 24;
            Entry e = entries.get(i);
            if (e.icon != null) e.icon.draw(ctx, ix, iy - 4, size);
            ctx.drawCenteredTextWithShadow(textRenderer, e.label, ix, iy + size / 2 - 1, i == hover ? 0xFFFFFFFF : 0xFFB8B8C8);
        }

        // the middle
        if (n == 0) {
            ctx.drawCenteredTextWithShadow(textRenderer, "Nothing here yet", cx, cy - 8, 0xFFFFFFFF);
            ctx.drawCenteredTextWithShadow(textRenderer, "Click Edit", cx, cy + 4, 0xFFB8B8C8);
        } else if (hover >= 0) {
            Entry e = entries.get(hover);
            ctx.drawCenteredTextWithShadow(textRenderer, e.label, cx, cy - 8, 0xFFFFFFFF);
            if (e.sub != null) ctx.drawCenteredTextWithShadow(textRenderer, e.sub, cx, cy + 4, 0xFFA0A0B4);
        } else {
            ctx.drawCenteredTextWithShadow(textRenderer, getTitle().getString(), cx, cy - 4, 0xFFB8B8C8);
        }
        if (hint != null) ctx.drawCenteredTextWithShadow(textRenderer, hint, cx, cy + R + 10, 0xFF9090A4);
    }
}
