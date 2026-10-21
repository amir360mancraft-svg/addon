package com.blockoutlines.addon.gui;

import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.gui.renderer.packer.GuiTexture;
import meteordevelopment.meteorclient.gui.themes.meteor.widgets.WMeteorWindow;
import meteordevelopment.meteorclient.gui.themes.meteor.widgets.input.WMeteorDropdown;
import meteordevelopment.meteorclient.gui.themes.meteor.widgets.input.WMeteorSlider;
import meteordevelopment.meteorclient.gui.themes.meteor.widgets.input.WMeteorTextBox;
import meteordevelopment.meteorclient.gui.themes.meteor.widgets.pressable.WMeteorButton;
import meteordevelopment.meteorclient.gui.themes.meteor.widgets.pressable.WMeteorCheckbox;
import meteordevelopment.meteorclient.gui.utils.CharFilter;
import meteordevelopment.meteorclient.gui.utils.Cell;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WHorizontalList;
import meteordevelopment.meteorclient.gui.widgets.pressable.WPressable;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import net.minecraft.client.gui.Click;
import net.minecraft.util.math.MathHelper;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/** The rounded, dark-glass look of the Lunar theme for windows, buttons, switches, sliders and input fields. */
public final class LunarWidgets {
    private LunarWidgets() {
    }

    private static void round(GuiRenderer r, double x, double y, double w, double h, double rad, Color c) {
        LunarDraw.round(r, x, y, w, h, rad, c);
    }

    private static Color col(int r, int g, int b, double a) {
        return LunarDraw.col(r, g, b, a);
    }

    private static double radius(WWidget w, double scale) {
        return (w.theme instanceof LunarGuiTheme t ? t.cornerRadius.get() : 10.0) * scale * w.theme.scale(1);
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
            SettingColor pc = ((LunarGuiTheme) theme).panelColor.get();
            double rad = radius(this, 1.2);
            round(r, x - 1, y - 1, width + 2, height + 2, rad + u, col(255, 255, 255, 30));
            round(r, x, y, width, height, rad, col(pc.r, pc.g, pc.b, Math.min(255, pc.a + 4)));
        }

        /** Big title on the left, a round close button on the right and a thin line below. No collapsing, no dragging. */
        private class LunarHeader extends WHeader {
            LunarHeader(WWidget icon) {
                super(icon);
            }

            @Override
            public void init() {
                WHorizontalList left = add(theme.horizontalList()).widget();
                left.spacing = 8;
                if (Window.this.icon != null) left.add(Window.this.icon).centerY();
                left.add(theme.label(Window.this.title, true)).centerY();

                CloseButton close = new CloseButton();
                add(close).right().centerY();
            }

            @Override
            public boolean onMouseClicked(Click click, boolean doubled) {
                return false;
            }

            @Override
            public boolean onMouseReleased(Click click) {
                return false;
            }

            @Override
            public void onMouseMoved(double mouseX, double mouseY, double lastMouseX, double lastMouseY) {
            }

            @Override
            protected void onCalculateSize() {
                width = 0;
                height = 0;
                for (Cell<?> cell : cells) {
                    width += cell.padLeft() + cell.widget().width + cell.padRight();
                    height = Math.max(height, cell.padTop() + cell.widget().height + cell.padBottom());
                }
                height += theme.scale(10);
            }

            @Override
            public boolean render(GuiRenderer r, double mouseX, double mouseY, double delta) {
                if (!visible) return true;
                onRender(r, mouseX, mouseY, delta);
                for (Cell<?> cell : cells) cell.widget().render(r, mouseX, mouseY, delta);
                return false;
            }

