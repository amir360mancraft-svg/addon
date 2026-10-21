package com.blockoutlines.addon.gui;

import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.systems.config.Config;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import net.minecraft.client.gui.Click;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import net.minecraft.util.math.MathHelper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static meteordevelopment.meteorclient.MeteorClient.mc;
import static meteordevelopment.meteorclient.utils.Utils.getWindowHeight;
import static meteordevelopment.meteorclient.utils.Utils.getWindowWidth;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_BACKSPACE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE;
import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT;
import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_RIGHT;

/**
 * The whole Lunar-style module browser as one widget: header, category list on the left, filter chips and a search
 * box on top, and a scrolling grid of module cards (round badge, name, OPTIONS button, favorite star and a big
 * ENABLED / DISABLED strip). It draws everything itself and keeps a list of clickable areas from the last frame.
 *
 * Left click on the strip toggles the module, left click on OPTIONS (or a right click anywhere on a card) opens its
 * settings, the star adds it to the favorites. Just start typing to search. Colors come from the theme settings.
 */
public class WLunarBrowser extends WWidget {
    private enum Filter { All, Enabled, Disabled }
    private enum Kind { Category, Chip, Strip, Options, Star, Card, Settings }

    private static final class Hit {
        final double x, y, w, h;
        final Kind kind;
        final int index;
        final Module module;
        Hit(double x, double y, double w, double h, Kind kind, int index, Module module) {
            this.x = x; this.y = y; this.w = w; this.h = h; this.kind = kind; this.index = index; this.module = module;
        }
        boolean contains(double px, double py) { return px >= x && px <= x + w && py >= y && py <= y + h; }
    }

    private final LunarModulesScreen screen;

    // colors read from the theme at the start of every frame
    private int[] accent = {64, 140, 255}, on = {38, 168, 100}, off = {176, 38, 64};
    private SettingColor panelC, cardC;
    private double radiusSetting = 10;

    private final List<Hit> hits = new ArrayList<>();
    private final List<Category> categories = new ArrayList<>();
    private final Map<Category, List<Module>> byCategory = new HashMap<>();
    private final List<Module> everything = new ArrayList<>();

    private final Map<Module, Double> hover = new HashMap<>();
    private final Map<Module, Double> onAnim = new HashMap<>();
    private final double[] rowHover = new double[64];
    private final double[] rowActive = new double[64];
    private final double[] chipHover = new double[Filter.values().length];
    private final double[] chipActive = new double[Filter.values().length];
    private double settingsHover;
    private boolean activeInit;

    private int selected = -1; // -1 all, -2 favorites, 0.. category index
    private Filter filter = Filter.All;
    private int pendingSelected = -1;
    private Filter pendingFilter = Filter.All;
    private boolean switching;
    private double outT;

    private String search = "";
    private double scroll, scrollTarget, maxScroll;
    private double intro, pageT, closeP;
    private boolean closing, closeDone;
    private double gridX, gridY, gridW, gridH;
    private double caret;
    private double mx, my;

    public WLunarBrowser(LunarModulesScreen screen) {
        this.screen = screen;
    }

    @Override
    public void init() {
        collect();
    }

    private void collect() {
        categories.clear();
        byCategory.clear();
        everything.clear();
        for (Category c : Modules.loopCategories()) {
            List<Module> list = new ArrayList<>();
            for (Module m : Modules.get().getGroup(c)) {
                if (!Config.get().hiddenModules.get().contains(m)) list.add(m);
            }
            if (list.isEmpty()) continue;
            categories.add(c);
            byCategory.put(c, list);
            everything.addAll(list);
        }
    }

    private LunarGuiTheme lunar() {
        return (LunarGuiTheme) theme;
    }

    @Override
    protected void onCalculateSize() {
        double w = getWindowWidth(), h = getWindowHeight();
        double u = unit();
        double ps = theme instanceof LunarGuiTheme t ? t.panelSize.get() : 1.0;
        width = Math.min(w - 40 * u, 1020 * u * ps);
        height = Math.min(h - 80 * u, 640 * u * ps);
    }

