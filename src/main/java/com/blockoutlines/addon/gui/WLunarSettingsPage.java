package com.blockoutlines.addon.gui;

import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import net.minecraft.client.gui.Click;
import net.minecraft.util.math.MathHelper;

import static meteordevelopment.meteorclient.MeteorClient.mc;
import static meteordevelopment.meteorclient.utils.Utils.getWindowHeight;
import static meteordevelopment.meteorclient.utils.Utils.getWindowWidth;
import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT;

/**
 * The settings page of the Lunar theme, laid out like the settings window of a launcher: title and close button on
 * top, a list of pages on the left, and on the right big theme cards, color swatches, sliders and switches.
 * Everything here changes the settings of the theme directly, so it is saved like any other setting.
 */
public class WLunarSettingsPage extends LunarCanvas {
    // clickable kinds: 1..3 are in the frame (always clickable), 10 and up are inside the scrolling content
    private static final int K_CLOSE = 1, K_NAV = 2, K_ADV = 3;
    private static final int K_PRESET = 10, K_ACCENT = 11, K_TOGGLE = 12, K_SLIDER = 13, K_EN = 14, K_DIS = 15, K_RESET = 16, K_WIZARD = 17;

    private static final String[] PAGES = {"Appearance", "Layout", "Behavior"};
    private static final String[] PRESET_NAMES = {"Midnight", "OLED", "Graphite", "Glass"};
    private static final int[][] PRESET_PANEL = {{14, 17, 24, 228}, {0, 0, 0, 245}, {26, 26, 30, 236}, {14, 17, 24, 150}};
    private static final int[][] PRESET_CARD = {{22, 26, 36, 215}, {14, 14, 16, 238}, {40, 40, 46, 228}, {34, 42, 60, 120}};
    private static final int[][] PRESET_SHOW = {{14, 17, 24}, {0, 0, 0}, {28, 28, 33}, {30, 42, 66}};
    private static final int[][] PRESET_SHOW_CARD = {{30, 36, 50}, {16, 16, 18}, {46, 46, 52}, {52, 66, 96}};
    private static final int[][] ENABLED = {{38, 168, 100}, {30, 190, 170}, {64, 140, 255}, {130, 200, 60}};
    private static final int[][] DISABLED = {{176, 38, 64}, {220, 110, 40}, {100, 106, 122}, {140, 70, 200}};

    private final LunarPageScreen screen;

    private int page, pendingPage;
    private boolean switching;
    private double outT, contentT, intro, closeP;
    private boolean closing, closeDone;

    private double scroll, scrollTarget, maxScroll;
    private double cx, cw, contentTop, contentBottom;
    private int dragSlider = -1;
    private final double[] sliderX = new double[5], sliderW = new double[5];

    public WLunarSettingsPage(LunarPageScreen screen) {
        this.screen = screen;
    }

    @Override
    protected void onCalculateSize() {
        double w = getWindowWidth(), h = getWindowHeight();
        double u = unit();
        double ps = theme instanceof LunarGuiTheme t ? t.panelSize.get() : 1.0;
        width = Math.min(w - 40 * u, 1000 * u * ps);
        height = Math.min(h - 80 * u, 640 * u * ps);
    }

    boolean beginClose() {
        if (closeDone) return false;
        closing = true;
        return true;
    }

    // ------------------------------------------------------------------ drawing

