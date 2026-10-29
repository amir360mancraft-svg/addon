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
import net.minecraft.util.math.Vec3d;

/**
 * A ring of dancing light bars around you while a song plays in Spotify. The bars follow a beat you set (BPM), bass in
 * front and treble to the sides, the colors come from the album cover, and every beat sends a wave across the floor.
 * The movement is made up (Spotify does not give us the real sound), but it stays on the beat.
 */
public class ConcertMode extends Module {
    public enum Colors { Album, Rainbow, Custom }

    private final SettingGroup sg = settings.getDefaultGroup();
    private final SettingGroup sgLook = settings.createGroup("Look");

    private final Setting<Integer> bpm = sg.add(new IntSetting.Builder().name("bpm")
        .description("Beats per minute of the song. The bars jump on the beat.").defaultValue(110).range(50, 220).sliderRange(70, 180).build());
    private final Setting<Integer> beatOffset = sg.add(new IntSetting.Builder().name("beat-offset-ms")
        .description("Moves the beat earlier or later.").defaultValue(0).range(-1000, 1000).sliderRange(-400, 400).build());
    private final Setting<Double> sensitivity = sg.add(new DoubleSetting.Builder().name("intensity")
        .defaultValue(1.0).range(0.2, 2.5).sliderRange(0.5, 2).build());
    private final Setting<Boolean> onlyPlaying = sg.add(new BoolSetting.Builder().name("only-when-playing")
        .description("The bars sink and disappear when the music stops.").defaultValue(true).build());
    private final Setting<Boolean> throughWalls = sg.add(new BoolSetting.Builder().name("through-walls")
        .defaultValue(false).build());

    private final Setting<Integer> bars = sgLook.add(new IntSetting.Builder().name("bars").defaultValue(48).range(12, 96).sliderRange(24, 72).build());
    private final Setting<Double> radius = sgLook.add(new DoubleSetting.Builder().name("radius").defaultValue(3.0).range(1.2, 10).sliderRange(2, 6).build());
    private final Setting<Double> maxHeight = sgLook.add(new DoubleSetting.Builder().name("max-height").defaultValue(2.6).range(0.5, 8).sliderRange(1, 5).build());
    private final Setting<Double> thickness = sgLook.add(new DoubleSetting.Builder().name("bar-thickness")
        .description("How much of the space between bars they fill.").defaultValue(0.6).range(0.2, 0.95).build());
    private final Setting<Colors> colors = sgLook.add(new EnumSetting.Builder<Colors>().name("colors").defaultValue(Colors.Album).build());
    private final Setting<SettingColor> custom = sgLook.add(new ColorSetting.Builder().name("custom-color")
        .defaultValue(new SettingColor(120, 170, 255, 255)).visible(() -> colors.get() == Colors.Custom).build());
    private final Setting<Boolean> floorGlow = sgLook.add(new BoolSetting.Builder().name("floor-glow").defaultValue(true).build());
    private final Setting<Boolean> shockwave = sgLook.add(new BoolSetting.Builder().name("beat-wave")
        .description("A ring of light that spreads over the floor on every beat.").defaultValue(true).build());

    private float[] cur = new float[96];
    private double vis;
    private double lastBeat = -1;
    private long waveBorn;
    private long last;

    public ConcertMode() {
        super(BlockOutlinesAddon.EFFECTS, "concert-mode", "A ring of dancing light bars around you while music plays in Spotify.");
    }

    @Override
    public void onActivate() {
        java.util.Arrays.fill(cur, 0f);
        vis = 0;
        lastBeat = -1;
        waveBorn = 0;
        last = System.nanoTime();
    }

    private static double frac(double x) {
        return x - Math.floor(x);
    }

