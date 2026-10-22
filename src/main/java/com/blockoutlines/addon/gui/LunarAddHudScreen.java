package com.blockoutlines.addon.gui;

import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.WindowScreen;
import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.gui.widgets.containers.WSection;
import meteordevelopment.meteorclient.gui.widgets.input.WTextBox;
import meteordevelopment.meteorclient.systems.hud.Hud;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudGroup;
import meteordevelopment.meteorclient.systems.hud.screens.HudEditorScreen;
import meteordevelopment.meteorclient.systems.hud.screens.HudElementPresetsScreen;
import meteordevelopment.meteorclient.utils.Utils;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static meteordevelopment.meteorclient.MeteorClient.mc;
import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT;

/** The "add a HUD element" window (right click on empty space in the HUD editor): every element is a card with an icon, a name and what it does. */
public class LunarAddHudScreen extends WindowScreen {
    private final int x, y;
    private final WTextBox search;
    private Runnable first;

    public LunarAddHudScreen(GuiTheme theme, int x, int y) {
        super(theme, "Add HUD element");
        this.x = x;
        this.y = y;

        search = theme.textBox("");
        search.action = () -> {
            clear();
            initWidgets();
        };
        enterAction = () -> {
            if (first != null) first.run();
        };
    }

    @Override
    public void initWidgets() {
        first = null;

        add(search).expandX();
        search.setFocused(true);

        Map<String, List<Row>> grouped = new TreeMap<>(Comparator.comparing(String::toLowerCase));
        Hud hud = Hud.get();
        String q = search.get();

        for (HudElementInfo<?> info : hud.infos.values()) {
            String group = ((HudGroup) info.group).title();
            if (info.hasPresets() && !q.isEmpty()) {
                for (HudElementInfo<?>.Preset preset : info.presets) {
                    String title = info.title + "  -  " + preset.title;
                    if (Utils.searchTextDefault(title, q, false)) {
                        grouped.computeIfAbsent(group, k -> new ArrayList<>()).add(new Row(title, info.description, info.name, "ADD", () -> use(preset)));
                    }
                }
            } else if (Utils.searchTextDefault(info.title, q, false)) {
                String pill = info.hasPresets() ? "PRESETS" : "ADD";
                grouped.computeIfAbsent(group, k -> new ArrayList<>()).add(new Row(info.title, info.description, info.name, pill, () -> use(info)));
            }
        }

        for (Map.Entry<String, List<Row>> e : grouped.entrySet()) {
            WSection section = add(theme.section(e.getKey(), true)).expandX().widget();
            for (Row row : e.getValue()) {
                section.add(row).expandX();
                if (first == null) first = row.action;
            }
        }
    }

    private void use(HudElementInfo<?>.Preset preset) {
        Hud.get().add(preset, x, y);
        Hud.get().active = true;
        close();
    }

    private void use(HudElementInfo<?> info) {
        if (info.hasPresets()) {
            HudElementPresetsScreen screen = new HudElementPresetsScreen(theme, info, x, y);
            screen.parent = parent;
            mc.setScreen(screen);
        } else {
            Hud.get().add(info, x, y);
            Hud.get().active = true;
            close();
        }
    }

    @Override
    protected void onRenderBefore(DrawContext drawContext, float delta) {
        HudEditorScreen.renderElements(drawContext);
    }

    /** One element: icon tile, name, short description and a pill on the right. */
    private static class Row extends LunarCanvas {
        final String title, description, glyph, pill;
        final Runnable action;

        Row(String title, String description, String glyph, String pill, Runnable action) {
            this.title = title;
            this.description = description == null ? "" : description;
            this.glyph = glyph;
            this.pill = pill;
            this.action = action;
        }

        @Override
        protected void onCalculateSize() {
            double u = unit();
            width = 380 * u;
            height = 44 * u;
        }

        @Override
        protected void onRender(GuiRenderer r, double mouseX, double mouseY, double delta) {
            mx = mouseX;
            my = mouseY;
            loadTheme();
            double u = unit();
            boolean over = mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
            double hv = anim(1, over, Math.min(delta, 0.1), 0.18);
            double rr = rad(0.8, u);

            round(r, x, y, width, height, rr, col(cardC.r, cardC.g, cardC.b, Math.max(120, cardC.a)));
            if (hv > 0.01) round(r, x, y, width, height, rr, col(accent, 40 * hv));

            double tile = height - 12 * u, tx = x + 6 * u, ty = y + 6 * u;
            round(r, tx, ty, tile, tile, rad(0.6, u), col(accent, 60 + 40 * hv));
            LunarGlyph.draw(r, glyph, tx + tile / 2, ty + tile / 2, tile * 0.5, col(255, 255, 255, 255), col(accent, 0));

            double pw = textW(pill) + 24 * u, ph = 26 * u;
            double px = x + width - pw - 10 * u, py = y + (height - ph) / 2;
            round(r, px, py, pw, ph, ph / 2, col(accent, 150 + 105 * hv));
            r.text(pill, px + (pw - textW(pill)) / 2, py + (ph - textH()) / 2, col(255, 255, 255, 255), false);

            double textX = tx + tile + 10 * u, maxW = px - textX - 8 * u;
            r.text(fit(title, maxW), textX, y + 7 * u, col(255, 255, 255, 255), false);
            r.text(fit(description, maxW), textX, y + 7 * u + textH() + 3 * u, col(160, 170, 190, 230), false);
        }

        @Override
        public boolean onMouseClicked(Click click, boolean doubled) {
            if (click.button() != GLFW_MOUSE_BUTTON_LEFT) return false;
            if (click.x() < x || click.x() > x + width || click.y() < y || click.y() > y + height) return false;
            action.run();
            return true;
        }
    }
}