    /** One design pixel, shrunk when the window is small so everything still fits. */
    private double unit() {
        double u = theme.scale(1);
        double fitW = getWindowWidth() / 1100.0, fitH = getWindowHeight() / 760.0;
        return Math.max(0.55, Math.min(u, Math.min(fitW, fitH)));
    }

    // ------------------------------------------------------------------ colors and shapes

    private static Color col(int[] c, double a) {
        return LunarDraw.col(c[0], c[1], c[2], a);
    }

    private static Color col(int r, int g, int b, double a) {
        return LunarDraw.col(r, g, b, a);
    }

    private static int[] mix(int[] a, int[] b, double t) {
        t = MathHelper.clamp(t, 0, 1);
        return new int[]{(int) (a[0] + (b[0] - a[0]) * t), (int) (a[1] + (b[1] - a[1]) * t), (int) (a[2] + (b[2] - a[2]) * t)};
    }

    private static void round(GuiRenderer r, double x, double y, double w, double h, double rad, Color c) {
        LunarDraw.round(r, x, y, w, h, rad, c);
    }

    /** Corner radius of this design: the theme setting times a factor. */
    private double rad(double factor, double u) {
        return radiusSetting * factor * u;
    }

    private double textW(String s) { return theme.textWidth(s); }
    private double textH() { return theme.textHeight(); }

    private void centered(GuiRenderer r, String s, double cx, double y, Color c) {
        r.text(s, cx - textW(s) / 2, y, c, false);
    }

    private String fit(String s, double max) {
        if (textW(s) <= max) return s;
        while (s.length() > 1 && textW(s + "...") > max) s = s.substring(0, s.length() - 1);
        return s + "...";
    }

    private static double approach(double v, double target, double speed) {
        return v + (target - v) * Math.min(1, speed);
    }

    /** Speed multiplier from the theme settings (1 = normal, lower = slower). */
    private double animSpeed() {
        return theme instanceof LunarGuiTheme t ? t.animationSpeed.get() : 1.0;
    }

    /** Moves a 0..1 progress toward its target in a fixed time, so every animation takes the same, gentle time. */
    private double move(double v, boolean target, double delta, double seconds) {
        double step = delta * animSpeed() / seconds;
        return target ? Math.min(1, v + step) : Math.max(0, v - step);
    }

    /** Smooth start and end. */
    private static double ease(double t) {
        t = MathHelper.clamp(t, 0, 1);
        return t * t * (3 - 2 * t);
    }

    // ------------------------------------------------------------------ closing

    /** Starts the fade-out; the screen closes when it is done. Returns false when this is not needed. */
    boolean beginClose() {
        if (closeDone) return false;
        closing = true;
        return true;
    }

    // ------------------------------------------------------------------ drawing