    @Override
    protected void onRender(GuiRenderer r, double mouseX, double mouseY, double delta) {
        mx = mouseX;
        my = mouseY;
        hits.clear();
        loadTheme();

        double u = unit();
        delta = Math.min(delta, 0.1);
        double sp = animSpeed();
        intro = Math.min(1, intro + delta * sp / 0.8);
        contentT = Math.min(1, contentT + delta * sp / 0.4);
        if (closing) {
            closeP = Math.min(1, closeP + delta * sp / 0.3);
            if (closeP >= 1 && !closeDone) {
                closeDone = true;
                screen.finishClose();
            }
        }
        if (switching) {
            outT = Math.min(1, outT + delta * sp / 0.14);
            if (outT >= 1) {
                page = pendingPage;
                switching = false;
                outT = 0;
                scroll = 0;
                scrollTarget = 0;
                contentT = 0;
            }
        }

        double fade = ease(intro) * (1 - ease(closeP));
        double pageFade = switching ? 1 - ease(outT) : ease(contentT);
        double a = fade * pageFade;
        double rise = (1 - ease(intro)) * 18 * u + ease(closeP) * 14 * u;

        double px = x, py = y + rise, pw = width, ph = height;
        double pad = 22 * u;

        // panel
        round(r, px - 1, py - 1, pw + 2, ph + 2, rad(1.3, u) + 1, col(255, 255, 255, 34 * fade));
        round(r, px, py, pw, ph, rad(1.2, u), col(panelC.r, panelC.g, panelC.b, panelC.a * fade));

        // header: title, close button, line
        double headerH = 70 * u;
        r.text("Settings", px + pad, py + (headerH - titleH()) / 2, col(255, 255, 255, 255 * fade), true);
        double cd = 38 * u, cxl = px + pw - pad - cd, cyl = py + (headerH - cd) / 2;
        boolean overClose = !closing && mx >= cxl && mx <= cxl + cd && my >= cyl && my <= cyl + cd;
        double ch = anim(K_CLOSE * 1000, overClose, delta, 0.2);
        circle(r, cxl + cd / 2, cyl + cd / 2, cd, col(255, 255, 255, (24 + 28 * ch) * fade));
        double k = cd * 0.18, mxl = cxl + cd / 2, myl = cyl + cd / 2;
        LunarDraw.seg(r, mxl - k, myl - k, mxl + k, myl + k, Math.max(1.5, 1.9 * u), col(235, 240, 250, 255 * fade));
        LunarDraw.seg(r, mxl - k, myl + k, mxl + k, myl - k, Math.max(1.5, 1.9 * u), col(235, 240, 250, 255 * fade));
        hit(cxl, cyl, cd, cd, K_CLOSE, 0);
        r.quad(px, py + headerH, pw, 1, col(255, 255, 255, 20 * fade));

        // sidebar
        double sideW = 240 * u;
        double bodyTop = py + headerH + 1;
        r.quad(px + sideW, bodyTop, 1, ph - headerH - 1, col(255, 255, 255, 18 * fade));
        double yy = bodyTop + 24 * u;
        r.text("DISPLAY", px + pad, yy, col(130, 142, 165, 255 * fade), false);
        yy += textH() + 12 * u;
        int shown = switching ? pendingPage : page;
        for (int i = 0; i < PAGES.length; i++) {
            yy = navItem(r, px + 14 * u, yy, sideW - 28 * u, 46 * u, u, delta, fade, PAGES[i], K_NAV, i, shown == i) + 4 * u;
        }
        yy += 14 * u;
        r.text("ADVANCED", px + pad, yy, col(130, 142, 165, 255 * fade), false);
        yy += textH() + 12 * u;
        navItem(r, px + 14 * u, yy, sideW - 28 * u, 46 * u, u, delta, fade, "All theme colors", K_ADV, 0, false);

        String foot = "Better Render";
        r.text(foot, px + pad, py + ph - pad - textH() * 2 - 4 * u, col(200, 208, 225, 255 * fade), false);
        r.text("Lunar theme", px + pad, py + ph - pad - textH(), col(120, 132, 155, 255 * fade), false);

        // content
        cx = px + sideW + 30 * u;
        cw = px + pw - pad - cx;
        contentTop = bodyTop;
        contentBottom = py + ph - 6 * u;
        r.scissorStart(px + sideW + 2, contentTop, pw - sideW - 4, contentBottom - contentTop);

        double y0 = contentTop + 24 * u;
        double y = y0 - scroll;
        switch (page) {
            case 0 -> y = pageAppearance(r, y, u, a, delta);
            case 1 -> y = pageLayout(r, y, u, a, delta);
            default -> y = pageBehavior(r, y, u, a, delta);
        }
        double contentH = (y + scroll) - contentTop + 12 * u;
        maxScroll = Math.max(0, contentH - (contentBottom - contentTop));
        scrollTarget = MathHelper.clamp(scrollTarget, 0, maxScroll);
        scroll += (scrollTarget - scroll) * Math.min(1, delta * 9 * sp);
        if (Math.abs(scroll - scrollTarget) < 0.3) scroll = scrollTarget;
        r.scissorEnd();

        if (maxScroll > 1) {
            double trackH = contentBottom - contentTop - 12 * u;
            double barH = Math.max(30 * u, trackH * ((contentBottom - contentTop) / contentH));
            double barY = contentTop + 6 * u + (trackH - barH) * (scroll / maxScroll);
            round(r, px + pw - 10 * u, barY, 4 * u, barH, 2 * u, col(255, 255, 255, 56 * fade));
        }
    }

