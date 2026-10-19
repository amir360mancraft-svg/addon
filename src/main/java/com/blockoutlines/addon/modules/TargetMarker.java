package com.blockoutlines.addon.modules;

import com.blockoutlines.addon.BlockOutlinesAddon;
import com.blockoutlines.addon.util.CosmeticUtil;
import meteordevelopment.meteorclient.events.entity.player.AttackEntityEvent;
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
import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Vec3d;

/**
 * Lock-on marker for your target: a ring on the ground whose arc shows its health, a slowly spinning crystal above the
 * head, little sparks circling the body, and a quick "lock-on" snap when you pick a new target.
 * Drawn on your screen only.
 */
public class TargetMarker extends Module {
    public enum Pick { LastHit, Crosshair, Both }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgLook = settings.createGroup("Look");
    private final SettingGroup sgColor = settings.createGroup("Colors");

    // -------- general
    private final Setting<Pick> pick = sgGeneral.add(new EnumSetting.Builder<Pick>()
        .name("target").description("LastHit = whoever you hit last, Crosshair = whoever you look at, Both = either one.")
        .defaultValue(Pick.Both).build());
    private final Setting<Integer> keepTime = sgGeneral.add(new IntSetting.Builder()
        .name("keep-time").description("How long the marker stays after your last hit (seconds).")
        .defaultValue(5).range(1, 30).sliderRange(1, 15).visible(() -> pick.get() != Pick.Crosshair).build());
    private final Setting<Boolean> playersOnly = sgGeneral.add(new BoolSetting.Builder()
        .name("players-only").description("Only players, no mobs.").defaultValue(true).build());
    private final Setting<Integer> range = sgGeneral.add(new IntSetting.Builder()
        .name("range").description("The marker disappears when the target is farther away than this.")
        .defaultValue(60).range(8, 128).sliderRange(16, 100).build());
    private final Setting<Boolean> throughWalls = sgGeneral.add(new BoolSetting.Builder()
        .name("through-walls").description("Keep it visible behind blocks.").defaultValue(true).build());

    // -------- look
    private final Setting<Double> size = sgLook.add(new DoubleSetting.Builder()
        .name("size").description("Overall size of the marker.")
        .defaultValue(1.0).range(0.4, 3.0).sliderRange(0.5, 2.0).build());
    private final Setting<Double> spin = sgLook.add(new DoubleSetting.Builder()
        .name("spin-speed").description("How fast things rotate.")
        .defaultValue(1.0).range(0.0, 5.0).sliderRange(0.0, 3.0).build());
    private final Setting<Boolean> ring = sgLook.add(new BoolSetting.Builder()
        .name("ground-ring").description("A ring on the ground around the target.").defaultValue(true).build());
    private final Setting<Boolean> healthArc = sgLook.add(new BoolSetting.Builder()
        .name("health-arc").description("The bright part of the ring shows how much health is left.")
        .defaultValue(true).visible(ring::get).build());
    private final Setting<Boolean> dashes = sgLook.add(new BoolSetting.Builder()
        .name("outer-dashes").description("A second ring of dashes turning the other way.")
        .defaultValue(true).visible(ring::get).build());
    private final Setting<Boolean> crystal = sgLook.add(new BoolSetting.Builder()
        .name("crystal").description("A spinning crystal floating above the head.").defaultValue(true).build());
    private final Setting<Boolean> sparks = sgLook.add(new BoolSetting.Builder()
        .name("orbiting-sparks").description("Little pixels circling the body.").defaultValue(true).build());
    private final Setting<Boolean> lockOn = sgLook.add(new BoolSetting.Builder()
        .name("lock-on-animation").description("The marker snaps in from big to small when you pick a new target.")
        .defaultValue(true).build());
    private final Setting<Integer> opacity = sgLook.add(new IntSetting.Builder()
        .name("opacity").description("How see-through the marker is.")
        .defaultValue(230).range(30, 255).sliderRange(60, 255).build());