    @Override
    protected void onRender(GuiRenderer r, double mouseX, double mouseY, double delta) {
        if (everything.isEmpty()) collect();
        mx = mouseX;
        my = mouseY;
        hits.clear();

        LunarGuiTheme lt = lunar();
        accent = lt.accent();
        SettingColor en = lt.enabledColor.get(), di = lt.disabledColor.get();
        on = new int[]{en.r, en.g, en.b};
        off = new int[]{di.r, di.g, di.b};
        panelC = lt.panelColor.get();
        cardC = lt.cardColor.get();
        radiusSetting = lt.cornerRadius.get();

        double u = unit();
        delta = Math.min(delta, 0.1);
        double sp = animSpeed();
        intro = Math.min(1, intro + delta * sp / 1.0);
        pageT = Math.min(1, pageT + delta * sp / 0.75);
        caret += delta;

        if (closing) {
            closeP = Math.min(1, closeP + delta * sp / 0.3);
            if (closeP >= 1 && !closeDone) {
                closeDone = true;
                screen.finishClose();
            }
        }

        // a category / filter change: the old cards fade out first, then the new ones come in one after the other
        if (switching) {
            outT = Math.min(1, outT + delta * sp / 0.16);
            if (outT >= 1) {
                selected = pendingSelected;
                filter = pendingFilter;
                scroll = 0;
                scrollTarget = 0;
                pageT = 0;
                switching = false;
                outT = 0;
            }
        }

        double fadeAll = ease(intro) * (1 - ease(closeP));
        double page = switching ? 1 - ease(outT) : 1;
        double rise = (1 - ease(intro)) * 18 * u + ease(closeP) * 14 * u;

        double pad = 16 * u;
        double px = x, py = y + rise, pw = width, ph = height;

        // panel: faint light edge, then the dark glass
        round(r, px - 1, py - 1, pw + 2, ph + 2, rad(1.3, u) + 1, col(255, 255, 255, 34 * fadeAll));
        round(r, px, py, pw, ph, rad(1.2, u), col(panelC.r, panelC.g, panelC.b, panelC.a * fadeAll));
        r.quad(px + rad(1.2, u), py + 1, pw - rad(2.4, u), 1, col(255, 255, 255, 30 * fadeAll));

        // header
        double headerH = 54 * u;
        r.text("BETTER RENDER", px + pad, py + (headerH - textH() * 1.25) / 2, col(255, 255, 255, 255 * fadeAll), true);

        String settingsLabel = "SETTINGS";
        double chipW = textW(settingsLabel) + 28 * u, chipH = 30 * u;
        double chipX = px + pw - pad - chipW, chipY = py + (headerH - chipH) / 2;
        boolean overSettings = !closing && mx >= chipX && mx <= chipX + chipW && my >= chipY && my <= chipY + chipH;
        settingsHover = move(settingsHover, overSettings, delta, 0.22);
        hits.add(new Hit(chipX, chipY, chipW, chipH, Kind.Settings, 0, null));
        round(r, chipX, chipY, chipW, chipH, rad(0.7, u), col(255, 255, 255, (20 + 22 * ease(settingsHover)) * fadeAll));
        r.text(settingsLabel, chipX + (chipW - textW(settingsLabel)) / 2, chipY + (chipH - textH()) / 2, col(225, 232, 245, 255 * fadeAll), false);

        String count = everything.size() + " modules";
        r.text(count, chipX - 16 * u - textW(count), py + (headerH - textH()) / 2, col(150, 160, 180, 255 * fadeAll), false);
        r.quad(px, py + headerH, pw, 1, col(255, 255, 255, 18 * fadeAll));

        // sidebar
        double sideX = px + pad, sideY = py + headerH + pad, sideW = 200 * u;
        drawSidebar(r, sideX, sideY, sideW, py + ph - pad - sideY, u, delta, fadeAll);

        // filters and search
        double cx = sideX + sideW + pad;
        double cw = px + pw - pad - cx;
        double rowH = 32 * u;
        drawTopRow(r, cx, sideY, cw, rowH, u, delta, fadeAll);

        // grid
        gridX = cx;
        gridY = sideY + rowH + pad * 0.9;
        gridW = cw;
        gridH = py + ph - pad - gridY;
        drawGrid(r, u, delta, fadeAll, page);
    }

