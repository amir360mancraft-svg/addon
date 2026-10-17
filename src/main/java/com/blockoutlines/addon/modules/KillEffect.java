package com.blockoutlines.addon.modules;

import com.blockoutlines.addon.BlockOutlinesAddon;
import com.blockoutlines.addon.util.CosmeticUtil;
import meteordevelopment.meteorclient.events.entity.player.AttackEntityEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
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
import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.packet.s2c.play.EntityStatusS2CPacket;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * When somebody dies, a custom lightning bolt strikes the spot. From the impact point a huge circle grows, then
 * suddenly shrinks to almost nothing and explodes into sparks. Everything is drawn on your screen only (no real
 * lightning, nothing is sent to the server).
 */
public class KillEffect extends Module {
    public enum Shape { Flat, Sphere, Both }
    public enum SparkStyle { Spark, Dot }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgBolt = settings.createGroup("Lightning");
    private final SettingGroup sgWave = settings.createGroup("Circle");
    private final SettingGroup sgBurst = settings.createGroup("Explosion");

    // -------- general
    private final Setting<Boolean> players = sgGeneral.add(new BoolSetting.Builder()
        .name("players").description("Effect when a player dies.").defaultValue(true).build());
    private final Setting<Boolean> mobs = sgGeneral.add(new BoolSetting.Builder()
        .name("mobs").description("Effect when a mob (zombie, cow, ...) dies.").defaultValue(false).build());
    private final Setting<Boolean> includeSelf = sgGeneral.add(new BoolSetting.Builder()
        .name("include-self").description("Also when you die.").defaultValue(false).build());
    private final Setting<Boolean> onlyMine = sgGeneral.add(new BoolSetting.Builder()
        .name("only-my-kills").description("Only when you hit the one who died within the last 6 seconds.").defaultValue(false).build());
    private final Setting<Integer> range = sgGeneral.add(new IntSetting.Builder()
        .name("range").description("Ignore deaths farther away than this many blocks.")
        .defaultValue(128).range(8, 512).sliderRange(16, 256).build());
    private final Setting<Boolean> throughWalls = sgGeneral.add(new BoolSetting.Builder()
        .name("through-walls").description("Draw the effect even behind blocks.").defaultValue(true).build());
    private final Setting<Integer> maxEffects = sgGeneral.add(new IntSetting.Builder()
        .name("max-effects").description("How many effects can run at the same time.")
        .defaultValue(6).range(1, 20).sliderRange(1, 12).build());

    // -------- lightning
    private final Setting<Boolean> bolt = sgBolt.add(new BoolSetting.Builder()
        .name("lightning").description("A lightning bolt strikes the spot.").defaultValue(true).build());
    private final Setting<SettingColor> boltCore = sgBolt.add(new ColorSetting.Builder()
        .name("core-color").description("Color of the bright middle of the bolt.")
        .defaultValue(new SettingColor(235, 245, 255, 255)).visible(bolt::get).build());
    private final Setting<SettingColor> boltGlow = sgBolt.add(new ColorSetting.Builder()
        .name("glow-color").description("Color of the glow around the bolt.")
        .defaultValue(new SettingColor(120, 110, 255, 255)).visible(bolt::get).build());
    private final Setting<Double> boltWidth = sgBolt.add(new DoubleSetting.Builder()
        .name("width").description("Thickness of the bolt in blocks.")
        .defaultValue(0.18).range(0.02, 1.5).sliderRange(0.05, 0.8).visible(bolt::get).build());
    private final Setting<Integer> boltHeight = sgBolt.add(new IntSetting.Builder()
        .name("height").description("How high the bolt starts above the spot.")
        .defaultValue(55).range(10, 200).sliderRange(15, 120).visible(bolt::get).build());
    private final Setting<Integer> boltBranches = sgBolt.add(new IntSetting.Builder()
        .name("branches").description("Number of side branches.")
        .defaultValue(5).range(0, 14).sliderRange(0, 10).visible(bolt::get).build());
    private final Setting<Integer> boltTime = sgBolt.add(new IntSetting.Builder()
        .name("duration").description("How long the bolt stays, in milliseconds.")
        .defaultValue(450).range(100, 2000).sliderRange(150, 1200).visible(bolt::get).build());
    private final Setting<Boolean> boltFlicker = sgBolt.add(new BoolSetting.Builder()
        .name("crackle").description("The bolt keeps changing its shape and flickers.").defaultValue(true).visible(bolt::get).build());
    private final Setting<Boolean> thunder = sgBolt.add(new BoolSetting.Builder()
        .name("thunder-sound").description("Play the thunder sound.").defaultValue(true).visible(bolt::get).build());
    private final Setting<Double> volume = sgBolt.add(new DoubleSetting.Builder()
        .name("volume").description("Loudness of the thunder.")
        .defaultValue(1.0).range(0.0, 4.0).sliderRange(0.0, 3.0).visible(() -> bolt.get() && thunder.get()).build());

