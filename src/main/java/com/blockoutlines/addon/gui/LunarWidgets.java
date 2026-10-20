package com.blockoutlines.addon.gui;

import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.gui.renderer.packer.GuiTexture;
import meteordevelopment.meteorclient.gui.themes.meteor.widgets.WMeteorWindow;
import meteordevelopment.meteorclient.gui.themes.meteor.widgets.input.WMeteorSlider;
import meteordevelopment.meteorclient.gui.themes.meteor.widgets.pressable.WMeteorButton;
import meteordevelopment.meteorclient.gui.themes.meteor.widgets.pressable.WMeteorCheckbox;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import net.minecraft.util.math.MathHelper;

/** The rounded, dark-glass look of the Lunar theme for windows, buttons, check boxes and sliders. */
public final class LunarWidgets {
    private LunarWidgets() {
    }

    /** A rectangle with round corners, drawn as one-pixel rows at the top and bottom. */
    static void round(GuiRenderer r, double x, double y, double w, double h, double rad, Color c) {
        if (w <= 0 || h <= 0) return;
        rad = Math.min(rad, Math.min(w, h) / 2.0);
        int rows = (int) Math.floor(rad);
        if (rows < 1) { r.quad(x, y, w, h, c); return; }
        for (int i = 0; i < rows; i++) {
            double d = rad - i - 0.5;
            double inset = rad - Math.sqrt(Math.max(0, rad * rad - d * d));
            r.quad(x + inset, y + i, w - inset * 2, 1, c);
            r.quad(x + inset, y + h - i - 1, w - inset * 2, 1, c);
        }
        r.quad(x, y + rows, w, h - rows * 2, c);
    }

    static Color col(int r, int g, int b, double a) {
        return new Color(r, g, b, (int) Math.max(0, Math.min(255, Math.round(a))));
    }

    private static Color withAlpha(SettingColor c, double a) {
        return new Color(c.r, c.g, c.b, (int) Math.max(0, Math.min(255, Math.round(a))));
    }

    private static double ease(double t) {
        t = MathHelper.clamp(t, 0, 1);
        return t * t * (3 - 2 * t);
    }

    private static double speed(WWidget w) {
        return w.theme instanceof LunarGuiTheme t ? t.animationSpeed.get() : 1.0;
    }

    // ------------------------------------------------------------------ window

    public static class Window extends WMeteorWindow {
        public Window(WWidget icon, String title) {
            super(icon, title);
        }

        @Override
        protected WHeader header(WWidget icon) {
            return new LunarHeader(icon);
        }

        @Override
        protected void onRender(GuiRenderer r, double mouseX, double mouseY, double delta) {
            double u = theme.scale(1);
            round(r, x - 1, y - 1, width + 2, height + 2, 11 * u, col(255, 255, 255, 30));
            round(r, x, y, width, height, 10 * u, col(14, 17, 24, 232));
        }

        private class LunarHeader extends WHeader {
            LunarHeader(WWidget icon) {
                super(icon);
            }

            @Override
            protected void onRender(GuiRenderer r, double mouseX, double mouseY, double delta) {
                double u = theme.scale(1);
                SettingColor accent = ((LunarGuiTheme) theme).accentColor.get();
                round(r, x + 2 * u, y + 2 * u, width - 4 * u, height - 4 * u, 8 * u, withAlpha(accent, 150));
                r.quad(x + 10 * u, y + 2 * u, width - 20 * u, 1, col(255, 255, 255, 70));
            }
        }
    }

    // ------------------------------------------------------------------ button

    public static class Button extends WMeteorButton {
        private double hoverP;

        public Button(String text, GuiTexture texture) {
            super(text, texture);
        }

        @Override
        protected void onRender(GuiRenderer r, double mouseX, double mouseY, double delta) {
            double u = theme.scale(1);
            hoverP = MathHelper.clamp(hoverP + (mouseOver ? 1 : -1) * delta * speed(this) / 0.2, 0, 1);
            double h = ease(hoverP);
            double a = pressed ? 56 : 24 + 22 * h;
            round(r, x, y, width, height, 6 * u, col(255, 255, 255, a));
            if (text != null) {
                r.text(text, x + width / 2 - textWidth / 2, y + pad(), theme().textColor.get(), false);
            } else if (texture != null) {
                double ts = theme.textHeight();
                r.quad(x + width / 2 - ts / 2, y + pad(), ts, ts, texture, theme().textColor.get());
            }
        }
    }

    // ------------------------------------------------------------------ check box

    public static class Checkbox extends WMeteorCheckbox {
        private double progress;
        private double hoverP;
        private boolean first = true;

        public Checkbox(boolean checked) {
            super(checked);
            progress = checked ? 1 : 0;
        }

        @Override
        protected void onRender(GuiRenderer r, double mouseX, double mouseY, double delta) {
            double u = theme.scale(1);
            progress = MathHelper.clamp(progress + (checked ? 1 : -1) * delta * speed(this) / 0.22, 0, 1);
            hoverP = MathHelper.clamp(hoverP + (mouseOver ? 1 : -1) * delta * speed(this) / 0.2, 0, 1);
            double p = ease(progress);
            SettingColor accent = theme().checkboxColor.get();

            round(r, x, y, width, height, 5 * u, col(255, 255, 255, 26 + 18 * ease(hoverP)));
            if (p > 0.01) {
                double m = 2 * u;
                double cs = (width - m * 2) * p, ch = (height - m * 2) * p;
                round(r, x + (width - cs) / 2, y + (height - ch) / 2, cs, ch, 4 * u, withAlpha(accent, 255 * p));
            }
        }
    }

    // ------------------------------------------------------------------ slider

    public static class Slider extends WMeteorSlider {
        public Slider(double value, double min, double max) {
            super(value, min, max);
        }

        @Override
        protected void onRender(GuiRenderer r, double mouseX, double mouseY, double delta) {
            double u = theme.scale(1);
            double valueWidth = valueWidth();
            double hs = handleSize();

            double th = 5 * u;
            double bx = x + hs / 2, by = y + height / 2 - th / 2;
            double total = width - hs;

            round(r, bx, by, total, th, th / 2, col(255, 255, 255, 30));
            if (valueWidth > 1) round(r, bx, by, Math.max(th, valueWidth), th, th / 2, withAlpha(theme().sliderLeft.get(), 255));

            double glow = (dragging || handleMouseOver) ? 1 : 0;
            if (glow > 0) r.quad(x + valueWidth - 2 * u, y - 2 * u, hs + 4 * u, hs + 4 * u, GuiRenderer.CIRCLE, col(255, 255, 255, 40));
            r.quad(x + valueWidth, y, hs, hs, GuiRenderer.CIRCLE, dragging ? col(255, 255, 255, 255) : col(235, 240, 250, 255));
        }
    }
}
