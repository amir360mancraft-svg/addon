package com.blockoutlines.addon.modules;

import com.blockoutlines.addon.BlockOutlinesAddon;
import com.blockoutlines.addon.util.CosmeticUtil;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.renderer.Renderer3D;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Every swing leaves a glowing crescent in the air, like a sword slash in an anime. The slashes alternate (down-left,
 * up-right, flat...) and come in ready-made styles. Drawn on your screen only.
 */
public class SwordTrails extends Module {
    public enum Style { Anime, Crimson, Azure, Sakura, Flame, Void, Thunder, Gold, Rainbow, Custom }
    public enum Items_ { Swords, SwordsAndAxes, AllWeapons, Anything }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgLook = settings.createGroup("Look");

    private final Setting<Style> style = sgGeneral.add(new EnumSetting.Builder<Style>()
        .name("style").description("Ready-made look. Anime = clean white and blue. Sakura drops petals, Flame throws embers, Thunder crackles, Void is dark purple.")
        .defaultValue(Style.Anime).build());
    private final Setting<Items_> items = sgGeneral.add(new EnumSetting.Builder<Items_>()
        .name("items").description("Which held items leave a trail.").defaultValue(Items_.SwordsAndAxes).build());
    private final Setting<Boolean> others = sgGeneral.add(new BoolSetting.Builder()
        .name("other-players").description("Also show trails for other players (it uses their swing, so it is a copy of the effect, not their real blade).").defaultValue(true).build());
    private final Setting<SettingColor> colorA = sgGeneral.add(new ColorSetting.Builder()
        .name("inner-color").description("Color of the body of the slash.").defaultValue(new SettingColor(120, 190, 255, 255))
        .visible(() -> style.get() == Style.Custom).build());
    private final Setting<SettingColor> colorB = sgGeneral.add(new ColorSetting.Builder()
        .name("edge-color").description("Color of the bright edge.").defaultValue(new SettingColor(255, 255, 255, 255))
        .visible(() -> style.get() == Style.Custom).build());

    private final Setting<Double> reach = sgLook.add(new DoubleSetting.Builder()
        .name("size").description("How far the slash reaches from you (blocks).").defaultValue(2.8).range(1.2, 5.0).sliderRange(1.5, 4.5).build());
    private final Setting<Double> thickness = sgLook.add(new DoubleSetting.Builder()
        .name("thickness").description("How wide the crescent is.").defaultValue(0.55).range(0.1, 1.0).sliderRange(0.2, 0.9).build());
    private final Setting<Double> arc = sgLook.add(new DoubleSetting.Builder()
        .name("arc").description("How wide the swing is (degrees).").defaultValue(150.0).range(60.0, 240.0).sliderRange(80.0, 200.0).build());
    private final Setting<Double> life = sgLook.add(new DoubleSetting.Builder()
        .name("fade-time").description("How long the slash stays before it fades (seconds).").defaultValue(0.4).range(0.1, 1.5).sliderRange(0.15, 1.0).build());
    private final Setting<Double> glow = sgLook.add(new DoubleSetting.Builder()
        .name("glow").description("Strength of the soft light around the slash.").defaultValue(1.0).range(0.0, 2.0).sliderRange(0.0, 2.0).build());
    private final Setting<Boolean> sparks = sgLook.add(new BoolSetting.Builder()
        .name("particles").description("Style particles: petals, embers, sparks.").defaultValue(true).build());
    private final Setting<Boolean> combo = sgLook.add(new BoolSetting.Builder()
        .name("combo-angles").description("Every swing comes from a different angle, like a combo.").defaultValue(true).build());

    // ------------------------------------------------------------------ state

    private static final class Sample { double ix, iy, iz, ox, oy, oz; long born; float u; }
    private static final class Slash {
        final List<Sample> samples = new ArrayList<>();
        int dir = 1;
        double tilt;
        double lastS;
        boolean open;
        double cx, cy, cz;
    }
    private static final class Spark { double x, y, z, vx, vy, vz, age, life, size, spin; int r, g, b; boolean petal; }

    private final Map<UUID, Slash> slashes = new HashMap<>();
    private final Map<UUID, Integer> comboIdx = new HashMap<>();
    private final List<Spark> particles = new ArrayList<>();
    private final Random rng = new Random();
    private double rx, rz, ux, uy, uz, camX, camY, camZ;

    public SwordTrails() {
        super(BlockOutlinesAddon.EFFECTS, "sword-trails",
            "Anime-style slash trails that follow every sword swing, in ready-made styles.");
    }

