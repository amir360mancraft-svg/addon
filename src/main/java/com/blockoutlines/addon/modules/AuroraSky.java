package com.blockoutlines.addon.modules;

import com.blockoutlines.addon.BlockOutlinesAddon;
import com.blockoutlines.addon.util.Shapes;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.renderer.Renderer3D;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;

/**
 * Northern lights: glowing curtains of light that wave across the night sky. Built from soft ribbons around you, so
 * they sit in the sky behind the mountains. Only on your screen.
 */
public class AuroraSky extends Module {
    public enum Palette { Green, TealPurple, Pink, Rainbow, Custom }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgLook = settings.createGroup("Look");

    private final Setting<Boolean> onlyNight = sgGeneral.add(new BoolSetting.Builder()
        .name("only-at-night").description("Fade in at dusk and out at dawn. Off = also by day (very faint).").defaultValue(true).build());
    private final Setting<Integer> bands = sgGeneral.add(new IntSetting.Builder()
        .name("curtains").description("How many curtains.").defaultValue(3).range(1, 6).sliderRange(1, 5).build());
    private final Setting<Double> brightness = sgGeneral.add(new DoubleSetting.Builder()
        .name("brightness").description("How bright they are.").defaultValue(1.0).range(0.2, 2.0).sliderRange(0.3, 1.6).build());
    private final Setting<Double> speed = sgGeneral.add(new DoubleSetting.Builder()
        .name("speed").description("How fast they wave.").defaultValue(1.0).range(0.2, 3.0).sliderRange(0.3, 2.5).build());

    private final Setting<Palette> palette = sgLook.add(new EnumSetting.Builder<Palette>()
        .name("colors").description("Color scheme of the lights.").defaultValue(Palette.Green).build());
    private final Setting<SettingColor> custom = sgLook.add(new ColorSetting.Builder()
        .name("custom-color").description("Your own color.").defaultValue(new SettingColor(90, 255, 170, 255)).visible(() -> palette.get() == Palette.Custom).build());
    private final Setting<Double> height = sgLook.add(new DoubleSetting.Builder()
        .name("height").description("How tall the curtains are.").defaultValue(1.0).range(0.4, 2.0).sliderRange(0.5, 1.6).build());
    private final Setting<Double> elevation = sgLook.add(new DoubleSetting.Builder()
        .name("elevation").description("How high above the horizon they hang (degrees).").defaultValue(24.0).range(8.0, 55.0).sliderRange(10.0, 45.0).build());
    private final Setting<Integer> span = sgLook.add(new IntSetting.Builder()
        .name("width").description("How much of the sky a curtain covers (degrees).").defaultValue(150).range(40, 300).sliderRange(60, 240).build());

    private static final int COLS = 56;
    private static final int ROWS = 6;

    public AuroraSky() {
        super(BlockOutlinesAddon.EFFECTS, "aurora-sky", "Northern lights: waving curtains of light in the night sky.");
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (mc.world == null || mc.player == null) return;
        if (!mc.world.getDimension().hasSkyLight()) return;
        double time = mc.world.getTimeOfDay() % 24000L;
        // 1 at deep night, 0 by day: dusk starts around 12000, dawn ends around 23500
        double night = clamp((time - 12000) / 1500.0) * (1.0 - clamp((time - 22000) / 1600.0));
        if (time < 12000) night = 0;
        double dayMin = onlyNight.get() ? 0.0 : 0.25;
        double vis = Math.max(night, dayMin) * (1.0 - 0.8 * mc.world.getRainGradient(event.tickDelta));
        if (vis < 0.02) return;

        double secs = System.currentTimeMillis() / 1000.0 * speed.get();
        double cx = event.offsetX, cy = event.offsetY, cz = event.offsetZ;
        Renderer3D r = event.depthRenderer;
        double R = 150.0;

        for (int b = 0; b < bands.get(); b++) {
            double base = b * 2.3999 + 0.7;                 // each curtain points in another direction (golden angle)
            double half = Math.toRadians(span.get()) / 2.0;
            double elev = Math.toRadians(elevation.get() + b * 5.5 + Math.sin(secs * 0.05 + b) * 3.0);
            double radius = R + b * 12.0;
            double tall = 55.0 * height.get() * (0.8 + 0.4 * Math.sin(b * 1.7 + 0.3));
            for (int i = 0; i < COLS; i++) {
                double u0 = i / (double) COLS, u1 = (i + 1) / (double) COLS;
                double a0 = base - half + u0 * half * 2, a1 = base - half + u1 * half * 2;
                double e0 = edge(u0), e1 = edge(u1);
                if (e0 < 0.01 && e1 < 0.01) continue;
                double[] p0 = column(cx, cy, cz, a0, elev, radius, secs, b, u0);
                double[] p1 = column(cx, cy, cz, a1, elev, radius, secs, b, u1);
                double r0 = ray(secs, b, u0), r1 = ray(secs, b, u1);
                for (int row = 0; row < ROWS; row++) {
                    double v0 = row / (double) ROWS, v1 = (row + 1) / (double) ROWS;
                    double y00 = p0[1] + v0 * tall * (0.6 + 0.4 * r0), y01 = p0[1] + v1 * tall * (0.6 + 0.4 * r0);
                    double y10 = p1[1] + v0 * tall * (0.6 + 0.4 * r1), y11 = p1[1] + v1 * tall * (0.6 + 0.4 * r1);
                    Color c00 = col(b, v0, u0, vis * e0 * (0.35 + 0.65 * r0));
                    Color c10 = col(b, v0, u1, vis * e1 * (0.35 + 0.65 * r1));
                    Color c11 = col(b, v1, u1, vis * e1 * (0.35 + 0.65 * r1));
                    Color c01 = col(b, v1, u0, vis * e0 * (0.35 + 0.65 * r0));
                    Shapes.quad(r, p0[0], y00, p0[2], p1[0], y10, p1[2], p1[0], y11, p1[2], p0[0], y01, p0[2], c00, c10, c11, c01);
                }
            }
        }
    }

