package com.blockoutlines.addon.modules;

import com.blockoutlines.addon.BlockOutlinesAddon;
import com.blockoutlines.addon.util.CosmeticUtil;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
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
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * A colored trail behind the player. On foot it is a stream of little square "pixels" that drift up and fade.
 * While gliding with an elytra it turns into a glowing ribbon with light pulses running along it.
 * Drawn on your screen only, nothing is sent to the server.
 */
public class PlayerTrail extends Module {
    public enum Facing { Camera, Flat }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgPixels = settings.createGroup("Pixels (walking)");
    private final SettingGroup sgGlide = settings.createGroup("Ribbon (elytra)");
    private final SettingGroup sgColor = settings.createGroup("Colors");

    // -------- general
    private final Setting<Boolean> self = sgGeneral.add(new BoolSetting.Builder()
        .name("yourself").description("Show the trail behind you.").defaultValue(true).build());
    private final Setting<Boolean> others = sgGeneral.add(new BoolSetting.Builder()
        .name("other-players").description("Also show trails behind other players near you.").defaultValue(false).build());
    private final Setting<Integer> range = sgGeneral.add(new IntSetting.Builder()
        .name("range").description("How far away other players still get a trail.")
        .defaultValue(40).range(8, 128).sliderRange(8, 100).visible(others::get).build());
    private final Setting<Boolean> throughWalls = sgGeneral.add(new BoolSetting.Builder()
        .name("through-walls").description("Draw the trail even when blocks are in front of it.").defaultValue(false).build());
    private final Setting<Double> camFade = sgGeneral.add(new DoubleSetting.Builder()
        .name("camera-fade").description("Parts of the trail closer than this to your camera fade out, so they never cover your view (blocks).")
        .defaultValue(3.5).range(0.0, 10.0).sliderRange(0.0, 8.0).build());
    private final Setting<Boolean> inAir = sgGeneral.add(new BoolSetting.Builder()
        .name("while-jumping").description("Keep leaving pixels while you are in the air (not gliding).").defaultValue(true).build());

    // -------- pixels
    private final Setting<Boolean> pixels = sgPixels.add(new BoolSetting.Builder()
        .name("pixels").description("Leave colored pixels behind your feet.").defaultValue(true).build());
    private final Setting<Facing> facing = sgPixels.add(new EnumSetting.Builder<Facing>()
        .name("facing").description("Camera = always turned to you, Flat = lying on the ground like footprints.")
        .defaultValue(Facing.Camera).visible(pixels::get).build());
    private final Setting<Double> size = sgPixels.add(new DoubleSetting.Builder()
        .name("size").description("Size of one pixel in blocks.")
        .defaultValue(0.09).range(0.01, 0.6).sliderRange(0.02, 0.3).visible(pixels::get).build());
    private final Setting<Double> speed = sgPixels.add(new DoubleSetting.Builder()
        .name("speed").description("How fast the pixels drift away from your feet.")
        .defaultValue(0.7).range(0.0, 5.0).sliderRange(0.0, 3.0).visible(pixels::get).build());
    private final Setting<Integer> density = sgPixels.add(new IntSetting.Builder()
        .name("density").description("How many pixels appear per second while you move.")
        .defaultValue(45).range(1, 300).sliderRange(5, 150).visible(pixels::get).build());
    private final Setting<Integer> life = sgPixels.add(new IntSetting.Builder()
        .name("life").description("How long a pixel stays (milliseconds).")
        .defaultValue(1100).range(200, 5000).sliderRange(300, 3000).visible(pixels::get).build());
    private final Setting<Double> spread = sgPixels.add(new DoubleSetting.Builder()
        .name("spread").description("How wide the pixels scatter around your feet.")
        .defaultValue(0.3).range(0.0, 1.5).sliderRange(0.0, 1.0).visible(pixels::get).build());
    private final Setting<Boolean> snap = sgPixels.add(new BoolSetting.Builder()
        .name("pixel-grid").description("Snap pixels to a 16x16 grid per block, like Minecraft textures.")
        .defaultValue(true).visible(pixels::get).build());
    private final Setting<Boolean> shrink = sgPixels.add(new BoolSetting.Builder()
        .name("shrink").description("Pixels get smaller while they fade.").defaultValue(true).visible(pixels::get).build());
    private final Setting<Boolean> halo = sgPixels.add(new BoolSetting.Builder()
        .name("glow").description("A soft glow around every pixel.").defaultValue(true).visible(pixels::get).build());
    private final Setting<Boolean> twinkle = sgPixels.add(new BoolSetting.Builder()
        .name("twinkle").description("Pixels flicker a little while they live.").defaultValue(true).visible(pixels::get).build());

