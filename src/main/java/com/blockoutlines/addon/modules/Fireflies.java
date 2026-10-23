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
import net.minecraft.client.render.Camera;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Glowing fireflies and magic dust floating around you in the dark. They wander slowly and softly, switch on and off
 * like real fireflies, stay near the ground, never go through blocks, and fade in when night (or a dark place) comes.
 * Drawn on your screen only, nothing is sent to the server.
 */
public class Fireflies extends Module {
    public enum Style { Fireflies, MagicDust, Mixed }

    public enum When { Night, Dark, NightOrDark, Always }

    public enum Palette { Natural, Rainbow, Custom }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgLook = settings.createGroup("Look");
    private final SettingGroup sgColor = settings.createGroup("Colors");

    // -------- general
    private final Setting<Style> style = sgGeneral.add(new EnumSetting.Builder<Style>()
        .name("style").description("Fireflies: warm little lights near the ground. Magic Dust: sparkles that float up. Mixed: both.")
        .defaultValue(Style.Fireflies).build());
    private final Setting<When> when = sgGeneral.add(new EnumSetting.Builder<When>()
        .name("show-when").description("Night: only in the night. Dark: only where it is dark (caves, forests, inside). Always: all the time.")
        .defaultValue(When.NightOrDark).build());
    private final Setting<Integer> darkLevel = sgGeneral.add(new IntSetting.Builder()
        .name("dark-light-level").description("Where the light is at or below this level it counts as dark.")
        .defaultValue(7).range(0, 15).sliderRange(0, 15)
        .visible(() -> when.get() == When.Dark || when.get() == When.NightOrDark).build());
    private final Setting<Integer> amount = sgGeneral.add(new IntSetting.Builder()
        .name("amount").description("How many lights around you.").defaultValue(45).range(5, 200).sliderRange(10, 120).build());
    private final Setting<Integer> radius = sgGeneral.add(new IntSetting.Builder()
        .name("radius").description("How far from you they float (blocks).").defaultValue(16).range(6, 40).sliderRange(8, 32).build());
    private final Setting<Double> speed = sgGeneral.add(new DoubleSetting.Builder()
        .name("speed").description("How fast they drift.").defaultValue(1.0).range(0.2, 3.0).sliderRange(0.2, 2.5).build());
    private final Setting<Double> hover = sgGeneral.add(new DoubleSetting.Builder()
        .name("hover-height").description("How high above the ground the fireflies float (blocks).")
        .defaultValue(2.5).range(0.5, 6.0).sliderRange(0.5, 5.0).visible(() -> style.get() != Style.MagicDust).build());
    private final Setting<Boolean> throughWalls = sgGeneral.add(new BoolSetting.Builder()
        .name("through-walls").description("Also draw the lights that are behind blocks.").defaultValue(false).build());

    // -------- look
    private final Setting<Double> size = sgLook.add(new DoubleSetting.Builder()
        .name("size").description("Size of the bright core of every light.").defaultValue(1.0).range(0.4, 3.0).sliderRange(0.4, 2.5).build());
    private final Setting<Double> glow = sgLook.add(new DoubleSetting.Builder()
        .name("glow").description("Size of the soft light around the core.").defaultValue(1.4).range(0.3, 4.0).sliderRange(0.5, 3.0).build());
    private final Setting<Integer> opacity = sgLook.add(new IntSetting.Builder()
        .name("brightness").description("How bright the lights are at their brightest.").defaultValue(230).range(30, 255).sliderRange(60, 255).build());
    private final Setting<Double> pulse = sgLook.add(new DoubleSetting.Builder()
        .name("pulse-speed").description("How fast they switch on and off.").defaultValue(1.0).range(0.3, 3.0).sliderRange(0.3, 2.5).build());
    private final Setting<Double> camFade = sgLook.add(new DoubleSetting.Builder()
        .name("camera-fade").description("Lights closer than this to your eyes fade out so they never cover your view (blocks).")
        .defaultValue(1.5).range(0.0, 6.0).sliderRange(0.0, 5.0).build());

