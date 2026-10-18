package com.blockoutlines.addon.modules;

import com.blockoutlines.addon.BlockOutlinesAddon;
import com.blockoutlines.addon.util.CosmeticUtil;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.renderer.Renderer3D;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EntityTypeListSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.Ownable;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * A glowing ribbon behind arrows, tridents, ender pearls, fireworks and other flying things.
 * Drawn on your screen only.
 */
public class ProjectileTrails extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgLook = settings.createGroup("Look");
    private final SettingGroup sgColor = settings.createGroup("Colors");

    private final Setting<Set<EntityType<?>>> types = sgGeneral.add(new EntityTypeListSetting.Builder()
        .name("projectiles").description("Which flying things get a trail.")
        .defaultValue(EntityType.ARROW, EntityType.SPECTRAL_ARROW, EntityType.TRIDENT, EntityType.ENDER_PEARL, EntityType.FIREWORK_ROCKET)
        .build());
    private final Setting<Boolean> onlyMine = sgGeneral.add(new BoolSetting.Builder()
        .name("only-mine").description("Only things you shot or threw yourself.").defaultValue(false).build());
    private final Setting<Integer> range = sgGeneral.add(new IntSetting.Builder()
        .name("range").description("How far away things still get a trail.")
        .defaultValue(80).range(8, 256).sliderRange(16, 200).build());
    private final Setting<Boolean> throughWalls = sgGeneral.add(new BoolSetting.Builder()
        .name("through-walls").description("Draw the trail even behind blocks.").defaultValue(false).build());
    private final Setting<Double> camFade = sgGeneral.add(new DoubleSetting.Builder()
        .name("camera-fade").description("Parts closer than this to your camera fade out so they never block your view (blocks).")
        .defaultValue(2.5).range(0.0, 10.0).sliderRange(0.0, 6.0).build());

    private final Setting<Double> width = sgLook.add(new DoubleSetting.Builder()
        .name("width").description("Width of the ribbon in blocks.")
        .defaultValue(0.16).range(0.02, 1.0).sliderRange(0.04, 0.6).build());
    private final Setting<Integer> life = sgLook.add(new IntSetting.Builder()
        .name("length").description("How long the trail stays (milliseconds). Longer = longer ribbon.")
        .defaultValue(900).range(200, 5000).sliderRange(300, 3000).build());
    private final Setting<Boolean> pulse = sgLook.add(new BoolSetting.Builder()
        .name("pulse").description("Light pulses run along the ribbon.").defaultValue(true).build());
    private final Setting<Double> pulseSpeed = sgLook.add(new DoubleSetting.Builder()
        .name("pulse-speed").description("How fast the pulses run.")
        .defaultValue(1.0).range(0.1, 5.0).sliderRange(0.2, 3.0).visible(pulse::get).build());
    private final Setting<Boolean> headGlow = sgLook.add(new BoolSetting.Builder()
        .name("head-glow").description("A soft glow on the flying thing itself.").defaultValue(true).build());
    private final Setting<Integer> opacity = sgLook.add(new IntSetting.Builder()
        .name("opacity").description("How see-through the trail is.")
        .defaultValue(230).range(20, 255).sliderRange(60, 255).build());

    private final Setting<Boolean> byType = sgColor.add(new BoolSetting.Builder()
        .name("color-by-type").description("Own color per kind: pearls purple, tridents teal, arrows white, fireworks orange.")
        .defaultValue(true).build());
    private final Setting<SettingColor> colorA = sgColor.add(new ColorSetting.Builder()
        .name("color-start").description("Color at the flying thing (when color-by-type is off).")
        .defaultValue(new SettingColor(90, 220, 255, 255)).visible(() -> !byType.get()).build());
    private final Setting<SettingColor> colorB = sgColor.add(new ColorSetting.Builder()
        .name("color-end").description("Color at the old end of the trail.")
        .defaultValue(new SettingColor(190, 80, 255, 255)).visible(() -> !byType.get()).build());
    private final Setting<Boolean> rainbow = sgColor.add(new BoolSetting.Builder()
        .name("rainbow").description("Rainbow colors instead.").defaultValue(false).build());
    private final Setting<Double> rainbowSpeed = sgColor.add(new DoubleSetting.Builder()
        .name("rainbow-speed").description("How fast the rainbow cycles.")
        .defaultValue(0.35).range(0.02, 3.0).sliderRange(0.05, 1.5).visible(rainbow::get).build());

    private static final class Pt { double x, y, z; long t; }

    private static final class Trail {
        final ArrayDeque<Pt> pts = new ArrayDeque<>(); // newest first
        EntityType<?> type;
        long seen;
        boolean alive;
    }

    private final Map<Integer, Trail> trails = new HashMap<>();
    private double fx, fy, fz, rx, ry, rz, ux, uy, uz, camX, camY, camZ;

    public ProjectileTrails() {
        super(BlockOutlinesAddon.EFFECTS, "projectile-trails",
            "A glowing pulse ribbon behind arrows, tridents, ender pearls and fireworks.");
    }

    @Override
    public void onDeactivate() {
        trails.clear();
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (mc.world == null || mc.player == null || mc.gameRenderer == null) return;
        long now = System.currentTimeMillis();
        double r2 = (double) range.get() * range.get();

        // ---- collect new points
        for (Trail t : trails.values()) t.alive = false;
        Set<EntityType<?>> set = types.get();
        for (Entity e : mc.world.getEntities()) {
            if (!set.contains(e.getType())) continue;
            if (e.squaredDistanceTo(mc.player) > r2) continue;
            if (onlyMine.get() && !(e instanceof Ownable o && o.getOwner() == mc.player)) continue;
            Trail t = trails.computeIfAbsent(e.getId(), k -> new Trail());
            t.alive = true;
            t.seen = now;
            t.type = e.getType();
            Vec3d v = e.getLerpedPos(event.tickDelta);
            Pt last = t.pts.peekFirst();
            if (last == null || (v.x - last.x) * (v.x - last.x) + (v.y - last.y) * (v.y - last.y) + (v.z - last.z) * (v.z - last.z) > 0.0036) {
                Pt p = new Pt();
                p.x = v.x; p.y = v.y; p.z = v.z; p.t = now;
                t.pts.addFirst(p);
            }
        }

        // ---- camera
        Camera cam = mc.gameRenderer.getCamera();
        double yaw = Math.toRadians(cam.getYaw()), pitch = Math.toRadians(cam.getPitch());
        fx = -Math.sin(yaw) * Math.cos(pitch); fy = -Math.sin(pitch); fz = Math.cos(yaw) * Math.cos(pitch);
        double rl = Math.max(1e-6, Math.sqrt(fz * fz + fx * fx));
        rx = -fz / rl; ry = 0; rz = fx / rl;
        ux = ry * fz - rz * fy; uy = rz * fx - rx * fz; uz = rx * fy - ry * fx;
        camX = event.offsetX; camY = event.offsetY; camZ = event.offsetZ;

        Renderer3D r = throughWalls.get() ? event.renderer : event.depthRenderer;
        double secs = now / 1000.0;

        Iterator<Map.Entry<Integer, Trail>> it = trails.entrySet().iterator();
        while (it.hasNext()) {
            Trail t = it.next().getValue();
            while (!t.pts.isEmpty() && now - t.pts.peekLast().t > life.get()) t.pts.pollLast();
            if (t.pts.isEmpty() && !t.alive) { it.remove(); continue; }
            draw(r, t, now, secs);
        }
    }

    private void draw(Renderer3D r, Trail t, long now, double secs) {
        int n = t.pts.size();
        if (n < 2) return;
        double[] x = new double[n], y = new double[n], z = new double[n], age = new double[n];
        int k = 0;
        for (Pt p : t.pts) {
            x[k] = p.x; y[k] = p.y; z[k] = p.z;
            age[k] = Math.min(1.0, (now - p.t) / (double) life.get());
            k++;
        }
        Color[] mid = new Color[n], edge = new Color[n];
        double[] sx = new double[n], sy = new double[n], sz = new double[n];
        double phase = secs * pulseSpeed.get() * 9.0;
        int op = opacity.get();
        for (int i = 0; i < n; i++) {
            double u = age[i];
            double m = pulse.get() ? 0.4 + 0.6 * (0.5 + 0.5 * Math.sin(i * 0.8 - phase)) : 1.0;
            double a = op * Math.pow(1.0 - u, 0.9) * m * nearFade(x[i], y[i], z[i]);
            Color c = color(t.type, u, secs, (int) Math.max(0, Math.min(255, a)));
            mid[i] = c;
            edge[i] = new Color(c.r, c.g, c.b, 0);

            int p0 = Math.max(0, i - 1), p1 = Math.min(n - 1, i + 1);
            double dx = x[p1] - x[p0], dy = y[p1] - y[p0], dz = z[p1] - z[p0];
            double ssx = dy * fz - dz * fy, ssy = dz * fx - dx * fz, ssz = dx * fy - dy * fx;
            double l = Math.max(1e-6, Math.sqrt(ssx * ssx + ssy * ssy + ssz * ssz));
            double w = width.get() * (0.2 + 0.8 * (1.0 - u)) * (pulse.get() ? 0.85 + 0.25 * m : 1.0) * 0.5;
            sx[i] = ssx / l * w; sy[i] = ssy / l * w; sz[i] = ssz / l * w;
        }
        for (int i = 0; i < n - 1; i++) {
            int j = i + 1;
            quad(r, x[i] - sx[i], y[i] - sy[i], z[i] - sz[i], x[i], y[i], z[i], x[j], y[j], z[j], x[j] - sx[j], y[j] - sy[j], z[j] - sz[j], edge[i], mid[i], mid[j], edge[j]);
            quad(r, x[i], y[i], z[i], x[i] + sx[i], y[i] + sy[i], z[i] + sz[i], x[j] + sx[j], y[j] + sy[j], z[j] + sz[j], x[j], y[j], z[j], mid[i], edge[i], edge[j], mid[j]);
        }
        if (t.alive && headGlow.get()) glow(r, x[0], y[0], z[0], width.get() * 1.6, t.type, secs);
    }

    private double nearFade(double x, double y, double z) {
        double d = camFade.get();
        if (d <= 0.01) return 1.0;
        double dx = x - camX, dy = y - camY, dz = z - camZ;
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double q = Math.max(0.0, Math.min(1.0, (dist - d * 0.3) / (d * 0.7)));
        return q * q * (3 - 2 * q);
    }

    private Color color(EntityType<?> type, double u, double secs, int alpha) {
        if (rainbow.get()) return CosmeticUtil.hsv((float) (secs * rainbowSpeed.get() + u * 0.3), 0.75f, 1f, alpha);
        if (byType.get()) {
            Color a, b;
            if (type == EntityType.ENDER_PEARL) { a = new Color(190, 110, 255, 255); b = new Color(90, 40, 200, 255); }
            else if (type == EntityType.TRIDENT) { a = new Color(90, 255, 230, 255); b = new Color(40, 120, 255, 255); }
            else if (type == EntityType.FIREWORK_ROCKET) { a = new Color(255, 220, 90, 255); b = new Color(255, 80, 40, 255); }
            else if (type == EntityType.ARROW || type == EntityType.SPECTRAL_ARROW) { a = new Color(255, 255, 255, 255); b = new Color(120, 190, 255, 255); }
            else { a = new Color(255, 160, 80, 255); b = new Color(255, 60, 60, 255); }
            return CosmeticUtil.lerp(a, b, u, alpha);
        }
        return CosmeticUtil.lerp(new Color(colorA.get()), new Color(colorB.get()), u, alpha);
    }

    private void glow(Renderer3D r, double x, double y, double z, double rad, EntityType<?> type, double secs) {
        Color c = color(type, 0, secs, (int) Math.min(255, opacity.get() * nearFade(x, y, z)));
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