    // -------- ribbon
    private final Setting<Boolean> ribbon = sgGlide.add(new BoolSetting.Builder()
        .name("ribbon").description("A glowing ribbon behind you while gliding with an elytra.").defaultValue(true).build());
    private final Setting<Double> ribbonWidth = sgGlide.add(new DoubleSetting.Builder()
        .name("width").description("Width of the ribbon in blocks.")
        .defaultValue(0.45).range(0.05, 2.0).sliderRange(0.1, 1.2).visible(ribbon::get).build());
    private final Setting<Integer> ribbonLife = sgGlide.add(new IntSetting.Builder()
        .name("length").description("How long the ribbon stays (milliseconds). Longer = longer ribbon.")
        .defaultValue(1600).range(300, 6000).sliderRange(500, 4000).visible(ribbon::get).build());
    private final Setting<Boolean> twin = sgGlide.add(new BoolSetting.Builder()
        .name("two-ribbons").description("One ribbon from each side of you instead of one in the middle.")
        .defaultValue(true).visible(ribbon::get).build());
    private final Setting<Double> twinGap = sgGlide.add(new DoubleSetting.Builder()
        .name("gap").description("Distance between the two ribbons.")
        .defaultValue(0.7).range(0.1, 2.5).sliderRange(0.2, 1.5).visible(() -> ribbon.get() && twin.get()).build());
    private final Setting<Boolean> pulse = sgGlide.add(new BoolSetting.Builder()
        .name("pulse").description("Light pulses run along the ribbon.").defaultValue(true).visible(ribbon::get).build());
    private final Setting<Double> pulseSpeed = sgGlide.add(new DoubleSetting.Builder()
        .name("pulse-speed").description("How fast the pulses run.")
        .defaultValue(1.0).range(0.1, 5.0).sliderRange(0.2, 3.0).visible(() -> ribbon.get() && pulse.get()).build());
    private final Setting<Boolean> glideSparks = sgGlide.add(new BoolSetting.Builder()
        .name("sparks").description("Pixels fly off the ribbon while gliding.").defaultValue(true).visible(ribbon::get).build());
    private final Setting<Boolean> headGlow = sgGlide.add(new BoolSetting.Builder()
        .name("head-glow").description("A bright glow where the ribbon starts.").defaultValue(true).visible(ribbon::get).build());

    // -------- colors
    private final Setting<SettingColor> colorA = sgColor.add(new ColorSetting.Builder()
        .name("color-start").description("Color of a fresh pixel / the start of the ribbon.")
        .defaultValue(new SettingColor(90, 220, 255, 255)).build());
    private final Setting<SettingColor> colorB = sgColor.add(new ColorSetting.Builder()
        .name("color-end").description("Color it fades to.")
        .defaultValue(new SettingColor(190, 80, 255, 255)).build());
    private final Setting<Boolean> rainbow = sgColor.add(new BoolSetting.Builder()
        .name("rainbow").description("Rainbow colors instead.").defaultValue(false).build());
    private final Setting<Double> rainbowSpeed = sgColor.add(new DoubleSetting.Builder()
        .name("rainbow-speed").description("How fast the rainbow cycles.")
        .defaultValue(0.35).range(0.02, 3.0).sliderRange(0.05, 1.5).visible(rainbow::get).build());
    private final Setting<Integer> opacity = sgColor.add(new IntSetting.Builder()
        .name("opacity").description("How see-through the trail is.")
        .defaultValue(230).range(20, 255).sliderRange(60, 255).build());