    // -------- colors
    private final Setting<Boolean> byHealth = sgColor.add(new BoolSetting.Builder()
        .name("color-by-health").description("Green when healthy, yellow, then red when low.").defaultValue(true).build());
    private final Setting<SettingColor> fixed = sgColor.add(new ColorSetting.Builder()
        .name("color").description("Marker color (when color-by-health is off).")
        .defaultValue(new SettingColor(255, 70, 110, 255)).visible(() -> !byHealth.get()).build());
    private final Setting<Boolean> rainbow = sgColor.add(new BoolSetting.Builder()
        .name("rainbow").description("Rainbow colors instead.").defaultValue(false).build());

    private static final double TAU = Math.PI * 2;

    private int lastId = -1;
    private long lastHit;
    private int shownId = -1;
    private long acq, lastSeen;
    private double rx, ry, rz, ux, uy, uz;

    public TargetMarker() {
        super(BlockOutlinesAddon.COMBAT, "target-marker",
            "A lock-on marker on your target: health ring on the ground, spinning crystal above, circling sparks.");
    }

    @Override
    public void onDeactivate() {
        shownId = -1;
        lastId = -1;
    }

    @EventHandler
    private void onAttack(AttackEntityEvent event) {
        if (event.entity != null) {
            lastId = event.entity.getId();
            lastHit = System.currentTimeMillis();
        }
    }

    private boolean valid(Entity e, double r2) {
        if (!(e instanceof LivingEntity le) || e == mc.player) return false;
        if (!le.isAlive() || le.getHealth() <= 0) return false;
        if (playersOnly.get() && !(e instanceof PlayerEntity)) return false;
        return e.squaredDistanceTo(mc.player) <= r2;
    }

    private LivingEntity findTarget(long now) {
        double r2 = (double) range.get() * range.get();
        Pick p = pick.get();
        if (p != Pick.LastHit && mc.targetedEntity != null && valid(mc.targetedEntity, r2)) return (LivingEntity) mc.targetedEntity;
        if (p != Pick.Crosshair && lastId != -1 && now - lastHit < keepTime.get() * 1000L) {
            Entity e = mc.world.getEntityById(lastId);
            if (e != null && valid(e, r2)) return (LivingEntity) e;
        }
        return null;
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (mc.world == null || mc.player == null || mc.gameRenderer == null) return;
        long now = System.currentTimeMillis();

        LivingEntity t = findTarget(now);
        if (t != null) {
            if (t.getId() != shownId) { shownId = t.getId(); acq = now; }
            lastSeen = now;
        } else if (shownId == -1) {
            return;
        }
        double vis = t != null ? 1.0 : 1.0 - (now - lastSeen) / 300.0;
        if (vis <= 0) { shownId = -1; return; }
        if (t == null) {
            Entity e = mc.world.getEntityById(shownId);
            if (!(e instanceof LivingEntity le)) { shownId = -1; return; }
            t = le;
        }

        // lock-on: arrives big and quick, settles with a little overshoot
        double u = Math.min(1.0, (now - acq) / 450.0);
        double snap = lockOn.get() ? 1.0 + 1.6 * Math.pow(1.0 - u, 3.0) : 1.0;
        vis *= lockOn.get() ? Math.min(1.0, u * 5.0 + 0.2) : 1.0;

        Camera cam = mc.gameRenderer.getCamera();
        double yaw = Math.toRadians(cam.getYaw()), pitch = Math.toRadians(cam.getPitch());
        double fx = -Math.sin(yaw) * Math.cos(pitch), fy = -Math.sin(pitch), fz = Math.cos(yaw) * Math.cos(pitch);
        double rl = Math.max(1e-6, Math.sqrt(fz * fz + fx * fx));
        rx = -fz / rl; ry = 0; rz = fx / rl;
        ux = ry * fz - rz * fy; uy = rz * fx - rx * fz; uz = rx * fy - ry * fx;

        Renderer3D r = throughWalls.get() ? event.renderer : event.depthRenderer;
        double secs = now / 1000.0;
        double sp = secs * spin.get();

        Vec3d p = t.getLerpedPos(event.tickDelta);
        double h = t.getHeight(), w = t.getWidth();
        double frac = Math.max(0.0, Math.min(1.0, (t.getHealth() + t.getAbsorptionAmount() * 0.0) / Math.max(1f, t.getMaxHealth())));
        int a = (int) (opacity.get() * Math.max(0, Math.min(1, vis)));
        Color base = baseColor(frac, secs);
        double sc = size.get();

        if (ring.get()) drawRing(r, p, w, sc * snap, frac, base, a, sp);
        if (crystal.get()) drawCrystal(r, p.x, p.y + h + 0.5 * sc + 0.07 * Math.sin(secs * 2.2), p.z, 0.15 * sc * (1.0 + (snap - 1.0) * 0.6), base, a, sp);
        if (sparks.get()) drawSparks(r, p, h, w, sc * snap, base, a, secs, sp);
    }