    private double navItem(GuiRenderer r, double x, double y, double w, double h, double u, double delta, double fade,
                           String label, int kind, int id, boolean active) {
        boolean over = !closing && mx >= x && mx <= x + w && my >= y && my <= y + h;
        double hv = anim(kind * 1000 + 100 + id, over, delta, 0.2);
        double act = animInit(kind * 1000 + id, active, delta, 0.25);
        if (hv > 0.01) round(r, x, y, w, h, rad(0.9, u), col(255, 255, 255, 14 * hv * (1 - act) * fade));
        if (act > 0.01) round(r, x, y, w, h, rad(0.9, u), col(accent, 46 * act * fade));
        int[] tc = mix(new int[]{205, 212, 228}, new int[]{Math.min(255, accent[0] + 40), Math.min(255, accent[1] + 40), Math.min(255, accent[2] + 40)}, act);
        r.text(label, x + 18 * u + 3 * u * act, y + (h - textH()) / 2, col(tc, 255 * fade), false);
        hit(x, y, w, h, kind, id);
        return y + h;
    }

    // ------------------------------------------------------------------ pages

    private double heading(GuiRenderer r, double y, String title, String desc, double a, double u) {
        r.text(title, cx, y, col(255, 255, 255, 255 * a), true);
        y += titleH() + 5 * u;
        if (desc != null) {
            r.text(fit(desc, cw), cx, y, col(150, 160, 182, 255 * a), false);
            y += textH();
        }
        return y + 16 * u;
    }

    private double pageAppearance(GuiRenderer r, double y, double u, double a, double delta) {
        LunarGuiTheme lt = lunar();
        y = heading(r, y, "Color theme", "Pick the look of the Better Render menus.", a, u);

        int cols = 3;
        double gap = 16 * u;
        double cardW = (cw - gap * (cols - 1)) / cols;
        double prevH = 110 * u, footH = 42 * u, cardH = prevH + footH;
        int sel = lt.themePreset.get();
        int rows = (PRESET_NAMES.length + cols - 1) / cols;
        for (int i = 0; i < PRESET_NAMES.length; i++) {
            double x0 = cx + (i % cols) * (cardW + gap), y0 = y + (i / cols) * (cardH + gap);
            boolean over = !closing && inContent(my) && mx >= x0 && mx <= x0 + cardW && my >= y0 && my <= y0 + cardH;
            double hv = anim(K_PRESET * 1000 + i, over, delta, 0.2);
            double sa = animInit(K_PRESET * 1000 + 100 + i, sel == i, delta, 0.3);
            presetCard(r, x0, y0, cardW, prevH, footH, i, hv, sa, a, u);
            hit(x0, y0, cardW, cardH, K_PRESET, i);
        }
        y += rows * (cardH + gap) + 8 * u;

        y = heading(r, y, "Accent color", "Used for buttons, switches, sliders and highlights.", a, u);
        double d = 34 * u, sgap = 14 * u;
        for (int i = 0; i < LunarGuiTheme.PRESETS.length; i++) {
            Object[] p = LunarGuiTheme.PRESETS[i];
            int[] c = {(Integer) p[1], (Integer) p[2], (Integer) p[3]};
            double x0 = cx + i * (d + sgap) + d / 2, y0 = y + d / 2;
            boolean chosen = c[0] == accent[0] && c[1] == accent[1] && c[2] == accent[2];
            boolean over = !closing && inContent(my) && Math.abs(mx - x0) <= d / 2 + 4 && Math.abs(my - y0) <= d / 2 + 4;
            double hv = anim(K_ACCENT * 1000 + i, over, delta, 0.2);
            double ring = animInit(K_ACCENT * 1000 + 100 + i, chosen, delta, 0.25);
            if (ring > 0.01) circle(r, x0, y0, d + 10 * u, col(255, 255, 255, 230 * ring * a));
            if (ring > 0.01) circle(r, x0, y0, d + 5 * u, col(panelC.r, panelC.g, panelC.b, 255 * a));
            circle(r, x0, y0, d + 2 * u * hv, col(c, 255 * a));
            hit(x0 - d / 2 - 4, y0 - d / 2 - 4, d + 8, d + 8, K_ACCENT, i);
        }
        y += d + 28 * u;

        y = sliderRow(r, y, 0, "Panel opacity", "How see-through the main panels are.", a, u, delta);
        y = sliderRow(r, y, 1, "Card opacity", "How see-through the module cards are.", a, u, delta);
        return y;
    }