    private void drawSidebar(GuiRenderer r, double sx, double sy, double sw, double sh, double u, double delta, double fade) {
        List<String> names = new ArrayList<>();
        List<Integer> counts = new ArrayList<>();
        names.add("All");
        counts.add(everything.size());
        for (Category c : categories) {
            names.add(c.name);
            counts.add(byCategory.get(c).size());
        }
        int fav = 0;
        for (Module m : everything) if (m.favorite) fav++;
        names.add("Favorites");
        counts.add(fav);

        int shownSel = switching ? pendingSelected : selected;
        double gap = 3 * u;
        double rowH = Math.min(36 * u, (sh - gap * (names.size() - 1)) / names.size());
        double yy = sy;
        for (int i = 0; i < names.size() && i < rowHover.length; i++) {
            int sel = i == 0 ? -1 : (i == names.size() - 1 ? -2 : i - 1);
            boolean active = shownSel == sel;
            boolean over = !closing && mx >= sx && mx <= sx + sw && my >= yy && my <= yy + rowH;
            rowHover[i] = move(rowHover[i], over, delta, 0.22);
            if (!activeInit) rowActive[i] = active ? 1 : 0;
            rowActive[i] = move(rowActive[i], active, delta, 0.28);
            hits.add(new Hit(sx, yy, sw, rowH, Kind.Category, sel, null));

            double rh = ease(rowHover[i]), act = ease(rowActive[i]);
            double hoverA = 16 * rh * (1 - act);
            if (hoverA > 0.5) round(r, sx, yy, sw, rowH, rad(0.8, u), col(255, 255, 255, hoverA * fade));
            if (act > 0.01) {
                round(r, sx, yy, sw, rowH, rad(0.8, u), col(accent, 70 * act * fade));
                double bh = rowH * 0.6 * act;
                round(r, sx, yy + (rowH - bh) / 2, 3 * u, bh, 1.5 * u, col(accent, 255 * fade));
            }

            int[] tcol = mix(new int[]{190, 198, 215}, new int[]{255, 255, 255}, act);
            r.text(fit(names.get(i), sw - 64 * u), sx + 14 * u + 3 * u * act, yy + (rowH - textH()) / 2,
                col(tcol, (200 + 40 * rh + 15 * act) * fade), false);
            String n = String.valueOf(counts.get(i));
            r.text(n, sx + sw - 12 * u - textW(n), yy + (rowH - textH()) / 2, col(120, 130, 150, 255 * fade), false);
            yy += rowH + gap;
        }
        activeInit = true;
    }

    private void drawTopRow(GuiRenderer r, double cx, double cy, double cw, double rowH, double u, double delta, double fade) {
        Filter shown = switching ? pendingFilter : filter;
        double x0 = cx;
        for (Filter f : Filter.values()) {
            int i = f.ordinal();
            String label = f.name().toUpperCase(Locale.ROOT);
            double w = textW(label) + 30 * u;
            boolean active = shown == f;
            boolean over = !closing && mx >= x0 && mx <= x0 + w && my >= cy && my <= cy + rowH;
            chipHover[i] = move(chipHover[i], over, delta, 0.22);
            chipActive[i] = move(chipActive[i], active, delta, 0.25);
            hits.add(new Hit(x0, cy, w, rowH, Kind.Chip, i, null));

            double act = ease(chipActive[i]);
            round(r, x0, cy, w, rowH, rad(0.7, u), col(255, 255, 255, (20 + 22 * ease(chipHover[i])) * (1 - act) * fade));
            if (act > 0.01) round(r, x0, cy, w, rowH, rad(0.7, u), col(accent, 255 * act * fade));
            r.text(label, x0 + (w - textW(label)) / 2, cy + (rowH - textH()) / 2, col(255, 255, 255, (215 + 40 * act) * fade), false);
            x0 += w + 8 * u;
        }

        // search box
        double sw = Math.min(260 * u, cw - (x0 - cx) - 10 * u);
        if (sw < 90 * u) return;
        double sx = cx + cw - sw;
        round(r, sx, cy, sw, rowH, rad(0.7, u), col(255, 255, 255, 22 * fade));
        round(r, sx + 1, cy + 1, sw - 2, rowH - 2, Math.max(1, rad(0.7, u) - 1), col(panelC.r / 2, panelC.g / 2, panelC.b / 2, 200 * fade));
        double ty = cy + (rowH - textH()) / 2;
        boolean blink = ((int) (caret * 2)) % 2 == 0;
        if (search.isEmpty()) {
            r.text("type to search...", sx + 12 * u, ty, col(110, 120, 140, 255 * fade), false);
            if (blink) r.quad(sx + 12 * u, ty, Math.max(1, u), textH(), col(220, 228, 245, 255 * fade));
        } else {
            String s = search;
            while (s.length() > 1 && textW(s) > sw - 28 * u) s = s.substring(1);
            r.text(s, sx + 12 * u, ty, col(235, 240, 250, 255 * fade), false);
            if (blink) r.quad(sx + 12 * u + textW(s) + 1, ty, Math.max(1, u), textH(), col(220, 228, 245, 255 * fade));
        }
    }