    private static final int MAX_PIXELS = 3500;

    private static final class Px {
        double x, y, z, vx, vy, vz;
        long born; int life; float hue, size, phase; boolean flat;
    }

    private static final class Pt { double x, y, z; long t; }

    private static final class Trail {
        double lx, ly, lz; boolean has, gliding; double acc;
        long seen;
        final ArrayDeque<Pt> pts = new ArrayDeque<>(); // newest first
    }

    private final List<Px> parts = new ArrayList<>();
    private final Map<Integer, Trail> trails = new HashMap<>();
    private final Random rng = new Random();

    // camera frame (set every frame)
    private double fx, fy, fz, rx, ry, rz, ux, uy, uz;
    private double camX, camY, camZ;

    public PlayerTrail() {
        super(BlockOutlinesAddon.EFFECTS, "player-trail",
            "A colored trail behind you: pixels while walking, a glowing pulse ribbon while gliding with an elytra.");
    }

    @Override
    public void onDeactivate() {
        parts.clear();
        trails.clear();
    }

    private List<AbstractClientPlayerEntity> targets() {
        List<AbstractClientPlayerEntity> out = new ArrayList<>();
        if (mc.world == null || mc.player == null) return out;
        for (AbstractClientPlayerEntity p : mc.world.getPlayers()) {
            if (p.isSpectator() || p.isInvisible()) continue;
            if (p == mc.player) {
                if (self.get()) out.add(p);
            } else if (others.get() && p.squaredDistanceTo(mc.player) <= (double) range.get() * range.get()) {
                out.add(p);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ sampling (20 times a second)

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null || mc.player == null) return;
        long now = System.currentTimeMillis();

        for (AbstractClientPlayerEntity p : targets()) {
            Trail t = trails.computeIfAbsent(p.getId(), k -> new Trail());
            t.seen = now;
            double x = p.getX(), y = p.getY(), z = p.getZ();
            if (!t.has) { t.lx = x; t.ly = y; t.lz = z; t.has = true; }
            double dx = x - t.lx, dy = y - t.ly, dz = z - t.lz;
            double moved = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (moved > 20) { t.lx = x; t.ly = y; t.lz = z; t.pts.clear(); continue; } // teleported
            boolean gliding = p.isGliding();
            t.gliding = gliding;
            boolean moving = moved > 0.03;

            if (gliding) {
                if (ribbon.get() && moving) {
                    Pt pt = new Pt();
                    pt.x = x; pt.y = y + 0.35; pt.z = z; pt.t = now;
                    t.pts.addFirst(pt);
                }
                if (pixels.get() && glideSparks.get() && moving) {
                    t.acc += density.get() / 20.0 * 0.6;
                    while (t.acc >= 1) {
                        t.acc -= 1;
                        double off = (rng.nextDouble() - 0.5) * (twin.get() ? twinGap.get() * 1.6 : ribbonWidth.get());
                        double l = Math.max(1e-6, Math.sqrt(dx * dx + dz * dz));
                        double sx = -dz / l * off, sz = dx / l * off;
                        spawn(x + sx, y + 0.35 + (rng.nextDouble() - 0.5) * 0.3, z + sz, false, 0.7);
                    }
                }
            } else if (pixels.get() && moving && (p.isOnGround() || inAir.get())) {
                t.acc += density.get() / 20.0;
                while (t.acc >= 1) {
                    t.acc -= 1;
                    double a = rng.nextDouble() * Math.PI * 2, rad = Math.sqrt(rng.nextDouble()) * spread.get();
                    spawn(x + Math.cos(a) * rad, y + 0.02, z + Math.sin(a) * rad, facing.get() == Facing.Flat, 1.0);
                }
            }

            t.lx = x; t.ly = y; t.lz = z;
            while (!t.pts.isEmpty() && now - t.pts.peekLast().t > ribbonLife.get()) t.pts.pollLast();
        }

        Iterator<Map.Entry<Integer, Trail>> it = trails.entrySet().iterator();
        while (it.hasNext()) {
            Trail t = it.next().getValue();
            if (now - t.seen > 4000 || (t.pts.isEmpty() && now - t.seen > 1500)) it.remove();
        }
    }

    private void spawn(double x, double y, double z, boolean flat, double power) {
        if (parts.size() >= MAX_PIXELS) return;
        Px p = new Px();
        p.x = x; p.y = y; p.z = z;
        double s = speed.get() * power;
        double a = rng.nextDouble() * Math.PI * 2;
        double h = 0.08 + rng.nextDouble() * 0.22; // sideways drift
        p.vx = Math.cos(a) * h * s;
        p.vz = Math.sin(a) * h * s;
        p.vy = (flat ? 0.0 : (0.25 + rng.nextDouble() * 0.6)) * s;
        p.born = System.currentTimeMillis();
        p.life = (int) (life.get() * (0.7 + rng.nextDouble() * 0.6));
        p.hue = rng.nextFloat();
        p.phase = rng.nextFloat() * 6.28f;
        p.size = (float) (size.get() * (0.6 + rng.nextDouble() * 0.8));
        p.flat = flat;
        parts.add(p);
    }

    // ------------------------------------------------------------------ drawing

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (parts.isEmpty() && trails.isEmpty()) return;
        if (mc.gameRenderer == null) return;

        Camera cam = mc.gameRenderer.getCamera();
        double yaw = Math.toRadians(cam.getYaw()), pitch = Math.toRadians(cam.getPitch());
        fx = -Math.sin(yaw) * Math.cos(pitch); fy = -Math.sin(pitch); fz = Math.cos(yaw) * Math.cos(pitch);
        double rl = Math.max(1e-6, Math.sqrt(fz * fz + fx * fx));
        rx = -fz / rl; ry = 0; rz = fx / rl;
        ux = ry * fz - rz * fy; uy = rz * fx - rx * fz; uz = rx * fy - ry * fx;

        camX = event.offsetX; camY = event.offsetY; camZ = event.offsetZ;
        Renderer3D r = throughWalls.get() ? event.renderer : event.depthRenderer;
        long now = System.currentTimeMillis();
        double dt = Math.min(0.1, event.frameTime);
        double secs = now / 1000.0;

        // ---- ribbons
        if (ribbon.get()) {
            for (Map.Entry<Integer, Trail> en : trails.entrySet()) {
                Trail t = en.getValue();
                double[] head = null;
                if (t.gliding && mc.world != null) {
                    Entity e = mc.world.getEntityById(en.getKey());
                    if (e instanceof AbstractClientPlayerEntity pl) {
                        Vec3d v = pl.getLerpedPos(event.tickDelta);
                        head = new double[]{v.x, v.y + 0.35, v.z};
                    }
                }
                drawRibbons(r, t, head, now, secs);
            }
        }

        // ---- pixels
        int op = opacity.get();
        for (int i = parts.size() - 1; i >= 0; i--) {
            Px p = parts.get(i);
            long age = now - p.born;
            if (age >= p.life) { parts.remove(i); continue; }
            double u = age / (double) p.life;

            p.x += p.vx * dt; p.y += p.vy * dt; p.z += p.vz * dt;
            double damp = Math.exp(-1.2 * dt);
            p.vx *= damp; p.vz *= damp; p.vy *= Math.exp(-0.6 * dt);

            double fade = Math.pow(1.0 - u, 1.1) * Math.min(1.0, u * 12.0 + 0.25);
            if (twinkle.get()) fade *= 0.75 + 0.25 * Math.sin(secs * 14.0 + p.phase);
            int alpha = (int) (op * Math.max(0, Math.min(1, fade)) * nearFade(p.x, p.y, p.z));
            if (alpha <= 2) continue;

            double s = p.size * 0.5 * (shrink.get() ? 0.35 + 0.65 * (1.0 - u) : 1.0);
            double x = p.x, y = p.y, z = p.z;
            if (snap.get()) {
                x = Math.round(x * 16.0) / 16.0; y = Math.round(y * 16.0) / 16.0; z = Math.round(z * 16.0) / 16.0;
            }
            Color c = color(u, p.hue, secs, alpha);
            if (halo.get()) {
                Color hc = new Color(c.r, c.g, c.b, Math.min(255, alpha / 4));
                square(r, x, y, z, s * 2.6, p.flat, hc);
            }
            square(r, x, y, z, s, p.flat, c);
        }
    }