    private double pageLayout(GuiRenderer r, double y, double u, double a, double delta) {
        y = heading(r, y, "Layout", "Size and shape of the menus.", a, u);
        y = sliderRow(r, y, 2, "Corner radius", "How round cards, buttons and panels are.", a, u, delta);
        y = sliderRow(r, y, 3, "Panel size", "Makes the module panel bigger or smaller.", a, u, delta);
        y = toggleRow(r, y, 0, "Module icons", "Small icons on the cards of the Better Render modules.", lunar().showIcons.get(), a, u, delta);
        return y;
    }

    private double pageBehavior(GuiRenderer r, double y, double u, double a, double delta) {
        LunarGuiTheme lt = lunar();
        y = heading(r, y, "Behavior", "Animations and the colors of the ENABLED and DISABLED strips.", a, u);
        y = sliderRow(r, y, 4, "Animation speed", "Lower is slower and smoother.", a, u, delta);
        y = swatchRow(r, y, K_EN, "Enabled color", "The strip of a module that is on.", ENABLED, lt.enabledColor.get(), a, u, delta);
        y = swatchRow(r, y, K_DIS, "Disabled color", "The strip of a module that is off.", DISABLED, lt.disabledColor.get(), a, u, delta);

        y = toggleRow(r, y, 1, "Hub on Right Shift", "Show the HUD editor with the big buttons first, then the module list.", lt.showHub.get(), a, u, delta);

        // reset
        double rowH = 62 * u, cy = y + rowH / 2;
        r.text("Reset appearance", cx, y + 10 * u, col(240, 244, 252, 255 * a), false);
        r.text("Puts every Lunar color and size back to the default.", cx, y + 10 * u + textH() + 4 * u, col(150, 160, 182, 255 * a), false);
        String label = "RESET";
        double bw = textW(label) + 36 * u, bh = 34 * u, bx = cx + cw - bw, by = cy - bh / 2;
        boolean over = !closing && inContent(my) && mx >= bx && mx <= bx + bw && my >= by && my <= by + bh;
        double hv = anim(K_RESET * 1000, over, delta, 0.2);
        round(r, bx, by, bw, bh, rad(0.7, u), col(255, 255, 255, (24 + 24 * hv) * a));
        r.text(label, bx + (bw - textW(label)) / 2, by + (bh - textH()) / 2, col(235, 240, 250, 255 * a), false);
        hit(bx, by, bw, bh, K_RESET, 0);
        return y + rowH;
    }