    // -------- circle
    private final Setting<Boolean> wave = sgWave.add(new BoolSetting.Builder()
        .name("circle").description("A big circle grows from the impact, shrinks suddenly, then explodes.").defaultValue(true).build());
    private final Setting<Shape> shape = sgWave.add(new EnumSetting.Builder<Shape>()
        .name("shape").description("Flat ring on the ground, a see-through ball, or both.")
        .defaultValue(Shape.Both).visible(wave::get).build());
    private final Setting<Double> radius = sgWave.add(new DoubleSetting.Builder()
        .name("max-radius").description("How big the circle gets, in blocks.")
        .defaultValue(9.0).range(1.0, 40.0).sliderRange(2.0, 25.0).visible(wave::get).build());
    private final Setting<Integer> growTime = sgWave.add(new IntSetting.Builder()
        .name("grow-time").description("Milliseconds the circle takes to grow to full size.")
        .defaultValue(650).range(100, 4000).sliderRange(200, 2000).visible(wave::get).build());
    private final Setting<Integer> shrinkTime = sgWave.add(new IntSetting.Builder()
        .name("shrink-time").description("Milliseconds of the sudden shrink (small = more sudden).")
        .defaultValue(110).range(30, 1500).sliderRange(40, 600).visible(wave::get).build());
    private final Setting<Double> ringWidth = sgWave.add(new DoubleSetting.Builder()
        .name("ring-width").description("Thickness of the flat ring in blocks.")
        .defaultValue(0.55).range(0.05, 4.0).sliderRange(0.1, 2.0).visible(wave::get).build());
    private final Setting<SettingColor> waveInner = sgWave.add(new ColorSetting.Builder()
        .name("inner-color").description("Color of the inside / start of the circle.")
        .defaultValue(new SettingColor(110, 190, 255, 255)).visible(wave::get).build());
    private final Setting<SettingColor> waveOuter = sgWave.add(new ColorSetting.Builder()
        .name("outer-color").description("Color of the outer edge.")
        .defaultValue(new SettingColor(170, 90, 255, 255)).visible(wave::get).build());
    private final Setting<Integer> waveAlpha = sgWave.add(new IntSetting.Builder()
        .name("opacity").description("How solid the circle is (0-255).")
        .defaultValue(200).range(10, 255).sliderRange(30, 255).visible(wave::get).build());
    private final Setting<Boolean> waveRainbow = sgWave.add(new BoolSetting.Builder()
        .name("rainbow").description("Rainbow circle instead of the two colors.").defaultValue(false).visible(wave::get).build());