    /** 0 right at the camera, 1 once the point is camera-fade blocks away. */
    private double nearFade(double x, double y, double z) {
        double d = camFade.get();
        if (d <= 0.01) return 1.0;
        double dx = x - camX, dy = y - camY, dz = z - camZ;
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double t = Math.max(0.0, Math.min(1.0, (dist - d * 0.3) / (d * 0.7)));
        return t * t * (3 - 2 * t);
    }

    private Color color(double u, float hue, double secs, int alpha) {
        if (rainbow.get()) return CosmeticUtil.hsv((float) (secs * rainbowSpeed.get() + hue * 0.25 + u * 0.3), 0.75f, 1f, alpha);
        return CosmeticUtil.lerp(new Color(colorA.get()), new Color(colorB.get()), u, alpha);
    }

    /** One hard-edged square pixel. */
    private void square(Renderer3D r, double x, double y, double z, double s, boolean flat, Color c) {
        if (flat) {
            quad(r, x - s, y, z - s, x + s, y, z - s, x + s, y, z + s, x - s, y, z + s, c, c, c, c);
        } else {
            quad(r,
                x - rx * s - ux * s, y - ry * s - uy * s, z - rz * s - uz * s,
                x + rx * s - ux * s, y + ry * s - uy * s, z + rz * s - uz * s,
                x + rx * s + ux * s, y + ry * s + uy * s, z + rz * s + uz * s,
                x - rx * s + ux * s, y - ry * s + uy * s, z - rz * s + uz * s,
                c, c, c, c);
        }
    }