    private Color baseColor(double frac, double secs) {
        if (rainbow.get()) return CosmeticUtil.hsv((float) (secs * 0.3), 0.7f, 1f, 255);
        if (!byHealth.get()) return new Color(fixed.get());
        Color red = new Color(255, 60, 60, 255), yel = new Color(255, 215, 60, 255), grn = new Color(70, 255, 120, 255);
        return frac > 0.5 ? CosmeticUtil.lerp(yel, grn, (frac - 0.5) * 2.0, 255) : CosmeticUtil.lerp(red, yel, frac * 2.0, 255);
    }

    // ---- ground ring: the bright arc is the health

    private void drawRing(Renderer3D r, Vec3d p, double w, double sc, double frac, Color base, int a, double sp) {
        double rad = (w * 0.5 + 0.35) * sc, th = 0.07 * sc, y = p.y + 0.03;
        int n = 56;
        double start = sp * 0.8;
        for (int i = 0; i < n; i++) {
            double a0 = TAU * i / n, a1 = TAU * (i + 1) / n;
            double mid = (i + 0.5) / n;
            boolean on = !healthArc.get() || mid <= frac;
            int al = on ? a : (int) (a * 0.18);
            Color c = new Color(base.r, base.g, base.b, al);
            Color e = new Color(base.r, base.g, base.b, 0);
            // the lit arc starts at a point that slowly turns around the ring
            double s0 = start + a0, s1 = start + a1;
            double ci0 = Math.cos(s0), si0 = Math.sin(s0), ci1 = Math.cos(s1), si1 = Math.sin(s1);
            double in = rad - th, out = rad + th;
            // soft edges: clear at both sides, solid in the middle
            quadFlat(r, p.x, y, p.z, ci0, si0, ci1, si1, in, rad, e, c, c, e);
            quadFlat(r, p.x, y, p.z, ci0, si0, ci1, si1, rad, out, c, e, e, c);
        }
        if (dashes.get()) {
            int d = 14;
            double rr = rad + th * 3.2;
            for (int i = 0; i < d; i++) {
                double a0 = -sp * 1.3 + TAU * i / d, a1 = a0 + TAU / d * 0.45;
                Color c = new Color(base.r, base.g, base.b, (int) (a * 0.85));
                double ci0 = Math.cos(a0), si0 = Math.sin(a0), ci1 = Math.cos(a1), si1 = Math.sin(a1);
                double in = rr - th * 0.45, out = rr + th * 0.45;
                r.quad(p.x + ci0 * in, y, p.z + si0 * in, p.x + ci0 * out, y, p.z + si0 * out,
                    p.x + ci1 * out, y, p.z + si1 * out, p.x + ci1 * in, y, p.z + si1 * in, c);
            }
        }
    }

    /** A flat quad of a ring slice. c0..c3 are given in vertex order (inner0, outer0, outer1, inner1). */
    private void quadFlat(Renderer3D r, double cx, double y, double cz, double ci0, double si0, double ci1, double si1,
                          double in, double out, Color c0, Color c1, Color c2, Color c3) {
        // Meteor's quad() colors are for vertices 2, 3, 4, 1
        r.quad(cx + ci0 * in, y, cz + si0 * in, cx + ci0 * out, y, cz + si0 * out,
            cx + ci1 * out, y, cz + si1 * out, cx + ci1 * in, y, cz + si1 * in, c1, c2, c3, c0);
    }

    // ---- crystal: two pyramids glued together, spinning, with a bright outline