    private List<Module> visible() {
        List<Module> src;
        if (selected == -1 || selected == -2) src = everything;
        else if (selected >= 0 && selected < categories.size()) src = byCategory.get(categories.get(selected));
        else src = everything;

        String q = search.trim().toLowerCase(Locale.ROOT);
        List<Module> out = new ArrayList<>();
        for (Module m : src) {
            if (selected == -2 && !m.favorite) continue;
            if (filter == Filter.Enabled && !m.isActive()) continue;
            if (filter == Filter.Disabled && m.isActive()) continue;
            if (!q.isEmpty() && !m.title.toLowerCase(Locale.ROOT).contains(q) && !m.name.toLowerCase(Locale.ROOT).contains(q)) continue;
            out.add(m);
        }
        return out;
    }

    private void drawGrid(GuiRenderer r, double u, double delta, double fade, double page) {
        List<Module> list = visible();
        double gap = 14 * u;
        double minCard = 200 * u;
        int cols = Math.max(1, (int) Math.floor((gridW + gap) / (minCard + gap)));
        double cardW = (gridW - gap * (cols - 1)) / cols;
        double cardH = 164 * u;
        int rows = (int) Math.ceil(list.size() / (double) cols);
        double contentH = rows * (cardH + gap) - (rows > 0 ? gap : 0);

        maxScroll = Math.max(0, contentH - gridH);
        scrollTarget = MathHelper.clamp(scrollTarget, 0, maxScroll);
        scroll = approach(scroll, scrollTarget, delta * 9 * animSpeed());
        if (Math.abs(scroll - scrollTarget) < 0.3) scroll = scrollTarget;

        if (list.isEmpty()) {
            String s = selected == -2 ? "No favorites yet. Press the star on a card." : "Nothing here.";
            centered(r, s, gridX + gridW / 2, gridY + gridH / 2 - textH() / 2, col(130, 140, 160, 255 * fade * page));
            return;
        }

        r.scissorStart(gridX - 4 * u, gridY, gridW + 8 * u, gridH);
        boolean inGrid = !closing && mx >= gridX && mx <= gridX + gridW && my >= gridY && my <= gridY + gridH;
        int[] cardRgb = {cardC.r, cardC.g, cardC.b};

        for (int i = 0; i < list.size(); i++) {
            Module m = list.get(i);
            int row = i / cols, colI = i % cols;
            double cardX = gridX + colI * (cardW + gap);
            double cardY = gridY + row * (cardH + gap) - scroll;
            if (cardY + cardH < gridY - 2 || cardY > gridY + gridH + 2) continue;

            // cards slide in one after the other (when the page opens and after every category change)
            double appear = MathHelper.clamp(pageT * 1.7 - (row * 0.14 + colI * 0.07), 0, 1);
            double ap0 = ease(appear);
            double ap = ap0 * page * fade;
            double a = ap * 255;
            double cy = cardY + (1 - ap0) * 16 * u + (1 - page) * 6 * u;

            boolean over = inGrid && mx >= cardX && mx <= cardX + cardW && my >= cy && my <= cy + cardH;
            double hp = move(hover.getOrDefault(m, 0.0), over, delta, 0.25);
            hover.put(m, hp);
            double hv = ease(hp);
            double onp = move(onAnim.getOrDefault(m, m.isActive() ? 1.0 : 0.0), m.isActive(), delta, 0.45);
            onAnim.put(m, onp);
            double onE = ease(onp);

            hits.add(new Hit(cardX, cy, cardW, cardH, Kind.Card, 0, m));

            // card body
            double cr = rad(1.0, u);
            round(r, cardX, cy, cardW, cardH, cr, col(255, 255, 255, (15 + 14 * hv) * ap));
            round(r, cardX + 1, cy + 1, cardW - 2, cardH - 2, Math.max(1, cr - 1), col(cardRgb, (cardC.a + 20 * hv) * ap));
            if (hv > 0.01) r.quad(cardX + cr, cy + 1, cardW - cr * 2, 1, col(accent, 120 * hv * ap));

            // badge: an icon for this addon's modules, the first letters for the others
            double bd = 44 * u;
            double bx = cardX + cardW / 2 - bd / 2, by = cy + 14 * u - 2 * u * hv;
            int[] tint = mix(new int[]{70, 80, 100}, accent, onE);
            double innerA = 95 + 40 * onE;
            round(r, bx, by, bd, bd, bd / 2, col(tint, (55 + 25 * hv) * ap));
            round(r, bx + 3 * u, by + 3 * u, bd - 6 * u, bd - 6 * u, (bd - 6 * u) / 2, col(tint, innerA * ap));
            boolean icon = false;
            if (lunar().showIcons.get() && LunarIcons.has(m.name)) {
                int[] eff = mix(cardRgb, tint, innerA / 255.0);
                icon = LunarIcons.draw(r, m.name, bx + bd / 2, by + bd / 2, bd * 0.27, new int[]{255, 255, 255}, a, eff);
            }
            if (!icon) centered(r, initials(m.title), cardX + cardW / 2, by + (bd - textH()) / 2, col(255, 255, 255, a));

            // name
            centered(r, fit(m.title, cardW - 20 * u), cardX + cardW / 2, cy + 66 * u, col(235, 240, 250, a));

            // OPTIONS + star
            double bh = 28 * u, byB = cy + 100 * u;
            double starW = 36 * u;
            double optW = cardW - 24 * u - starW - 6 * u;
            double ox = cardX + 12 * u;
            boolean overOpt = over && mx >= ox && mx <= ox + optW && my >= byB && my <= byB + bh;
            round(r, ox, byB, optW, bh, rad(0.7, u), col(255, 255, 255, (22 + (overOpt ? 20 : 0)) * ap));
            centered(r, "OPTIONS", ox + optW / 2, byB + (bh - textH()) / 2, col(220, 228, 245, a));
            hits.add(new Hit(ox, byB, optW, bh, Kind.Options, 0, m));

            double sx = ox + optW + 6 * u;
            boolean overStar = over && mx >= sx && mx <= sx + starW && my >= byB && my <= byB + bh;
            round(r, sx, byB, starW, bh, rad(0.7, u), col(255, 255, 255, (22 + (overStar ? 20 : 0)) * ap));
            Color starC = m.favorite ? col(255, 205, 60, a) : col(170, 180, 200, a * (overStar ? 1.0 : 0.8));
            LunarDraw.star(r, sx + starW / 2, byB + bh / 2 + 0.5 * u, 5, 9 * u, 3.9 * u, 0, starC);
            hits.add(new Hit(sx, byB, starW, bh, Kind.Star, 0, m));

            // ENABLED / DISABLED strip
            double sh = 26 * u, sy = cy + 132 * u;
            boolean overStrip = over && my >= sy && my <= sy + sh;
            int[] sc = mix(off, on, onE);
            round(r, cardX + 8 * u, sy, cardW - 16 * u, sh, rad(0.7, u), col(sc, (overStrip ? 255 : 235) * ap));
            centered(r, onE > 0.5 ? "ENABLED" : "DISABLED", cardX + cardW / 2, sy + (sh - textH()) / 2, col(255, 255, 255, a));
            hits.add(new Hit(cardX + 8 * u, sy, cardW - 16 * u, sh, Kind.Strip, 0, m));
        }
        r.scissorEnd();

        // slim scrollbar
        if (maxScroll > 1) {
            double trackH = gridH;
            double barH = Math.max(30 * u, trackH * (gridH / contentH));
            double barY = gridY + (trackH - barH) * (scroll / maxScroll);
            round(r, gridX + gridW + 5 * u, barY, 4 * u, barH, 2 * u, col(255, 255, 255, 60 * fade));
        }
    }