    @Override
    public void onDeactivate() {
        slashes.clear();
        particles.clear();
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (mc.world == null || mc.player == null || mc.gameRenderer == null) return;
        double dt = Math.min(0.1, event.frameTime);
        float td = event.tickDelta;
        long now = System.currentTimeMillis();

        camX = event.offsetX;
        camY = event.offsetY;
        camZ = event.offsetZ;
        net.minecraft.client.render.Camera cam = mc.gameRenderer.getCamera();
        double yaw = Math.toRadians(cam.getYaw()), pitch = Math.toRadians(cam.getPitch());
        double fx = -Math.sin(yaw) * Math.cos(pitch), fy = -Math.sin(pitch), fz = Math.cos(yaw) * Math.cos(pitch);
        double rl = Math.max(1e-6, Math.sqrt(fz * fz + fx * fx));
        rx = -fz / rl;
        rz = fx / rl;
        ux = -rz * fy;
        uy = rz * fx - rx * fz;
        uz = rx * fy;

        // record new samples while a swing is going on
        for (PlayerEntity p : mc.world.getPlayers()) {
            if (p != mc.player && (!others.get() || p.squaredDistanceTo(mc.player) > 40 * 40)) continue;
            if (!holdsOk(p.getMainHandStack())) { endSlash(p); continue; }
            track(p, td, now);
        }

        Renderer3D r = event.renderer;
        long lifeMs = (long) (life.get() * 1000);
        for (Iterator<Map.Entry<UUID, Slash>> it = slashes.entrySet().iterator(); it.hasNext(); ) {
            Slash s = it.next().getValue();
            s.samples.removeIf(a -> now - a.born > lifeMs);
            if (s.samples.isEmpty() && !s.open) { it.remove(); continue; }
            drawSlash(r, s, now, lifeMs);
        }
        drawParticles(r, dt);
    }

    private boolean holdsOk(ItemStack st) {
        return switch (items.get()) {
            case Anything -> true;
            case Swords -> st.isIn(ItemTags.SWORDS);
            case SwordsAndAxes -> st.isIn(ItemTags.SWORDS) || st.isIn(ItemTags.AXES);
            case AllWeapons -> st.isIn(ItemTags.SWORDS) || st.isIn(ItemTags.AXES) || st.isOf(Items.MACE) || st.isOf(Items.TRIDENT);
        };
    }

    private void endSlash(PlayerEntity p) {
        Slash s = slashes.get(p.getUuid());
        if (s != null) s.open = false;
    }

    private void track(PlayerEntity p, float td, long now) {
        double sp = p.getHandSwingProgress(td);
        Slash s = slashes.computeIfAbsent(p.getUuid(), k -> new Slash());
        if (sp > 0.001 && (!s.open || sp < s.lastS - 0.2)) {
            // a new swing begins
            s.open = true;
            int idx = comboIdx.merge(p.getUuid(), 1, Integer::sum);
            s.dir = (idx % 2 == 0) ? 1 : -1;
            double[] tilts = {-40, 35, -15, 55, -60, 20};
            s.tilt = combo.get() ? Math.toRadians(tilts[idx % tilts.length]) : Math.toRadians(-25);
        }
        if (s.open && sp > 0.001) {
            Vec3d pos = p.getLerpedPos(td);
            // swing is drawn around the chest, turned the way the body looks
            double yawR = Math.toRadians(p.getYaw(td));
            double pitchR = Math.toRadians(p.getPitch(td)) * 0.35;
            double fxx = -Math.sin(yawR), fzz = Math.cos(yawR);
            double rxx = -fzz, rzz = fxx; // right of the player
            double cy0 = pos.y + p.getStandingEyeHeight() - 0.35;
            // ease: fast start, slower end (the blade whips through the middle)
            double u = 1 - Math.pow(1 - Math.min(1.0, sp), 2.2);
            double half = Math.toRadians(arc.get()) * 0.5;
            double ang = s.dir * (half - 2 * half * u);
            double ca = Math.cos(ang), sa = Math.sin(ang);
            // slash plane is turned around the forward axis by the tilt
            double ct = Math.cos(s.tilt), st = Math.sin(s.tilt);
            double dirX = fxx * ca + (rxx * ct) * sa;
            double dirY = st * sa + Math.sin(pitchR) * ca;
            double dirZ = fzz * ca + (rzz * ct) * sa;
            double dl = Math.sqrt(dirX * dirX + dirY * dirY + dirZ * dirZ);
            dirX /= dl; dirY /= dl; dirZ /= dl;
            double R1 = reach.get(), R0 = R1 * (1 - thickness.get());
            double lift = Math.sin(Math.PI * u) * 0.25; // the blade rises a little in the middle of the swing
            Sample a = new Sample();
            a.ix = pos.x + dirX * R0;
            a.iy = cy0 + dirY * R0 + lift * 0.5;
            a.iz = pos.z + dirZ * R0;
            a.ox = pos.x + dirX * R1;
            a.oy = cy0 + dirY * R1 + lift;
            a.oz = pos.z + dirZ * R1;
            a.born = now;
            a.u = (float) u;
            s.samples.add(a);
            if (s.samples.size() > 160) s.samples.remove(0);
            if (sparks.get() && particles.size() < 500) emit(a);
        } else if (sp <= 0.001) {
            s.open = false;
        }
        s.lastS = sp;
    }