    // -------- colors
    private final Setting<Palette> palette = sgColor.add(new EnumSetting.Builder<Palette>()
        .name("colors").description("Natural: yellow-green fireflies and purple / blue / pink dust. Rainbow: slowly changing. Custom: one color.")
        .defaultValue(Palette.Natural).build());
    private final Setting<SettingColor> customColor = sgColor.add(new ColorSetting.Builder()
        .name("custom-color").description("The color of the lights.")
        .defaultValue(new SettingColor(190, 255, 90, 255)).visible(() -> palette.get() == Palette.Custom).build());
    private final Setting<Double> rainbowSpeed = sgColor.add(new DoubleSetting.Builder()
        .name("rainbow-speed").description("How fast the rainbow turns.").defaultValue(1.0).range(0.1, 5.0).sliderRange(0.1, 3.0)
        .visible(() -> palette.get() == Palette.Rainbow).build());

    private static final class Fly {
        double x, y, z;
        double yaw, pitch, tYaw, tPitch, turnIn;
        double speed, phase, period, hue, bob;
        double age, life, homeY, rehomeIn;
        boolean dust, alive;
    }

    private final List<Fly> flies = new ArrayList<>();
    private final Random rng = new Random();
    private final BlockPos.Mutable pos = new BlockPos.Mutable();

    private Object lastWorld;
    private double target;     // 0..1 how much the surroundings call for lights
    private double intensity;  // 0..1 smoothed, so they fade in and out

    // camera frame (set every frame)
    private double rx, ry, rz, ux, uy, uz;
    private double camX, camY, camZ;

    public Fireflies() {
        super(BlockOutlinesAddon.EFFECTS, "fireflies",
            "Glowing fireflies and magic dust that float around you in the dark, switching on and off softly.");
    }

    @Override
    public void onActivate() {
        flies.clear();
        intensity = 0;
    }

    @Override
    public void onDeactivate() {
        flies.clear();
    }

