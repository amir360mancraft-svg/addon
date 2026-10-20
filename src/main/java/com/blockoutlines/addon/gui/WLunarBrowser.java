package com.blockoutlines.addon.gui;

import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.systems.config.Config;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.render.color.Color;
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
 * settings, the star adds it to the favorites. Just start typing to search.
 */
public class WLunarBrowser extends WWidget {
    private enum Filter { All, Enabled, Disabled }
    private enum Kind { Category, Chip, Strip, Options, Star, Card }

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

    // accent colors
    private static final int[] ACCENT = {64, 140, 255};
    private static final int[] ON = {38, 168, 100};
    private static final int[] OFF = {176, 38, 64};

    private final List<Hit> hits = new ArrayList<>();
    private final List<Category> categories = new ArrayList<>();
    private final Map<Category, List<Module>> byCategory = new HashMap<>();
    private final List<Module> everything = new ArrayList<>();

    private final Map<Module, Double> hover = new HashMap<>();
    private final Map<Module, Double> onAnim = new HashMap<>();
    private final double[] rowHover = new double[64];
    private final double[] chipHover = new double[Filter.values().length];

    private int selected = -1; // -1 all, -2 favorites, 0.. category index
    private Filter filter = Filter.All;
    private String search = "";

    private double scroll, scrollTarget, maxScroll;
    private double intro;
    private double gridX, gridY, gridW, gridH;
    private double caret;
    private double mx, my;

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

    @Override
    protected void onCalculateSize() {
        double w = getWindowWidth(), h = getWindowHeight();
        double u = unit();
        width = Math.min(w - 40 * u, 1020 * u);
        height = Math.min(h - 80 * u, 640 * u);
    }

    /** One design pixel, shrunk when the window is small so everything still fits. */
    private double unit() {
        double u = theme.scale(1);
        double fitW = getWindowWidth() / 1100.0, fitH = getWindowHeight() / 760.0;
        return Math.max(0.55, Math.min(u, Math.min(fitW, fitH)));
    }

    // ------------------------------------------------------------------ colors and shapes

    private static Color col(int[] c, double a) {
        return new Color(c[0], c[1], c[2], (int) Math.max(0, Math.min(255, Math.round(a))));
    }

    private static Color col(int r, int g, int b, double a) {
        return new Color(r, g, b, (int) Math.max(0, Math.min(255, Math.round(a))));
    }

    private static int[] mix(int[] a, int[] b, double t) {
        t = MathHelper.clamp(t, 0, 1);
        return new int[]{(int) (a[0] + (b[0] - a[0]) * t), (int) (a[1] + (b[1] - a[1]) * t), (int) (a[2] + (b[2] - a[2]) * t)};
    }

