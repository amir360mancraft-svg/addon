package com.blockoutlines.addon.modules;

import com.blockoutlines.addon.BlockOutlinesAddon;
import com.blockoutlines.addon.gui.CrosshairEditorScreen;
import com.blockoutlines.addon.util.PixelGrid;
import com.blockoutlines.addon.util.SvgIcon;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WVerticalList;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.util.Identifier;
import org.lwjgl.BufferUtils;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.util.ArrayList;
import java.util.List;

/** Draws an SVG (black/white shapes) as your crosshair. The default is the pixel crosshair bundled in the jar. */
public class CustomCrosshair extends Module {
    private static final int GRID = 128;
    private static final Identifier WHITE = Identifier.of("blockoutlines", "white");
    /** Grid the current rects were built on: 33 for a drawn crosshair, 128 for the default / uploads. */
    private int rectGrid = GRID;
    private static final String DEFAULT_SVG = "/assets/blockoutlines/crosshair.svg";
    private static final String CUSTOM_SVG = "meteor-client/better-crosshair.svg";

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    public final Setting<SettingColor> color = sgGeneral.add(new ColorSetting.Builder()
        .name("color")
        .description("Color of the light (white) parts of the crosshair.")
        .defaultValue(new SettingColor(255, 255, 255, 255))
        .build()
    );

    public final Setting<SettingColor> darkColor = sgGeneral.add(new ColorSetting.Builder()
        .name("dark-color")
        .description("Color of the dark (black) parts of the crosshair, if the SVG has any.")
        .defaultValue(new SettingColor(0, 0, 0, 255))
        .build()
    );

    public final Setting<Double> alpha = sgGeneral.add(new DoubleSetting.Builder()
        .name("alpha")
        .description("Opacity of the crosshair.")
        .defaultValue(1.0)
        .range(0.0, 1.0)
        .sliderRange(0.0, 1.0)
        .build()
    );

    public final Setting<Integer> size = sgGeneral.add(new IntSetting.Builder()
        .name("size")
        .description("Size of the crosshair in GUI pixels. 32 shows a drawn crosshair 1 pixel = 1 GUI pixel; multiples of 33 stay sharp.")
        .defaultValue(33)
        .range(8, 256)
        .sliderRange(8, 128)
        .build()
    );

    public final Setting<Boolean> hideVanilla = sgGeneral.add(new BoolSetting.Builder()
        .name("hide-vanilla")
        .description("Hides the normal Minecraft crosshair.")
        .defaultValue(true)
        .build()
    );

    public final Setting<Boolean> invert = sgGeneral.add(new BoolSetting.Builder()
        .name("invert-blend")
        .description("Like the normal crosshair: the light parts change color depending on what is behind them.")
        .defaultValue(true)
        .build()
    );

    /** Each entry: x, y, width, height (in GRID cells), kind (1 light, 2 dark). */
    private final List<int[]> rects = new ArrayList<>();

    public CustomCrosshair() {
        super(BlockOutlinesAddon.CATEGORY, "custom-crosshair", "Replaces the crosshair with an SVG of your choice.");
        buildRects(SvgIcon.load(DEFAULT_SVG), GRID);
    }

    @Override
    public void onActivate() {
        SvgIcon loaded = null;
        String text = null;
        try {
            java.io.File f = new java.io.File(mc.runDirectory, CUSTOM_SVG);
            if (f.isFile()) {
                text = new String(java.nio.file.Files.readAllBytes(f.toPath()), java.nio.charset.StandardCharsets.UTF_8);
                loaded = SvgIcon.parse(text);
            }
        } catch (Exception ignored) {
        }
        if (loaded != null && !loaded.layers.isEmpty()) buildRects(loaded, gridFor(text));
        else buildRects(SvgIcon.load(DEFAULT_SVG), GRID);
    }

    @Override
    public WWidget getWidget(GuiTheme theme) {
        WVerticalList list = theme.verticalList();
        WButton draw = list.add(theme.button("Draw crosshair (33 x 33)")).expandX().widget();
        WButton upload = list.add(theme.button("Upload crosshair (.svg)")).expandX().widget();
        WButton reset = list.add(theme.button("Reset crosshair")).expandX().widget();
        draw.action = this::openEditor;
        upload.action = this::uploadSvg;
        reset.action = this::resetSvg;
        return list;
    }