    // ------------------------------------------------------------------ when to show (20 times a second)

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null || mc.player == null) {
            target = 0;
            return;
        }
        if (lastWorld != mc.world) {
            lastWorld = mc.world;
            flies.clear();
        }

        double night = nightFactor();
        int light = mc.world.getLightLevel(mc.player.getBlockPos());
        double dark = clamp((darkLevel.get() + 3.0 - light) / 3.0);

        target = switch (when.get()) {
            case Night -> night;
            case Dark -> dark;
            case NightOrDark -> Math.max(night, dark);
            case Always -> 1.0;
        };
    }

    /** 0 in the day, 1 in the night, with a soft change at dusk and dawn. */
    private double nightFactor() {
        long t = mc.world.getTimeOfDay() % 24000L;
        if (t < 12000L) return 0;
        if (t < 13500L) return (t - 12000L) / 1500.0;
        if (t < 22500L) return 1;
        return (24000L - t) / 1500.0;
    }

    // ------------------------------------------------------------------ world checks

    private boolean clear(double x, double y, double z) {
        pos.set((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
        return mc.world.getBlockState(pos).isAir();
    }

    /** The top of the first floor below (x, startY, z). Skips the blocks you are inside of first. NaN when there is none. */
    private double groundY(double x, double startY, double z, int maxDown) {
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        int by = (int) Math.floor(startY);
        boolean inAir = false;
        for (int i = 0; i <= maxDown; i++) {
            pos.set(bx, by - i, bz);
            if (mc.world.getBlockState(pos).isAir()) inAir = true;
            else if (inAir) return by - i + 1;
        }
        return Double.NaN;
    }

    // ------------------------------------------------------------------ the lights

    private boolean wantDust() {
        return switch (style.get()) {
            case Fireflies -> false;
            case MagicDust -> true;
            case Mixed -> rng.nextDouble() < 0.35;
        };
    }

    /** Puts a light somewhere new near the player. {@code initial} spreads them over the whole area, later ones appear near the edge. */
    private void spawn(Fly f, boolean initial) {
        double px = mc.player.getX(), py = mc.player.getY(), pz = mc.player.getZ();
        double r = radius.get();
        f.dust = wantDust();

        for (int attempt = 0; attempt < 8; attempt++) {
            double ang = rng.nextDouble() * Math.PI * 2;
            double d = initial ? r * Math.sqrt(rng.nextDouble()) : r * (0.7 + 0.3 * rng.nextDouble());
            double x = px + Math.cos(ang) * d, z = pz + Math.sin(ang) * d;
            double y, home;

            if (f.dust) {
                y = py + 0.3 + rng.nextDouble() * 3.5;
                home = y;
            } else {
                double gy = groundY(x, py + 3.0 + rng.nextDouble() * 3.0, z, 12);
                if (Double.isNaN(gy) || Math.abs(gy - py) > 10) continue;
                home = gy + 0.4 + rng.nextDouble() * hover.get();
                y = home;
            }
            if (!clear(x, y, z)) continue;

            f.x = x;
            f.y = y;
            f.z = z;
            f.homeY = home;
            f.yaw = rng.nextDouble() * Math.PI * 2;
            f.tYaw = f.yaw;
            f.pitch = 0;
            f.tPitch = 0;
            f.turnIn = rng.nextDouble() * 2.0;
            f.speed = (f.dust ? 0.22 : 0.55) * (0.55 + rng.nextDouble() * 0.75);
            f.phase = rng.nextDouble() * Math.PI * 2;
            f.period = f.dust ? 1.2 + rng.nextDouble() * 1.8 : 2.5 + rng.nextDouble() * 3.0;
            f.bob = rng.nextDouble() * Math.PI * 2;
            f.hue = f.dust ? 0.5 + rng.nextDouble() * 0.42 : 0.14 + rng.nextDouble() * 0.08;
            f.age = initial ? rng.nextDouble() * 20.0 : 0;
            f.life = f.dust ? 5.0 + rng.nextDouble() * 5.0 : 35.0 + rng.nextDouble() * 50.0;
            if (initial && f.age > f.life - 3) f.age = 0;
            f.rehomeIn = 1.0 + rng.nextDouble();
            f.alive = true;
            return;
        }
        f.alive = false;
    }

    private void update(Fly f, double dt, double secs) {
        f.age += dt;
        double dx0 = f.x - mc.player.getX(), dz0 = f.z - mc.player.getZ();
        double reach = radius.get() * 1.2;
        if (f.age >= f.life || dx0 * dx0 + dz0 * dz0 > reach * reach) {
            spawn(f, false);
            return;
        }

        // choose a new, gentle heading from time to time and turn toward it smoothly
        f.turnIn -= dt;
        if (f.turnIn <= 0) {
            f.tYaw = f.yaw + (rng.nextDouble() - 0.5) * 2.4;
            f.tPitch = (rng.nextDouble() - 0.5) * 0.6;
            f.turnIn = 0.8 + rng.nextDouble() * 2.0;
        }
        double k = 1.0 - Math.exp(-dt * 1.6);
        f.yaw += (f.tYaw - f.yaw) * k;
        f.pitch += (f.tPitch - f.pitch) * k;

        double v = f.speed * speed.get();
        double vx = -Math.sin(f.yaw) * Math.cos(f.pitch) * v;
        double vz = Math.cos(f.yaw) * Math.cos(f.pitch) * v;
        double vy = Math.sin(f.pitch) * v * 0.5 + Math.sin(secs * 1.7 + f.bob) * 0.10;
        if (f.dust) vy += 0.16 * speed.get();

        double nx = f.x + vx * dt, ny = f.y + vy * dt, nz = f.z + vz * dt;
        if (!f.dust) {
            // stay near the ground: pull toward the hover height, and look for the floor again now and then
            ny += (f.homeY - ny) * (1.0 - Math.exp(-dt * 0.7));
            f.rehomeIn -= dt;
            if (f.rehomeIn <= 0) {
                f.rehomeIn = 1.2;
                double gy = groundY(nx, ny + 2.0, nz, 10);
                if (!Double.isNaN(gy)) f.homeY = gy + 0.4 + rng.nextDouble() * hover.get();
            }
        }

        if (clear(nx, ny, nz)) {
            f.x = nx;
            f.y = ny;
            f.z = nz;
        } else {
            // a block is in the way: turn around
            f.yaw += Math.PI * (0.6 + rng.nextDouble() * 0.8);
            f.tYaw = f.yaw;
            f.turnIn = 0.6;
        }
    }

    /** How lit the light is right now, 0..1. Fireflies switch fully on and off, dust twinkles. */
    private double brightness(Fly f, double secs) {
        double p = pulse.get();
        if (f.dust) return 0.4 + 0.6 * (0.5 + 0.5 * Math.sin(secs * p * 2.6 / f.period * 2.0 + f.phase * 3.0));
        double w = Math.sin(secs * p * Math.PI * 2.0 / f.period + f.phase);
        double x = clamp((w - 0.2) / 0.8);
        return 0.03 + 0.97 * x * x * (3 - 2 * x);
    }

    // ------------------------------------------------------------------ drawing

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (mc.world == null || mc.player == null || mc.gameRenderer == null) return;
        double dt = Math.min(0.1, event.frameTime);

        intensity += (target - intensity) * Math.min(1.0, dt * 1.2);
        if (target <= 0.001 && intensity < 0.01) {
            flies.clear();
            return;
        }

        // keep exactly "amount" lights, adding a few per frame so nothing stalls
        int want = amount.get();
        while (flies.size() > want) flies.remove(flies.size() - 1);
        int spawned = 0;
        while (flies.size() < want && spawned < 8) {
            Fly f = new Fly();
            spawn(f, true);
            flies.add(f);
            spawned++;
        }

        Camera cam = mc.gameRenderer.getCamera();
        double yaw = Math.toRadians(cam.getYaw()), pitch = Math.toRadians(cam.getPitch());
        double fx = -Math.sin(yaw) * Math.cos(pitch), fy = -Math.sin(pitch), fz = Math.cos(yaw) * Math.cos(pitch);
        double rl = Math.max(1e-6, Math.sqrt(fz * fz + fx * fx));
        rx = -fz / rl;
        ry = 0;
        rz = fx / rl;
        ux = ry * fz - rz * fy;
        uy = rz * fx - rx * fz;
        uz = rx * fy - ry * fx;
        camX = event.offsetX;
        camY = event.offsetY;
        camZ = event.offsetZ;

        Renderer3D r = throughWalls.get() ? event.renderer : event.depthRenderer;
        double secs = System.currentTimeMillis() / 1000.0;
        int respawns = 0;

        for (Fly f : flies) {
            if (!f.alive) {
                if (respawns++ < 4) spawn(f, false);
                continue;
            }
            update(f, dt, secs);
            if (!f.alive) continue;

            double fade = clamp(Math.min(f.age / 1.2, (f.life - f.age) / 1.5));
            double b = brightness(f, secs);
            int alpha = (int) (opacity.get() * b * fade * intensity * nearFade(f.x, f.y, f.z));
            if (alpha < 3) continue;
            draw(r, f, secs, alpha, b);
        }
    }

    private void draw(Renderer3D r, Fly f, double secs, int alpha, double b) {
        Color c = color(f, secs, alpha);
        double core = 0.045 * size.get();
        double rad = core * 6.0 * glow.get();

        // soft light around it, then a brighter inner light
        halo(r, f.x, f.y, f.z, rad, new Color(c.r, c.g, c.b, (int) (alpha * 0.45)));
        halo(r, f.x, f.y, f.z, rad * 0.45, new Color(c.r, c.g, c.b, (int) (alpha * 0.8)));

        // the hot core, a little closer to white
        Color hot = CosmeticUtil.lerp(c, new Color(255, 255, 255, alpha), 0.6, alpha);
        quad(r,
            f.x - rx * core - ux * core, f.y - ry * core - uy * core, f.z - rz * core - uz * core,
            f.x + rx * core - ux * core, f.y + ry * core - uy * core, f.z + rz * core - uz * core,
            f.x + rx * core + ux * core, f.y + ry * core + uy * core, f.z + rz * core + uz * core,
            f.x - rx * core + ux * core, f.y - ry * core + uy * core, f.z - rz * core + uz * core,
            hot, hot, hot, hot);

        // magic dust sparkles with four soft spikes
        if (f.dust) {
            double len = rad * (1.4 + 1.2 * b), w = core * 0.5;
            Color tip = new Color(c.r, c.g, c.b, 0);
            Color mid = new Color(hot.r, hot.g, hot.b, (int) (alpha * 0.9));
            spike(r, f, rx, ry, rz, ux, uy, uz, len, w, mid, tip);
            spike(r, f, -rx, -ry, -rz, ux, uy, uz, len, w, mid, tip);
            spike(r, f, ux, uy, uz, rx, ry, rz, len * 0.8, w, mid, tip);
            spike(r, f, -ux, -uy, -uz, rx, ry, rz, len * 0.8, w, mid, tip);
        }
    }

    /** A round soft light facing the camera: the color in the middle, clear at the edge. */
    private void halo(Renderer3D r, double x, double y, double z, double rad, Color c) {
        Color z0 = new Color(c.r, c.g, c.b, 0);
        int n = 10;
        for (int i = 0; i < n; i++) {
            double a0 = Math.PI * 2 * i / n, a1 = Math.PI * 2 * (i + 1) / n;
            double c0 = Math.cos(a0) * rad, s0 = Math.sin(a0) * rad, c1 = Math.cos(a1) * rad, s1 = Math.sin(a1) * rad;
            quad(r, x, y, z, x, y, z,
                x + rx * c1 + ux * s1, y + ry * c1 + uy * s1, z + rz * c1 + uz * s1,
                x + rx * c0 + ux * s0, y + ry * c0 + uy * s0, z + rz * c0 + uz * s0,
                c, c, z0, z0);
        }
    }

    private void spike(Renderer3D r, Fly f, double dx, double dy, double dz, double px, double py, double pz,
                       double len, double w, Color mid, Color tip) {
        quad(r,
            f.x - px * w, f.y - py * w, f.z - pz * w,
            f.x + px * w, f.y + py * w, f.z + pz * w,
            f.x + dx * len, f.y + dy * len, f.z + dz * len,
            f.x + dx * len, f.y + dy * len, f.z + dz * len,
            mid, mid, tip, tip);
    }

    private Color color(Fly f, double secs, int alpha) {
        return switch (palette.get()) {
            case Rainbow -> CosmeticUtil.hsv(secs * rainbowSpeed.get() * 0.08 + f.hue * 0.4, 0.65f, 1f, alpha);
            case Custom -> {
                SettingColor c = customColor.get();
                yield new Color(c.r, c.g, c.b, alpha);
            }
            default -> CosmeticUtil.hsv(f.hue, f.dust ? 0.5f : 0.72f, 1f, alpha);
        };
    }

    /** 0 right at the camera, 1 once the point is camera-fade blocks away. */
    private double nearFade(double x, double y, double z) {
        double d = camFade.get();
        if (d <= 0.01) return 1.0;
        double dx = x - camX, dy = y - camY, dz = z - camZ;
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double t = clamp((dist - d * 0.3) / (d * 0.7));
        return t * t * (3 - 2 * t);
    }

    private static double clamp(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    /** Meteor's quad() wants the colors as top-left, top-right, bottom-right, bottom-left (= vertices 2, 3, 4, 1). */
    private static void quad(Renderer3D r,
                             double x1, double y1, double z1, double x2, double y2, double z2,
                             double x3, double y3, double z3, double x4, double y4, double z4,
                             Color cv1, Color cv2, Color cv3, Color cv4) {
        r.quad(x1, y1, z1, x2, y2, z2, x3, y3, z3, x4, y4, z4, cv2, cv3, cv4, cv1);
    }
}