    private void presetCard(GuiRenderer r, double x, double y, double w, double ph, double fh, int i, double hv, double sa, double a, double u) {
        double rr = rad(1.0, u);
        int[] edge = mix(new int[]{255, 255, 255}, accent, sa);
        double edgeA = 26 + 24 * hv + 205 * sa;
        round(r, x, y, w, ph + fh, rr, col(edge, edgeA * a));
        round(r, x + 1, y + 1, w - 2, ph + fh - 2, Math.max(1, rr - 1), col(17, 19, 25, 255 * a));

        // preview: the panel color with a small window in it
        int[] bg = PRESET_SHOW[i], cd = PRESET_SHOW_CARD[i];
        round(r, x + 1, y + 1, w - 2, ph, Math.max(1, rr - 1), col(bg, 255 * a));
        r.quad(x + 1, y + ph - rr, w - 2, rr, col(bg, 255 * a));
        double mw = w * 0.62, mh = ph * 0.54, mxp = x + (w - mw) / 2, myp = y + (ph - mh) / 2;
        round(r, mxp, myp, mw, mh, rad(0.6, u), col(cd, 255 * a));
        double sq = mh * 0.46;
        round(r, mxp + mw * 0.08, myp + (mh - sq) / 2, sq, sq, rad(0.4, u), col(accent, 210 * a));
        double bx = mxp + mw * 0.08 + sq + mw * 0.08, bw = mw - (bx - mxp) - mw * 0.08;
        round(r, bx, myp + mh * 0.30, bw, mh * 0.13, mh * 0.065, col(225, 230, 240, 200 * a));
        round(r, bx, myp + mh * 0.54, bw * 0.6, mh * 0.13, mh * 0.065, col(160, 170, 188, 170 * a));

        // footer: radio button and name
        double fy = y + ph + fh / 2;
        double rd = 17 * u, rx = x + 18 * u + rd / 2;
        int[] ring = mix(new int[]{170, 178, 196}, accent, sa);
        circle(r, rx, fy, rd, col(ring, 255 * a));
        circle(r, rx, fy, rd - 4 * u, col(17, 19, 25, 255 * a));
        if (sa > 0.01) circle(r, rx, fy, (rd - 8 * u) * sa, col(accent, 255 * a));
        r.text(PRESET_NAMES[i], rx + rd / 2 + 10 * u, fy - textH() / 2, col(255, 255, 255, 255 * a), false);
    }

    private boolean inContent(double py) {
        return py >= contentTop && py <= contentBottom;
    }

    // ------------------------------------------------------------------ rows

    private double sliderT(int id) {
        LunarGuiTheme lt = lunar();
        double t = switch (id) {
            case 0 -> (lt.panelColor.get().a - 40) / 215.0;
            case 1 -> (lt.cardColor.get().a - 30) / 225.0;
            case 2 -> lt.cornerRadius.get() / 24.0;
            case 3 -> (lt.panelSize.get() - 0.6) / 0.7;
            default -> (lt.animationSpeed.get() - 0.2) / 2.8;
        };
        return MathHelper.clamp(t, 0, 1);
    }

    private String sliderText(int id) {
        LunarGuiTheme lt = lunar();
        return switch (id) {
            case 0 -> Math.round(lt.panelColor.get().a / 2.55) + "%";
            case 1 -> Math.round(lt.cardColor.get().a / 2.55) + "%";
            case 2 -> String.valueOf(Math.round(lt.cornerRadius.get()));
            case 3 -> Math.round(lt.panelSize.get() * 100) + "%";
            default -> String.format(java.util.Locale.ROOT, "%.2fx", lt.animationSpeed.get());
        };
    }

    private static void setAlpha(Setting<SettingColor> s, int alpha) {
        SettingColor c = s.get();
        s.set(new SettingColor(c.r, c.g, c.b, MathHelper.clamp(alpha, 0, 255)));
    }