            @Override
            protected void onRender(GuiRenderer r, double mouseX, double mouseY, double delta) {
                double u = theme.scale(1);
                r.quad(x + 10 * u, y + height - 1, width - 20 * u, 1, col(255, 255, 255, 22));
            }
        }
    }

    /** A round button with an X, closes the current screen. */
    public static class CloseButton extends WPressable {
        private double hoverP;

        public CloseButton() {
            action = () -> {
                if (mc.currentScreen != null) mc.currentScreen.close();
            };
        }

        @Override
        protected void onCalculateSize() {
            double s = theme.textHeight() + theme.pad() * 1.4;
            width = s;
            height = s;
        }

        @Override
        protected void onRender(GuiRenderer r, double mouseX, double mouseY, double delta) {
            double u = theme.scale(1);
            hoverP = MathHelper.clamp(hoverP + (mouseOver ? 1 : -1) * delta * speed(this) / 0.2, 0, 1);
            double h = ease(hoverP);
            round(r, x, y, width, height, width / 2, col(255, 255, 255, 22 + 26 * h + (pressed ? 20 : 0)));
            double c = width * 0.17;
            double cx = x + width / 2, cy = y + height / 2;
            Color line = col(235, 240, 250, 255);
            LunarDraw.seg(r, cx - c, cy - c, cx + c, cy + c, Math.max(1.5, 1.8 * u), line);
            LunarDraw.seg(r, cx - c, cy + c, cx + c, cy - c, Math.max(1.5, 1.8 * u), line);
        }
    }

    // ------------------------------------------------------------------ button

    public static class Button extends WMeteorButton {
        private double hoverP;

        public Button(String text, GuiTexture texture) {
            super(text, texture);
        }

        /** True for the small circular-arrow button that every setting has next to it. */
        public boolean isReset() {
            return texture != null && texture == GuiRenderer.RESET;
        }

        @Override
        protected void onRender(GuiRenderer r, double mouseX, double mouseY, double delta) {
            double u = theme.scale(1);
            hoverP = MathHelper.clamp(hoverP + (mouseOver ? 1 : -1) * delta * speed(this) / 0.2, 0, 1);
            double h = ease(hoverP);
            double a = pressed ? 56 : 24 + 22 * h;
            round(r, x, y, width, height, radius(this, 0.6), col(255, 255, 255, a));
            if (text != null) {
                r.text(text, x + width / 2 - textWidth / 2, y + pad(), theme().textColor.get(), false);
            } else if (texture != null) {
                double ts = theme.textHeight();
                r.quad(x + width / 2 - ts / 2, y + pad(), ts, ts, texture, theme().textColor.get());
            }
        }
    }

    // ------------------------------------------------------------------ switch (the check box)

    public static class Checkbox extends WMeteorCheckbox {
        private double progress;
        private double hoverP;

        public Checkbox(boolean checked) {
            super(checked);
            progress = checked ? 1 : 0;
        }

        @Override
        protected void onCalculateSize() {
            double h = theme.textHeight() + theme.pad() * 0.9;
            height = h;
            width = h * 1.85;
        }

        @Override
        protected void onRender(GuiRenderer r, double mouseX, double mouseY, double delta) {
            double u = theme.scale(1);
            progress = MathHelper.clamp(progress + (checked ? 1 : -1) * delta * speed(this) / 0.22, 0, 1);
            hoverP = MathHelper.clamp(hoverP + (mouseOver ? 1 : -1) * delta * speed(this) / 0.2, 0, 1);
            double p = ease(progress);
            SettingColor accent = theme().accentColor.get();

            round(r, x, y, width, height, height / 2, col(255, 255, 255, 30 + 14 * ease(hoverP)));
            if (p > 0.01) round(r, x, y, width, height, height / 2, withAlpha(accent, 255 * p));

            double m = 2.5 * u;
            double d = height - m * 2;
            double kx = x + m + (width - m * 2 - d) * p;
            round(r, kx, y + m, d, d, d / 2, col(245, 247, 252, 255));
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
            if (valueWidth > 1) round(r, bx, by, Math.max(th, valueWidth), th, th / 2, withAlpha(theme().accentColor.get(), 255));

            double glow = (dragging || handleMouseOver) ? 1 : 0;
            if (glow > 0) r.quad(x + valueWidth - 2 * u, y - 2 * u, hs + 4 * u, hs + 4 * u, GuiRenderer.CIRCLE, col(255, 255, 255, 40));
            r.quad(x + valueWidth, y, hs, hs, GuiRenderer.CIRCLE, dragging ? col(255, 255, 255, 255) : col(235, 240, 250, 255));
        }
    }

    // ------------------------------------------------------------------ text box and drop-down

    public static class TextBox extends WMeteorTextBox {
        public TextBox(String text, String placeholder, CharFilter filter, Class<? extends Renderer> renderer) {
            super(text, placeholder, filter, renderer);
        }

        @Override
        public void renderBackground(GuiRenderer r, WWidget w, Color outline, Color background) {
            double rad = radius(w, 0.55);
            round(r, w.x, w.y, w.width, w.height, rad, col(255, 255, 255, focused ? 70 : 28));
            SettingColor pc = ((LunarGuiTheme) w.theme).panelColor.get();
            round(r, w.x + 1, w.y + 1, w.width - 2, w.height - 2, Math.max(1, rad - 1), col(pc.r / 2, pc.g / 2, pc.b / 2, 215));
        }
    }

    public static class Dropdown<T> extends WMeteorDropdown<T> {
        public Dropdown(T[] values, T value) {
            super(values, value);
        }

        @Override
        public void renderBackground(GuiRenderer r, WWidget w, Color outline, Color background) {
            double rad = radius(w, 0.55);
            round(r, w.x, w.y, w.width, w.height, rad, col(255, 255, 255, 30 + (mouseOver ? 18 : 0)));
        }
    }
}
