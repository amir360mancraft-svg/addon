package com.blockoutlines.addon.modules;

import com.blockoutlines.addon.BlockOutlinesAddon;
import com.blockoutlines.addon.util.CosmeticUtil;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;

import java.util.ArrayList;
import java.util.List;

/** When you jump, a ring appears under your feet: it starts small, grows and fades out. */
public class JumpCircle extends Module {
    private final SettingGroup sg = settings.getDefaultGroup();

    private final Setting<Double> startRadius = sg.add(new DoubleSetting.Builder()
        .name("start-radius").description("Radius of the ring when it appears.")
        .defaultValue(0.3).range(0.05, 2.0).sliderRange(0.05, 2.0).build());
    private final Setting<Double> maxRadius = sg.add(new DoubleSetting.Builder()
        .name("max-radius").description("Radius the ring grows to.")
        .defaultValue(2.2).range(0.5, 8.0).sliderRange(0.5, 6.0).build());
    private final Setting<Integer> duration = sg.add(new IntSetting.Builder()
        .name("duration").description("How long the ring lives, in milliseconds.")
        .defaultValue(900).range(200, 3000).sliderRange(200, 2500).build());
    private final Setting<Integer> thickness = sg.add(new IntSetting.Builder()
        .name("ring-thickness").description("Number of thin rings stacked together (more = thicker, softer ring).")
        .defaultValue(4).range(1, 10).sliderRange(1, 10).build());
    private final Setting<Boolean> fill = sg.add(new BoolSetting.Builder()
        .name("fill").description("Also fill the inside with faint rings, so it looks like a disc.")
        .defaultValue(true).build());
    private final Setting<Integer> segments = sg.add(new IntSetting.Builder()
        .name("segments").description("How round the ring is.")
        .defaultValue(48).range(12, 128).sliderRange(12, 96).build());
    private final Setting<SettingColor> colorA = sg.add(new ColorSetting.Builder()
        .name("inner-color").description("Colour of the inside of the ring.")
        .defaultValue(new SettingColor(120, 200, 255, 255)).build());
    private final Setting<SettingColor> colorB = sg.add(new ColorSetting.Builder()
        .name("outer-color").description("Colour of the outer edge.")
        .defaultValue(new SettingColor(150, 90, 255, 255)).build());
    private final Setting<Boolean> onlyGround = sg.add(new BoolSetting.Builder()
        .name("only-from-ground").description("Only spawn a ring when you jump off the ground (not mid air).")
        .defaultValue(true).build());

    private static final class Ring {
        final double x, y, z;
        final long start;
        Ring(double x, double y, double z, long start) { this.x = x; this.y = y; this.z = z; this.start = start; }
    }

    private final List<Ring> rings = new ArrayList<>();
    private boolean wasOnGround;

    public JumpCircle() {
        super(BlockOutlinesAddon.COSMETICS, "jump-circle", "A ring grows under your feet every time you jump.");
    }

    @Override
    public void onActivate() {
        rings.clear();
        wasOnGround = mc.player != null && mc.player.isOnGround();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null) return;
        boolean ground = mc.player.isOnGround();
        boolean rising = mc.player.getVelocity().y > 0.0;
        boolean jumped = rising && !ground && (wasOnGround || !onlyGround.get())
            && !mc.player.isGliding() && !mc.player.isTouchingWater() && !mc.player.isClimbing();
        if (jumped && (wasOnGround || mc.player.age % 8 == 0)) {
            rings.add(new Ring(mc.player.getX(), mc.player.getY() + 0.02, mc.player.getZ(), System.currentTimeMillis()));
            if (rings.size() > 24) rings.remove(0);
        }
        wasOnGround = ground;
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (rings.isEmpty()) return;
        long now = System.currentTimeMillis();
        int life = duration.get();
        int n = segments.get();
        double r0 = startRadius.get();
        double r1 = Math.max(r0 + 0.01, maxRadius.get());

        rings.removeIf(r -> now - r.start >= life);

        for (Ring ring : rings) {
            double t = (now - ring.start) / (double) life;
            double ease = 1.0 - Math.pow(1.0 - t, 3.0);
            double radius = r0 + (r1 - r0) * ease;
            double fade = Math.pow(1.0 - t, 1.3);

            int bands = thickness.get();
            for (int b = 0; b < bands; b++) {
                double rr = radius - b * 0.025;
                if (rr <= 0.02) break;
                int alpha = (int) (255 * fade * (1.0 - b / (double) (bands + 1)));
                draw(event, ring, rr, n, alpha, 1.0);
            }

            if (fill.get()) {
                int steps = Math.max(2, (int) (radius / 0.18));
                for (int k = 1; k < steps; k++) {
                    double frac = k / (double) steps;
                    int alpha = (int) (70 * fade * (1.0 - frac * 0.5));
                    draw(event, ring, radius * frac, Math.max(12, (int) (n * frac)), alpha, frac);
                }
            }
        }
    }

    private void draw(Render3DEvent event, Ring ring, double radius, int n, int alpha, double mix) {
        if (alpha <= 0) return;
        Color c = CosmeticUtil.lerp(new Color(colorA.get()), new Color(colorB.get()), mix, Math.min(255, alpha));
        double px = ring.x + radius, pz = ring.z;
        for (int i = 1; i <= n; i++) {
            double a = (Math.PI * 2.0 * i) / n;
            double nx = ring.x + Math.cos(a) * radius;
            double nz = ring.z + Math.sin(a) * radius;
            event.renderer.line(px, ring.y, pz, nx, ring.y, nz, c);
            px = nx;
            pz = nz;
        }
    }
}