    /** Opens the pixel editor. It starts from your saved crosshair, or from an empty grid. */
    private void openEditor() {
        PixelGrid initial = new PixelGrid();
        try {
            java.io.File f = new java.io.File(mc.runDirectory, CUSTOM_SVG);
            if (f.isFile()) initial = PixelGrid.fromSvg(new String(java.nio.file.Files.readAllBytes(f.toPath()), java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
        mc.setScreen(new CrosshairEditorScreen(mc.currentScreen, initial, this::saveDrawing));
    }

    private void saveDrawing(PixelGrid grid) {
        if (grid.isEmpty()) {
            resetSvg(); // nothing drawn: go back to the built-in crosshair instead of showing nothing
            return;
        }
        try {
            String svg = grid.toSvg();
            java.io.File target = new java.io.File(mc.runDirectory, CUSTOM_SVG);
            target.getParentFile().mkdirs();
            java.nio.file.Files.write(target.toPath(), svg.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            buildRects(SvgIcon.parse(svg), gridFor(svg));
            info("Crosshair saved.");
        } catch (Exception e) {
            error("Could not save the crosshair: " + e.getMessage());
        }
    }

    private void uploadSvg() {
        try {
            PointerBuffer filters = BufferUtils.createPointerBuffer(1);
            filters.put(MemoryUtil.memASCII("*.svg"));
            filters.rewind();
            String path = TinyFileDialogs.tinyfd_openFileDialog("Select SVG crosshair", null, filters, "SVG files", false);
            if (path == null) return;

            String text = new String(java.nio.file.Files.readAllBytes(java.nio.file.Path.of(path)), java.nio.charset.StandardCharsets.UTF_8);
            SvgIcon loaded = SvgIcon.parse(text);
            if (loaded.layers.isEmpty()) {
                error("That SVG has no shapes I can draw (polygon, rect or path).");
                return;
            }

            java.io.File target = new java.io.File(mc.runDirectory, CUSTOM_SVG);
            target.getParentFile().mkdirs();
            java.nio.file.Files.write(target.toPath(), text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            buildRects(loaded, gridFor(text));
            info("Crosshair updated.");
        } catch (Exception e) {
            error("Could not load that SVG: " + e.getMessage());
        }
    }

    private void resetSvg() {
        try {
            java.nio.file.Files.deleteIfExists(new java.io.File(mc.runDirectory, CUSTOM_SVG).toPath());
        } catch (Exception ignored) {
        }
        buildRects(SvgIcon.load(DEFAULT_SVG), GRID);
        info("Crosshair reset.");
    }

    /** A drawing made in the editor has viewBox 33 and is rendered on its own 33 x 33 grid, so every pixel stays exact. */
    private static int gridFor(String svg) {
        return svg != null && svg.contains("viewBox=\"0 0 " + PixelGrid.SIZE + " " + PixelGrid.SIZE + "\"") ? PixelGrid.SIZE : GRID;
    }

    /** Turns the SVG into a short list of filled rectangles (rows merged, then equal rows merged). */
    private void buildRects(SvgIcon icon, int G) {
        byte[] grid = icon.raster(G);
        List<int[]> out = new ArrayList<>();
        List<int[]> open = new ArrayList<>();
        for (int y = 0; y <= G; y++) {
            List<int[]> row = new ArrayList<>();
            if (y < G) {
                int x = 0;
                while (x < G) {
                    byte k = grid[y * G + x];
                    if (k == 0) { x++; continue; }
                    int s = x;
                    while (x < G && grid[y * G + x] == k) x++;
                    row.add(new int[]{s, y, x - s, 1, k});
                }
            }
            List<int[]> nextOpen = new ArrayList<>();
            for (int[] r : row) {
                int[] match = null;
                for (int[] o : open) {
                    if (o[0] == r[0] && o[2] == r[2] && o[4] == r[4] && o[1] + o[3] == y) { match = o; break; }
                }
                if (match != null) { match[3]++; open.remove(match); nextOpen.add(match); }
                else nextOpen.add(r);
            }
            out.addAll(open); // rectangles that did not continue into this row
            open = nextOpen;
        }
        out.addAll(open);
        synchronized (rects) {
            rectGrid = G;
            rects.clear();
            rects.addAll(out);
        }
    }

    @EventHandler
    private void onRender2D(Render2DEvent event) {
        if (mc.currentScreen != null || mc.options.hudHidden) return;
        if (!mc.options.getPerspective().isFirstPerson()) return; // hidden in third person (F5), like vanilla

        int sz = size.get();
        // Meteor passes the scaled width as the height too, so read both from the window.
        int ox = (mc.getWindow().getScaledWidth() - sz) / 2;
        int oy = (mc.getWindow().getScaledHeight() - sz) / 2;
        int a = (int) Math.max(0, Math.min(255, Math.round(alpha.get() * 255.0)));

        SettingColor light = color.get(), dark = darkColor.get();
        int darkArgb = argb(a * dark.a / 255, dark.r, dark.g, dark.b);
        boolean inv = invert.get();
        int lightArgb;
        if (inv) { // the invert blend ignores alpha, so fade the tint instead
            float f = a / 255f * light.a / 255f;
            lightArgb = argb(255, Math.round(light.r * f), Math.round(light.g * f), Math.round(light.b * f));
        } else {
            lightArgb = argb(a * light.a / 255, light.r, light.g, light.b);
        }

        synchronized (rects) {
            double scale = sz / (double) rectGrid;
            for (int pass = 2; pass >= 1; pass--) { // dark first, light on top
                for (int[] r : rects) {
                    if (r[4] != pass) continue;
                    int x1 = ox + (int) Math.round(r[0] * scale), x2 = ox + (int) Math.round((r[0] + r[2]) * scale);
                    int y1 = oy + (int) Math.round(r[1] * scale), y2 = oy + (int) Math.round((r[1] + r[3]) * scale);
                    if (x2 <= x1) x2 = x1 + 1;
                    if (y2 <= y1) y2 = y1 + 1;
                    if (pass == 1 && inv) event.drawContext.drawGuiTexture(RenderPipelines.CROSSHAIR, WHITE, x1, y1, x2 - x1, y2 - y1, lightArgb);
                    else event.drawContext.fill(x1, y1, x2, y2, pass == 1 ? lightArgb : darkArgb);
                }
            }
        }
    }

    private static int argb(int a, int r, int g, int b) {
        return (a << 24) | (r << 16) | (g << 8) | b;
    }
}