    // -------- explosion
    private final Setting<Boolean> burst = sgBurst.add(new BoolSetting.Builder()
        .name("explosion").description("Sparks fly out when the circle collapses.").defaultValue(true).build());
    private final Setting<Integer> count = sgBurst.add(new IntSetting.Builder()
        .name("particles").description("Number of sparks.")
        .defaultValue(110).range(10, 600).sliderRange(20, 400).visible(burst::get).build());
    private final Setting<Double> speed = sgBurst.add(new DoubleSetting.Builder()
        .name("speed").description("How fast the sparks fly out (blocks per second).")
        .defaultValue(9.0).range(1.0, 40.0).sliderRange(2.0, 25.0).visible(burst::get).build());
    private final Setting<Double> size = sgBurst.add(new DoubleSetting.Builder()
        .name("size").description("Size of a spark in blocks.")
        .defaultValue(0.16).range(0.02, 1.0).sliderRange(0.04, 0.6).visible(burst::get).build());
    private final Setting<Integer> life = sgBurst.add(new IntSetting.Builder()
        .name("lifetime").description("How long the sparks live, in milliseconds.")
        .defaultValue(1100).range(200, 5000).sliderRange(300, 3000).visible(burst::get).build());
    private final Setting<Double> gravity = sgBurst.add(new DoubleSetting.Builder()
        .name("gravity").description("How fast the sparks fall (0 = float).")
        .defaultValue(6.0).range(-10.0, 30.0).sliderRange(-5.0, 20.0).visible(burst::get).build());
    private final Setting<Double> drag = sgBurst.add(new DoubleSetting.Builder()
        .name("slow-down").description("How fast the sparks lose speed.")
        .defaultValue(1.4).range(0.0, 8.0).sliderRange(0.0, 5.0).visible(burst::get).build());
    private final Setting<SparkStyle> sparkStyle = sgBurst.add(new EnumSetting.Builder<SparkStyle>()
        .name("style").description("Spark = short streaks, Dot = round glowing dots.")
        .defaultValue(SparkStyle.Spark).visible(burst::get).build());
    private final Setting<SettingColor> sparkA = sgBurst.add(new ColorSetting.Builder()
        .name("color-start").description("Color of a new spark.")
        .defaultValue(new SettingColor(255, 245, 170, 255)).visible(burst::get).build());
    private final Setting<SettingColor> sparkB = sgBurst.add(new ColorSetting.Builder()
        .name("color-end").description("Color the spark fades to.")
        .defaultValue(new SettingColor(255, 70, 50, 255)).visible(burst::get).build());
    private final Setting<Boolean> sparkRainbow = sgBurst.add(new BoolSetting.Builder()
        .name("rainbow").description("Every spark gets its own rainbow color.").defaultValue(false).visible(burst::get).build());
    private final Setting<Boolean> flash = sgBurst.add(new BoolSetting.Builder()
        .name("flash").description("A bright glow at the moment of the explosion.").defaultValue(true).visible(burst::get).build());
    private final Setting<Boolean> burstRing = sgBurst.add(new BoolSetting.Builder()
        .name("shock-ring").description("A fast flat ring flies out with the explosion.").defaultValue(true).visible(burst::get).build());

    // -------- state
    private static final class Fx {
        final double x, y, z;
        final long start;
        final long seed;
        boolean burstDone;
        Fx(double x, double y, double z, long start, long seed) { this.x = x; this.y = y; this.z = z; this.start = start; this.seed = seed; }
    }

    private static final class Spark {
        double x, y, z, vx, vy, vz;
        double age, life, size;
        float hue;
    }

    private final List<Fx> effects = new ArrayList<>();
    private final List<Spark> sparks = new ArrayList<>();
    private final Map<Integer, Long> handled = new HashMap<>();
    private final Map<Integer, Long> attacked = new HashMap<>();
    private final Random random = new Random();

    // camera vectors for the current frame
    private double fx, fy, fz, rx, ry, rz, ux, uy, uz;

    public KillEffect() {
        super(BlockOutlinesAddon.COSMETICS, "kill-effect", "Lightning, a huge circle that collapses and an explosion of sparks whenever someone dies.");
    }

    @Override
    public void onActivate() {
        effects.clear();
        sparks.clear();
        handled.clear();
        attacked.clear();
    }

    // ------------------------------------------------------------------ detecting deaths

    @EventHandler
    private void onAttack(AttackEntityEvent event) {
        if (event.entity != null) attacked.put(event.entity.getId(), System.currentTimeMillis());
    }

    @EventHandler
    private void onPacket(PacketEvent.Receive event) {
        if (mc.world == null || !(event.packet instanceof EntityStatusS2CPacket p)) return;
        if (p.getStatus() != 3) return; // entity died
        Entity e = p.getEntity(mc.world);
        if (e != null) died(e);
    }