    private static String initials(String title) {
        StringBuilder sb = new StringBuilder();
        for (String part : title.split("[ \\-_]+")) {
            if (part.isEmpty()) continue;
            sb.append(Character.toUpperCase(part.charAt(0)));
            if (sb.length() >= 2) break;
        }
        return sb.length() == 0 ? "?" : sb.toString();
    }

    // ------------------------------------------------------------------ input

    private void switchTo(int newSelected, Filter newFilter) {
        int curSel = switching ? pendingSelected : selected;
        Filter curFilter = switching ? pendingFilter : filter;
        if (curSel == newSelected && curFilter == newFilter) return;
        pendingSelected = newSelected;
        pendingFilter = newFilter;
        switching = true;
        // when a fade-out is already running it just continues toward the newest choice
    }

    @Override
    public boolean onMouseClicked(Click click, boolean doubled) {
        if (closing) return false;
        int button = click.button();
        if (button != GLFW_MOUSE_BUTTON_LEFT && button != GLFW_MOUSE_BUTTON_RIGHT) return false;
        double px = click.x(), py = click.y();
        boolean inGrid = px >= gridX && px <= gridX + gridW && py >= gridY && py <= gridY + gridH;

        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit h = hits.get(i);
            if (!h.contains(px, py)) continue;
            boolean gridItem = h.kind == Kind.Strip || h.kind == Kind.Options || h.kind == Kind.Star || h.kind == Kind.Card;
            if (gridItem && !inGrid) continue;

            switch (h.kind) {
                case Category -> {
                    if (button == GLFW_MOUSE_BUTTON_LEFT) switchTo(h.index, switching ? pendingFilter : filter);
                }
                case Chip -> {
                    if (button == GLFW_MOUSE_BUTTON_LEFT) switchTo(switching ? pendingSelected : selected, Filter.values()[h.index]);
                }
                case Settings -> {
                    if (button == GLFW_MOUSE_BUTTON_LEFT) LunarTab.INSTANCE.openScreen(theme);
                }
                case Strip -> {
                    if (button == GLFW_MOUSE_BUTTON_LEFT) h.module.toggle();
                    else mc.setScreen(theme.moduleScreen(h.module));
                }
                case Options -> mc.setScreen(theme.moduleScreen(h.module));
                case Star -> {
                    if (button == GLFW_MOUSE_BUTTON_LEFT) h.module.favorite = !h.module.favorite;
                }
                case Card -> {
                    if (button == GLFW_MOUSE_BUTTON_RIGHT) mc.setScreen(theme.moduleScreen(h.module));
                }
            }
            return true;
        }
        return false;
    }

    @Override
    public boolean onMouseScrolled(double amount) {
        if (closing) return false;
        if (mx < x || mx > x + width || my < y || my > y + height) return false;
        scrollTarget = MathHelper.clamp(scrollTarget - amount * 70 * unit(), 0, maxScroll);
        return true;
    }

    @Override
    public boolean onCharTyped(CharInput input) {
        if (closing) return false;
        String s = input.asString();
        if (s.isEmpty() || s.charAt(0) < 32) return false;
        search += s;
        scrollTarget = 0;
        scroll = 0;
        pageT = 0.35;
        return true;
    }

    @Override
    public boolean onKeyPressed(KeyInput input) {
        return handleKey(input);
    }

    @Override
    public boolean onKeyRepeated(KeyInput input) {
        return handleKey(input);
    }

    private boolean handleKey(KeyInput input) {
        if (closing) return false;
        if (input.key() == GLFW_KEY_BACKSPACE && !search.isEmpty()) {
            search = search.substring(0, search.length() - 1);
            scrollTarget = 0;
            scroll = 0;
            pageT = 0.35;
            return true;
        }
        if (input.key() == GLFW_KEY_ESCAPE && !search.isEmpty()) {
            search = "";
            pageT = 0.35;
            return true;
        }
        return false;
    }
}
