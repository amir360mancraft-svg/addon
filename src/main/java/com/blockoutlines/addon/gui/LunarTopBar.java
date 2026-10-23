package com.blockoutlines.addon.gui;

import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.gui.tabs.Tab;
import meteordevelopment.meteorclient.gui.tabs.TabScreen;
import meteordevelopment.meteorclient.gui.tabs.Tabs;
import meteordevelopment.meteorclient.gui.tabs.builtin.GuiTab;
import meteordevelopment.meteorclient.gui.tabs.builtin.ModulesTab;
import meteordevelopment.meteorclient.gui.widgets.WTopBar;
import meteordevelopment.meteorclient.gui.widgets.pressable.WPressable;
import meteordevelopment.meteorclient.utils.render.color.Color;
import net.minecraft.util.math.MathHelper;

import java.util.Locale;

import static meteordevelopment.meteorclient.MeteorClient.mc;
import static org.lwjgl.glfw.GLFW.glfwSetCursorPos;

/**
 * The row of tabs at the top: floating rounded chips styled like Lunar Client.
 * Displays crisp vector glyph icons for each tab (MODS, CONFIG, HUD, FRIENDS, MACROS, PROFILES, LUNAR).
 * Can display Icon + Text or minimal Icon Only based on the theme setting.
 */
public class LunarTopBar extends WTopBar {
    @Override
    protected Color getButtonColor(boolean pressed, boolean hovered) {
        return new Color(255, 255, 255, 30);
    }

    @Override
    protected Color getNameColor() {
        return new Color(255, 255, 255, 255);
    }

    @Override
    public void init() {
        spacing = 6;
        for (Tab tab : Tabs.get()) {
            if (tab instanceof GuiTab) continue; // the theme picker, the Lunar tab replaces it
            add(new Chip(tab)).centerY();
        }
    }

    /** The hub has its own buttons in the middle, so the bar is not drawn there. */
    @Override
    public boolean render(GuiRenderer renderer, double mouseX, double mouseY, double delta) {
        visible = !(mc.currentScreen instanceof LunarHubScreen);
        return super.render(renderer, mouseX, mouseY, delta);
    }

    private static String label(Tab tab) {
        return tab instanceof ModulesTab ? "MODS" : tab.name.toUpperCase(Locale.ROOT);
    }

    private static class Chip extends WPressable {
        private final Tab tab;
        private double hoverP, activeP = -1;

        Chip(Tab tab) {
            this.tab = tab;
        }

        private boolean isCurrent() {
            return mc.currentScreen instanceof TabScreen ts && ts.tab == tab;
        }

        @Override
        protected void onCalculateSize() {
            double pad = pad();
            double u = theme.scale(1);
            boolean showText = theme instanceof LunarGuiTheme lt ? lt.topBarText.get() : true;
            double iconSz = 11.5 * u;

            if (showText) {
                double gap = 6.0 * u;
                width = iconSz + gap + theme.textWidth(label(tab)) + pad * 2.8;
                tooltip = null;
            } else {
                width = iconSz + pad * 2.6;
                tooltip = label(tab);
            }
            height = theme.textHeight() + pad * 1.8;
        }

        @Override
        protected void onPressed(int button) {
            if (mc.currentScreen instanceof LunarHubScreen) return;
            if (isCurrent() && !(tab instanceof ModulesTab)) return;
            if (tab instanceof ModulesTab && theme instanceof LunarGuiTheme lt) lt.browserRequested = true;

            double mouseX = mc.mouse.getX();
            double mouseY = mc.mouse.getY();
            tab.openScreen(theme);
            glfwSetCursorPos(mc.getWindow().getHandle(), mouseX, mouseY);
        }

        @Override
        protected void onRender(GuiRenderer r, double mouseX, double mouseY, double delta) {
            LunarGuiTheme lt = (LunarGuiTheme) theme;
            double sp = lt.animationSpeed.get();
            boolean current = isCurrent();
            if (activeP < 0) activeP = current ? 1 : 0;
            hoverP = MathHelper.clamp(hoverP + (mouseOver ? 1 : -1) * delta * sp / 0.2, 0, 1);
            activeP = MathHelper.clamp(activeP + (current ? 1 : -1) * delta * sp / 0.25, 0, 1);
            double h = hoverP * hoverP * (3 - 2 * hoverP), a = activeP * activeP * (3 - 2 * activeP);

            int[] ac = lt.accent();
            var pc = lt.panelColor.get();
            double rad = Math.min(height / 2, lt.cornerRadius.get() * 0.9 * theme.scale(1));

            // Background & Border
            LunarDraw.round(r, x - 1, y - 1, width + 2, height + 2, rad + 1, LunarDraw.col(255, 255, 255, 26));
            LunarDraw.round(r, x, y, width, height, rad, LunarDraw.col(pc.r, pc.g, pc.b, Math.max(200, pc.a)));
            if (h > 0.01) LunarDraw.round(r, x, y, width, height, rad, LunarDraw.col(255, 255, 255, 24 * h * (1 - a)));
            if (a > 0.01) LunarDraw.round(r, x, y, width, height, rad, LunarDraw.col(ac[0], ac[1], ac[2], 255 * a));

            String s = label(tab);
            boolean showText = lt.topBarText.get();
            double u = theme.scale(1);
            double iconSz = 11.0 * u;
            double gap = 6.0 * u;

            double contentW = showText ? (iconSz + gap + theme.textWidth(s)) : iconSz;
            double contentX = x + (width - contentW) / 2;
            double cy = y + height / 2;

            // Smoothly blended background behind icon cut-out
            int bgR = (int) Math.round(pc.r + (ac[0] - pc.r) * a);
            int bgG = (int) Math.round(pc.g + (ac[1] - pc.g) * a);
            int bgB = (int) Math.round(pc.b + (ac[2] - pc.b) * a);
            Color iconBg = LunarDraw.col(bgR, bgG, bgB, 255);
            Color iconColor = a > 0.01 ? LunarDraw.col(255, 255, 255, 255) : LunarDraw.col(230, 238, 250, (int) (180 + 75 * h));

            // Draw Glyph Icon
            LunarGlyph.draw(r, label(tab), contentX + iconSz / 2, cy, iconSz, iconColor, iconBg);

            // Draw Label text if enabled
            if (showText) {
                r.text(s, contentX + iconSz + gap, cy - theme.textHeight() / 2, LunarDraw.col(255, 255, 255, (int) (215 + 40 * a)), false);
            }
        }
    }
}