    private void drawCrystal(Renderer3D r, double cx, double cy, double cz, double s, Color base, int a, double sp) {
        double ang = sp * 1.6;
        double[][] eq = new double[4][2];
        for (int i = 0; i < 4; i++) {
            double aa = ang + i * TAU / 4;
            eq[i][0] = Math.cos(aa) * s;
            eq[i][1] = Math.sin(aa) * s;
        }
        double top = cy + s * 1.5, bot = cy - s * 1.5;
        for (int i = 0; i < 4; i++) {
            int j = (i + 1) % 4;
            // each face is shaded by how much it turns toward the light, so the spin looks 3D
            double fa = ang + (i + 0.5) * TAU / 4;
            double lit = 0.55 + 0.45 * Math.cos(fa - 0.8);
            Color up = shade(base, lit, (int) (a * 0.8));
            Color dn = shade(base, lit * 0.65, (int) (a * 0.8));
            r.quad(cx, top, cz, cx + eq[i][0], cy, cz + eq[i][1], cx + eq[j][0], cy, cz + eq[j][1], cx, top, cz, up);
            r.quad(cx, bot, cz, cx + eq[j][0], cy, cz + eq[j][1], cx + eq[i][0], cy, cz + eq[i][1], cx, bot, cz, dn);
        }
        Color line = new Color(255, 255, 255, (int) (a * 0.9));
        for (int i = 0; i < 4; i++) {
            int j = (i + 1) % 4;
            r.line(cx + eq[i][0], cy, cz + eq[i][1], cx + eq[j][0], cy, cz + eq[j][1], line);
            r.line(cx, top, cz, cx + eq[i][0], cy, cz + eq[i][1], line);
            r.line(cx, bot, cz, cx + eq[i][0], cy, cz + eq[i][1], line);
        }
        glow(r, cx, cy, cz, s * 2.4, base, a);
    }

    private Color shade(Color c, double k, int alpha) {
        k = Math.max(0, Math.min(1.3, k));
        return new Color(Math.min(255, (int) (c.r * k + 40 * (k - 0.5) * (k > 0.5 ? 1 : 0))), Math.min(255, (int) (c.g * k + 40 * (k > 0.5 ? k - 0.5 : 0))), Math.min(255, (int) (c.b * k + 40 * (k > 0.5 ? k - 0.5 : 0))), alpha);
    }

    // ---- sparks circling the body

    private void drawSparks(Renderer3D r, Vec3d p, double h, double w, double sc, Color base, int a, double secs, double sp) {
        int n = 6;
        double rad = (w * 0.5 + 0.3) * sc;
        for (int i = 0; i < n; i++) {
            double ang = sp * 1.5 + i * TAU / n;
            double yy = p.y + h * (0.5 + 0.48 * Math.sin(secs * 0.9 * spin.get() + i * 1.7));
            double x = p.x + Math.cos(ang) * rad, z = p.z + Math.sin(ang) * rad;
            double s = 0.035 * sc;
            Color c = new Color(base.r, base.g, base.b, a);
            Color hc = new Color(base.r, base.g, base.b, a / 4);
            square(r, x, yy, z, s * 2.6, hc);
            square(r, x, yy, z, s, c);
        }
    }

    private void square(Renderer3D r, double x, double y, double z, double s, Color c) {
        r.quad(
            x - rx * s - ux * s, y - ry * s - uy * s, z - rz * s - uz * s,
            x + rx * s - ux * s, y + ry * s - uy * s, z + rz * s - uz * s,
            x + rx * s + ux * s, y + ry * s + uy * s, z + rz * s + uz * s,
            x - rx * s + ux * s, y - ry * s + uy * s, z - rz * s + uz * s, c);
    }

    private void glow(Renderer3D r, double x, double y, double z, double rad, Color base, int a) {
        Color c = new Color(base.r, base.g, base.b, a / 2);
        Color z0 = new Color(base.r, base.g, base.b, 0);
        int n = 12;
        for (int i = 0; i < n; i++) {
            double a0 = TAU * i / n, a1 = TAU * (i + 1) / n;
            double c0 = Math.cos(a0) * rad, s0 = Math.sin(a0) * rad, c1 = Math.cos(a1) * rad, s1 = Math.sin(a1) * rad;
            // vertices 1,2 are the centre, 3,4 the rim; colors go to vertices 2,3,4,1
            r.quad(x, y, z, x, y, z,
                x + rx * c1 + ux * s1, y + ry * c1 + uy * s1, z + rz * c1 + uz * s1,
                x + rx * c0 + ux * s0, y + ry * c0 + uy * s0, z + rz * c0 + uz * s0,
                c, z0, z0, c);
        }
    }
}