    private void drawRibbons(Renderer3D r, Trail t, double[] head, long now, double secs) {
        int n = t.pts.size() + (head != null ? 1 : 0);
        if (n < 2) return;
        double[] x = new double[n], y = new double[n], z = new double[n];
        double[] age = new double[n];
        int k = 0;
        if (head != null) { x[0] = head[0]; y[0] = head[1]; z[0] = head[2]; age[0] = 0; k = 1; }
        for (Pt pt : t.pts) {
            x[k] = pt.x; y[k] = pt.y; z[k] = pt.z;
            age[k] = Math.min(1.0, (now - pt.t) / (double) ribbonLife.get());
            k++;
        }
        if (twin.get()) {
            double[] xa = new double[n], za = new double[n], xb = new double[n], zb = new double[n];
            for (int i = 0; i < n; i++) {
                int a = Math.max(0, i - 1), b = Math.min(n - 1, i + 1);
                double dx = x[a] - x[b], dz = z[a] - z[b];
                double l = Math.max(1e-6, Math.sqrt(dx * dx + dz * dz));
                double px = -dz / l * twinGap.get() * 0.5, pz = dx / l * twinGap.get() * 0.5;
                xa[i] = x[i] + px; za[i] = z[i] + pz;
                xb[i] = x[i] - px; zb[i] = z[i] - pz;
            }
            strip(r, xa, y, za, age, n, secs);
            strip(r, xb, y, zb, age, n, secs);
            if (headGlow.get()) {
                glow(r, xa[0], y[0], za[0], ribbonWidth.get() * 0.9, secs);
                glow(r, xb[0], y[0], zb[0], ribbonWidth.get() * 0.9, secs);
            }
        } else {
            strip(r, x, y, z, age, n, secs);
            if (headGlow.get()) glow(r, x[0], y[0], z[0], ribbonWidth.get() * 1.2, secs);
        }
    }