    private void setSlider(int id, double t) {
        LunarGuiTheme lt = lunar();
        t = MathHelper.clamp(t, 0, 1);
        switch (id) {
            case 0 -> setAlpha(lt.panelColor, (int) Math.round(40 + t * 215));
            case 1 -> setAlpha(lt.cardColor, (int) Math.round(30 + t * 225));
            case 2 -> lt.cornerRadius.set((double) Math.round(t * 24));
            case 3 -> {
                lt.panelSize.set(Math.round((0.6 + t * 0.7) * 20) / 20.0);
                invalidate();
            }
            default -> lt.animationSpeed.set(Math.round((0.2 + t * 2.8) * 20) / 20.0);
        }
    }

    private void rowTitle(GuiRenderer r, double y, String title, String desc, double a, double u) {
        r.text(title, cx, y + 11 * u, col(240, 244, 252, 255 * a), false);
        r.text(fit(desc, cw * 0.5), cx, y + 11 * u + textH() + 4 * u, col(150, 160, 182, 255 * a), false);
    }

    private double sliderRow(GuiRenderer r, double y, int id, String title, String desc, double a, double u, double delta) {
        double rowH = 66 * u, cy = y + rowH / 2;
        rowTitle(r, y, title, desc, a, u);
        double valueW = 64 * u, tw = Math.min(260 * u, cw * 0.38);
        double tx = cx + cw - valueW - tw - 8 * u;
        sliderX[id] = tx;
        sliderW[id] = tw;
        double t = sliderT(id);
        boolean over = !closing && inContent(my) && mx >= tx - 10 * u && mx <= tx + tw + 10 * u && my >= cy - 16 * u && my <= cy + 16 * u;
        double kh = anim(K_SLIDER * 1000 + id, over || dragSlider == id, delta, 0.18);
        round(r, tx, cy - 3 * u, tw, 6 * u, 3 * u, col(255, 255, 255, 34 * a));
        round(r, tx, cy - 3 * u, Math.max(6 * u, tw * t), 6 * u, 3 * u, col(accent, 255 * a));
        circle(r, tx + tw * t, cy, (14 + 4 * kh) * u, col(245, 247, 252, 255 * a));
        String v = sliderText(id);
        r.text(v, cx + cw - textW(v), cy - textH() / 2, col(200, 208, 225, 255 * a), false);
        hit(tx - 10 * u, cy - 16 * u, tw + 20 * u, 32 * u, K_SLIDER, id);
        r.quad(cx, y + rowH - 1, cw, 1, col(255, 255, 255, 14 * a));
        return y + rowH;
    }

    private double toggleRow(GuiRenderer r, double y, int id, String title, String desc, boolean value, double a, double u, double delta) {
        double rowH = 66 * u, cy = y + rowH / 2;
        rowTitle(r, y, title, desc, a, u);
        double tw = 46 * u, th = 26 * u, tx = cx + cw - tw, ty = cy - th / 2;
        boolean over = !closing && inContent(my) && mx >= tx && mx <= tx + tw && my >= ty && my <= ty + th;
        double p = animInit(K_TOGGLE * 1000 + id, value, delta, 0.22);
        double hv = anim(K_TOGGLE * 1000 + 100 + id, over, delta, 0.2);
        round(r, tx, ty, tw, th, th / 2, col(255, 255, 255, (34 + 14 * hv) * a));
        if (p > 0.01) round(r, tx, ty, tw, th, th / 2, col(accent, 255 * p * a));
        double m = 3 * u, d = th - m * 2;
        round(r, tx + m + (tw - m * 2 - d) * p, ty + m, d, d, d / 2, col(245, 247, 252, 255 * a));
        hit(tx, ty, tw, th, K_TOGGLE, id);
        r.quad(cx, y + rowH - 1, cw, 1, col(255, 255, 255, 14 * a));
        return y + rowH;
    }