    /** Some servers do not send the death packet, so also look at players that have no health left. */
    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null || mc.player == null) return;
        long now = System.currentTimeMillis();
        handled.values().removeIf(t -> now - t > 5000);
        attacked.values().removeIf(t -> now - t > 6000);
        if (!players.get()) return;
        for (PlayerEntity pl : mc.world.getPlayers()) {
            if (pl.getHealth() <= 0f || pl.isDead()) died(pl);
        }
    }

    private void died(Entity e) {
        if (mc.player == null || !(e instanceof LivingEntity)) return;
        boolean isPlayer = e instanceof PlayerEntity;
        if (isPlayer) {
            if (!players.get()) return;
            if (e == mc.player && !includeSelf.get()) return;
        } else {
            if (!mobs.get() || !(e instanceof MobEntity)) return;
        }
        long now = System.currentTimeMillis();
        Long seen = handled.get(e.getId());
        if (seen != null && now - seen < 4000) return;
        handled.put(e.getId(), now);

        if (onlyMine.get()) {
            Long t = attacked.get(e.getId());
            if (t == null || now - t > 6000) return;
        }
        if (mc.player.squaredDistanceTo(e) > (double) range.get() * range.get()) return;

        effects.add(new Fx(e.getX(), e.getY(), e.getZ(), now, random.nextLong()));
        while (effects.size() > maxEffects.get()) effects.remove(0);

        if (bolt.get() && thunder.get() && volume.get() > 0.01) {
            float v = volume.get().floatValue();
            mc.world.playSoundClient(e.getX(), e.getY(), e.getZ(), SoundEvents.ENTITY_LIGHTNING_BOLT_THUNDER, SoundCategory.WEATHER, 4f * v, 0.9f + random.nextFloat() * 0.2f, false);
            mc.world.playSoundClient(e.getX(), e.getY(), e.getZ(), SoundEvents.ENTITY_LIGHTNING_BOLT_IMPACT, SoundCategory.WEATHER, 2f * v, 1.0f, false);
        }
    }

    // ------------------------------------------------------------------ drawing

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (effects.isEmpty() && sparks.isEmpty()) return;
        if (mc.gameRenderer == null) return;

        Camera cam = mc.gameRenderer.getCamera();
        double yaw = Math.toRadians(cam.getYaw()), pitch = Math.toRadians(cam.getPitch());
        fx = -Math.sin(yaw) * Math.cos(pitch); fy = -Math.sin(pitch); fz = Math.cos(yaw) * Math.cos(pitch);
        double rl = Math.max(1e-6, Math.sqrt(fz * fz + fx * fx));
        rx = -fz / rl; ry = 0; rz = fx / rl;
        ux = ry * fz - rz * fy; uy = rz * fx - rx * fz; uz = rx * fy - ry * fx;

        Renderer3D r = throughWalls.get() ? event.renderer : event.depthRenderer;
        long now = System.currentTimeMillis();
        double dt = Math.min(0.1, event.frameTime);

        long grow = growTime.get(), shrink = shrinkTime.get();
        long collapseEnd = wave.get() ? grow + shrink : 0;

        for (int i = effects.size() - 1; i >= 0; i--) {
            Fx f = effects.get(i);
            long age = now - f.start;

            if (bolt.get() && age < boltTime.get()) drawBolt(r, f, age);

            if (wave.get() && age < collapseEnd) drawCircle(r, f, age, grow, shrink);

            // the moment of collapse: sparks, flash and shock ring
            if (!f.burstDone && age >= collapseEnd) {
                f.burstDone = true;
                if (burst.get()) spawnSparks(f);
            }
            long burstAge = age - collapseEnd;
            if (burst.get() && burstAge >= 0) {
                if (flash.get() && burstAge < 260) drawFlash(r, f, burstAge);
                if (burstRing.get() && burstAge < 420) drawShockRing(r, f, burstAge);
            }

            long end = Math.max(collapseEnd + 450, boltTime.get());
            if (age > end) { effects.remove(i); }
        }

        updateSparks(r, dt);
    }

    // ---- lightning

    private void drawBolt(Renderer3D r, Fx f, long age) {
        double t = age / (double) boltTime.get();
        double fade = Math.pow(1.0 - t, 0.8);

        int frame = boltFlicker.get() ? (int) (age / 55) : 0;
        Random rnd = new Random(f.seed + frame * 7919L);
        double flick = boltFlicker.get() ? 0.55 + 0.45 * rnd.nextDouble() : 1.0;
        if (age < 60) flick = 1.0; // the first instant is always full bright

        double w = boltWidth.get();
        int segs = 26;
        double top = f.y + boltHeight.get();
        double[] px = new double[segs + 1], py = new double[segs + 1], pz = new double[segs + 1];
        double dx = 0, dz = 0;
        for (int k = 0; k <= segs; k++) {
            double h = k / (double) segs;
            px[k] = f.x + dx * (1 - h);
            pz[k] = f.z + dz * (1 - h);
            py[k] = top + (f.y - top) * h;
            double step = 1.5 * (1 - h * 0.6);
            dx += (rnd.nextDouble() - 0.5) * step * 1.6;
            dz += (rnd.nextDouble() - 0.5) * step * 1.6;
            if (k == segs) { px[k] = f.x; pz[k] = f.z; }
        }
        // pull the end exactly onto the spot: blend the last points
        for (int k = segs - 4; k < segs; k++) {
            double a = (k - (segs - 4)) / 4.0;
            px[k] = px[k] * (1 - a) + f.x * a;
            pz[k] = pz[k] * (1 - a) + f.z * a;
        }

        ribbon(r, px, py, pz, segs + 1, w * 3.2, boltGlow.get(), (int) (150 * fade * flick));
        ribbon(r, px, py, pz, segs + 1, w, boltCore.get(), (int) (255 * fade * flick));

        // side branches
        int branches = boltBranches.get();
        for (int b = 0; b < branches; b++) {
            int from = 5 + rnd.nextInt(Math.max(1, segs - 9));
            int bs = 8;
            double[] bx = new double[bs + 1], by = new double[bs + 1], bz = new double[bs + 1];
            double ang = rnd.nextDouble() * Math.PI * 2;
            double len = 5 + rnd.nextDouble() * 9;
            bx[0] = px[from]; by[0] = py[from]; bz[0] = pz[from];
            for (int k = 1; k <= bs; k++) {
                double stepLen = len / bs;
                ang += (rnd.nextDouble() - 0.5) * 0.9;
                bx[k] = bx[k - 1] + Math.cos(ang) * stepLen;
                bz[k] = bz[k - 1] + Math.sin(ang) * stepLen;
                by[k] = by[k - 1] - stepLen * (0.55 + rnd.nextDouble() * 0.5);
            }
            double bw = w * 0.55;
            ribbon(r, bx, by, bz, bs + 1, bw * 3.0, boltGlow.get(), (int) (110 * fade * flick));
            ribbon(r, bx, by, bz, bs + 1, bw, boltCore.get(), (int) (220 * fade * flick));
        }

        // flash where it hits the ground
        if (age < 220) {
            double a = 1.0 - age / 220.0;
            glow(r, f.x, f.y + 0.3, f.z, 2.6 + 1.2 * (1 - a), boltGlow.get(), (int) (200 * a));
        }
    }

    /** A strip that always faces the camera along a list of points. */
    private void ribbon(Renderer3D r, double[] x, double[] y, double[] z, int n, double width, SettingColor col, int alpha) {
        if (alpha <= 0) return;
        Color c = new Color(col.r, col.g, col.b, Math.max(0, Math.min(255, alpha)));
        Color edge = new Color(col.r, col.g, col.b, 0);
        double[] sx = new double[n], sy = new double[n], sz = new double[n];
        for (int i = 0; i < n; i++) {
            int a = Math.max(0, i - 1), b = Math.min(n - 1, i + 1);
            double dx = x[b] - x[a], dy = y[b] - y[a], dz = z[b] - z[a];
            // sideways = direction x camera forward
            double ssx = dy * fz - dz * fy, ssy = dz * fx - dx * fz, ssz = dx * fy - dy * fx;
            double l = Math.max(1e-6, Math.sqrt(ssx * ssx + ssy * ssy + ssz * ssz));
            sx[i] = ssx / l * width * 0.5; sy[i] = ssy / l * width * 0.5; sz[i] = ssz / l * width * 0.5;
        }
        for (int i = 0; i < n - 1; i++) {
            int j = i + 1;
            // middle of the ribbon is solid, the two edges are clear: soft glow look
            quad(r, x[i] - sx[i], y[i] - sy[i], z[i] - sz[i], x[i], y[i], z[i], x[j], y[j], z[j], x[j] - sx[j], y[j] - sy[j], z[j] - sz[j], edge, c, c, edge);
            quad(r, x[i], y[i], z[i], x[i] + sx[i], y[i] + sy[i], z[i] + sz[i], x[j] + sx[j], y[j] + sy[j], z[j] + sz[j], x[j], y[j], z[j], c, edge, edge, c);
        }
    }

    /** A soft round glow facing the camera. */
    private void glow(Renderer3D r, double x, double y, double z, double rad, SettingColor col, int alpha) {
        if (alpha <= 0) return;
        Color c = new Color(col.r, col.g, col.b, Math.min(255, alpha));
        Color z0 = new Color(col.r, col.g, col.b, 0);
        int n = 14;
        for (int i = 0; i < n; i++) {
            double a0 = Math.PI * 2 * i / n, a1 = Math.PI * 2 * (i + 1) / n;
            double c0 = Math.cos(a0) * rad, s0 = Math.sin(a0) * rad, c1 = Math.cos(a1) * rad, s1 = Math.sin(a1) * rad;
            quad(r, x, y, z, x, y, z,
                x + rx * c1 + ux * s1, y + ry * c1 + uy * s1, z + rz * c1 + uz * s1,
                x + rx * c0 + ux * s0, y + ry * c0 + uy * s0, z + rz * c0 + uz * s0,
                c, c, z0, z0);
        }
    }

    // ---- circle

    private Color waveColor(double mix, int alpha, double seconds, double hue) {
        if (waveRainbow.get()) return CosmeticUtil.hsv((float) (seconds * 0.4 + hue), 0.7f, 1f, alpha);
        return CosmeticUtil.lerp(new Color(waveInner.get()), new Color(waveOuter.get()), mix, alpha);
    }

    private void drawCircle(Renderer3D r, Fx f, long age, long grow, long shrink) {
        double rad, glowUp;
        if (age < grow) {
            double u = age / (double) grow;
            rad = radius.get() * (1.0 - Math.pow(1.0 - u, 3.0)); // quick start, slows down near the end
            glowUp = 0.55 + 0.25 * u;
        } else {
            double u = (age - grow) / (double) shrink;
            double e = u * u * u; // sudden: slow first, then very fast
            rad = radius.get() * (1.0 - e) + 0.12 * e;
            glowUp = 0.8 + 0.2 * u; // brightens as it squeezes together
        }
        rad = Math.max(0.05, rad);
        int alpha = (int) Math.min(255, waveAlpha.get() * glowUp);
        double seconds = age / 1000.0;
        double y = f.y + 0.06;

        Shape s = shape.get();
        if (s == Shape.Flat || s == Shape.Both) {
            flatRing(r, f.x, y, f.z, rad, Math.min(ringWidth.get(), rad), alpha, seconds);
            // faint disc inside
            int n = 40;
            Color centre = waveColor(0.0, alpha / 6, seconds, 0), edge = waveColor(0.6, alpha / 4, seconds, 0.3);
            for (int i = 0; i < n; i++) {
                double a0 = Math.PI * 2 * i / n, a1 = Math.PI * 2 * (i + 1) / n;
                quad(r, f.x, y, f.z, f.x, y, f.z, f.x + Math.cos(a1) * rad, y, f.z + Math.sin(a1) * rad, f.x + Math.cos(a0) * rad, y, f.z + Math.sin(a0) * rad, centre, centre, edge, edge);
            }
        }
        if (s == Shape.Sphere || s == Shape.Both) sphere(r, f.x, f.y + rad * 0.5 + 0.2, f.z, rad * 0.9, (int) (alpha * 0.32), seconds);
    }

    private void flatRing(Renderer3D r, double x, double y, double z, double rad, double width, int alpha, double seconds) {
        int n = 64;
        double inner = Math.max(0.01, rad - width);
        for (int i = 0; i < n; i++) {
            double a0 = Math.PI * 2 * i / n, a1 = Math.PI * 2 * (i + 1) / n;
            double c0 = Math.cos(a0), s0 = Math.sin(a0), c1 = Math.cos(a1), s1 = Math.sin(a1);
            double hue = i / (double) n;
            Color outer = waveColor(1.0, alpha, seconds, hue);
            Color in = waveColor(0.0, 0, seconds, hue); // inner side fades out: glowing band
            quad(r, x + c0 * rad, y, z + s0 * rad, x + c1 * rad, y, z + s1 * rad, x + c1 * inner, y, z + s1 * inner, x + c0 * inner, y, z + s0 * inner, outer, outer, in, in);
        }
    }

    private void sphere(Renderer3D r, double x, double y, double z, double rad, int alpha, double seconds) {
        if (alpha <= 0) return;
        int lat = 10, lon = 24;
        for (int i = 0; i < lat; i++) {
            double p0 = Math.PI * (-0.5 + i / (double) lat), p1 = Math.PI * (-0.5 + (i + 1) / (double) lat);
            double y0 = Math.sin(p0) * rad, y1 = Math.sin(p1) * rad, r0 = Math.cos(p0) * rad, r1 = Math.cos(p1) * rad;
            Color ca = waveColor(i / (double) lat, alpha, seconds, i / (double) lat);
            Color cb = waveColor((i + 1) / (double) lat, alpha, seconds, (i + 1) / (double) lat);
            for (int j = 0; j < lon; j++) {
                double a0 = Math.PI * 2 * j / lon, a1 = Math.PI * 2 * (j + 1) / lon;
                quad(r, x + Math.cos(a0) * r0, y + y0, z + Math.sin(a0) * r0,
                    x + Math.cos(a1) * r0, y + y0, z + Math.sin(a1) * r0,
                    x + Math.cos(a1) * r1, y + y1, z + Math.sin(a1) * r1,
                    x + Math.cos(a0) * r1, y + y1, z + Math.sin(a0) * r1, ca, ca, cb, cb);
            }
        }
    }

    // ---- explosion

    private void drawFlash(Renderer3D r, Fx f, long burstAge) {
        double a = 1.0 - burstAge / 260.0;
        glow(r, f.x, f.y + 0.6, f.z, 1.5 + 3.5 * (1 - a), sparkA.get(), (int) (255 * a * a));
    }

    private void drawShockRing(Renderer3D r, Fx f, long burstAge) {
        double u = burstAge / 420.0;
        double rad = 0.3 + radius.get() * 0.7 * (1.0 - Math.pow(1.0 - u, 3.0));
        int alpha = (int) (230 * Math.pow(1.0 - u, 1.2));
        flatRing(r, f.x, f.y + 0.08, f.z, rad, Math.min(0.5, rad), alpha, burstAge / 1000.0);
    }

    private void spawnSparks(Fx f) {
        int n = count.get();
        double sp = speed.get();
        for (int i = 0; i < n; i++) {
            Spark s = new Spark();
            s.x = f.x; s.y = f.y + 0.6; s.z = f.z;
            // random direction on a sphere, a bit biased upward
            double u = random.nextDouble() * 2 - 1, a = random.nextDouble() * Math.PI * 2;
            double h = Math.sqrt(1 - u * u);
            double v = sp * (0.35 + 0.65 * random.nextDouble());
            s.vx = Math.cos(a) * h * v; s.vz = Math.sin(a) * h * v; s.vy = (u * 0.8 + 0.25) * v;
            s.life = life.get() / 1000.0 * (0.6 + 0.4 * random.nextDouble());
            s.size = size.get() * (0.6 + 0.8 * random.nextDouble());
            s.hue = random.nextFloat();
            sparks.add(s);
        }
        while (sparks.size() > 2500) sparks.remove(0);
    }

    private void updateSparks(Renderer3D r, double dt) {
        if (sparks.isEmpty()) return;
        double g = gravity.get(), slow = Math.exp(-drag.get() * dt);
        boolean dots = sparkStyle.get() == SparkStyle.Dot;
        SettingColor ca = sparkA.get(), cb = sparkB.get();

        for (int i = sparks.size() - 1; i >= 0; i--) {
            Spark s = sparks.get(i);
            s.age += dt;
            if (s.age >= s.life) { sparks.remove(i); continue; }

            s.vy -= g * dt;
            s.vx *= slow; s.vy *= slow; s.vz *= slow;
            s.x += s.vx * dt; s.y += s.vy * dt; s.z += s.vz * dt;

            double t = s.age / s.life;
            double fade = Math.pow(1.0 - t, 1.2);
            double sz = s.size * (1.0 - 0.6 * t);
            Color core, glowC;
            if (sparkRainbow.get()) {
                core = CosmeticUtil.hsv(s.hue, 0.65f, 1f, (int) (255 * fade));
                glowC = CosmeticUtil.hsv(s.hue, 0.8f, 1f, (int) (90 * fade));
            } else {
                int rr = (int) (ca.r + (cb.r - ca.r) * t), gg = (int) (ca.g + (cb.g - ca.g) * t), bb = (int) (ca.b + (cb.b - ca.b) * t);
                core = new Color(rr, gg, bb, (int) (255 * fade));
                glowC = new Color(rr, gg, bb, (int) (90 * fade));
            }

            double speedNow = Math.sqrt(s.vx * s.vx + s.vy * s.vy + s.vz * s.vz);
            if (dots || speedNow < 0.4) {
                billboard(r, s.x, s.y, s.z, sz, core);
                billboard(r, s.x, s.y, s.z, sz * 2.6, glowC);
            } else {
                // a short streak along the way the spark flies
                double len = Math.min(1.6, speedNow * 0.06) + sz;
                double dx = s.vx / speedNow, dy = s.vy / speedNow, dz = s.vz / speedNow;
                double tx = s.x - dx * len, ty = s.y - dy * len, tz = s.z - dz * len;
                double sx = dy * fz - dz * fy, sy = dz * fx - dx * fz, sz2 = dx * fy - dy * fx;
                double l = Math.max(1e-6, Math.sqrt(sx * sx + sy * sy + sz2 * sz2));
                double w = sz * 0.5 / l;
                Color tail = new Color(core.r, core.g, core.b, 0);
                quad(r, tx - sx * w, ty - sy * w, tz - sz2 * w, tx + sx * w, ty + sy * w, tz + sz2 * w,
                    s.x + sx * w, s.y + sy * w, s.z + sz2 * w, s.x - sx * w, s.y - sy * w, s.z - sz2 * w, tail, tail, core, core);
                billboard(r, s.x, s.y, s.z, sz * 2.2, glowC);
            }
        }
    }

    private void billboard(Renderer3D r, double x, double y, double z, double hs, Color c) {
        if (c.a <= 0) return;
        quad(r, x - rx * hs - ux * hs, y - ry * hs - uy * hs, z - rz * hs - uz * hs,
            x + rx * hs - ux * hs, y + ry * hs - uy * hs, z + rz * hs - uz * hs,
            x + rx * hs + ux * hs, y + ry * hs + uy * hs, z + rz * hs + uz * hs,
            x - rx * hs + ux * hs, y - ry * hs + uy * hs, z - rz * hs + uz * hs, c, c, c, c);
    }

    /**
     * Meteor's quad() takes the colors in the order top-left, top-right, bottom-right, bottom-left, and uses them for
     * the vertices 2, 3, 4, 1. This helper takes one color per vertex in the order the vertices are given.
     */
    private static void quad(Renderer3D r,
                             double x1, double y1, double z1, double x2, double y2, double z2,
                             double x3, double y3, double z3, double x4, double y4, double z4,
                             Color cv1, Color cv2, Color cv3, Color cv4) {
        r.quad(x1, y1, z1, x2, y2, z2, x3, y3, z3, x4, y4, z4, cv2, cv3, cv4, cv1);
    }
}