    /** A rectangle with round corners, drawn as one-pixel rows at the top and bottom. */
    private static void round(GuiRenderer r, double x, double y, double w, double h, double rad, Color c) {
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

    // ------------------------------------------------------------------ drawing

    @Override
    protected void onRender(GuiRenderer r, double mouseX, double mouseY, double delta) {
        if (everything.isEmpty()) collect();
        mx = mouseX;
        my = mouseY;
        hits.clear();

        double u = unit();
        delta = Math.min(delta, 0.1);
        intro = Math.min(1, intro + delta * animSpeed() / 1.0);
        caret += delta;
        double ease = ease(intro);
        double fade = ease * 255;

        double pad = 16 * u;
        double rise = (1 - ease) * 18 * u;
        double px = x, py = y + rise, pw = width, ph = height;

        // panel: faint light edge, then the dark glass
        round(r, px - 1, py - 1, pw + 2, ph + 2, 13 * u, col(255, 255, 255, 34 * ease));
        round(r, px, py, pw, ph, 12 * u, col(14, 17, 24, 228 * ease));
        r.quad(px + 12 * u, py + 1, pw - 24 * u, 1, col(255, 255, 255, 30 * ease));

        // header
        double headerH = 54 * u;
        r.text("BETTER RENDER", px + pad, py + (headerH - textH() * 1.25) / 2, col(255, 255, 255, fade), true);
        String count = everything.size() + " modules";
        r.text(count, px + pw - pad - textW(count), py + (headerH - textH()) / 2, col(150, 160, 180, fade), false);
        r.quad(px, py + headerH, pw, 1, col(255, 255, 255, 18 * ease));

        // sidebar
        double sideX = px + pad, sideY = py + headerH + pad, sideW = 200 * u;
        drawSidebar(r, sideX, sideY, sideW, py + ph - pad - sideY, u, delta, ease);

        // filters and search
        double cx = sideX + sideW + pad;
        double cw = px + pw - pad - cx;
        double rowH = 32 * u;
        drawTopRow(r, cx, sideY, cw, rowH, u, delta, ease);

        // grid
        gridX = cx;
        gridY = sideY + rowH + pad * 0.9;
        gridW = cw;
        gridH = py + ph - pad - gridY;
        drawGrid(r, u, delta, ease);
    }

    private void drawSidebar(GuiRenderer r, double sx, double sy, double sw, double sh, double u, double delta, double ease) {
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

        double gap = 3 * u;
        double rowH = Math.min(36 * u, (sh - gap * (names.size() - 1)) / names.size());
        double yy = sy;
        for (int i = 0; i < names.size() && i < rowHover.length; i++) {
            int sel = i == 0 ? -1 : (i == names.size() - 1 ? -2 : i - 1);
            boolean active = selected == sel;
            boolean over = mx >= sx && mx <= sx + sw && my >= yy && my <= yy + rowH;
            rowHover[i] = move(rowHover[i], over, delta, 0.22);
            hits.add(new Hit(sx, yy, sw, rowH, Kind.Category, sel, null));

            double rh = ease(rowHover[i]);
            double a = active ? 70 : 16 * rh;
            if (a > 0.5) round(r, sx, yy, sw, rowH, 8 * u, col(active ? ACCENT : new int[]{255, 255, 255}, a * ease));
            if (active) round(r, sx, yy + rowH * 0.2, 3 * u, rowH * 0.6, 1.5 * u, col(ACCENT, 255 * ease));

            Color tc = active ? col(255, 255, 255, 255 * ease) : col(190, 198, 215, (200 + 40 * rh) * ease);
            r.text(fit(names.get(i), sw - 64 * u), sx + 14 * u, yy + (rowH - textH()) / 2, tc, false);
            String n = String.valueOf(counts.get(i));
            r.text(n, sx + sw - 12 * u - textW(n), yy + (rowH - textH()) / 2, col(120, 130, 150, 255 * ease), false);
            yy += rowH + gap;
        }
    }

    private void drawTopRow(GuiRenderer r, double cx, double cy, double cw, double rowH, double u, double delta, double ease) {
        // filter chips
        double x0 = cx;
        for (Filter f : Filter.values()) {
            String label = f.name().toUpperCase(Locale.ROOT);
            double w = textW(label) + 30 * u;
            boolean active = filter == f;
            boolean over = mx >= x0 && mx <= x0 + w && my >= cy && my <= cy + rowH;
            chipHover[f.ordinal()] = move(chipHover[f.ordinal()], over, delta, 0.22);
            hits.add(new Hit(x0, cy, w, rowH, Kind.Chip, f.ordinal(), null));
            if (active) round(r, x0, cy, w, rowH, 7 * u, col(ACCENT, 255 * ease));
            else round(r, x0, cy, w, rowH, 7 * u, col(255, 255, 255, (20 + 22 * ease(chipHover[f.ordinal()])) * ease));
            r.text(label, x0 + (w - textW(label)) / 2, cy + (rowH - textH()) / 2, col(255, 255, 255, (active ? 255 : 215) * ease), false);
            x0 += w + 8 * u;
        }

        // search box
        double sw = Math.min(260 * u, cw - (x0 - cx) - 10 * u);
        if (sw < 90 * u) return;
        double sx = cx + cw - sw;
        round(r, sx, cy, sw, rowH, 7 * u, col(255, 255, 255, 22 * ease));
        round(r, sx + 1, cy + 1, sw - 2, rowH - 2, 6 * u, col(8, 10, 15, 200 * ease));
        double ty = cy + (rowH - textH()) / 2;
        boolean blink = ((int) (caret * 2)) % 2 == 0;
        if (search.isEmpty()) {
            r.text("type to search...", sx + 12 * u, ty, col(110, 120, 140, 255 * ease), false);
            if (blink) r.quad(sx + 12 * u, ty, Math.max(1, u), textH(), col(220, 228, 245, 255 * ease));
        } else {
            String shown = search;
            while (shown.length() > 1 && textW(shown) > sw - 28 * u) shown = shown.substring(1);
            r.text(shown, sx + 12 * u, ty, col(235, 240, 250, 255 * ease), false);
            if (blink) r.quad(sx + 12 * u + textW(shown) + 1, ty, Math.max(1, u), textH(), col(220, 228, 245, 255 * ease));
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

    private void drawGrid(GuiRenderer r, double u, double delta, double ease) {
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
            centered(r, s, gridX + gridW / 2, gridY + gridH / 2 - textH() / 2, col(130, 140, 160, 255 * ease));
            return;
        }

        r.scissorStart(gridX - 4 * u, gridY, gridW + 8 * u, gridH);
        boolean inGrid = mx >= gridX && mx <= gridX + gridW && my >= gridY && my <= gridY + gridH;

        for (int i = 0; i < list.size(); i++) {
            Module m = list.get(i);
            int row = i / cols, colI = i % cols;
            double cardX = gridX + colI * (cardW + gap);
            double cardY = gridY + row * (cardH + gap) - scroll;
            if (cardY + cardH < gridY - 2 || cardY > gridY + gridH + 2) continue;

            // cards slide in one after the other when the page is opened
            double appear = MathHelper.clamp(intro * 1.7 - (row * 0.14 + colI * 0.07), 0, 1);
            double ap = ease(appear);
            double a = ap * 255;
            double cy = cardY + (1 - ap) * 16 * u;

            boolean over = inGrid && mx >= cardX && mx <= cardX + cardW && my >= cy && my <= cy + cardH;
            double hp = move(hover.getOrDefault(m, 0.0), over, delta, 0.25);
            hover.put(m, hp);
            double hv = ease(hp);
            double onp = move(onAnim.getOrDefault(m, m.isActive() ? 1.0 : 0.0), m.isActive(), delta, 0.45);
            onAnim.put(m, onp);
            double on = ease(onp);

            hits.add(new Hit(cardX, cy, cardW, cardH, Kind.Card, 0, m));

            // card body
            round(r, cardX, cy, cardW, cardH, 10 * u, col(255, 255, 255, (15 + 14 * hv) * ap));
            round(r, cardX + 1, cy + 1, cardW - 2, cardH - 2, 9 * u, col(20, 24, 33, (210 + 20 * hv) * ap));
            if (hv > 0.01) r.quad(cardX + 12 * u, cy + 1, cardW - 24 * u, 1, col(ACCENT, 120 * hv * ap));

            // badge with the first letters of the name
            double bd = 44 * u;
            double bx = cardX + cardW / 2 - bd / 2, by = cy + 14 * u - 2 * u * hv;
            int[] tint = mix(new int[]{70, 80, 100}, ACCENT, on);
            round(r, bx, by, bd, bd, bd / 2, col(tint, (55 + 25 * hv) * ap));
            round(r, bx + 3 * u, by + 3 * u, bd - 6 * u, bd - 6 * u, (bd - 6 * u) / 2, col(tint, (95 + 40 * on) * ap));
            String letters = initials(m.title);
            centered(r, letters, cardX + cardW / 2, by + (bd - textH()) / 2, col(255, 255, 255, a));

            // name
            String title = fit(m.title, cardW - 20 * u);
            centered(r, title, cardX + cardW / 2, cy + 66 * u, col(235, 240, 250, a));

            // OPTIONS + star
            double bh = 28 * u, byB = cy + 100 * u;
            double starW = 36 * u;
            double optW = cardW - 24 * u - starW - 6 * u;
            double ox = cardX + 12 * u;
            boolean overOpt = over && mx >= ox && mx <= ox + optW && my >= byB && my <= byB + bh;
            round(r, ox, byB, optW, bh, 7 * u, col(255, 255, 255, (22 + (overOpt ? 20 : 0)) * ap));
            centered(r, "OPTIONS", ox + optW / 2, byB + (bh - textH()) / 2, col(220, 228, 245, a));
            hits.add(new Hit(ox, byB, optW, bh, Kind.Options, 0, m));

            double sx = ox + optW + 6 * u;
            boolean overStar = over && mx >= sx && mx <= sx + starW && my >= byB && my <= byB + bh;
            round(r, sx, byB, starW, bh, 7 * u, col(255, 255, 255, (22 + (overStar ? 20 : 0)) * ap));
            drawStar(r, sx + starW / 2, byB + bh / 2, 8.5 * u, m.favorite ? col(255, 205, 60, a) : col(150, 160, 180, a));
            hits.add(new Hit(sx, byB, starW, bh, Kind.Star, 0, m));

            // ENABLED / DISABLED strip
            double sh = 26 * u, sy = cy + 132 * u;
            boolean overStrip = over && my >= sy && my <= sy + sh;
            int[] sc = mix(OFF, ON, on);
            round(r, cardX + 8 * u, sy, cardW - 16 * u, sh, 7 * u, col(sc, (overStrip ? 255 : 235) * ap));
            String state = on > 0.5 ? "ENABLED" : "DISABLED";
            centered(r, state, cardX + cardW / 2, sy + (sh - textH()) / 2, col(255, 255, 255, a));
            hits.add(new Hit(cardX + 8 * u, sy, cardW - 16 * u, sh, Kind.Strip, 0, m));
        }
        r.scissorEnd();

        // slim scrollbar
        if (maxScroll > 1) {
            double trackH = gridH;
            double barH = Math.max(30 * u, trackH * (gridH / contentH));
            double barY = gridY + (trackH - barH) * (scroll / maxScroll);
            round(r, gridX + gridW + 5 * u, barY, 4 * u, barH, 2 * u, col(255, 255, 255, 60 * ease));
        }
    }

    /** A five-point star made of triangles. */
    private static void drawStar(GuiRenderer r, double cx, double cy, double rad, Color c) {
        double[] px = new double[10], py = new double[10];
        for (int i = 0; i < 10; i++) {
            double ang = -Math.PI / 2 + i * Math.PI / 5;
            double rr = (i % 2 == 0) ? rad : rad * 0.42;
            px[i] = cx + Math.cos(ang) * rr;
            py[i] = cy + Math.sin(ang) * rr;
        }
        for (int i = 0; i < 10; i++) {
            int j = (i + 1) % 10;
            r.triangle(cx, cy, px[i], py[i], px[j], py[j], c);
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

    @Override
    public boolean onMouseClicked(Click click, boolean doubled) {
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
                    if (button == GLFW_MOUSE_BUTTON_LEFT) { selected = h.index; scrollTarget = 0; scroll = 0; intro = 0.25; }
                }
                case Chip -> {
                    if (button == GLFW_MOUSE_BUTTON_LEFT) { filter = Filter.values()[h.index]; scrollTarget = 0; scroll = 0; }
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
        if (mx < x || mx > x + width || my < y || my > y + height) return false;
        scrollTarget = MathHelper.clamp(scrollTarget - amount * 70 * unit(), 0, maxScroll);
        return true;
    }

    @Override
    public boolean onCharTyped(CharInput input) {
        String s = input.asString();
        if (s.isEmpty() || s.charAt(0) < 32) return false;
        search += s;
        scrollTarget = 0;
        scroll = 0;
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
        if (input.key() == GLFW_KEY_BACKSPACE && !search.isEmpty()) {
            search = search.substring(0, search.length() - 1);
            scrollTarget = 0;
            scroll = 0;
            return true;
        }
        if (input.key() == GLFW_KEY_ESCAPE && !search.isEmpty()) {
            search = "";
            return true;
        }
        return false;
    }
}
