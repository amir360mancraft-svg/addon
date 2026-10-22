package com.blockoutlines.addon.gui;

import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.gui.tabs.Tab;
import meteordevelopment.meteorclient.gui.tabs.TabScreen;
import meteordevelopment.meteorclient.gui.tabs.Tabs;
import meteordevelopment.meteorclient.gui.tabs.builtin.ModulesTab;
import meteordevelopment.meteorclient.gui.widgets.WTopBar;
import meteordevelopment.meteorclient.gui.widgets.pressable.WPressable;
import meteordevelopment.meteorclient.utils.render.color.Color;
import net.minecraft.util.math.MathHelper;

import java.util.Locale;

import static meteordevelopment.meteorclient.MeteorClient.mc;
import static org.lwjgl.glfw.GLFW.glfwSetCursorPos;

/** The row of tabs at the top: floating round chips instead of Meteor's bar. The active tab is filled with the accent color. */
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
        for (Tab tab : Tabs.get()) add(new Chip(tab)).centerY();
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
            width = theme.textWidth(label(tab)) + pad * 3.2;
            height = theme.textHeight() + pad * 1.7;
        }

        @Override
        protected void onPressed(int button) {
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
            LunarDraw.round(r, x - 1, y - 1, width + 2, height + 2, rad + 1, LunarDraw.col(255, 255, 255, 26));
            LunarDraw.round(r, x, y, width, height, rad, LunarDraw.col(pc.r, pc.g, pc.b, Math.max(200, pc.a)));
            if (h > 0.01) LunarDraw.round(r, x, y, width, height, rad, LunarDraw.col(255, 255, 255, 22 * h * (1 - a)));
            if (a > 0.01) LunarDraw.round(r, x, y, width, height, rad, LunarDraw.col(ac[0], ac[1], ac[2], 255 * a));

            String s = label(tab);
            r.text(s, x + (width - theme.textWidth(s)) / 2, y + (height - theme.textHeight()) / 2, LunarDraw.col(255, 255, 255, 235 + 20 * a), false);
        }
    }
}