    private double swatchRow(GuiRenderer r, double y, int kind, String title, String desc, int[][] colors, SettingColor current,
                             double a, double u, double delta) {
        double rowH = 66 * u, cy = y + rowH / 2;
        rowTitle(r, y, title, desc, a, u);
        double d = 26 * u, gap = 12 * u;
        double total = colors.length * d + (colors.length - 1) * gap;
        double x0 = cx + cw - total;
        for (int i = 0; i < colors.length; i++) {
            double sx = x0 + i * (d + gap) + d / 2;
            boolean chosen = colors[i][0] == current.r && colors[i][1] == current.g && colors[i][2] == current.b;
            boolean over = !closing && inContent(my) && Math.abs(mx - sx) <= d / 2 + 3 && Math.abs(my - cy) <= d / 2 + 3;
            double hv = anim(kind * 1000 + i, over, delta, 0.2);
            double ring = animInit(kind * 1000 + 100 + i, chosen, delta, 0.25);
            if (ring > 0.01) circle(r, sx, cy, d + 10 * u, col(255, 255, 255, 230 * ring * a));
            if (ring > 0.01) circle(r, sx, cy, d + 5 * u, col(panelC.r, panelC.g, panelC.b, 255 * a));
            circle(r, sx, cy, d + 2 * u * hv, col(colors[i], 255 * a));
            hit(sx - d / 2 - 3, cy - d / 2 - 3, d + 6, d + 6, kind, i);
        }
        r.quad(cx, y + rowH - 1, cw, 1, col(255, 255, 255, 14 * a));
        return y + rowH;
    }

    // ------------------------------------------------------------------ input

    private void applyPreset(int i) {
        LunarGuiTheme lt = lunar();
        int[] p = PRESET_PANEL[i], c = PRESET_CARD[i];
        lt.panelColor.set(new SettingColor(p[0], p[1], p[2], p[3]));
        lt.cardColor.set(new SettingColor(c[0], c[1], c[2], c[3]));
        lt.themePreset.set(i);
    }

    @Override
    public boolean onMouseClicked(Click click, boolean doubled) {
        if (closing || click.button() != GLFW_MOUSE_BUTTON_LEFT) return false;
        double px = click.x(), py = click.y();
        Hit h = hitAt(px, py);
        if (h == null) return false;
        if (h.kind >= 10 && !inContent(py)) return false;
        LunarGuiTheme lt = lunar();

        switch (h.kind) {
            case K_CLOSE -> mc.currentScreen.close();
            case K_NAV -> {
                int cur = switching ? pendingPage : page;
                if (cur != h.id) {
                    pendingPage = h.id;
                    switching = true;
                }
            }
            case K_ADV -> mc.setScreen(new LunarSettingsScreen(theme, LunarTab.INSTANCE));
            case K_PRESET -> applyPreset(h.id);
            case K_ACCENT -> {
                Object[] p = LunarGuiTheme.PRESETS[h.id];
                lt.applyAccent((Integer) p[1], (Integer) p[2], (Integer) p[3]);
            }
            case K_TOGGLE -> {
                if (h.id == 1) lt.showHub.set(!lt.showHub.get());
                else lt.showIcons.set(!lt.showIcons.get());
            }
            case K_SLIDER -> {
                dragSlider = h.id;
                setSlider(h.id, (px - sliderX[h.id]) / sliderW[h.id]);
            }
            case K_EN -> lt.enabledColor.set(new SettingColor(ENABLED[h.id][0], ENABLED[h.id][1], ENABLED[h.id][2], 255));
            case K_DIS -> lt.disabledColor.set(new SettingColor(DISABLED[h.id][0], DISABLED[h.id][1], DISABLED[h.id][2], 255));
            case K_RESET -> lt.resetLook();
            case K_WIZARD -> LunarSetup.openWizard(screen);
            default -> {
                return false;
            }
        }
        return true;
    }

    @Override
    public void onMouseMoved(double mouseX, double mouseY, double lastMouseX, double lastMouseY) {
        if (dragSlider >= 0) setSlider(dragSlider, (mouseX - sliderX[dragSlider]) / sliderW[dragSlider]);
    }

    @Override
    public boolean onMouseReleased(Click click) {
        dragSlider = -1;
        return false;
    }

    @Override
    public boolean onMouseScrolled(double amount) {
        if (closing || !inContent(my) || mx < x || mx > x + width) return false;
        scrollTarget = MathHelper.clamp(scrollTarget - amount * 70 * unit(), 0, maxScroll);
        return true;
    }
}
