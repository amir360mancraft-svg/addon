package com.blockoutlines.addon.gui;

import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.gui.tabs.Tab;
import meteordevelopment.meteorclient.gui.tabs.Tabs;
import meteordevelopment.meteorclient.gui.tabs.builtin.GuiTab;
import meteordevelopment.meteorclient.gui.tabs.builtin.HudTab;
import meteordevelopment.meteorclient.gui.tabs.builtin.ModulesTab;
import net.minecraft.client.gui.Click;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT;

/** The middle of the hub: the logo, the two big buttons (modules / HUDs) and a row of round icon shortcuts to the other tabs. */
public class WLunarHub extends LunarCanvas {
    private static final int K_MODS = 1, K_HUDS = 2, K_TAB = 3;

    private final List<Tab> shortcuts = new ArrayList<>();
    private double intro;

    public WLunarHub(LunarHubScreen screen) {
    }

    @Override
    public void init() {
        shortcuts.clear();
        for (Tab t : Tabs.get()) {
            if (t instanceof ModulesTab || t instanceof HudTab || t instanceof GuiTab) continue;
            shortcuts.add(t);
        }
    }

    @Override
    protected void onCalculateSize() {
        double u = unit();
        width = 520 * u;
        height = 250 * u;
    }

    @Override
    protected void onRender(GuiRenderer r, double mouseX, double mouseY, double delta) {
        mx = mouseX;
        my = mouseY;
        hits.clear();
        loadTheme();
        double u = unit();
        delta = Math.min(delta, 0.1);
        intro = Math.min(1, intro + delta * animSpeed() / 0.7);
        double a = ease(intro);
        double rise = (1 - a) * 16 * u;
        double cx = x + width / 2, top = y + rise;

        // logo tile and name
        double tile = 46 * u;
        round(r, cx - tile / 2 - 1, top - 1, tile + 2, tile + 2, rad(1.0, u) + 1, col(255, 255, 255, 40 * a));
        round(r, cx - tile / 2, top, tile, tile, rad(1.0, u), col(accent, 255 * a));
        LunarDraw.star(r, cx, top + tile / 2, 4, tile * 0.32, tile * 0.11, 0, col(255, 255, 255, 255 * a));
        String name = "BETTER RENDER";
        r.text(name, cx - titleW(name) / 2, top + tile + 10 * u, col(255, 255, 255, 255 * a), true);

        // the two big buttons
        double by = top + tile + 10 * u + titleH() + 22 * u;
        double bh = 46 * u, gap = 14 * u;
        double w1 = 210 * u, w2 = 150 * u;
        double bx = cx - (w1 + gap + w2) / 2;
        bigButton(r, bx, by, w1, bh, "EDIT MODS", "mods", K_MODS, 0, u, delta, a);
        bigButton(r, bx + w1 + gap, by, w2, bh, "HUDS", "huds", K_HUDS, 0, u, delta, a);

        // icon shortcuts, one round square each. The name shows under the one you point at.
        double sz = 40 * u, sgap = 10 * u, sy = by + bh + 14 * u;
        int n = shortcuts.size();
        double sx = cx - (n * sz + (n - 1) * sgap) / 2;
        String hovered = null;
        double hoveredX = 0;
        for (int i = 0; i < n; i++) {
            double px = sx + i * (sz + sgap);
            boolean over = mx >= px && mx <= px + sz && my >= sy && my <= sy + sz;
            double hv = anim(K_TAB * 1000 + i, over, delta, 0.2);
            double rr = rad(0.8, u);
            round(r, px - 1, sy - 1, sz + 2, sz + 2, rr + 1, col(255, 255, 255, (24 + 44 * hv) * a));
            round(r, px, sy, sz, sz, rr, col(panelC.r, panelC.g, panelC.b, Math.max(215, panelC.a) * a));
            if (hv > 0.01) round(r, px, sy, sz, sz, rr, col(accent, 70 * hv * a));
            LunarGlyph.draw(r, shortcuts.get(i).name, px + sz / 2, sy + sz / 2, sz * 0.46,
                col(255, 255, 255, (200 + 55 * hv) * a), col(panelC.r, panelC.g, panelC.b, 255));
            hit(px, sy, sz, sz, K_TAB, i);
            if (over) {
                hovered = shortcuts.get(i).name.toUpperCase(Locale.ROOT);
                hoveredX = px + sz / 2;
            }
        }
        double ty = sy + sz + 12 * u;
        String line = hovered != null ? hovered : "Drag HUD elements to move them  |  right click an element for its settings";
        r.text(line, (hovered != null ? hoveredX : cx) - textW(line) / 2, ty,
            col(hovered != null ? 255 : 190, hovered != null ? 255 : 198, hovered != null ? 255 : 215, (hovered != null ? 255 : 190) * a), false);
    }

    private void bigButton(GuiRenderer r, double x, double y, double w, double h, String text, String glyph, int kind, int id,
                           double u, double delta, double a) {
        boolean over = mx >= x && mx <= x + w && my >= y && my <= y + h;
        double hv = anim(kind * 1000 + id, over, delta, 0.2);
        double rr = rad(0.9, u);
        round(r, x - 1, y - 1, w + 2, h + 2, rr + 1, col(255, 255, 255, (26 + 40 * hv) * a));
        round(r, x, y, w, h, rr, col(panelC.r, panelC.g, panelC.b, Math.max(215, panelC.a) * a));
        if (hv > 0.01) round(r, x, y, w, h, rr, col(accent, 60 * hv * a));
        double is = h * 0.42, g = 10 * u;
        double total = is + g + textW(text), left = x + (w - total) / 2;
        LunarGlyph.draw(r, glyph, left + is / 2, y + h / 2, is, col(accent, 255 * a), col(panelC.r, panelC.g, panelC.b, 255));
        r.text(text, left + is + g, y + (h - textH()) / 2, col(255, 255, 255, 255 * a), false);
        hit(x, y, w, h, kind, id);
    }

    @Override
    public boolean onMouseClicked(Click click, boolean doubled) {
        if (click.button() != GLFW_MOUSE_BUTTON_LEFT) return false;
        Hit h = hitAt(click.x(), click.y());
        if (h == null) return false;
        LunarGuiTheme lt = lunar();
        switch (h.kind) {
            case K_MODS -> {
                lt.browserRequested = true;
                Tabs.get().getFirst().openScreen(theme);
            }
            case K_HUDS -> {
                for (Tab t : Tabs.get()) {
                    if (t instanceof HudTab) {
                        t.openScreen(theme);
                        break;
                    }
                }
            }
            case K_TAB -> {
                if (h.id >= 0 && h.id < shortcuts.size()) shortcuts.get(h.id).openScreen(theme);
            }
            default -> {
                return false;
            }
        }
        return true;
    }
}
