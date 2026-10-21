package com.blockoutlines.addon.gui;

import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import net.minecraft.util.math.MathHelper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static meteordevelopment.meteorclient.utils.Utils.getWindowHeight;
import static meteordevelopment.meteorclient.utils.Utils.getWindowWidth;

/** Base of the widgets that draw a whole page themselves: colors, shapes, easing and a list of clickable areas. */
abstract class LunarCanvas extends WWidget {
    protected static final class Hit {
        final double x, y, w, h;
        final int kind, id;

        Hit(double x, double y, double w, double h, int kind, int id) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.kind = kind;
            this.id = id;
        }

        boolean contains(double px, double py) {
            return px >= x && px <= x + w && py >= y && py <= y + h;
        }
    }

    protected final List<Hit> hits = new ArrayList<>();
    private final Map<Integer, Double> anims = new HashMap<>();

    protected double mx, my;
    protected int[] accent = {64, 140, 255};
    protected SettingColor panelC = new SettingColor(14, 17, 24, 228), cardC = new SettingColor(22, 26, 36, 215);
    protected double radiusSetting = 10;

    protected LunarGuiTheme lunar() {
        return (LunarGuiTheme) theme;
    }

    /** Reads the colors and sizes from the theme settings. Call at the start of every frame. */
    protected void loadTheme() {
        LunarGuiTheme lt = lunar();
        accent = lt.accent();
        panelC = lt.panelColor.get();
        cardC = lt.cardColor.get();
        radiusSetting = lt.cornerRadius.get();
    }

    /** One design pixel, shrunk when the window is small so everything still fits. */
    protected double unit() {
        double u = theme.scale(1);
        double fitW = getWindowWidth() / 1100.0, fitH = getWindowHeight() / 760.0;
        return Math.max(0.55, Math.min(u, Math.min(fitW, fitH)));
    }

    protected static Color col(int[] c, double a) {
        return LunarDraw.col(c[0], c[1], c[2], a);
    }

    protected static Color col(int r, int g, int b, double a) {
        return LunarDraw.col(r, g, b, a);
    }

    protected static int[] mix(int[] a, int[] b, double t) {
        t = MathHelper.clamp(t, 0, 1);
        return new int[]{(int) (a[0] + (b[0] - a[0]) * t), (int) (a[1] + (b[1] - a[1]) * t), (int) (a[2] + (b[2] - a[2]) * t)};
    }

    protected static void round(GuiRenderer r, double x, double y, double w, double h, double rad, Color c) {
        LunarDraw.round(r, x, y, w, h, rad, c);
    }

    protected static void circle(GuiRenderer r, double cx, double cy, double d, Color c) {
        LunarDraw.round(r, cx - d / 2, cy - d / 2, d, d, d / 2, c);
    }

    protected double rad(double factor, double u) {
        return radiusSetting * factor * u;
    }

    protected double textW(String s) {
        return theme.textWidth(s);
    }

    protected double textH() {
        return theme.textHeight();
    }

    protected double titleW(String s) {
        return theme.textWidth(s, s.length(), true);
    }

    protected double titleH() {
        return theme.textHeight(true);
    }

    protected void centered(GuiRenderer r, String s, double cx, double y, Color c) {
        r.text(s, cx - textW(s) / 2, y, c, false);
    }

    protected String fit(String s, double max) {
        if (textW(s) <= max) return s;
        while (s.length() > 1 && textW(s + "...") > max) s = s.substring(0, s.length() - 1);
        return s + "...";
    }

    protected double animSpeed() {
        return theme instanceof LunarGuiTheme t ? t.animationSpeed.get() : 1.0;
    }

    /** Moves a 0..1 progress toward its target in a fixed time. */
    protected double move(double v, boolean target, double delta, double seconds) {
        double step = delta * animSpeed() / seconds;
        return target ? Math.min(1, v + step) : Math.max(0, v - step);
    }

    protected static double ease(double t) {
        t = MathHelper.clamp(t, 0, 1);
        return t * t * (3 - 2 * t);
    }

    /** Eased hover (or any on/off) amount remembered under a key. */
    protected double anim(int key, boolean target, double delta, double seconds) {
        double v = move(anims.getOrDefault(key, 0.0), target, delta, seconds);
        anims.put(key, v);
        return ease(v);
    }

    /** Same, but the very first value is already the target, so nothing animates when a page opens. */
    protected double animInit(int key, boolean target, double delta, double seconds) {
        if (!anims.containsKey(key)) anims.put(key, target ? 1.0 : 0.0);
        return anim(key, target, delta, seconds);
    }

    protected void hit(double x, double y, double w, double h, int kind, int id) {
        hits.add(new Hit(x, y, w, h, kind, id));
    }

    protected Hit hitAt(double px, double py) {
        for (int i = hits.size() - 1; i >= 0; i--) {
            if (hits.get(i).contains(px, py)) return hits.get(i);
        }
        return null;
    }
}
