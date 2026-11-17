package com.blockoutlines.addon.gui;

import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.WindowScreen;
import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.gui.widgets.containers.WSection;
import meteordevelopment.meteorclient.gui.widgets.input.WTextBox;
import meteordevelopment.meteorclient.renderer.Renderer2D;
import meteordevelopment.meteorclient.systems.hud.Hud;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudGroup;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.systems.hud.screens.HudElementPresetsScreen;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static meteordevelopment.meteorclient.MeteorClient.mc;
import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT;

/**
 * The "add a HUD element" window (right click on empty space in the HUD editor).
 * Every element is a tile that really renders the module inside it (the same render() the module uses on your
 * real HUD, just boxed small and clipped), not just an icon and some text. Click a tile to drop that module on
 * the screen where you right-clicked; drag it afterwards and right click it to open its own settings (that part
 * was already real, this file only changes how you pick what to add).
 *
 * Not compiled / tested yet - the live preview pokes at Meteor's internal GUI matrix math (see onRenderBefore()).
 * If a preview looks blank, stretched, or offset once you actually build this, that's the first place to look;
 * every tile has a safe fallback (its old icon) if the live render throws so one broken module can't lock up
 * the whole panel.
 */
public class LunarAddHudScreen extends WindowScreen {
    private static final int COLUMNS = 3;
    private static final double TILE_W = 134, TILE_H = 88, GAP = 8;
    private static final double PREVIEW_SPLIT = 0.62; // top part of the tile is the live preview, the rest is the label strip
    private static final double PREVIEW_ZOOM = 0.46; // how big the module renders inside the preview area

    private final int x, y;
    private final WTextBox search;
    private Runnable first;

    /** Every tile currently shown, across every group - read by onRenderBefore() to draw the live previews. */
    private final List<Tile> liveTiles = new ArrayList<>();

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
        liveTiles.clear();

        add(search).expandX();
        search.setFocused(true);

        Map<String, List<Tile>> grouped = new TreeMap<>(Comparator.comparing(String::toLowerCase));
        Hud hud = Hud.get();
        String q = search.get();

        for (HudElementInfo<?> info : hud.infos.values()) {
            String group = ((HudGroup) info.group).title();

            if (info.hasPresets() && !q.isEmpty()) {
                for (HudElementInfo<?>.Preset preset : info.presets) {
                    String title = info.title + "  -  " + preset.title;
                    if (Utils.searchTextDefault(title, q, false)) {
                        grouped.computeIfAbsent(group, k -> new ArrayList<>()).add(new Tile(info, preset, title, false));
                    }
                }
            } else if (Utils.searchTextDefault(info.title, q, false)) {
                grouped.computeIfAbsent(group, k -> new ArrayList<>()).add(new Tile(info, null, info.title, info.hasPresets()));
            }
        }