    private int barColor(int i, int n, double t, double h, int album) {
        int rgb;
        switch (colors.get()) {
            case Rainbow -> rgb = java.awt.Color.HSBtoRGB((float) frac(i / (double) n + t * 0.08), 0.65f, 1f);
            case Custom -> {
                SettingColor c = custom.get();
                rgb = (c.r << 16) | (c.g << 8) | c.b;
            }
            default -> rgb = album;
        }
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        // taller bars glow whiter
        double w = Math.min(1, h) * 0.4;
        r = (int) (r + (255 - r) * w);
        g = (int) (g + (255 - g) * w);
        b = (int) (b + (255 - b) * w);
        return (r << 16) | (g << 8) | b;
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (mc.player == null || mc.world == null) return;
        long nanos = System.nanoTime();
        double dt = Math.min(0.1, (nanos - last) / 1e9);
        last = nanos;
        double t = nanos / 1e9;

        BetterSpotifyHud.Now now = BetterSpotifyHud.now();
        boolean playing = now.has() && now.playing();
        double target = (playing || !onlyPlaying.get()) ? 1 : 0;
        vis += (target - vis) * Math.min(1, dt * 4);
        if (vis < 0.02) return;

        int n = bars.get();
        double phase;
        if (playing) phase = (now.positionMs() + beatOffset.get()) / 1000.0 * bpm.get() / 60.0;
        else phase = t * bpm.get() / 60.0;
        double bf = frac(phase);
        double pulse = playing || !onlyPlaying.get() ? Math.exp(-bf * 3.5) : 0;
        double offbeat = Math.exp(-frac(phase * 2 + 0.5) * 5.0);
        if (Math.floor(phase) != lastBeat) {
            lastBeat = Math.floor(phase);
            if (pulse > 0) waveBorn = System.currentTimeMillis();
        }
        double sens = sensitivity.get();

        Vec3d p = mc.player.getLerpedPos(event.tickDelta);
        double R = radius.get(), maxH = maxHeight.get();
        double y0 = p.y;
        Renderer3D r = throughWalls.get() ? event.renderer : event.depthRenderer;
        int album = now.has() ? (now.albumColor() & 0xFFFFFF) : 0x1ED760;
        int alpha = (int) (235 * vis);

        double arc = 2 * Math.PI * R / n;
        double w = arc * thickness.get();

        for (int i = 0; i < n; i++) {
            int j = Math.min(i, n - i);
            double u = j / (n / 2.0);                 // 0 = front/back (bass), 1 = sides (treble)
            double wob = 0.5 + 0.5 * Math.sin(t * (2.1 + j * 0.37) + j * 1.9);
            double wob2 = 0.5 + 0.5 * Math.sin(t * 5.3 + j * 2.7);
            double tg = 0.05 + sens * (pulse * (0.35 + 0.65 * (1 - u)) * (0.55 + 0.45 * wob)
                + offbeat * 0.35 * u * wob2 + 0.13 * wob * (0.5 + 0.5 * (1 - u)));
            if (!playing && onlyPlaying.get()) tg = 0.02;
            tg = Math.max(0, Math.min(1, tg));
            double k = tg > cur[i] ? Math.min(1, dt * 30) : Math.min(1, dt * 7);
            cur[i] += (tg - cur[i]) * k;

            double h = 0.06 + cur[i] * maxH;
            double ang = i * 2 * Math.PI / n;
            double ox = Math.cos(ang), oz = Math.sin(ang);
            double tx = -oz, tz = ox;
            int col = barColor(i, n, t, cur[i], album);
            int cr = (col >> 16) & 0xFF, cg = (col >> 8) & 0xFF, cb = col & 0xFF;
            Shapes.Tf tf = (lx, ly, lz) -> new double[]{p.x + ox * (R + lz) + tx * lx, y0 + ly, p.z + oz * (R + lz) + tz * lx};
            Shapes.box(r, tf, 0, h / 2, 0, w, h, w, 0, cr, cg, cb, alpha);
            // a bright cap on top
            Shapes.box(r, tf, 0, h + 0.03, 0, w * 1.15, 0.06, w * 1.15, 0, 255, 255, 255, (int) (alpha * 0.9));
        }

        if (floorGlow.get()) {
            int col = barColor(0, n, t, 0.3, album);
            ring(r, p.x, y0 + 0.03, p.z, R - 0.35, R + 0.35, new Color((col >> 16) & 0xFF, (col >> 8) & 0xFF, col & 0xFF, (int) ((50 + 70 * pulse) * vis)));
        }
        if (shockwave.get() && waveBorn > 0) {
            double age = (System.currentTimeMillis() - waveBorn) / 1000.0;
            if (age < 0.9) {
                double rr = R + age * 5.0;
                int col = barColor(n / 4, n, t, 0.6, album);
                ring(r, p.x, y0 + 0.04, p.z, rr - 0.08, rr + 0.08, new Color((col >> 16) & 0xFF, (col >> 8) & 0xFF, col & 0xFF, (int) (200 * (1 - age / 0.9) * vis)));
            }
        }
    }

    private static void ring(Renderer3D r, double cx, double y, double cz, double r0, double r1, Color c) {
        if (c.a <= 2) return;
        int seg = 72;
        for (int s = 0; s < seg; s++) {
            double a0 = s * 2 * Math.PI / seg, a1 = (s + 1) * 2 * Math.PI / seg;
            double c0 = Math.cos(a0), s0 = Math.sin(a0), c1 = Math.cos(a1), s1 = Math.sin(a1);
            r.quad(cx + c0 * r0, y, cz + s0 * r0,
                cx + c0 * r1, y, cz + s0 * r1,
                cx + c1 * r1, y, cz + s1 * r1,
                cx + c1 * r0, y, cz + s1 * r0, c);
        }
    }
}
