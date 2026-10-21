package com.blockoutlines.addon.gui;

import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WSection;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;

/** A collapsible block: small arrow and title on top, the content in a soft rounded box below. */
public class LunarSection extends WSection {
    public LunarSection(String title, boolean expanded, WWidget headerWidget) {
        super(title, expanded, headerWidget);
    }

    @Override
    protected WHeader createHeader() {
        return new Header(title);
    }

    private double headerHeight() {
        return cells.isEmpty() ? 0 : cells.get(0).widget().height;
    }

    @Override
    protected void onRender(GuiRenderer r, double mouseX, double mouseY, double delta) {
        if (!expanded && animProgress <= 0) return;
        double u = theme.scale(1);
        double hh = headerHeight();
        double rad = (theme instanceof LunarGuiTheme t ? t.cornerRadius.get() : 10.0) * 0.8 * u;
        double by = y + hh, bh = height - hh;
        if (bh <= 2) return;
        LunarDraw.round(r, x, by, width, bh, rad, LunarDraw.col(255, 255, 255, 20));
        LunarDraw.round(r, x + 1, by + 1, width - 2, bh - 2, Math.max(1, rad - 1), LunarDraw.col(255, 255, 255, 9));
    }

    private class Header extends WHeader {
        Header(String title) {
            super(title);
        }

        @Override
        public void init() {
            add(theme.label(title)).expandCellX().padLeft(22).padVertical(4);
            if (headerWidget != null) add(headerWidget);
        }

        @Override
        protected void onRender(GuiRenderer r, double mouseX, double mouseY, double delta) {
            double u = theme.scale(1);
            SettingColor ac = theme instanceof LunarGuiTheme t ? t.accentColor.get() : null;
            int cr = ac == null ? 255 : ac.r, cg = ac == null ? 150 : ac.g, cb = ac == null ? 60 : ac.b;

            // arrow: points down when open, to the right when closed
            double cx = x + 10 * u, cy = y + height / 2, s = 4.5 * u;
            double ang = -(1 - animProgress) * Math.PI / 2;
            double[] px = {-s, s, 0}, py = {-s * 0.55, -s * 0.55, s * 0.65};
            double[] ox = new double[3], oy = new double[3];
            for (int i = 0; i < 3; i++) {
                ox[i] = cx + px[i] * Math.cos(ang) - py[i] * Math.sin(ang);
                oy[i] = cy + px[i] * Math.sin(ang) + py[i] * Math.cos(ang);
            }
            LunarDraw.tri(r, ox[0], oy[0], ox[1], oy[1], ox[2], oy[2], LunarDraw.col(cr, cg, cb, 255));
        }
    }
}