    // ------------------------------------------------------------------ colors

    /** [0] body color, [1] edge color, [2] particle color. {@code hue} is used by Rainbow. */
    private int[][] palette(double hue) {
        return switch (style.get()) {
            case Anime -> new int[][]{{110, 185, 255}, {255, 255, 255}, {200, 235, 255}};
            case Crimson -> new int[][]{{235, 40, 70}, {255, 190, 200}, {255, 90, 110}};
            case Azure -> new int[][]{{40, 130, 255}, {170, 240, 255}, {120, 210, 255}};
            case Sakura -> new int[][]{{255, 140, 190}, {255, 235, 245}, {255, 170, 205}};
            case Flame -> new int[][]{{255, 110, 20}, {255, 235, 130}, {255, 150, 40}};
            case Void -> new int[][]{{120, 40, 220}, {225, 170, 255}, {170, 90, 255}};
            case Thunder -> new int[][]{{250, 225, 60}, {255, 255, 235}, {255, 245, 150}};
            case Gold -> new int[][]{{255, 195, 60}, {255, 248, 200}, {255, 215, 110}};
            case Rainbow -> {
                Color c = CosmeticUtil.hsv(hue, 0.75f, 1f, 255), e = CosmeticUtil.hsv(hue + 0.08, 0.25f, 1f, 255);
                yield new int[][]{{c.r, c.g, c.b}, {e.r, e.g, e.b}, {c.r, c.g, c.b}};
            }
            case Custom -> {
                Color a = colorA.get(), b = colorB.get();
                yield new int[][]{{a.r, a.g, a.b}, {b.r, b.g, b.b}, {a.r, a.g, a.b}};
            }
        };
    }

    // ------------------------------------------------------------------ drawing

    private void drawSlash(Renderer3D r, Slash s, long now, long lifeMs) {
        int n = s.samples.size();
        if (n < 2) return;
        double g = glow.get();
        double secs = now / 1000.0;
        for (int i = 0; i < n - 1; i++) {
            Sample a = s.samples.get(i), b = s.samples.get(i + 1);
            double fa = 1 - (now - a.born) / (double) lifeMs, fb = 1 - (now - b.born) / (double) lifeMs;
            if (fa <= 0 && fb <= 0) continue;
            fa = Math.max(0, fa); fb = Math.max(0, fb);
            // the newest part is brightest; the tail thins out
            double ta = fa * fa * (0.4 + 0.6 * (i / (double) n)), tb = fb * fb * (0.4 + 0.6 * ((i + 1) / (double) n));
            int[][] pa = palette(secs * 0.25 + i * 0.01);
            int[][] pb = palette(secs * 0.25 + (i + 1) * 0.01);

            // soft glow: a wider, faint copy
            if (g > 0.01) {
                double ex = 0.45, ey = 0.45;
                Sample ga = grow(a, ex), gb = grow(b, ey);
                Color ca = rgba(pa[0], (int) (70 * ta * g)), cb = rgba(pb[0], (int) (70 * tb * g));
                Color none = rgba(pa[0], 0);
                quad(r, ga.ix, ga.iy, ga.iz, gb.ix, gb.iy, gb.iz, b.ix, b.iy, b.iz, a.ix, a.iy, a.iz, none, none, cb, ca);
                quad(r, a.ox, a.oy, a.oz, b.ox, b.oy, b.oz, gb.ox, gb.oy, gb.oz, ga.ox, ga.oy, ga.oz, ca, cb, none, none);
            }
            // body: transparent on the inner side, strong toward the edge
            Color i0 = rgba(pa[0], 0), i1 = rgba(pb[0], 0);
            Color m0 = rgba(pa[0], (int) (200 * ta)), m1 = rgba(pb[0], (int) (200 * tb));
            quad(r, a.ix, a.iy, a.iz, b.ix, b.iy, b.iz, b.ox, b.oy, b.oz, a.ox, a.oy, a.oz, i0, i1, m1, m0);
            // bright thin edge on the outer side
            Sample ea = thin(a, 0.9), eb = thin(b, 0.9);
            Color e0 = rgba(pa[1], (int) (255 * ta)), e1 = rgba(pb[1], (int) (255 * tb));
            Color e0n = rgba(pa[1], (int) (60 * ta)), e1n = rgba(pb[1], (int) (60 * tb));
            quad(r, ea.ox, ea.oy, ea.oz, eb.ox, eb.oy, eb.oz, b.ox, b.oy, b.oz, a.ox, a.oy, a.oz, e0n, e1n, e1, e0);
        }
        // a bright spot at the leading tip while the swing is going on
        if (s.open) {
            Sample t = s.samples.get(n - 1);
            int[][] p = palette(secs * 0.25);
            glowDot(r, t.ox, t.oy, t.oz, 0.22 * (0.7 + 0.3 * g), rgba(p[1], 210));
        }
    }

