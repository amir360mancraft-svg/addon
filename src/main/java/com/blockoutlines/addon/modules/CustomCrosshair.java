package com.blockoutlines.addon.modules;

import com.blockoutlines.addon.BlockOutlinesAddon;
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
import org.lwjgl.BufferUtils;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.util.ArrayList;
import java.util.List;

/** Draws an SVG (black/white shapes) as your crosshair. The default is the pixel crosshair bundled in the jar. */
public class CustomCrosshair extends Module {
    private static final int GRID = 128;
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
        .description("Size of the crosshair in GUI pixels.")
        .defaultValue(30)
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

    /** Each entry: x, y, width, height (in GRID cells), kind (1 light, 2 dark). */
    private final List<int[]> rects = new ArrayList<>();

    public CustomCrosshair() {
        super(BlockOutlinesAddon.CATEGORY, "custom-crosshair", "Replaces the crosshair with an SVG of your choice.");
        buildRects(SvgIcon.load(DEFAULT_SVG));
    }

    @Override
    public void onActivate() {
        SvgIcon loaded = null;
        try {
            java.io.File f = new java.io.File(mc.runDirectory, CUSTOM_SVG);
            if (f.isFile()) loaded = SvgIcon.parse(new String(java.nio.file.Files.readAllBytes(f.toPath()), java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
        buildRects(loaded != null && !loaded.layers.isEmpty() ? loaded : SvgIcon.load(DEFAULT_SVG));
    }

    @Override
    public WWidget getWidget(GuiTheme theme) {
        WVerticalList list = theme.verticalList();
        WButton upload = list.add(theme.button("Upload crosshair (.svg)")).expandX().widget();
        WButton reset = list.add(theme.button("Reset crosshair")).expandX().widget();
        upload.action = this::uploadSvg;
        reset.action = this::resetSvg;
        return list;
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
            buildRects(loaded);
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
        buildRects(SvgIcon.load(DEFAULT_SVG));
        info("Crosshair reset.");
    }

    /** Turns the SVG into a short list of filled rectangles (rows merged, then equal rows merged). */
    private void buildRects(SvgIcon icon) {
        byte[] grid = icon.raster(GRID);
        List<int[]> out = new ArrayList<>();
        List<int[]> open = new ArrayList<>();
        for (int y = 0; y <= GRID; y++) {
            List<int[]> row = new ArrayList<>();
            if (y < GRID) {
                int x = 0;
                while (x < GRID) {
                    byte k = grid[y * GRID + x];
                    if (k == 0) { x++; continue; }
                    int s = x;
                    while (x < GRID && grid[y * GRID + x] == k) x++;
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
            rects.clear();
            rects.addAll(out);
        }
    }

    @EventHandler
    private void onRender2D(Render2DEvent event) {
        if (mc.currentScreen != null || mc.options.hudHidden) return;

        int sz = size.get();
        // Meteor passes the scaled width as the height too, so read both from the window.
        int ox = mc.getWindow().getScaledWidth() / 2 - sz / 2;
        int oy = mc.getWindow().getScaledHeight() / 2 - sz / 2;
        double scale = sz / (double) GRID;
        int a = (int) Math.max(0, Math.min(255, Math.round(alpha.get() * 255.0)));

        SettingColor light = color.get(), dark = darkColor.get();
        int lightArgb = argb(a * light.a / 255, light.r, light.g, light.b);
        int darkArgb = argb(a * dark.a / 255, dark.r, dark.g, dark.b);

        synchronized (rects) {
            for (int[] r : rects) {
                int x1 = ox + (int) Math.round(r[0] * scale), x2 = ox + (int) Math.round((r[0] + r[2]) * scale);
                int y1 = oy + (int) Math.round(r[1] * scale), y2 = oy + (int) Math.round((r[1] + r[3]) * scale);
                if (x2 <= x1) x2 = x1 + 1;
                if (y2 <= y1) y2 = y1 + 1;
                event.drawContext.fill(x1, y1, x2, y2, r[4] == 1 ? lightArgb : darkArgb);
            }
        }
    }

    private static int argb(int a, int r, int g, int b) {
        return (a << 24) | (r << 16) | (g << 8) | b;
    }
}