        for (Map.Entry<String, List<Tile>> e : grouped.entrySet()) {
            WSection section = add(theme.section(e.getKey(), true)).expandX().widget();
            section.add(new Grid(e.getValue()));

            liveTiles.addAll(e.getValue());
            if (first == null && !e.getValue().isEmpty()) first = e.getValue().get(0)::click;
        }
    }

    /**
     * Draws the real module inside every tile, before the normal widget pass runs (so the tile's own border/label,
     * drawn normally afterwards, land on top). This is the part that depends on Meteor's own GUI internals: the
     * HUD editor's custom GUI draws at "real" (unscaled) pixels, same space a HudElement's x/y live in, while
     * HudRenderer.begin() is written for the normal in-game HUD pass and expects to still have to divide by the
     * GUI scale once. To land a module exactly at a tile's position in that space, the translate fed to it here
     * is pre-multiplied by the GUI scale so HudRenderer's own division cancels it back out - see the GUI scale
     * use just below. If this math is off once this actually runs, previews will look shifted/mis-scaled rather
     * than crash (everything is wrapped so one bad tile just falls back to its icon).
     */
    @Override
    protected void onRenderBefore(DrawContext context, float delta) {
        if (!(theme instanceof LunarGuiTheme lt)) return;

        var cardC = lt.cardColor.get();
        double rr = lt.cornerRadius.get();
        Color backdrop = new Color(cardC.r, cardC.g, cardC.b, Math.max(150, cardC.a));

        // Tile backdrops, batched together in one pass so the live content drawn after them isn't painted over.
        Renderer2D.COLOR.begin();
        for (Tile t : liveTiles) {
            if (t.tw <= 0 || t.th <= 0) continue;
            roundRaw(Renderer2D.COLOR, t.tx, t.ty, t.tw, t.th, Math.min(rr * 0.8, Math.min(t.tw, t.th) / 2.0), backdrop);
        }
        Renderer2D.COLOR.render();

        if (mc.player == null || mc.world == null) return;
        double s = mc.getWindow().getScaleFactor();
        boolean touchedProjection = false;

        for (Tile t : liveTiles) {
            if (t.tw <= 0 || t.th <= 0 || t.broken || t.preview == null) continue;

            double u = t.tw / TILE_W;
            double pad = 6 * u;
            double previewH = t.th * PREVIEW_SPLIT;

            try {
                context.enableScissor((int) t.tx, (int) t.ty, (int) (t.tx + t.tw), (int) (t.ty + previewH));

                HudRenderer.INSTANCE.begin(context);
                touchedProjection = true;
                context.getMatrices().pushMatrix();
                context.getMatrices().translate((float) ((t.tx + pad) * s), (float) ((t.ty + pad) * s));
                context.getMatrices().scale((float) (PREVIEW_ZOOM * s), (float) (PREVIEW_ZOOM * s));
                t.preview.render(HudRenderer.INSTANCE);
                context.getMatrices().popMatrix();
                HudRenderer.INSTANCE.end();

                context.disableScissor();
            } catch (Throwable err) {
                try {
                    context.disableScissor();
                } catch (Throwable ignored) {
                }
                t.broken = true;
            }
        }

        // HudRenderer.begin()/end() switches to the normal (scaled) projection while it draws; the rest of this
        // screen's own widgets (drawn right after this method returns) need the pixel one back, same as
        // LunarHubScreen does after HudEditorScreen.render() for the same reason.
        if (touchedProjection) Utils.unscaledProjection();
    }

    /** Same rounded-rect fill as LunarDraw.round(), just against Renderer2D instead of a GuiRenderer. */
    private static void roundRaw(Renderer2D renderer, double x, double y, double w, double h, double rad, Color c) {
        if (w <= 0 || h <= 0) return;
        rad = Math.min(rad, Math.min(w, h) / 2.0);
        int rows = (int) Math.floor(rad);
        if (rows < 1) {
            renderer.quad(x, y, w, h, c);
            return;
        }
        for (int i = 0; i < rows; i++) {
            double d = rad - i - 0.5;
            double inset = rad - Math.sqrt(Math.max(0, rad * rad - d * d));
            renderer.quad(x + inset, y + i, w - inset * 2, 1, c);
            renderer.quad(x + inset, y + h - i - 1, w - inset * 2, 1, c);
        }
        renderer.quad(x, y + rows, w, h - rows * 2, c);
    }

    private static void outline(GuiRenderer r, double x, double y, double w, double h, Color c) {
        r.quad(x, y, w, 1, c);
        r.quad(x, y + h - 1, w, 1, c);
        r.quad(x, y + 1, 1, h - 2, c);
        r.quad(x + w - 1, y + 1, 1, h - 2, c);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static HudElement buildPreview(HudElementInfo<?> info, HudElementInfo.Preset preset) {
        HudElement el = info.create();
        if (preset != null) preset.callback.accept(el);
        return el;
    }

    /** One module tile: what to show (a cached preview instance) and what to do when it's clicked. */
    private final class Tile {
        final HudElementInfo<?> info;
        final HudElementInfo<?>.Preset preset;
        final String title;
        final String glyph;
        final boolean opensPresets;

        HudElement preview;
        boolean broken;

        double tx, ty, tw, th;

        Tile(HudElementInfo<?> info, HudElementInfo<?>.Preset preset, String title, boolean opensPresets) {
            this.info = info;
            this.preset = preset;
            this.title = title;
            this.glyph = info.name;
            this.opensPresets = opensPresets;

            try {
                preview = buildPreview(info, preset);
            } catch (Throwable err) {
                preview = null;
                broken = true;
            }
        }

        void click() {
            if (preset != null) {
                Hud.get().add(preset, x, y);
                Hud.get().active = true;
                close();
            } else if (opensPresets) {
                HudElementPresetsScreen screen = new HudElementPresetsScreen(theme, info, x, y);
                screen.parent = parent;
                mc.setScreen(screen);
            } else {
                Hud.get().add(info, x, y);
                Hud.get().active = true;
                close();
            }
        }
    }

    /** Lays the tiles of one group out in a wrapping grid and draws their border, hover glow, label and pill. */
    private final class Grid extends LunarCanvas {
        private final List<Tile> tiles;

        Grid(List<Tile> tiles) {
            this.tiles = tiles;
        }

        @Override
        protected void onCalculateSize() {
            double u = unit();
            double tw = TILE_W * u, th = TILE_H * u, gap = GAP * u;
            int cols = Math.max(1, Math.min(COLUMNS, Math.max(1, tiles.size())));
            int rows = (int) Math.ceil(tiles.size() / (double) cols);

            width = cols * tw + (cols - 1) * gap;
            height = rows * th + Math.max(0, rows - 1) * gap;
        }

        @Override
        protected void onRender(GuiRenderer r, double mouseX, double mouseY, double delta) {
            mx = mouseX;
            my = mouseY;
            loadTheme();
            hits.clear();

            double u = unit();
            double tw = TILE_W * u, th = TILE_H * u, gap = GAP * u;
            int cols = Math.max(1, Math.min(COLUMNS, Math.max(1, tiles.size())));
            double rr = rad(0.8, u);

            for (int i = 0; i < tiles.size(); i++) {
                Tile t = tiles.get(i);
                int col = i % cols, row = i / cols;

                t.tx = x + col * (tw + gap);
                t.ty = y + row * (th + gap);
                t.tw = tw;
                t.th = th;

                boolean over = mouseX >= t.tx && mouseX <= t.tx + t.tw && mouseY >= t.ty && mouseY <= t.ty + t.th;
                double hv = anim(i + 1, over, Math.min(delta, 0.1), 0.18);

                double previewH = t.th * PREVIEW_SPLIT;

                // Fallback icon, only actually seen while the live preview isn't available (not built yet / threw).
                if (t.broken || t.preview == null) {
                    double cx = t.tx + t.tw / 2, cy = t.ty + previewH / 2;
                    LunarGlyph.draw(r, t.glyph, cx, cy, Math.min(t.tw, previewH) * 0.42, col(255, 255, 255, 170), col(accent, 0));
                }

                if (hv > 0.01) round(r, t.tx - 2 * u, t.ty - 2 * u, t.tw + 4 * u, previewH + 4 * u, rr, col(accent, 45 * hv));

                // Label strip
                double labelY = t.ty + previewH;
                double labelH = t.th - previewH;
                round(r, t.tx, labelY, t.tw, labelH, rr * 0.6, col(cardC.r, cardC.g, cardC.b, Math.max(170, cardC.a)));
                if (hv > 0.01) round(r, t.tx, labelY, t.tw, labelH, rr * 0.6, col(accent, 45 * hv));

                String shown = fit(t.title, t.tw - 14 * u);
                centered(r, shown, t.tx + t.tw / 2, labelY + (labelH - textH()) / 2, col(255, 255, 255, 255));

                if (t.opensPresets) {
                    double dotd = 6 * u;
                    round(r, t.tx + t.tw - dotd - 5 * u, t.ty + 5 * u, dotd, dotd, dotd / 2, col(accent, 210));
                }

                outline(r, t.tx, t.ty, t.tw, t.th, col(255, 255, 255, 22 + 18 * hv));

                hit(t.tx, t.ty, t.tw, t.th, 0, i);
            }
        }

        @Override
        public boolean onMouseClicked(Click click, boolean doubled) {
            if (click.button() != GLFW_MOUSE_BUTTON_LEFT) return false;

            Hit h = hitAt(click.x(), click.y());
            if (h == null) return false;

            tiles.get(h.id).click();
            return true;
        }
    }
}