    private Sample grow(Sample a, double k) {
        Sample o = new Sample();
        double mx = (a.ix + a.ox) / 2, my = (a.iy + a.oy) / 2, mz = (a.iz + a.oz) / 2;
        o.ix = mx + (a.ix - mx) * (1 + k * 0.4);
        o.iy = my + (a.iy - my) * (1 + k * 0.4) - 0.0;
        o.iz = mz + (a.iz - mz) * (1 + k * 0.4);
        o.ox = mx + (a.ox - mx) * (1 + k);
        o.oy = my + (a.oy - my) * (1 + k);
        o.oz = mz + (a.oz - mz) * (1 + k);
        return o;
    }

    /** A copy whose outer point is moved toward the inner one (the inner end of the bright edge). */
    private Sample thin(Sample a, double k) {
        Sample o = new Sample();
        o.ox = a.ox + (a.ix - a.ox) * (1 - k) * 0.12;
        o.oy = a.oy + (a.iy - a.oy) * (1 - k) * 0.12;
        o.oz = a.oz + (a.iz - a.oz) * (1 - k) * 0.12;
        o.ox = a.ox + (a.ix - a.ox) * 0.1;
        o.oy = a.oy + (a.iy - a.oy) * 0.1;
        o.oz = a.oz + (a.iz - a.oz) * 0.1;
        return o;
    }

    private void glowDot(Renderer3D r, double x, double y, double z, double rad, Color c) {
        int n = 10;
        Color none = new Color(c.r, c.g, c.b, 0);
        for (int i = 0; i < n; i++) {
            double a0 = Math.PI * 2 * i / n, a1 = Math.PI * 2 * (i + 1) / n;
            double c0 = Math.cos(a0), s0 = Math.sin(a0), c1 = Math.cos(a1), s1 = Math.sin(a1);
            quad(r, x, y, z, x, y, z,
                x + (rx * c1 + ux * s1) * rad, y + (uy * s1) * rad, z + (rz * c1 + uz * s1) * rad,
                x + (rx * c0 + ux * s0) * rad, y + (uy * s0) * rad, z + (rz * c0 + uz * s0) * rad,
                c, c, none, none);
        }
    }

    // ------------------------------------------------------------------ particles

