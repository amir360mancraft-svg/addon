package com.blockoutlines.addon.modules;

import com.blockoutlines.addon.BlockOutlinesAddon;
import com.blockoutlines.addon.util.CosmeticUtil;
import meteordevelopment.meteorclient.events.entity.player.AttackEntityEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
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
import net.minecraft.block.BlockState;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.packet.s2c.play.EntityStatusS2CPacket;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;

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
    public enum RingPattern { Smooth, Hatch, Dashed, Pixels }
    public enum DiscPattern { None, Radar, Grid }
    public enum SphereStyle { Solid, Wireframe }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgBolt = settings.createGroup("Lightning");
    private final SettingGroup sgWave = settings.createGroup("Circle");
    private final SettingGroup sgBurst = settings.createGroup("Explosion");
    private final SettingGroup sgEpic = settings.createGroup("Epic extras");
    private final SettingGroup sgLook = settings.createGroup("Texture");
    private final SettingGroup sgScorch = settings.createGroup("Scorch marks");

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
        .defaultValue(0.22).range(0.02, 1.5).sliderRange(0.05, 0.8).visible(bolt::get).build());
    private final Setting<Integer> boltHeight = sgBolt.add(new IntSetting.Builder()
        .name("height").description("How high the bolt starts above the spot.")
        .defaultValue(70).range(10, 200).sliderRange(15, 120).visible(bolt::get).build());
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
        .defaultValue(170).range(10, 600).sliderRange(20, 400).visible(burst::get).build());
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

    // -------- epic extras
    private final Setting<Boolean> multiStrike = sgEpic.add(new BoolSetting.Builder()
        .name("triple-strike").description("Two more bolts strike right after the first one, close by.").defaultValue(true).build());
    private final Setting<Boolean> pillar = sgEpic.add(new BoolSetting.Builder()
        .name("light-pillar").description("A beam of light rises from the spot after the strike, and a bigger one at the explosion.").defaultValue(true).build());
    private final Setting<Boolean> runes = sgEpic.add(new BoolSetting.Builder()
        .name("rune-circle").description("A spinning magic star and inner circle on the ground while the circle grows.").defaultValue(true).build());
    private final Setting<Boolean> extraRings = sgEpic.add(new BoolSetting.Builder()
        .name("extra-rings").description("Two smaller rings inside the big one and a faint energy wall around it.").defaultValue(true).build());
    private final Setting<Boolean> arcs = sgEpic.add(new BoolSetting.Builder()
        .name("edge-lightning").description("Small lightning arcs crackle along the edge of the growing circle.").defaultValue(true).build());
    private final Setting<Boolean> embers = sgEpic.add(new BoolSetting.Builder()
        .name("rising-embers").description("Glowing embers float up after the explosion.").defaultValue(true).build());
    private final Setting<Boolean> screenFlash = sgEpic.add(new BoolSetting.Builder()
        .name("screen-flash").description("The screen flashes white for a moment when the lightning strikes (stronger when you are close).").defaultValue(true).build());
    private final Setting<Integer> flashStrength = sgEpic.add(new IntSetting.Builder()
        .name("flash-strength").description("How bright the screen flash is (0-255).")
        .defaultValue(150).range(10, 255).sliderRange(30, 255).visible(screenFlash::get).build());
    private final Setting<Boolean> boom = sgEpic.add(new BoolSetting.Builder()
        .name("explosion-sound").description("A deep boom at the explosion and a rumble after the thunder.").defaultValue(true).build());

    // -------- texture
    private final Setting<RingPattern> ringPattern = sgLook.add(new EnumSetting.Builder<RingPattern>()
        .name("ring-pattern").description("Smooth = plain glow, Hatch = diagonal stripes, Dashed = broken ring, Pixels = flickering pixel blocks.")
        .defaultValue(RingPattern.Hatch).build());
    private final Setting<DiscPattern> discPattern = sgLook.add(new EnumSetting.Builder<DiscPattern>()
        .name("disc-pattern").description("Lines inside the circle: Radar = rings that sweep outward, Grid = rings and spokes.")
        .defaultValue(DiscPattern.Radar).build());
    private final Setting<SphereStyle> sphereStyle = sgLook.add(new EnumSetting.Builder<SphereStyle>()
        .name("ball-style").description("Solid = see-through ball, Wireframe = glowing grid lines.")
        .defaultValue(SphereStyle.Wireframe).build());
    private final Setting<Boolean> energyPulses = sgLook.add(new BoolSetting.Builder()
        .name("energy-pulses").description("Bright pulses run along the lightning and its branches.").defaultValue(true).build());
    private final Setting<Double> patternSpeed = sgLook.add(new DoubleSetting.Builder()
        .name("pattern-speed").description("How fast the patterns move.")
        .defaultValue(1.0).range(0.1, 5.0).sliderRange(0.2, 3.0).build());

    // -------- scorch marks
    private final Setting<Boolean> scorch = sgScorch.add(new BoolSetting.Builder()
        .name("scorch-marks").description("The blocks around the spot get a burnt, cracked look for a while (only on your screen).").defaultValue(true).build());
    private final Setting<Double> scorchRadius = sgScorch.add(new DoubleSetting.Builder()
        .name("radius").description("Size of the burnt area in blocks.")
        .defaultValue(5.0).range(1.5, 12.0).sliderRange(2.0, 9.0).visible(scorch::get).build());
    private final Setting<Integer> scorchTime = sgScorch.add(new IntSetting.Builder()
        .name("duration").description("How many seconds the marks stay (they fade at the end).")
        .defaultValue(14).range(2, 120).sliderRange(4, 40).visible(scorch::get).build());
    private final Setting<Integer> scorchAlpha = sgScorch.add(new IntSetting.Builder()
        .name("darkness").description("How dark the burnt ground is (0-255).")
        .defaultValue(215).range(20, 255).sliderRange(60, 255).visible(scorch::get).build());
    private final Setting<Boolean> cracks = sgScorch.add(new BoolSetting.Builder()
        .name("glowing-cracks").description("Cracks run out from the middle. They glow at first and cool down.").defaultValue(true).visible(scorch::get).build());
    private final Setting<SettingColor> scorchColor = sgScorch.add(new ColorSetting.Builder()
        .name("char-color").description("Color of the burnt ground.")
        .defaultValue(new SettingColor(22, 18, 16, 255)).visible(scorch::get).build());
    private final Setting<SettingColor> crackColor = sgScorch.add(new ColorSetting.Builder()
        .name("crack-color").description("Color of the glowing cracks.")
        .defaultValue(new SettingColor(255, 120, 35, 255)).visible(() -> scorch.get() && cracks.get()).build());

    // -------- state
    private static final class Fx {
        final double x, y, z;
        final long start;
        final long seed;
        boolean burstDone, rumbled;
        Fx(double x, double y, double z, long start, long seed) { this.x = x; this.y = y; this.z = z; this.start = start; this.seed = seed; }
    }

    private static final class Spark {
        double x, y, z, vx, vy, vz;
        double age, life, size;
        float hue;
        double gMul = 1, dragMul = 1;
    }

    private static final class Scorch {
        long start;
        int n;
        double[] x, y, z;
        float[] base;      // base opacity 0..1
        boolean[] crack;
        float[] heat;      // 1 = glows a lot (middle), 0 = plain
        Color[] col;
        int total;         // lifetime ms
    }

    private final List<Scorch> scorches = new ArrayList<>();
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
        scorches.clear();
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

        Fx nf = new Fx(e.getX(), e.getY(), e.getZ(), now, random.nextLong());
        effects.add(nf);
        if (scorch.get()) buildScorch(nf);
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
        if (effects.isEmpty() && sparks.isEmpty() && scorches.isEmpty()) return;
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

        drawScorches(event.depthRenderer, now);

        long grow = growTime.get(), shrink = shrinkTime.get();
        long collapseEnd = wave.get() ? grow + shrink : 0;

        for (int i = effects.size() - 1; i >= 0; i--) {
            Fx f = effects.get(i);
            long age = now - f.start;

            if (bolt.get()) {
                if (age < boltTime.get()) drawBolt(r, f, age, 0, 0, 0, 1.0);
                if (multiStrike.get()) {
                    if (age > 90 && age - 90 < boltTime.get()) drawBolt(r, f, age - 90, 3.6, -2.4, 11, 0.7);
                    if (age > 170 && age - 170 < boltTime.get()) drawBolt(r, f, age - 170, -3.1, 3.3, 23, 0.7);
                }
            }
            if (pillar.get() && age < 900) drawPillar(r, f, age);
            if (boom.get() && !f.rumbled && age > 380) {
                f.rumbled = true;
                if (mc.world != null) mc.world.playSoundClient(f.x, f.y, f.z, SoundEvents.ENTITY_LIGHTNING_BOLT_THUNDER, SoundCategory.WEATHER, 3f * volume.get().floatValue(), 0.55f, false);
            }

            if (wave.get() && age < collapseEnd) drawCircle(r, f, age, grow, shrink);

            // the moment of collapse: sparks, flash and shock ring
            if (!f.burstDone && age >= collapseEnd) {
                f.burstDone = true;
                if (burst.get()) spawnSparks(f);
                if (boom.get() && mc.world != null) mc.world.playSoundClient(f.x, f.y, f.z, SoundEvents.ENTITY_LIGHTNING_BOLT_IMPACT, SoundCategory.WEATHER, 3f * volume.get().floatValue(), 0.5f, false);
            }
            long burstAge = age - collapseEnd;
            if (burst.get() && burstAge >= 0) {
                if (flash.get() && burstAge < 260) drawFlash(r, f, burstAge);
                if (burstRing.get() && burstAge < 420) drawShockRing(r, f, burstAge);
                if (pillar.get() && burstAge < 450) drawBigBeam(r, f, burstAge);
            }

            long end = Math.max(collapseEnd + 500, Math.max(boltTime.get() + 200L, 900L));
            if (age > end) { effects.remove(i); }
        }

        updateSparks(r, dt);
    }

    // ---- lightning

    private void drawBolt(Renderer3D r, Fx f, long age, double ox, double oz, long seedAdd, double widthScale) {
        double t = age / (double) boltTime.get();
        double fade = Math.pow(1.0 - t, 0.8);

        int frame = boltFlicker.get() ? (int) (age / 55) : 0;
        Random rnd = new Random(f.seed + seedAdd + frame * 7919L);
        double bx0 = f.x + ox, bz0 = f.z + oz;
        double flick = boltFlicker.get() ? 0.55 + 0.45 * rnd.nextDouble() : 1.0;
        if (age < 60) flick = 1.0; // the first instant is always full bright

        double w = boltWidth.get() * widthScale;
        int segs = 26;
        double top = f.y + boltHeight.get();
        double[] px = new double[segs + 1], py = new double[segs + 1], pz = new double[segs + 1];
        double dx = 0, dz = 0;
        for (int k = 0; k <= segs; k++) {
            double h = k / (double) segs;
            px[k] = bx0 + dx * (1 - h);
            pz[k] = bz0 + dz * (1 - h);
            py[k] = top + (f.y - top) * h;
            double step = 1.5 * (1 - h * 0.6);
            dx += (rnd.nextDouble() - 0.5) * step * 1.6;
            dz += (rnd.nextDouble() - 0.5) * step * 1.6;
            if (k == segs) { px[k] = bx0; pz[k] = bz0; }
        }
        // pull the end exactly onto the spot: blend the last points
        for (int k = segs - 4; k < segs; k++) {
            double a = (k - (segs - 4)) / 4.0;
            px[k] = px[k] * (1 - a) + bx0 * a;
            pz[k] = pz[k] * (1 - a) + bz0 * a;
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
            glow(r, bx0, f.y + 0.3, bz0, (2.6 + 1.2 * (1 - a)) * widthScale, boltGlow.get(), (int) (200 * a));
        }
    }

    /** A strip that always faces the camera along a list of points. */
    private void ribbon(Renderer3D r, double[] x, double[] y, double[] z, int n, double width, SettingColor col, int alpha) {
        ribbonRGB(r, x, y, z, n, width, col.r, col.g, col.b, alpha, energyPulses.get());
    }

    private void ribbonRGB(Renderer3D r, double[] x, double[] y, double[] z, int n, double width, int cr, int cg, int cb, int alpha, boolean pulse) {
        if (alpha <= 0) return;
        alpha = Math.min(255, alpha);
        double phase = System.nanoTime() / 1.0e9 * patternSpeed.get() * 11.0;
        Color[] mid = new Color[n];
        Color[] edge = new Color[n];
        double[] sx = new double[n], sy = new double[n], sz = new double[n];
        for (int i = 0; i < n; i++) {
            double m = pulse ? 0.4 + 0.6 * (0.5 + 0.5 * Math.sin(i * 1.15 - phase)) : 1.0;
            mid[i] = new Color(cr, cg, cb, (int) (alpha * m));
            edge[i] = new Color(cr, cg, cb, 0);
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
            quad(r, x[i] - sx[i], y[i] - sy[i], z[i] - sz[i], x[i], y[i], z[i], x[j], y[j], z[j], x[j] - sx[j], y[j] - sy[j], z[j] - sz[j], edge[i], mid[i], mid[j], edge[j]);
            quad(r, x[i], y[i], z[i], x[i] + sx[i], y[i] + sy[i], z[i] + sz[i], x[j] + sx[j], y[j] + sy[j], z[j] + sz[j], x[j], y[j], z[j], mid[i], edge[i], edge[j], mid[j]);
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

        boolean flat = s == Shape.Flat || s == Shape.Both;
        if (flat && extraRings.get()) {
            flatRing(r, f.x, y + 0.01, f.z, rad * 0.66, Math.min(ringWidth.get() * 0.6, rad * 0.66), (int) (alpha * 0.8), seconds + 0.2);
            flatRing(r, f.x, y + 0.02, f.z, rad * 0.33, Math.min(ringWidth.get() * 0.4, rad * 0.33), (int) (alpha * 0.7), seconds + 0.4);
            energyWall(r, f.x, y, f.z, rad, Math.min(2.2, 0.5 + rad * 0.18), (int) (alpha * 0.28), seconds);
        }
        if (flat) drawDiscPattern(r, f, y + 0.015, rad, alpha, seconds);
        if (flat && runes.get()) drawRunes(r, f, y + 0.03, rad, alpha, age);
        if (flat && arcs.get() && age < grow) drawEdgeArcs(r, f, y, rad, age);
    }

    private static double hash(int a, int b, int c) {
        long h = a * 0x9E3779B97F4A7C15L ^ (b * 0xC2B2AE3D27D4EB4FL) ^ (c * 0x165667B19E3779F9L);
        h ^= h >>> 29; h *= 0xBF58476D1CE4E5B9L; h ^= h >>> 32;
        return (h & 0xFFFFFFL) / (double) 0x1000000;
    }

    /** How strong one cell of the ring is (1 = full) for the picked pattern. */
    private double patternMul(int i, int row, int tick) {
        switch (ringPattern.get()) {
            case Hatch: return ((i + row * 2 + tick) & 3) < 2 ? 1.0 : 0.12;
            case Dashed: return ((i + tick / 2) & 3) < 3 ? 1.0 : 0.0;
            case Pixels: {
                double h = hash(i, row, tick / 2);
                return h < 0.3 ? 0.05 : 0.4 + 0.6 * h;
            }
            default: return 1.0;
        }
    }

    private void flatRing(Renderer3D r, double x, double y, double z, double rad, double width, int alpha, double seconds) {
        int n = Math.max(16, Math.min(72, ((int) (rad * 14) / 4) * 4));
        final int rows = 3;
        double inner = Math.max(0.01, rad - width);
        int tick = (int) (seconds * patternSpeed.get() * 6);
        for (int i = 0; i < n; i++) {
            double a0 = Math.PI * 2 * i / n, a1 = Math.PI * 2 * (i + 1) / n;
            double c0 = Math.cos(a0), s0 = Math.sin(a0), c1 = Math.cos(a1), s1 = Math.sin(a1);
            double hue = i / (double) n;
            for (int row = 0; row < rows; row++) {
                double ra = inner + (rad - inner) * row / rows, rb = inner + (rad - inner) * (row + 1) / rows;
                double mul = patternMul(i, row, tick);
                if (mul <= 0.01) continue;
                // the inner side fades out so the band glows; the pattern multiplies on top
                Color ci = waveColor(row / (double) rows, (int) (alpha * (row / (double) rows) * mul), seconds, hue);
                Color co = waveColor((row + 1) / (double) rows, (int) (alpha * ((row + 1) / (double) rows) * mul), seconds, hue);
                quad(r, x + c0 * rb, y, z + s0 * rb, x + c1 * rb, y, z + s1 * rb, x + c1 * ra, y, z + s1 * ra, x + c0 * ra, y, z + s0 * ra, co, co, ci, ci);
            }
        }
    }

    private void sphere(Renderer3D r, double x, double y, double z, double rad, int alpha, double seconds) {
        if (alpha <= 0) return;
        if (sphereStyle.get() == SphereStyle.Wireframe) {
            wireSphere(r, x, y, z, rad, alpha, seconds);
            alpha = alpha / 3; // faint fill under the lines
        }
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

    /** Glowing grid lines (latitudes and meridians) instead of a solid ball. */
    private void wireSphere(Renderer3D r, double x, double y, double z, double rad, int alpha, double seconds) {
        int lat = 10, lon = 12, seg = 28;
        double lw = 0.05 + rad * 0.007;
        double[] px = new double[seg + 1], py = new double[seg + 1], pz = new double[seg + 1];
        int la = Math.min(255, alpha * 3);
        for (int i = 1; i < lat; i++) {
            double p = Math.PI * (-0.5 + i / (double) lat);
            double yy = Math.sin(p) * rad, rr = Math.cos(p) * rad;
            for (int k = 0; k <= seg; k++) {
                double a = Math.PI * 2 * k / seg + seconds * 0.25 * (i % 2 == 0 ? 1 : -1);
                px[k] = x + Math.cos(a) * rr; py[k] = y + yy; pz[k] = z + Math.sin(a) * rr;
            }
            Color c = waveColor(i / (double) lat, 255, seconds, i / (double) lat);
            ribbonRGB(r, px, py, pz, seg + 1, lw, c.r, c.g, c.b, la, false);
        }
        double[] mx = new double[lat + 1], my = new double[lat + 1], mz = new double[lat + 1];
        for (int j = 0; j < lon; j++) {
            double a = Math.PI * 2 * j / lon + seconds * 0.18;
            for (int k = 0; k <= lat; k++) {
                double p = Math.PI * (-0.5 + k / (double) lat);
                mx[k] = x + Math.cos(a) * Math.cos(p) * rad; my[k] = y + Math.sin(p) * rad; mz[k] = z + Math.sin(a) * Math.cos(p) * rad;
            }
            Color c = waveColor(0.5, 255, seconds, j / (double) lon);
            ribbonRGB(r, mx, my, mz, lat + 1, lw, c.r, c.g, c.b, la, false);
        }
    }

    /** Rings that sweep outward and, for Grid, spokes: a radar / tech look inside the circle. */
    private void drawDiscPattern(Renderer3D r, Fx f, double y, double rad, int alpha, double seconds) {
        DiscPattern dp = discPattern.get();
        if (dp == DiscPattern.None || rad < 0.6) return;
        double w = 0.05 + rad * 0.004;
        double step = Math.max(1.0, rad / 6.0);
        double sweep = (seconds * patternSpeed.get() * 1.6) % 1.0;
        int rings = (int) Math.ceil(rad / step) + 1;
        for (int k = 0; k < rings; k++) {
            double rr = (k + sweep) * step;
            if (rr >= rad - 0.15 || rr < 0.2) continue;
            int a = (int) (alpha * 0.55 * (1.0 - rr / rad));
            Color c = waveColor(rr / rad, a, seconds, 0.2);
            int n = Math.max(16, Math.min(64, (int) (rr * 10)));
            for (int i = 0; i < n; i++) {
                double a0 = Math.PI * 2 * i / n, a1 = Math.PI * 2 * (i + 1) / n;
                groundLine(r, f.x + Math.cos(a0) * rr, f.z + Math.sin(a0) * rr, f.x + Math.cos(a1) * rr, f.z + Math.sin(a1) * rr, y, w, c);
            }
        }
        if (dp == DiscPattern.Grid) {
            double spin = seconds * 0.15;
            Color c = waveColor(0.4, (int) (alpha * 0.4), seconds, 0.5);
            for (int k = 0; k < 16; k++) {
                double a = spin + Math.PI * 2 * k / 16;
                groundLine(r, f.x + Math.cos(a) * 0.3, f.z + Math.sin(a) * 0.3, f.x + Math.cos(a) * rad * 0.96, f.z + Math.sin(a) * rad * 0.96, y, w, c);
            }
        }
    }

    // ---- scorch marks (burnt ground made of little pixels)

    private double surfaceY(int bx, int bz, int fy) {
        BlockPos.Mutable p = new BlockPos.Mutable();
        for (int yy = fy + 1; yy >= fy - 4; yy--) {
            p.set(bx, yy, bz);
            BlockState st = mc.world.getBlockState(p);
            if (!st.isSolidBlock(mc.world, p)) continue;
            p.set(bx, yy + 1, bz);
            BlockState above = mc.world.getBlockState(p);
            if (above.isSolidBlock(mc.world, p) || !above.getFluidState().isEmpty()) return Double.NaN;
            return yy + 1;
        }
        return Double.NaN;
    }

    private void buildScorch(Fx f) {
        if (mc.world == null) return;
        final int PX = 4; // pixels per block side
        double R = scorchRadius.get();
        int fy = (int) Math.floor(f.y);
        int x0 = (int) Math.floor(f.x - R), x1 = (int) Math.floor(f.x + R);
        int z0 = (int) Math.floor(f.z - R), z1 = (int) Math.floor(f.z + R);
        Random rnd = new Random(f.seed ^ 0x5DEECE66DL);

        // cracks: random walks that start in the middle, remembered as pixel cells
        java.util.Set<Long> crackCells = new java.util.HashSet<>();
        if (cracks.get()) {
            int nc = 6 + rnd.nextInt(4);
            for (int c = 0; c < nc; c++) {
                double a = rnd.nextDouble() * Math.PI * 2;
                double px = f.x, pz = f.z;
                double len = R * (0.55 + 0.45 * rnd.nextDouble());
                int steps = (int) (len / 0.2);
                for (int st = 0; st < steps; st++) {
                    a += (rnd.nextDouble() - 0.5) * 0.7;
                    px += Math.cos(a) * 0.2; pz += Math.sin(a) * 0.2;
                    crackCells.add(((long) Math.floor(px * PX) << 32) ^ ((long) Math.floor(pz * PX) & 0xFFFFFFFFL));
                    if (st > 4 && rnd.nextDouble() < 0.06 && steps - st > 6) {
                        // a short side crack
                        double ba = a + (rnd.nextBoolean() ? 1 : -1) * (0.6 + rnd.nextDouble() * 0.6);
                        double bx = px, bz = pz;
                        for (int q = 0; q < 6; q++) {
                            ba += (rnd.nextDouble() - 0.5) * 0.7;
                            bx += Math.cos(ba) * 0.2; bz += Math.sin(ba) * 0.2;
                            crackCells.add(((long) Math.floor(bx * PX) << 32) ^ ((long) Math.floor(bz * PX) & 0xFFFFFFFFL));
                        }
                    }
                }
            }
        }

        List<double[]> pix = new ArrayList<>(); // x, y, z, base, crack(0/1), heat
        for (int bx = x0; bx <= x1; bx++) {
            for (int bz = z0; bz <= z1; bz++) {
                double sy = surfaceY(bx, bz, fy);
                if (Double.isNaN(sy)) continue;
                for (int i = 0; i < PX; i++) {
                    for (int j = 0; j < PX; j++) {
                        double cx = bx + (i + 0.5) / PX, cz = bz + (j + 0.5) / PX;
                        double dx = cx - f.x, dz = cz - f.z;
                        double d = Math.sqrt(dx * dx + dz * dz) / R;
                        int gi = bx * PX + i, gj = bz * PX + j;
                        double n1 = hash(gi, gj, 17), n2 = hash(gi, gj, 91);
                        boolean isCrack = crackCells.contains(((long) gi << 32) ^ (gj & 0xFFFFFFFFL));
                        if (!isCrack && d + (n1 - 0.5) * 0.55 > 1.0) continue;
                        if (isCrack && d > 1.05) continue;
                        double base = isCrack ? 1.0 : Math.pow(Math.max(0, 1.0 - d), 0.7) * (0.55 + 0.45 * n2);
                        double heat = isCrack ? Math.max(0.35, 1.0 - d) : (d < 0.16 ? 1.0 - d / 0.16 : 0);
                        pix.add(new double[]{cx, sy + 0.02, cz, base, isCrack ? 1 : 0, heat, n1});
                    }
                }
            }
        }
        if (pix.isEmpty()) return;
        if (pix.size() > 9000) pix = pix.subList(0, 9000);

        Scorch sc = new Scorch();
        sc.start = System.currentTimeMillis();
        sc.total = scorchTime.get() * 1000;
        sc.n = pix.size();
        sc.x = new double[sc.n]; sc.y = new double[sc.n]; sc.z = new double[sc.n];
        sc.base = new float[sc.n]; sc.crack = new boolean[sc.n]; sc.heat = new float[sc.n]; sc.col = new Color[sc.n];
        SettingColor ch = scorchColor.get();
        for (int i = 0; i < sc.n; i++) {
            double[] p = pix.get(i);
            sc.x[i] = p[0]; sc.y[i] = p[1]; sc.z[i] = p[2];
            sc.base[i] = (float) p[3]; sc.crack[i] = p[4] > 0; sc.heat[i] = (float) p[5];
            double v = (p[6] - 0.5) * 22; // little brightness changes so the char is not flat
            boolean ash = p[6] > 0.93;     // a few light ash pixels
            int rr = (int) (ch.r + v + (ash ? 38 : 0)), gg = (int) (ch.g + v + (ash ? 36 : 0)), bb = (int) (ch.b + v + (ash ? 34 : 0));
            sc.col[i] = new Color(Math.max(0, Math.min(255, rr)), Math.max(0, Math.min(255, gg)), Math.max(0, Math.min(255, bb)), 255);
        }
        scorches.add(sc);
        while (scorches.size() > 6) scorches.remove(0);
    }

    private void drawScorches(Renderer3D r, long now) {
        if (scorches.isEmpty()) return;
        SettingColor glowC = crackColor.get();
        double dark = scorchAlpha.get() / 255.0;
        final double h = 0.125;
        for (int si = scorches.size() - 1; si >= 0; si--) {
            Scorch sc = scorches.get(si);
            double age = now - sc.start;
            if (age >= sc.total) { scorches.remove(si); continue; }
            // marks appear fast, stay, then fade over the last 40 percent
            double appear = Math.min(1.0, age / 350.0);
            double fade = age < sc.total * 0.6 ? 1.0 : 1.0 - (age - sc.total * 0.6) / (sc.total * 0.4);
            double cool = Math.max(0.0, 1.0 - age / 4500.0); // glow cools down in 4.5 seconds
            double flick = 0.85 + 0.15 * Math.sin(age * 0.012);
            for (int i = 0; i < sc.n; i++) {
                Color c = sc.col[i];
                double a = sc.base[i] * dark * appear * fade;
                int cr = c.r, cg = c.g, cb = c.b;
                double heat = sc.heat[i] * cool * flick;
                if (heat > 0.02) {
                    double k = Math.min(1.0, heat);
                    if (sc.crack[i]) {
                        cr = (int) (35 + (glowC.r - 35) * k); cg = (int) (10 + (glowC.g - 10) * k); cb = (int) (8 + (glowC.b - 8) * k);
                        a = Math.max(a, 0.95 * appear * fade);
                    } else {
                        cr = (int) (cr + (glowC.r - cr) * k * 0.7); cg = (int) (cg + (glowC.g - cg) * k * 0.55); cb = (int) (cb + (glowC.b - cb) * k * 0.3);
                    }
                } else if (sc.crack[i]) {
                    cr = 28; cg = 10; cb = 8;
                    a = Math.max(a, 0.9 * appear * fade);
                }
                int ia = (int) Math.max(0, Math.min(255, a * 255));
                if (ia <= 2) continue;
                c.set(cr, cg, cb, ia);
                quad(r, sc.x[i] - h, sc.y[i], sc.z[i] - h, sc.x[i] + h, sc.y[i], sc.z[i] - h, sc.x[i] + h, sc.y[i], sc.z[i] + h, sc.x[i] - h, sc.y[i], sc.z[i] + h, c, c, c, c);
            }
        }
    }

    /** A faint see-through wall that stands on the edge of the circle. */
    private void energyWall(Renderer3D r, double x, double y, double z, double rad, double h, int alpha, double seconds) {
        if (alpha <= 0) return;
        int n = 48;
        for (int i = 0; i < n; i++) {
            double a0 = Math.PI * 2 * i / n, a1 = Math.PI * 2 * (i + 1) / n;
            Color low = waveColor(1.0, alpha, seconds, i / (double) n);
            Color high = waveColor(1.0, 0, seconds, i / (double) n);
            quad(r, x + Math.cos(a0) * rad, y, z + Math.sin(a0) * rad, x + Math.cos(a1) * rad, y, z + Math.sin(a1) * rad,
                x + Math.cos(a1) * rad, y + h, z + Math.sin(a1) * rad, x + Math.cos(a0) * rad, y + h, z + Math.sin(a0) * rad, low, low, high, high);
        }
    }

    /** A flat line on the ground between two points. */
    private void groundLine(Renderer3D r, double x0, double z0, double x1, double z1, double y, double width, Color c) {
        double dx = x1 - x0, dz = z1 - z0;
        double l = Math.max(1e-6, Math.sqrt(dx * dx + dz * dz));
        double nx = -dz / l * width * 0.5, nz = dx / l * width * 0.5;
        quad(r, x0 - nx, y, z0 - nz, x0 + nx, y, z0 + nz, x1 + nx, y, z1 + nz, x1 - nx, y, z1 - nz, c, c, c, c);
    }

    /** A spinning six pointed star with an inner circle, like a magic seal. */
    private void drawRunes(Renderer3D r, Fx f, double y, double rad, int alpha, long age) {
        double spin = age * 0.0011;
        double seconds = age / 1000.0;
        double star = rad * 0.82;
        double w = Math.max(0.05, Math.min(0.2, rad * 0.018));
        Color c = waveColor(0.5, (int) (alpha * 0.9), seconds, 0.1);
        for (int tri = 0; tri < 2; tri++) {
            for (int k = 0; k < 3; k++) {
                double a0 = spin + tri * Math.PI / 3 + k * Math.PI * 2 / 3;
                double a1 = spin + tri * Math.PI / 3 + (k + 1) * Math.PI * 2 / 3;
                groundLine(r, f.x + Math.cos(a0) * star, f.z + Math.sin(a0) * star, f.x + Math.cos(a1) * star, f.z + Math.sin(a1) * star, y, w, c);
            }
        }
        // small circle at every tip, turning the other way
        for (int k = 0; k < 6; k++) {
            double a = -spin * 0.6 + k * Math.PI / 3;
            flatRing(r, f.x + Math.cos(a) * star, y + 0.005, f.z + Math.sin(a) * star, Math.max(0.2, rad * 0.06), Math.max(0.05, rad * 0.015), (int) (alpha * 0.9), seconds);
        }
        flatRing(r, f.x, y + 0.004, f.z, star * 0.55, w * 1.4, (int) (alpha * 0.9), seconds + 0.3);
    }

    /** Small lightning arcs that jump from the edge of the circle. */
    private void drawEdgeArcs(Renderer3D r, Fx f, double y, double rad, long age) {
        int frame = (int) (age / 70);
        Random rnd = new Random(f.seed + 91L * frame);
        int k = Math.max(3, Math.min(9, (int) (rad * 0.8)));
        for (int i = 0; i < k; i++) {
            double a = rnd.nextDouble() * Math.PI * 2;
            double len = 1.2 + rnd.nextDouble() * 2.4;
            int n = 6;
            double[] x = new double[n + 1], yy = new double[n + 1], z = new double[n + 1];
            double dirA = a + Math.PI + (rnd.nextDouble() - 0.5) * 1.2; // mostly inward
            x[0] = f.x + Math.cos(a) * rad; z[0] = f.z + Math.sin(a) * rad; yy[0] = y + 0.1;
            for (int j = 1; j <= n; j++) {
                dirA += (rnd.nextDouble() - 0.5) * 1.3;
                x[j] = x[j - 1] + Math.cos(dirA) * len / n;
                z[j] = z[j - 1] + Math.sin(dirA) * len / n;
                yy[j] = yy[j - 1] + (rnd.nextDouble() - 0.35) * 0.45;
            }
            ribbon(r, x, yy, z, n + 1, 0.22, boltGlow.get(), 130);
            ribbon(r, x, yy, z, n + 1, 0.07, boltCore.get(), 230);
        }
    }

    /** A column of light that rises from the spot: it goes bright, then fades. */
    private void drawPillar(Renderer3D r, Fx f, long age) {
        double t = age / 900.0;
        double rad = 0.9 * (1.0 - 0.7 * t) + 0.12;
        int a = (int) (170 * Math.pow(1.0 - t, 1.5));
        beam(r, f.x, f.y, f.z, rad, boltHeight.get(), boltGlow.get(), a);
        beam(r, f.x, f.y, f.z, rad * 0.4, boltHeight.get(), boltCore.get(), (int) (a * 1.1));
    }

    /** A big, wide beam at the moment of the explosion. */
    private void drawBigBeam(Renderer3D r, Fx f, long burstAge) {
        double u = burstAge / 450.0;
        double rad = Math.min(radius.get() * 0.12, 1.6) * (0.6 + 0.8 * Math.sqrt(u)) * (1 - u * 0.4) + 0.3;
        int a = (int) (200 * Math.pow(1.0 - u, 1.3));
        beam(r, f.x, f.y, f.z, rad, boltHeight.get() * 1.2, sparkA.get(), a);
        beam(r, f.x, f.y, f.z, rad * 0.45, boltHeight.get() * 1.2, boltCore.get(), (int) (a * 1.1));
    }

    private void beam(Renderer3D r, double x, double y, double z, double rad, double h, SettingColor col, int alpha) {
        if (alpha <= 0) return;
        Color low = new Color(col.r, col.g, col.b, Math.min(255, alpha));
        Color high = new Color(col.r, col.g, col.b, 0);
        int n = 14;
        for (int i = 0; i < n; i++) {
            double a0 = Math.PI * 2 * i / n, a1 = Math.PI * 2 * (i + 1) / n;
            quad(r, x + Math.cos(a0) * rad, y, z + Math.sin(a0) * rad, x + Math.cos(a1) * rad, y, z + Math.sin(a1) * rad,
                x + Math.cos(a1) * rad * 0.6, y + h, z + Math.sin(a1) * rad * 0.6, x + Math.cos(a0) * rad * 0.6, y + h, z + Math.sin(a0) * rad * 0.6,
                low, low, high, high);
        }
    }

    // ---- screen flash

    @EventHandler
    private void onRender2D(Render2DEvent event) {
        if (!screenFlash.get() || effects.isEmpty() || mc.player == null || mc.getWindow() == null) return;
        long now = System.currentTimeMillis();
        double best = 0;
        long collapseEnd = wave.get() ? growTime.get() + shrinkTime.get() : 0;
        for (Fx f : effects) {
            long age = now - f.start;
            double d = Math.sqrt(mc.player.squaredDistanceTo(f.x, f.y, f.z));
            double near = Math.max(0, 1.0 - d / Math.max(20.0, range.get() * 0.5)); // closer = brighter
            double v = 0;
            if (bolt.get() && age >= 0 && age < 170) v = Math.max(v, 1.0 - age / 170.0);
            if (burst.get() && age >= collapseEnd && age - collapseEnd < 150) v = Math.max(v, 0.55 * (1.0 - (age - collapseEnd) / 150.0));
            best = Math.max(best, v * near);
        }
        int a = (int) (flashStrength.get() * best);
        if (a <= 1) return;
        int w = mc.getWindow().getScaledWidth(), h = mc.getWindow().getScaledHeight();
        event.drawContext.fill(0, 0, w, h, (Math.min(255, a) << 24) | 0xEAF0FF);
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
        if (embers.get()) {
            double spread = Math.max(1.0, radius.get() * 0.55);
            for (int i = 0; i < n / 2; i++) {
                Spark s = new Spark();
                double a = random.nextDouble() * Math.PI * 2, d = Math.sqrt(random.nextDouble()) * spread;
                s.x = f.x + Math.cos(a) * d; s.y = f.y + 0.1 + random.nextDouble() * 0.6; s.z = f.z + Math.sin(a) * d;
                s.vx = (random.nextDouble() - 0.5) * 0.8; s.vz = (random.nextDouble() - 0.5) * 0.8; s.vy = 1.0 + random.nextDouble() * 2.5;
                s.life = 1.6 + random.nextDouble() * 1.6;
                s.size = size.get() * (0.35 + 0.4 * random.nextDouble());
                s.hue = random.nextFloat();
                s.gMul = -0.15; s.dragMul = 0.35;
                sparks.add(s);
            }
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

            s.vy -= g * s.gMul * dt;
            double sl = s.dragMul == 1 ? slow : Math.exp(-drag.get() * s.dragMul * dt);
            s.vx *= sl; s.vy *= sl; s.vz *= sl;
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
            if (dots || speedNow < 0.4 || s.gMul < 0) {
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
