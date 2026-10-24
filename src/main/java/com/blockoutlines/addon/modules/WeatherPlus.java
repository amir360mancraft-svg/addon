package com.blockoutlines.addon.modules;

import com.blockoutlines.addon.BlockOutlinesAddon;
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
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.Registries;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * A much richer rain. Thin falling streaks, little water crowns that jump up where every drop hits the ground,
 * puddles that fill up while it rains and dry out later, rings that spread over the puddles, and a mirror: the lamps,
 * you and the mobs around you show up upside down in the water. Lightning flashes on storms.
 * Everything is drawn on your screen only. Real mirrors need a shader, so the reflections are built from shapes:
 * they are a good imitation, not a perfect copy.
 */
public class WeatherPlus extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgSplash = settings.createGroup("Splashes");
    private final SettingGroup sgPuddle = settings.createGroup("Puddles & mirror");
    private final SettingGroup sgStorm = settings.createGroup("Storm");

    // -------- rain
    private final Setting<Boolean> forceRain = sgGeneral.add(new BoolSetting.Builder()
        .name("force-rain").description("Rain all the time, even when the sky is clear (only on your screen).").defaultValue(false).build());
    private final Setting<Integer> amount = sgGeneral.add(new IntSetting.Builder()
        .name("drops").description("How many streaks fall around you.").defaultValue(500).range(100, 1500).sliderRange(100, 1000).build());
    private final Setting<Integer> range = sgGeneral.add(new IntSetting.Builder()
        .name("range").description("How far around you the rain falls (blocks).").defaultValue(14).range(8, 24).sliderRange(8, 24).build());
    private final Setting<Double> length = sgGeneral.add(new DoubleSetting.Builder()
        .name("streak-length").description("Length of each streak.").defaultValue(0.9).range(0.3, 2.5).sliderRange(0.3, 2.0).build());
    private final Setting<Double> speed = sgGeneral.add(new DoubleSetting.Builder()
        .name("speed").description("How fast the drops fall.").defaultValue(1.0).range(0.4, 2.0).sliderRange(0.5, 1.6).build());
    private final Setting<Double> wind = sgGeneral.add(new DoubleSetting.Builder()
        .name("wind").description("How much the rain leans to the side, with slow gusts.").defaultValue(0.25).range(0.0, 1.0).sliderRange(0.0, 1.0).build());
    private final Setting<SettingColor> rainColor = sgGeneral.add(new ColorSetting.Builder()
        .name("rain-color").description("Color of the streaks.").defaultValue(new SettingColor(185, 205, 230, 150)).build());

    // -------- splashes
    private final Setting<Boolean> droplets = sgSplash.add(new BoolSetting.Builder()
        .name("jumping-water").description("Every drop that hits the ground throws small drops of water up around it.").defaultValue(true).build());
    private final Setting<Integer> dropletCount = sgSplash.add(new IntSetting.Builder()
        .name("drops-per-splash").description("How many little drops jump up from each hit.").defaultValue(4).range(1, 8).sliderRange(1, 8)
        .visible(droplets::get).build());
    private final Setting<Double> dropletHeight = sgSplash.add(new DoubleSetting.Builder()
        .name("jump-height").description("How high the water jumps.").defaultValue(1.0).range(0.4, 2.5).sliderRange(0.5, 2.0)
        .visible(droplets::get).build());
    private final Setting<Boolean> ripples = sgSplash.add(new BoolSetting.Builder()
        .name("ripples").description("Small rings that spread where drops hit the wet ground and the puddles.").defaultValue(true).build());

    // -------- puddles
    private final Setting<Boolean> puddles = sgPuddle.add(new BoolSetting.Builder()
        .name("puddles").description("Puddles on flat blocks. They fill up while it rains and dry out slowly afterwards.").defaultValue(true).build());
    private final Setting<Integer> puddleCount = sgPuddle.add(new IntSetting.Builder()
        .name("max-puddles").description("Most puddles drawn at once (the nearest ones).").defaultValue(60).range(10, 150).sliderRange(20, 120)
        .visible(puddles::get).build());
    private final Setting<Integer> coverage = sgPuddle.add(new IntSetting.Builder()
        .name("coverage").description("How much of the ground gets puddles (percent).").defaultValue(55).range(10, 100).sliderRange(20, 100)
        .visible(puddles::get).build());
    private final Setting<Double> puddleSize = sgPuddle.add(new DoubleSetting.Builder()
        .name("size").description("Size of the puddles.").defaultValue(1.0).range(0.5, 2.5).sliderRange(0.5, 2.0)
        .visible(puddles::get).build());
    private final Setting<SettingColor> waterColor = sgPuddle.add(new ColorSetting.Builder()
        .name("water-color").description("Color of the water when you look straight down. Closer to the horizon it turns into a mirror of the sky.")
        .defaultValue(new SettingColor(55, 75, 100, 175)).visible(puddles::get).build());
    private final Setting<Boolean> reflectLights = sgPuddle.add(new BoolSetting.Builder()
        .name("mirror-lights").description("Torches, lanterns and other lights shine in the puddles as long glowing streaks.").defaultValue(true)
        .visible(puddles::get).build());
    private final Setting<Boolean> reflectEntities = sgPuddle.add(new BoolSetting.Builder()
        .name("mirror-players-and-mobs").description("You, other players and mobs show up upside down in the water.").defaultValue(true)
        .visible(puddles::get).build());
    private final Setting<Double> reflection = sgPuddle.add(new DoubleSetting.Builder()
        .name("mirror-strength").description("How bright the reflections are.").defaultValue(1.0).range(0.2, 2.0).sliderRange(0.3, 1.8)
        .visible(puddles::get).build());

    // -------- storm
    private final Setting<Boolean> lightning = sgStorm.add(new BoolSetting.Builder()
        .name("lightning").description("Flashes and bolts far away. Needs a thunderstorm (or force-rain).").defaultValue(true).build());
    public enum StormMode { AnyRain, Thunderstorm }
    private final Setting<StormMode> stormMode = sgStorm.add(new EnumSetting.Builder<StormMode>()
        .name("when").description("AnyRain = lightning in every rain. Thunderstorm = only in real thunderstorms (or with force-rain).")
        .defaultValue(StormMode.AnyRain).visible(() -> lightning.get()).build());
    private final Setting<Boolean> strikeNow = sgStorm.add(new BoolSetting.Builder()
        .name("strike-now").description("Click to call one bolt right now (to test it). It switches itself off again.")
        .defaultValue(false).visible(() -> lightning.get()).build());
    private final Setting<Integer> boltEvery = sgStorm.add(new IntSetting.Builder()
        .name("bolt-every").description("Average seconds between bolts.").defaultValue(9).range(3, 60).sliderRange(4, 40)
        .visible(lightning::get).build());
    private final Setting<Double> flashPower = sgStorm.add(new DoubleSetting.Builder()
        .name("flash-power").description("How bright the screen flashes (0 = no flash).").defaultValue(0.35).range(0.0, 0.8).sliderRange(0.0, 0.7)
        .visible(lightning::get).build());
    private final Setting<Boolean> thunder = sgStorm.add(new BoolSetting.Builder()
        .name("thunder-sound").description("Thunder a moment after the flash.").defaultValue(true).visible(lightning::get).build());

    // ------------------------------------------------------------------ state

    private static final int N = 14;    // corners of a puddle outline
    private static final int CELL = 3;  // blocks per puddle cell

    private static final class Drop { double x, y, z, gy; double spd; boolean on; }
    private static final class Droplet { double x, y, z, vx, vy, vz, gy, age, life; }
    private static final class Ripple { double x, y, z, age, life, maxR, a; }
    private static final class Puddle {
        double x, y, z, maxR, area, grow, dist;
        final double[] rad = new double[N];
    }
    private static final class Light { double x, y, z; int r, g, b; double dist; }

    private final List<Drop> drops = new ArrayList<>();
    private final List<Droplet> dropletList = new ArrayList<>();
    private final List<Ripple> ripleList = new ArrayList<>();
    private final Map<Long, Puddle> puddleMap = new HashMap<>();
    private final Set<Long> noPuddle = new HashSet<>();
    private final List<Puddle> nearPuddles = new ArrayList<>();
    private final List<Light> lights = new ArrayList<>();
    private final Random rng = new Random();
    private final BlockPos.Mutable pos = new BlockPos.Mutable();

    private double rainAmt, wet;
    private Object lastWorld;
    private int tickN;

    // camera frame
    private double camX, camY, camZ, rx, rz, ux, uy, uz;

    // lightning
    private double nextBolt, boltBorn = -100, flashNow;
    private final List<double[][]> boltLines = new ArrayList<>(); // each: {xs, ys, zs, {widthFactor}}
    private double fwdAng;
    private double impactX, impactY, impactZ;
    private boolean haveBolt;
    private double boltDist;
    private double thunderAt = -1;

    public WeatherPlus() {
        super(BlockOutlinesAddon.EFFECTS, "weather-plus",
            "Better rain: falling streaks, jumping water, puddles that mirror lights, you and mobs, and lightning on storms.");
    }

    @Override
    public void onActivate() {
        clearAll();
        nextBolt = System.currentTimeMillis() / 1000.0 + 6;
    }

    @Override
    public void onDeactivate() {
        clearAll();
    }

    private void clearAll() {
        drops.clear();
        dropletList.clear();
        ripleList.clear();
        puddleMap.clear();
        noPuddle.clear();
        nearPuddles.clear();
        lights.clear();
        rainAmt = wet = 0;
        boltLines.clear();
        haveBolt = false;
        flashNow = 0;
        thunderAt = -1;
    }

    // ------------------------------------------------------------------ tick: puddles and lights

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null || mc.player == null) return;
        if (lastWorld != mc.world) {
            lastWorld = mc.world;
            clearAll();
        }
        tickN++;
        if (!puddles.get() || (wet < 0.02 && rainAmt < 0.02)) {
            if (!puddleMap.isEmpty()) { puddleMap.clear(); noPuddle.clear(); nearPuddles.clear(); }
            lights.clear();
            return;
        }
        if (tickN % 10 == 0) updatePuddles();
        if (tickN % 20 == 5 && reflectLights.get()) scanLights();
    }

    private void updatePuddles() {
        double px = mc.player.getX(), pz = mc.player.getZ();
        int pcx = (int) Math.floor(px / CELL), pcz = (int) Math.floor(pz / CELL);
        int rc = 9;

        puddleMap.keySet().removeIf(k -> {
            int cx = (int) (k >> 32), cz = (int) (long) k;
            return Math.abs(cx - pcx) > rc + 1 || Math.abs(cz - pcz) > rc + 1;
        });
        noPuddle.removeIf(k -> {
            int cx = (int) (k >> 32), cz = (int) (long) k;
            return Math.abs(cx - pcx) > rc + 1 || Math.abs(cz - pcz) > rc + 1;
        });

        for (int dx = -rc; dx <= rc; dx++) {
            for (int dz = -rc; dz <= rc; dz++) {
                int cx = pcx + dx, cz = pcz + dz;
                long k = key(cx, cz);
                if (puddleMap.containsKey(k) || noPuddle.contains(k)) continue;
                if (!mc.world.getChunkManager().isChunkLoaded((cx * CELL) >> 4, (cz * CELL) >> 4)) continue;
                Puddle p = makePuddle(cx, cz);
                if (p != null) puddleMap.put(k, p); else noPuddle.add(k);
            }
        }

        nearPuddles.clear();
        for (Puddle p : puddleMap.values()) {
            p.dist = Math.hypot(p.x - px, p.z - pz);
            nearPuddles.add(p);
        }
        nearPuddles.sort((a, b) -> Double.compare(a.dist, b.dist));
        while (nearPuddles.size() > puddleCount.get()) nearPuddles.remove(nearPuddles.size() - 1);
    }

    private Puddle makePuddle(int cx, int cz) {
        Random g = new Random(hash(cx, cz));
        if (g.nextInt(100) >= coverage.get()) return null;
        double x = (cx + 0.15 + 0.7 * g.nextDouble()) * CELL;
        double z = (cz + 0.15 + 0.7 * g.nextDouble()) * CELL;
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        int top = mc.world.getTopY(Heightmap.Type.MOTION_BLOCKING, bx, bz);
        if (!flatTop(bx, top - 1, bz)) return null;

        double base = (0.55 + 0.95 * g.nextDouble()) * puddleSize.get();
        double[] raw = new double[N];
        for (int i = 0; i < N; i++) raw[i] = base * (0.6 + 0.8 * g.nextDouble());
        Puddle p = new Puddle();
        p.x = x;
        p.z = z;
        p.y = top;
        for (int i = 0; i < N; i++) {
            double r = (raw[(i + N - 1) % N] + raw[i] * 2 + raw[(i + 1) % N]) / 4.0;
            double ang = Math.PI * 2 * i / N;
            // stop at ledges, walls and other kinds of ground
            double allowed = r;
            for (double d = 0.25; d <= r; d += 0.25) {
                int ex = (int) Math.floor(x + Math.cos(ang) * d), ez = (int) Math.floor(z + Math.sin(ang) * d);
                if (ex == bx && ez == bz) continue;
                if (mc.world.getTopY(Heightmap.Type.MOTION_BLOCKING, ex, ez) != top || !flatTop(ex, top - 1, ez)) {
                    allowed = Math.max(0.15, d - 0.3);
                    break;
                }
            }
            p.rad[i] = allowed;
            p.maxR = Math.max(p.maxR, allowed);
        }
        double s = 0;
        for (double v : p.rad) s += v;
        double avg = s / N;
        p.area = Math.PI * avg * avg;
        if (avg < 0.3) return null;
        return p;
    }

    private boolean flatTop(int x, int y, int z) {
        pos.set(x, y, z);
        BlockState st = mc.world.getBlockState(pos);
        return st.getFluidState().isEmpty() && st.isSideSolidFullSquare(mc.world, pos, Direction.UP);
    }

    private void scanLights() {
        lights.clear();
        if (nearPuddles.isEmpty()) return;
        int px = (int) Math.floor(mc.player.getX()), py = (int) Math.floor(mc.player.getY()), pz = (int) Math.floor(mc.player.getZ());
        int rr = 13;
        for (int x = px - rr; x <= px + rr; x++) {
            for (int z = pz - rr; z <= pz + rr; z++) {
                for (int y = py - 4; y <= py + 10; y++) {
                    pos.set(x, y, z);
                    BlockState st = mc.world.getBlockState(pos);
                    if (st.isAir() || st.getLuminance() < 12) continue;
                    Light l = new Light();
                    l.x = x + 0.5;
                    l.y = y + 0.6;
                    l.z = z + 0.5;
                    String id = Registries.BLOCK.getId(st.getBlock()).getPath();
                    if (id.contains("soul")) { l.r = 90; l.g = 210; l.b = 255; }
                    else if (id.contains("redstone")) { l.r = 255; l.g = 70; l.b = 50; }
                    else if (id.contains("sea_lantern") || id.contains("end_rod") || id.contains("beacon")) { l.r = 200; l.g = 235; l.b = 255; }
                    else if (id.contains("glowstone") || id.contains("shroomlight") || id.contains("froglight")) { l.r = 255; l.g = 225; l.b = 150; }
                    else { l.r = 255; l.g = 175; l.b = 80; }
                    l.dist = Math.hypot(x + 0.5 - mc.player.getX(), z + 0.5 - mc.player.getZ());
                    lights.add(l);
                }
            }
        }
        lights.sort((a, b) -> Double.compare(a.dist, b.dist));
        while (lights.size() > 40) lights.remove(lights.size() - 1);
    }

    // ------------------------------------------------------------------ frame

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (mc.world == null || mc.player == null || mc.gameRenderer == null) return;
        double dt = Math.min(0.1, event.frameTime);
        float td = event.tickDelta;
        double secs = System.currentTimeMillis() / 1000.0;

        double goal = forceRain.get() ? 1.0 : mc.world.getRainGradient(td);
        rainAmt += (goal - rainAmt) * Math.min(1.0, dt * 1.5);
        if (goal > 0.3) wet = Math.min(1.0, wet + dt / 25.0);
        else if (rainAmt < 0.05) wet = Math.max(0.0, wet - dt / 150.0);

        flashNow = flashValue(secs);
        if (rainAmt < 0.01 && wet < 0.01 && flashNow < 0.01) return;

        // camera frame
        Camera cam = mc.gameRenderer.getCamera();
        double yaw = Math.toRadians(cam.getYaw()), pitch = Math.toRadians(cam.getPitch());
        double fx = -Math.sin(yaw) * Math.cos(pitch), fy = -Math.sin(pitch), fz = Math.cos(yaw) * Math.cos(pitch);
        double rl = Math.max(1e-6, Math.sqrt(fz * fz + fx * fx));
        rx = -fz / rl;
        rz = fx / rl;
        ux = -rz * fy;
        uy = rz * fx - rx * fz;
        uz = rx * fy;
        fwdAng = Math.atan2(fz, fx);
        camX = event.offsetX;
        camY = event.offsetY;
        camZ = event.offsetZ;

        Renderer3D r = event.depthRenderer;
        long t = mc.world.getTimeOfDay() % 24000L;
        double dayF = clamp(0.5 + 0.9 * Math.cos((t - 6000) / 24000.0 * Math.PI * 2));

        if (rainAmt > 0.02) {
            updateRain(r, dt, secs, dayF);
        }
        updateDroplets(r, dt);
        if (puddles.get() && wet > 0.02) drawPuddles(r, dt, secs, dayF, td);
        updateRipples(r, dt);
        if (lightning.get()) stormTick(event.renderer, secs);
    }

    // ------------------------------------------------------------------ rain streaks

    private void updateRain(Renderer3D r, double dt, double secs, double dayF) {
        int want = amount.get();
        while (drops.size() < want) drops.add(new Drop());
        int active = (int) (want * Math.min(1.0, rainAmt));
        double wa = secs * 0.07 + Math.sin(secs * 0.31) * 0.8;
        double vx = Math.cos(wa) * wind.get() * 4.5, vz = Math.sin(wa) * wind.get() * 4.5;
        double fall = 17.0 * speed.get();
        double len = length.get();
        Color rc = rainColor.get();
        int baseA = (int) (rc.a * (0.45 + 0.55 * dayF) * Math.min(1.0, rainAmt + 0.2));

        for (int i = 0; i < active; i++) {
            Drop d = drops.get(i);
            if (!d.on) { spawnDrop(d, true); if (!d.on) continue; }
            d.y -= d.spd * fall / 17.0 * dt;
            d.x += vx * dt;
            d.z += vz * dt;
            if (d.y <= d.gy) {
                land(d.x, d.gy, d.z);
                spawnDrop(d, false);
                if (!d.on) continue;
            }
            // the streak: from the head (d.y) up along the opposite of the movement
            double mx = vx, my = -fall, mz = vz;
            double ml = Math.sqrt(mx * mx + my * my + mz * mz);
            double tx = -mx / ml * len, ty = -my / ml * len, tz = -mz / ml * len;
            double hx = d.x, hy = d.y, hz = d.z;
            double cdx = camX - hx, cdy = camY - hy, cdz = camZ - hz;
            double cd = Math.sqrt(cdx * cdx + cdy * cdy + cdz * cdz);
            if (cd < 0.9) continue;
            // width vector = cross(streak, view)
            double wx = ty * cdz - tz * cdy, wy = tz * cdx - tx * cdz, wz = tx * cdy - ty * cdx;
            double wl = Math.sqrt(wx * wx + wy * wy + wz * wz);
            if (wl < 1e-9) continue;
            double w = 0.0055 * (1 + cd * 0.025) / wl;
            wx *= w; wy *= w; wz *= w;
            int a = (int) (baseA * clamp((cd - 0.9) / 3.0) * clamp((rangeNow() + 2 - cd) / 6.0));
            if (a < 3) continue;
            Color head = new Color(rc.r, rc.g, rc.b, a);
            Color tail = new Color(rc.r, rc.g, rc.b, 0);
            quad(r,
                hx - wx, hy - wy, hz - wz,
                hx + wx, hy + wy, hz + wz,
                hx + tx + wx, hy + ty + wy, hz + tz + wz,
                hx + tx - wx, hy + ty - wy, hz + tz - wz,
                head, head, tail, tail);
        }
    }

    private double rangeNow() {
        return range.get();
    }

    private void spawnDrop(Drop d, boolean randomHeight) {
        double a = rng.nextDouble() * Math.PI * 2, rr = Math.sqrt(rng.nextDouble()) * range.get();
        d.x = camX + Math.cos(a) * rr;
        d.z = camZ + Math.sin(a) * rr;
        int bx = (int) Math.floor(d.x), bz = (int) Math.floor(d.z);
        d.on = false;
        if (!mc.world.getChunkManager().isChunkLoaded(bx >> 4, bz >> 4)) return;
        int top = mc.world.getTopY(Heightmap.Type.MOTION_BLOCKING, bx, bz);
        if (top > camY + 2.2) return; // a roof over your head: no rain there
        d.gy = top;
        double startY = Math.max(camY + 11, top + 3) + rng.nextDouble() * 4;
        if (startY - top > 45) startY = top + 45;
        d.y = randomHeight ? top + rng.nextDouble() * (startY - top) : startY;
        d.spd = 16 + rng.nextDouble() * 5;
        d.on = true;
    }

    /** A drop hit the ground at (x, y, z). */
    private void land(double x, double y, double z) {
        double dx = x - camX, dz = z - camZ;
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist > 13) return;
        if (droplets.get() && dropletList.size() < 700 && rng.nextDouble() < 0.7) {
            splash(x, y, z, 1 + rng.nextInt(Math.max(1, dropletCount.get())));
        }
        if (ripples.get() && wet > 0.25 && dist < 9 && ripleList.size() < 220 && rng.nextDouble() < 0.4) {
            boolean inPuddle = puddleAt(x, z) != null;
            addRipple(x, y, z, inPuddle ? 0.34 : 0.17, inPuddle ? 150 : 70);
        }
    }

    private void splash(double x, double y, double z, int n) {
        for (int k = 0; k < n; k++) {
            Droplet d = new Droplet();
            double ang = rng.nextDouble() * Math.PI * 2, hs = 0.25 + rng.nextDouble() * 0.55;
            d.x = x; d.y = y + 0.02; d.z = z; d.gy = y;
            d.vx = Math.cos(ang) * hs;
            d.vz = Math.sin(ang) * hs;
            d.vy = (1.3 + rng.nextDouble() * 1.5) * dropletHeight.get();
            d.life = 0.7;
            dropletList.add(d);
        }
    }

    private void addRipple(double x, double y, double z, double maxR, double a) {
        Ripple rp = new Ripple();
        rp.x = x; rp.y = y; rp.z = z;
        rp.maxR = maxR * (0.8 + 0.4 * rng.nextDouble());
        rp.life = 0.55 + rng.nextDouble() * 0.4;
        rp.a = a;
        ripleList.add(rp);
    }

    private void updateDroplets(Renderer3D r, double dt) {
        Iterator<Droplet> it = dropletList.iterator();
        while (it.hasNext()) {
            Droplet d = it.next();
            d.age += dt;
            d.vy -= 9.5 * dt;
            d.x += d.vx * dt;
            d.y += d.vy * dt;
            d.z += d.vz * dt;
            if (d.age > d.life || d.y < d.gy) { it.remove(); continue; }
            double dx = d.x - camX, dy = d.y - camY, dz = d.z - camZ;
            double cd = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (cd < 0.6 || cd > 16) continue;
            double s = 0.017 * (1 + cd * 0.06);
            int a = (int) (190 * (1 - d.age / d.life));
            Color c = new Color(225, 238, 255, a);
            // a small drop with a short tail in the direction it came from
            double tx = -d.vx * 0.035, ty = -d.vy * 0.035, tz = -d.vz * 0.035;
            Color tc = new Color(225, 238, 255, 0);
            quad(r,
                d.x - rx * s, d.y - s, d.z - rz * s,
                d.x + rx * s, d.y - s, d.z + rz * s,
                d.x + rx * s + tx, d.y + s + ty, d.z + rz * s + tz,
                d.x - rx * s + tx, d.y + s + ty, d.z - rz * s + tz,
                c, c, tc, tc);
        }
    }

    private void updateRipples(Renderer3D r, double dt) {
        Iterator<Ripple> it = ripleList.iterator();
        while (it.hasNext()) {
            Ripple rp = it.next();
            rp.age += dt;
            double k = rp.age / rp.life;
            if (k >= 1) { it.remove(); continue; }
            double dx = rp.x - camX, dz = rp.z - camZ;
            double hd = Math.sqrt(dx * dx + dz * dz);
            double lift = 0.02 + hd * 0.0009;
            double e = 1 - (1 - k) * (1 - k);
            double R = rp.maxR * e;
            int a = (int) (rp.a * (1 - k) * Math.min(1.0, wet * 1.4));
            if (a < 3) continue;
            a = (int) (a * 0.55);
            ring(r, rp.x, rp.y + lift, rp.z, R, Math.max(0.02, R * 0.24), a);
            if (k > 0.25) ring(r, rp.x, rp.y + lift, rp.z, R * 0.6, Math.max(0.015, R * 0.16), a / 2);
        }
    }

    /** A soft ring: transparent on both edges, brightest in the middle of its width. */
    private void ring(Renderer3D r, double x, double y, double z, double R, double w, int a) {
        int n = R > 0.25 ? 32 : 22;
        double r0 = Math.max(0.0, R - w * 0.5), r1 = R, r2 = R + w * 0.5;
        Color none = new Color(225, 238, 255, 0), peak = new Color(235, 245, 255, a);
        for (int i = 0; i < n; i++) {
            double a0 = Math.PI * 2 * i / n, a1 = Math.PI * 2 * (i + 1) / n;
            double c0 = Math.cos(a0), s0 = Math.sin(a0), c1 = Math.cos(a1), s1 = Math.sin(a1);
            quad(r,
                x + c0 * r0, y, z + s0 * r0, x + c1 * r0, y, z + s1 * r0,
                x + c1 * r1, y, z + s1 * r1, x + c0 * r1, y, z + s0 * r1,
                none, none, peak, peak);
            quad(r,
                x + c0 * r1, y, z + s0 * r1, x + c1 * r1, y, z + s1 * r1,
                x + c1 * r2, y, z + s1 * r2, x + c0 * r2, y, z + s0 * r2,
                peak, peak, none, none);
        }
    }

    // ------------------------------------------------------------------ puddles + mirror

    private void drawPuddles(Renderer3D r, double dt, double secs, double dayF, float td) {
        if (nearPuddles.isEmpty()) return;
        Color wc = waterColor.get();
        double sky = 1.0 + flashNow * 2.2;
        // the sky as the water sees it: light grey-blue by day, dark blue at night, darker when stormy
        double stormDim = 1.0 - 0.3 * rainAmt;
        double skyR = (18 + 150 * dayF) * stormDim * sky, skyG = (24 + 160 * dayF) * stormDim * sky, skyB = (40 + 170 * dayF) * stormDim * sky;

        List<Entity> ents = collectEntities(td);

        for (Puddle p : nearPuddles) {
            p.grow = Math.min(1.0, p.grow + dt / 1.5);
            double vis = Math.min(1.0, wet * 1.15) * p.grow * (1.0 - clamp((p.dist - 18) / 8.0));
            if (vis < 0.03) continue;

            double dx = p.x - camX, dz = p.z - camZ;
            double hd = Math.sqrt(dx * dx + dz * dz);
            double elev = Math.toDegrees(Math.atan2(camY - p.y, Math.max(0.3, hd)));
            double fres = 0.25 + 0.75 * clamp(1 - elev / 65.0);
            double lift = 0.012 + hd * 0.0007;
            double y = p.y + lift;

            // own ripples
            if (ripples.get() && rainAmt > 0.2 && p.dist < 15 && ripleList.size() < 220) {
                double expect = p.area * 3.0 * rainAmt * dt;
                int cnt = (int) expect + (rng.nextDouble() < expect - (int) expect ? 1 : 0);
                for (int k = 0; k < cnt; k++) {
                    double ang = rng.nextDouble() * Math.PI * 2, rr = Math.sqrt(rng.nextDouble()) * radiusAt(p, ang) * 0.8;
                    double sx = p.x + Math.cos(ang) * rr, sz = p.z + Math.sin(ang) * rr;
                    addRipple(sx, p.y, sz, 0.38, 170);
                    if (droplets.get() && dropletList.size() < 700 && rng.nextDouble() < 0.5) splash(sx, p.y, sz, 2);
                }
            }

            // water body: sky mirror in the middle, soft edge
            double cr = lerp(wc.r, skyR, fres * 0.8), cg = lerp(wc.g, skyG, fres * 0.8), cb = lerp(wc.b, skyB, fres * 0.8);
            int aMid = (int) (wc.a * 0.8 * vis * (0.6 + 0.4 * fres));
            for (int i = 0; i < N; i++) {
                int j = (i + 1) % N;
                double a0 = Math.PI * 2 * i / N, a1 = Math.PI * 2 * j / N;
                double r0 = p.rad[i], r1 = p.rad[j];
                double ix0 = p.x + Math.cos(a0) * r0 * 0.5, iz0 = p.z + Math.sin(a0) * r0 * 0.5;
                double ix1 = p.x + Math.cos(a1) * r1 * 0.5, iz1 = p.z + Math.sin(a1) * r1 * 0.5;
                double ox0 = p.x + Math.cos(a0) * r0, oz0 = p.z + Math.sin(a0) * r0;
                double ox1 = p.x + Math.cos(a1) * r1, oz1 = p.z + Math.sin(a1) * r1;
                // clouds drifting over the mirror
                double n0 = 0.86 + 0.22 * Math.sin(ix0 * 1.7 + secs * 0.35) * Math.cos(iz0 * 1.3 - secs * 0.25);
                double n1 = 0.86 + 0.22 * Math.sin(ix1 * 1.7 + secs * 0.35) * Math.cos(iz1 * 1.3 - secs * 0.25);
                Color mid = col(cr, cg, cb, aMid, 1.0);
                Color e0 = col(cr, cg, cb, aMid, n0), e1 = col(cr, cg, cb, aMid, n1);
                Color clear = col(cr, cg, cb, 0, 1.0);
                quad(r, p.x, y, p.z, ix0, y, iz0, ix1, y, iz1, p.x, y, p.z, mid, e0, e1, mid);
                quad(r, ix0, y, iz0, ix1, y, iz1, ox1, y, oz1, ox0, y, oz0, e0, e1, clear, clear);
            }

            double mirror = vis * fres * reflection.get();
            if (mirror < 0.04) continue;

            if (reflectLights.get() && !lights.isEmpty()) {
                for (Light l : lights) reflectLight(r, p, l, mirror, secs, lift * 1.5);
            }
            if (reflectEntities.get()) {
                for (Entity e : ents) {
                    if (Math.hypot(e.getX() - p.x, e.getZ() - p.z) > p.maxR + 3.2) continue;
                    reflectEntity(r, p, e, mirror, lift * 1.3, td);
                }
            }
        }
    }

    private List<Entity> collectEntities(float td) {
        List<Entity> out = new ArrayList<>();
        if (!reflectEntities.get()) return out;
        for (Entity e : mc.world.getEntities()) {
            if (!(e instanceof LivingEntity le) || !le.isAlive() || le.isInvisible()) continue;
            if (e == mc.player && mc.options.getPerspective().isFirstPerson()) {
                out.add(e); // still mirrored (without the head) so puddles under you react
                continue;
            }
            if (Math.hypot(e.getX() - camX, e.getZ() - camZ) > 26) continue;
            out.add(e);
            if (out.size() > 24) break;
        }
        return out;
    }

    /**
     * The mirror image of a light: a soft glow in the water below it. Seen from the side it stretches into a long
     * shimmering streak that points at you, seen from above it is a short round glow.
     */
    private void reflectLight(Renderer3D r, Puddle p, Light l, double mirror, double secs, double lift) {
        if (l.y < p.y + 0.15) return;
        double dxz = Math.hypot(l.x - p.x, l.z - p.z);
        if (dxz > p.maxR + 9) return;
        double[] o = new double[2];
        if (!project(l.x, l.y, l.z, p.y, o)) return;
        double radAng = Math.atan2(o[1] - p.z, o[0] - p.x);
        if (Math.hypot(o[0] - p.x, o[1] - p.z) > radiusAt(p, radAng) * 0.92) return;

        double fall = clamp(1.0 - dxz / (p.maxR + 9));
        double a = 170 * mirror * (0.35 + 0.65 * fall) * (0.9 + 0.1 * Math.sin(secs * 6 + l.x * 3 + l.z));
        if (a < 6) return;

        double dx = o[0] - camX, dz = o[1] - camZ;
        double dl = Math.max(1e-6, Math.hypot(dx, dz));
        dx /= dl; dz /= dl;
        double elev = Math.atan2(camY - p.y, Math.max(0.3, dl));
        double elong = Math.max(1.0, Math.min(4.0, 1.0 / Math.max(0.25, Math.sin(elev))));
        double h = l.y - p.y;
        double len = (0.30 + 0.07 * h) * elong, wid = 0.14 + 0.02 * h;
        for (int k = 0; k < 5; k++) {
            if (inside(p, o[0] + dx * len, o[1] + dz * len) && inside(p, o[0] - dx * len, o[1] - dz * len)) break;
            len *= 0.7;
            wid *= 0.85;
        }
        double y = p.y + lift;
        softGlow(r, o[0], y, o[1], dx, dz, len * 2.2, wid * 2.6, l.r, l.g, l.b, (int) (a * 0.35), secs, 0.0);
        softGlow(r, o[0], y + 0.002, o[1], dx, dz, len, wid, l.r, l.g, l.b, (int) a, secs, 1.0);
        softGlow(r, o[0], y + 0.004, o[1], dx, dz, len * 0.4, wid * 0.45, 255, 245, 225, (int) Math.min(255, a * 1.1), secs, 2.0);
    }

    /** A soft elliptical glow lying on the water: bright in the middle, fading to nothing at the edge, with a slow shimmer. */
    private void softGlow(Renderer3D r, double x, double y, double z, double ax, double az, double len, double wid,
                          int cr, int cg, int cb, int a, double secs, double phase) {
        int segs = 22;
        double[] fr = {0.0, 0.4, 0.75, 1.0};
        double[] al = {1.0, 0.55, 0.16, 0.0};
        double px = -az, pz = ax;
        for (int ring = 0; ring < 3; ring++) {
            for (int i = 0; i < segs; i++) {
                double t0 = Math.PI * 2 * i / segs, t1 = Math.PI * 2 * (i + 1) / segs;
                double s0 = 0.88 + 0.12 * Math.sin(secs * 3.2 + t0 * 3 + phase * 2.1);
                double s1 = 0.88 + 0.12 * Math.sin(secs * 3.2 + t1 * 3 + phase * 2.1);
                double f0 = fr[ring], f1 = fr[ring + 1];
                double[] u = new double[4], v = new double[4];
                u[0] = Math.cos(t0) * len * f0; v[0] = Math.sin(t0) * wid * f0;
                u[1] = Math.cos(t1) * len * f0; v[1] = Math.sin(t1) * wid * f0;
                u[2] = Math.cos(t1) * len * f1; v[2] = Math.sin(t1) * wid * f1;
                u[3] = Math.cos(t0) * len * f1; v[3] = Math.sin(t0) * wid * f1;
                Color c0 = new Color(cr, cg, cb, (int) (a * al[ring] * s0));
                Color c1 = new Color(cr, cg, cb, (int) (a * al[ring] * s1));
                Color c2 = new Color(cr, cg, cb, (int) (a * al[ring + 1] * s1));
                Color c3 = new Color(cr, cg, cb, (int) (a * al[ring + 1] * s0));
                quad(r,
                    x + ax * u[0] + px * v[0], y, z + az * u[0] + pz * v[0],
                    x + ax * u[1] + px * v[1], y, z + az * u[1] + pz * v[1],
                    x + ax * u[2] + px * v[2], y, z + az * u[2] + pz * v[2],
                    x + ax * u[3] + px * v[3], y, z + az * u[3] + pz * v[3],
                    c0, c1, c2, c3);
            }
        }
    }

    /** You and the mobs, upside down: every corner of the body is projected onto the water along your line of sight. */
    private void reflectEntity(Renderer3D r, Puddle p, Entity e, double mirror, double lift, float td) {
        Vec3d at = e.getLerpedPos(td);
        if (at.y < p.y - 0.6 || at.y > p.y + 3.5) return; // only things that stand near this water level
        boolean self = e == mc.player;
        boolean first = self && mc.options.getPerspective().isFirstPerson();
        double yawRad = Math.toRadians(e instanceof LivingEntity le ? le.getBodyYaw() : e.getYaw());
        double y0 = Math.max(at.y, p.y);

        double mv = Math.min(1.0, e.getVelocity().horizontalLength() * 7.0);
        double sw = Math.sin(System.currentTimeMillis() / 1000.0 * 9.0) * 0.32 * mv;
        double sneak = e.isSneaking() ? 0.2 : 0.0;
        if (e instanceof PlayerEntity) {
            // a player built from boxes: legs and arms swing while it walks, tinted like the sky so it reads as a reflection
            Color skin = new Color(190, 150, 125, 255), shirt = new Color(80, 105, 150, 255), pants = new Color(60, 66, 110, 255);
            double off = y0 - at.y;
            reflectBox(r, p, at.x, at.z, y0, 0.25, 0.75 - off, 0.13, yawRad, 0.125, sw, pants, mirror, lift);
            reflectBox(r, p, at.x, at.z, y0, 0.25, 0.75 - off, 0.13, yawRad, -0.125, -sw, pants, mirror, lift);
            reflectBox(r, p, at.x, at.z, at.y + 0.75, 0.5, 0.75 - sneak, 0.13, yawRad, 0.0, 0.0, shirt, mirror, lift);
            reflectBox(r, p, at.x, at.z, at.y + 0.75, 0.14, 0.7 - sneak, 0.13, yawRad, 0.37, -sw, shirt, mirror, lift);
            reflectBox(r, p, at.x, at.z, at.y + 0.75, 0.14, 0.7 - sneak, 0.13, yawRad, -0.37, sw, shirt, mirror, lift);
            if (!first) reflectBox(r, p, at.x, at.z, at.y + 1.5 - sneak, 0.5, 0.5, 0.25, yawRad, 0.0, 0.0, skin, mirror, lift);
        } else {
            LivingEntity le = (LivingEntity) e;
            Color tint = e instanceof Monster ? new Color(75, 105, 70, 255) : new Color(105, 90, 80, 255);
            double hw = le.getWidth() * 0.5;
            reflectBox(r, p, at.x, at.z, y0, hw * 2, le.getHeight() - (y0 - at.y), hw, yawRad, 0.0, 0.0, tint, mirror, lift);
        }
    }

    /**
     * One box standing on the water, mirrored. {@code sideOff} moves it to the side of the entity (in its own
     * left-right direction). Corners are projected and kept inside the puddle.
     */
    private void reflectBox(Renderer3D r, Puddle p, double cx, double cz, double bottom, double widthX, double h, double halfZ,
                            double yawRad, double sideOff, double fwdOff, Color tint, double mirror, double lift) {
        if (h <= 0.02) return;
        double cs = Math.cos(yawRad), sn = Math.sin(yawRad);
        double hx = widthX * 0.5;
        // side direction in the world (the entity's left-right axis)
        double bx = cx + sideOff * cs - fwdOff * sn, bz = cz + sideOff * sn + fwdOff * cs;
        double[] px = new double[8], pz = new double[8];
        double[] o = new double[2];
        double mx = 0, mz = 0;
        for (int i = 0; i < 8; i++) {
            double lx = (i == 0 || i == 3 || i == 4 || i == 7) ? -hx : hx;
            double lz = (i == 0 || i == 1 || i == 4 || i == 5) ? -halfZ : halfZ;
            double wy = i < 4 ? bottom : bottom + h;
            double wx = bx + lx * cs - lz * sn, wz = bz + lx * sn + lz * cs;
            if (!project(wx, wy, wz, p.y, o)) return;
            px[i] = o[0];
            pz[i] = o[1];
            mx += o[0];
            mz += o[1];
        }
        mx /= 8;
        mz /= 8;
        if (!inside(p, mx, mz, 1.15)) return;
        for (int i = 0; i < 8; i++) clampInside(p, px, pz, i);

        double y = p.y + lift;
        int a = (int) Math.min(110, 80 * mirror);
        Color foot = new Color(tint.r, tint.g, tint.b, a);
        Color head = new Color(tint.r, tint.g, tint.b, (int) (a * 0.12));
        // bottom face is nearest the feet, top face is the faded far end
        quad(r, px[0], y, pz[0], px[1], y, pz[1], px[2], y, pz[2], px[3], y, pz[3], foot, foot, foot, foot);
        quad(r, px[4], y, pz[4], px[5], y, pz[5], px[6], y, pz[6], px[7], y, pz[7], head, head, head, head);
        int[][] sides = {{0, 1, 5, 4}, {1, 2, 6, 5}, {2, 3, 7, 6}, {3, 0, 4, 7}};
        for (int[] s : sides) {
            quad(r, px[s[0]], y, pz[s[0]], px[s[1]], y, pz[s[1]], px[s[2]], y, pz[s[2]], px[s[3]], y, pz[s[3]], foot, foot, head, head);
        }
    }

    /** Where the mirror image of the point (vx, vy, vz) in the water plane y = py appears, seen from the camera. */
    private boolean project(double vx, double vy, double vz, double py, double[] out) {
        if (camY - py < 0.15) return false;
        double v = Math.max(vy, py);
        double my = 2 * py - v;
        double den = my - camY;
        if (den > -1e-6) return false;
        double t = (py - camY) / den;
        out[0] = camX + t * (vx - camX);
        out[1] = camZ + t * (vz - camZ);
        return true;
    }

    private double radiusAt(Puddle p, double ang) {
        double u = ang / (Math.PI * 2);
        u -= Math.floor(u);
        double f = u * N;
        int i0 = (int) f % N;
        double fr = f - Math.floor(f);
        return p.rad[i0] * (1 - fr) + p.rad[(i0 + 1) % N] * fr;
    }

    private boolean inside(Puddle p, double x, double z) {
        return inside(p, x, z, 0.92);
    }

    private boolean inside(Puddle p, double x, double z, double k) {
        return Math.hypot(x - p.x, z - p.z) <= radiusAt(p, Math.atan2(z - p.z, x - p.x)) * k;
    }

    private void clampInside(Puddle p, double[] xs, double[] zs, int i) {
        double dx = xs[i] - p.x, dz = zs[i] - p.z;
        double d = Math.hypot(dx, dz);
        double R = radiusAt(p, Math.atan2(dz, dx)) * 0.9;
        if (d > R && d > 1e-6) {
            xs[i] = p.x + dx / d * R;
            zs[i] = p.z + dz / d * R;
        }
    }

    private Puddle puddleAt(double x, double z) {
        for (Puddle p : nearPuddles) {
            if (Math.abs(p.x - x) > p.maxR || Math.abs(p.z - z) > p.maxR) continue;
            if (inside(p, x, z, 1.0)) return p;
        }
        return null;
    }

    // ------------------------------------------------------------------ lightning

    private void stormTick(Renderer3D r, double secs) {
        boolean storm = rainAmt > 0.3 && (forceRain.get() || stormMode.get() == StormMode.AnyRain || mc.world.isThundering());
        if (strikeNow.get()) {
            strikeNow.set(false);
            strike(secs);
            nextBolt = secs + boltEvery.get() * (0.5 + rng.nextDouble());
        } else if (storm && secs >= nextBolt) {
            strike(secs);
            nextBolt = secs + boltEvery.get() * (0.4 + rng.nextDouble() * 1.2);
        }
        if (thunderAt > 0 && secs >= thunderAt) {
            thunderAt = -1;
            if (thunder.get()) {
                double vol = 0.9 - Math.min(0.5, boltDist / 200.0);
                mc.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.ENTITY_LIGHTNING_BOLT_THUNDER, 0.85f + rng.nextFloat() * 0.2f, (float) vol));
            }
        }
        double t = secs - boltBorn;
        if (haveBolt && t < 0.45) {
            double f = Math.max(Math.exp(-t * 8), 0.8 * Math.exp(-Math.pow((t - 0.18) * 16, 2)));
            int a = (int) (255 * Math.min(1.0, f));
            if (a > 4) drawBolt(r, a);
        }
    }

    private void strike(double secs) {
        // mostly in front of you so you actually see it, sometimes to the side
        double ang = rng.nextDouble() < 0.75 ? fwdAng + (rng.nextDouble() - 0.5) * 1.8 : rng.nextDouble() * Math.PI * 2;
        double dist = 32 + rng.nextDouble() * 38;
        double bx = camX + Math.cos(ang) * dist, bz = camZ + Math.sin(ang) * dist;
        int ix = (int) Math.floor(bx), iz = (int) Math.floor(bz);
        double gy = camY - 3;
        if (mc.world.getChunkManager().isChunkLoaded(ix >> 4, iz >> 4)) gy = mc.world.getTopY(Heightmap.Type.MOTION_BLOCKING, ix, iz);
        double top = Math.max(camY, gy) + 95 + rng.nextDouble() * 25;

        boltLines.clear();
        double[][] main = jaggedLine(bx + (rng.nextDouble() - 0.5) * 30, top, bz + (rng.nextDouble() - 0.5) * 30, bx, gy, bz, 20, 1.0, 5.5);
        boltLines.add(main);
        // branches
        int branches = 3 + rng.nextInt(4);
        for (int b = 0; b < branches; b++) {
            int from = 3 + rng.nextInt(main[0].length - 6);
            double sx = main[0][from], sy = main[1][from], sz = main[2][from];
            double bang = rng.nextDouble() * Math.PI * 2, reach = 8 + rng.nextDouble() * 22;
            double ey = sy - (10 + rng.nextDouble() * 28);
            boltLines.add(jaggedLine(sx, sy, sz, sx + Math.cos(bang) * reach, Math.max(gy, ey), sz + Math.sin(bang) * reach, 7, 0.45, 2.8));
        }
        impactX = bx; impactY = gy; impactZ = bz;
        haveBolt = true;
        boltBorn = secs;
        boltDist = dist;
        thunderAt = secs + dist / 60.0;
    }

    /** A zig-zag path from the start to the end; the sideways wobble gets smaller towards the end. Returns {xs, ys, zs, {width}}. */
    private double[][] jaggedLine(double x0, double y0, double z0, double x1, double y1, double z1, int seg, double width, double jitter) {
        double[] xs = new double[seg + 1], ys = new double[seg + 1], zs = new double[seg + 1];
        for (int i = 0; i <= seg; i++) {
            double k = i / (double) seg;
            double j = (i == 0 || i == seg) ? 0 : jitter * (0.35 + 0.65 * (1 - k));
            xs[i] = x0 + (x1 - x0) * k + (rng.nextDouble() - 0.5) * 2 * j;
            ys[i] = y0 + (y1 - y0) * k;
            zs[i] = z0 + (z1 - z0) * k + (rng.nextDouble() - 0.5) * 2 * j;
        }
        return new double[][] {xs, ys, zs, {width}};
    }

    private void drawBolt(Renderer3D r, int a) {
        double gx = impactX - camX, gz = impactZ - camZ;
        double gl = Math.max(1e-6, Math.hypot(gx, gz));
        double sx = -gz / gl, sz = gx / gl; // sideways, facing the camera
        double base = Math.max(0.35, boltDist * 0.02);
        Color glowE = new Color(150, 175, 255, 0);
        Color glowC = new Color(170, 195, 255, a / 3);
        Color core = new Color(250, 252, 255, a);
        for (double[][] line : boltLines) {
            double[] xs = line[0], ys = line[1], zs = line[2];
            double wf = line[3][0];
            double wGlow = base * 3.0 * wf, wMid = base * 1.2 * wf, wCore = base * 0.38 * wf;
            for (int i = 0; i < xs.length - 1; i++) {
                bolt(r, xs[i], ys[i], zs[i], xs[i + 1], ys[i + 1], zs[i + 1], sx, sz, wGlow, glowE, glowC);
                bolt(r, xs[i], ys[i], zs[i], xs[i + 1], ys[i + 1], zs[i + 1], sx, sz, wMid, glowC, core);
                bolt(r, xs[i], ys[i], zs[i], xs[i + 1], ys[i + 1], zs[i + 1], sx, sz, wCore, core, core);
            }
        }
        // glow where it hits the ground
        double f = a / 255.0;
        for (int k = 0; k < 3; k++) {
            double R = 2.5 + k * 2.5;
            Color in = new Color(200, 215, 255, (int) (a * 0.35 / (k + 1)));
            Color out = new Color(200, 215, 255, 0);
            for (int i = 0; i < 20; i++) {
                double a0 = Math.PI * 2 * i / 20, a1 = Math.PI * 2 * (i + 1) / 20;
                quad(r, impactX, impactY + 0.1, impactZ, impactX, impactY + 0.1, impactZ,
                    impactX + Math.cos(a1) * R, impactY + 0.1, impactZ + Math.sin(a1) * R, impactX + Math.cos(a0) * R, impactY + 0.1, impactZ + Math.sin(a0) * R,
                    in, in, out, out);
            }
        }
    }

    /** One segment of the bolt as a ribbon: {@code mid} color on the center line, {@code edge} color on both sides. */
    private void bolt(Renderer3D r, double x0, double y0, double z0, double x1, double y1, double z1,
                      double sx, double sz, double w, Color edge, Color mid) {
        quad(r, x0 - sx * w, y0, z0 - sz * w, x0, y0, z0, x1, y1, z1, x1 - sx * w, y1, z1 - sz * w, edge, mid, mid, edge);
        quad(r, x0, y0, z0, x0 + sx * w, y0, z0 + sz * w, x1 + sx * w, y1, z1 + sz * w, x1, y1, z1, mid, edge, edge, mid);
    }

    private double flashValue(double secs) {
        double t = secs - boltBorn;
        if (t < 0 || t > 0.6 || !haveBolt) return 0;
        return Math.max(Math.exp(-t * 9), 0.7 * Math.exp(-Math.pow((t - 0.22) * 14, 2)));
    }

    @EventHandler
    private void onRender2D(Render2DEvent event) {
        if (flashNow < 0.02 || flashPower.get() <= 0.001 || !lightning.get()) return;
        int a = (int) (255 * Math.min(0.9, flashNow * flashPower.get()));
        if (a < 3) return;
        event.drawContext.fill(0, 0, event.screenWidth, event.screenHeight, (a << 24) | 0xDDE6FF);
    }

    // ------------------------------------------------------------------ helpers

    private static double clamp(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private static Color col(double r, double g, double b, int a, double k) {
        return new Color((int) Math.max(0, Math.min(255, r * k)), (int) Math.max(0, Math.min(255, g * k)), (int) Math.max(0, Math.min(255, b * k)), Math.max(0, Math.min(255, a)));
    }

    private static long hash(int a, int b) {
        long h = a * 341873128712L + b * 132897987541L;
        h ^= h >>> 29;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 32;
        return h;
    }

    private static long key(int a, int b) {
        return ((long) a << 32) ^ (b & 0xffffffffL);
    }

    /** Meteor's quad() wants the colors as top-left, top-right, bottom-right, bottom-left (= vertices 2, 3, 4, 1). */
    private static void quad(Renderer3D r,
                             double x1, double y1, double z1, double x2, double y2, double z2,
                             double x3, double y3, double z3, double x4, double y4, double z4,
                             Color cv1, Color cv2, Color cv3, Color cv4) {
        r.quad(x1, y1, z1, x2, y2, z2, x3, y3, z3, x4, y4, z4, cv2, cv3, cv4, cv1);
        // the other side as well, so the quad shows from every angle
        r.quad(x4, y4, z4, x3, y3, z3, x2, y2, z2, x1, y1, z1, cv1, cv4, cv3, cv2);
    }
}