    private void emit(Sample a) {
        if (rng.nextDouble() > 0.55) return;
        Spark p = new Spark();
        double k = rng.nextDouble();
        p.x = a.ix + (a.ox - a.ix) * (0.6 + 0.4 * k);
        p.y = a.iy + (a.oy - a.iy) * (0.6 + 0.4 * k);
        p.z = a.iz + (a.oz - a.iz) * (0.6 + 0.4 * k);
        int[][] pal = palette(System.currentTimeMillis() / 4000.0);
        p.r = pal[2][0]; p.g = pal[2][1]; p.b = pal[2][2];
        switch (style.get()) {
            case Sakura -> {
                p.petal = true;
                p.vx = (rng.nextDouble() - 0.5) * 0.7;
                p.vy = -0.15 - rng.nextDouble() * 0.3;
                p.vz = (rng.nextDouble() - 0.5) * 0.7;
                p.life = 1.2 + rng.nextDouble() * 0.8;
                p.size = 0.05 + rng.nextDouble() * 0.03;
            }
            case Flame -> {
                p.vx = (rng.nextDouble() - 0.5) * 0.5;
                p.vy = 0.5 + rng.nextDouble() * 0.9;
                p.vz = (rng.nextDouble() - 0.5) * 0.5;
                p.life = 0.5 + rng.nextDouble() * 0.5;
                p.size = 0.03 + rng.nextDouble() * 0.025;
            }
            case Thunder -> {
                p.vx = (rng.nextDouble() - 0.5) * 2.2;
                p.vy = (rng.nextDouble() - 0.5) * 2.2;
                p.vz = (rng.nextDouble() - 0.5) * 2.2;
                p.life = 0.18 + rng.nextDouble() * 0.15;
                p.size = 0.025;
            }
            default -> {
                p.vx = (rng.nextDouble() - 0.5) * 0.9;
                p.vy = (rng.nextDouble() - 0.3) * 0.7;
                p.vz = (rng.nextDouble() - 0.5) * 0.9;
                p.life = 0.45 + rng.nextDouble() * 0.45;
                p.size = 0.022 + rng.nextDouble() * 0.02;
            }
        }
        p.spin = rng.nextDouble() * 6.28;
        particles.add(p);
    }

    private void drawParticles(Renderer3D r, double dt) {
        for (Iterator<Spark> it = particles.iterator(); it.hasNext(); ) {
            Spark p = it.next();
            p.age += dt;
            if (p.age >= p.life) { it.remove(); continue; }
            double k = p.age / p.life;
            if (p.petal) {
                p.vx += Math.sin(p.age * 5 + p.spin) * 0.6 * dt;
                p.vz += Math.cos(p.age * 4 + p.spin) * 0.6 * dt;
            } else {
                p.vy -= (style.get() == Style.Flame ? -0.4 : 0.8) * dt;
            }
            p.x += p.vx * dt;
            p.y += p.vy * dt;
            p.z += p.vz * dt;
            double fade = Math.sin(Math.PI * Math.min(1.0, k * 1.15));
            int a = (int) (230 * Math.max(0, fade));
            if (a < 4) continue;
            double s = p.size * (p.petal ? 1.0 : (1.0 - 0.5 * k));
            Color c = new Color(p.r, p.g, p.b, a);
            Color none = new Color(p.r, p.g, p.b, a / 2);
            if (p.petal) {
                // a petal: a small tilted diamond that turns
                double sa = Math.sin(p.age * 6 + p.spin), ca = Math.cos(p.age * 6 + p.spin);
                double ax = (rx * ca + ux * sa) * s * 1.6, ay = (uy * sa) * s * 1.6, az = (rz * ca + uz * sa) * s * 1.6;
                double bx = (-rx * sa + ux * ca) * s, by = (uy * ca) * s, bz = (-rz * sa + uz * ca) * s;
                quad(r, p.x - ax, p.y - ay, p.z - az, p.x + bx, p.y + by, p.z + bz, p.x + ax, p.y + ay, p.z + az, p.x - bx, p.y - by, p.z - bz, c, none, c, none);
            } else {
                // a spark with a small tail along its movement
                double tx = -p.vx * 0.06, ty = -p.vy * 0.06, tz = -p.vz * 0.06;
                Color tail = new Color(p.r, p.g, p.b, 0);
                quad(r,
                    p.x - rx * s, p.y - s, p.z - rz * s,
                    p.x + rx * s, p.y - s, p.z + rz * s,
                    p.x + rx * s + tx, p.y + s + ty, p.z + rz * s + tz,
                    p.x - rx * s + tx, p.y + s + ty, p.z - rz * s + tz,
                    c, c, tail, tail);
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private static Color rgba(int[] c, int a) {
        return new Color(c[0], c[1], c[2], Math.max(0, Math.min(255, a)));
    }

    /** Meteor's quad() wants the colors as top-left, top-right, bottom-right, bottom-left (= vertices 2, 3, 4, 1). Drawn on both sides. */
    private static void quad(Renderer3D r,
                             double x1, double y1, double z1, double x2, double y2, double z2,
                             double x3, double y3, double z3, double x4, double y4, double z4,
                             Color cv1, Color cv2, Color cv3, Color cv4) {
        r.quad(x1, y1, z1, x2, y2, z2, x3, y3, z3, x4, y4, z4, cv2, cv3, cv4, cv1);
        r.quad(x4, y4, z4, x3, y3, z3, x2, y2, z2, x1, y1, z1, cv1, cv4, cv3, cv2);
    }
}