    /** A camera-facing strip: bright in the middle, clear at the edges, thinner and fainter toward the old end. */
    private void strip(Renderer3D r, double[] x, double[] y, double[] z, double[] age, int n, double secs) {
        Color[] mid = new Color[n], edge = new Color[n];
        double[] sx = new double[n], sy = new double[n], sz = new double[n];
        double phase = secs * pulseSpeed.get() * 9.0;
        int op = opacity.get();
        for (int i = 0; i < n; i++) {
            double u = age[i];
            double m = pulse.get() ? 0.35 + 0.65 * (0.5 + 0.5 * Math.sin(i * 0.75 - phase)) : 1.0;
            int a = (int) (op * Math.pow(1.0 - u, 0.9) * m * nearFade(x[i], y[i], z[i]));
            Color c = color(u, 0f, secs, Math.max(0, Math.min(255, a)));
            mid[i] = c;
            edge[i] = new Color(c.r, c.g, c.b, 0);

            int p0 = Math.max(0, i - 1), p1 = Math.min(n - 1, i + 1);
            double dx = x[p1] - x[p0], dy = y[p1] - y[p0], dz = z[p1] - z[p0];
            double ssx = dy * fz - dz * fy, ssy = dz * fx - dx * fz, ssz = dx * fy - dy * fx;
            double l = Math.max(1e-6, Math.sqrt(ssx * ssx + ssy * ssy + ssz * ssz));
            double w = ribbonWidth.get() * (0.25 + 0.75 * (1.0 - u)) * (pulse.get() ? 0.85 + 0.25 * m : 1.0) * 0.5;
            sx[i] = ssx / l * w; sy[i] = ssy / l * w; sz[i] = ssz / l * w;
        }
        for (int i = 0; i < n - 1; i++) {
            int j = i + 1;
            quad(r, x[i] - sx[i], y[i] - sy[i], z[i] - sz[i], x[i], y[i], z[i], x[j], y[j], z[j], x[j] - sx[j], y[j] - sy[j], z[j] - sz[j], edge[i], mid[i], mid[j], edge[j]);
            quad(r, x[i], y[i], z[i], x[i] + sx[i], y[i] + sy[i], z[i] + sz[i], x[j] + sx[j], y[j] + sy[j], z[j] + sz[j], x[j], y[j], z[j], mid[i], edge[i], edge[j], mid[j]);
        }
    }

    private void glow(Renderer3D r, double x, double y, double z, double rad, double secs) {
        Color c = color(0, 0f, secs, (int) Math.min(255, opacity.get() * nearFade(x, y, z)));
        Color z0 = new Color(c.r, c.g, c.b, 0);
        rad *= 0.85 + 0.15 * Math.sin(secs * 12.0);
        int n = 12;
        for (int i = 0; i < n; i++) {
            double a0 = Math.PI * 2 * i / n, a1 = Math.PI * 2 * (i + 1) / n;
            double c0 = Math.cos(a0) * rad, s0 = Math.sin(a0) * rad, c1 = Math.cos(a1) * rad, s1 = Math.sin(a1) * rad;
            quad(r, x, y, z, x, y, z,
                x + rx * c1 + ux * s1, y + ry * c1 + uy * s1, z + rz * c1 + uz * s1,
                x + rx * c0 + ux * s0, y + ry * c0 + uy * s0, z + rz * c0 + uz * s0,
                c, c, z0, z0);
        }
    }

    /** Meteor's quad() wants the colors as top-left, top-right, bottom-right, bottom-left (= vertices 2, 3, 4, 1). */
    private static void quad(Renderer3D r,
                             double x1, double y1, double z1, double x2, double y2, double z2,
                             double x3, double y3, double z3, double x4, double y4, double z4,
                             Color cv1, Color cv2, Color cv3, Color cv4) {
        r.quad(x1, y1, z1, x2, y2, z2, x3, y3, z3, x4, y4, z4, cv2, cv3, cv4, cv1);
    }
}