    /** Position of the foot of a curtain at the given angle, waving slowly. */
    private double[] column(double cx, double cy, double cz, double ang, double elev, double radius, double t, int band, double u) {
        double wave = Math.sin(ang * 5.0 + t * 0.35 + band * 2.0) * 9.0 + Math.sin(ang * 11.0 - t * 0.6) * 3.0;
        double rad = radius + wave;
        double y = cy + Math.tan(elev) * rad + Math.sin(ang * 3.0 + t * 0.2 + band) * 6.0;
        return new double[] {cx + Math.cos(ang) * rad, y, cz + Math.sin(ang) * rad};
    }

    /** Vertical stripes that move: the curtain is brighter in some rays than in others. */
    private double ray(double t, int band, double u) {
        double a = Math.sin(u * 40.0 + t * 0.9 + band * 3.0) * 0.5 + 0.5;
        double c = Math.sin(u * 17.0 - t * 0.5 + band) * 0.5 + 0.5;
        return clamp(0.25 + 0.5 * a + 0.35 * c * c);
    }

    private static double edge(double u) {
        return Math.pow(Math.sin(u * Math.PI), 0.8);
    }

    private Color col(int band, double v, double u, double strength) {
        // brightest at the bottom edge, fading to nothing at the top
        double fall = Math.pow(1.0 - v, 1.4) * (v < 0.08 ? 0.5 + v * 6.0 : 1.0);
        int a = (int) (Math.min(1.0, strength * fall * brightness.get()) * 150);
        double[] rgb = switch (palette.get()) {
            case Green -> mix(new double[] {70, 255, 140}, new double[] {150, 90, 255}, v * v);
            case TealPurple -> mix(new double[] {40, 230, 220}, new double[] {200, 70, 255}, v);
            case Pink -> mix(new double[] {255, 120, 190}, new double[] {120, 100, 255}, v);
            case Rainbow -> hue((u * 0.6 + band * 0.2 + v * 0.3) % 1.0);
            case Custom -> {
                SettingColor c = custom.get();
                yield mix(new double[] {c.r, c.g, c.b}, new double[] {Math.min(255, c.r + 70), Math.min(255, c.g * 0.6 + 40), Math.min(255, c.b + 90)}, v);
            }
        };
        return new Color((int) rgb[0], (int) rgb[1], (int) rgb[2], Math.max(0, a));
    }

    private static double[] mix(double[] a, double[] b, double t) {
        return new double[] {a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t};
    }

    private static double[] hue(double h) {
        double r = Math.abs(h * 6 - 3) - 1, g = 2 - Math.abs(h * 6 - 2), b = 2 - Math.abs(h * 6 - 4);
        return new double[] {255 * clamp(r), 255 * clamp(g), 255 * clamp(b)};
    }

    private static double clamp(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}
