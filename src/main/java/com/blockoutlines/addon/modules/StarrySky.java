package com.blockoutlines.addon.modules;

import com.blockoutlines.addon.BlockOutlinesAddon;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.renderer.Renderer3D;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.meteorclient.utils.world.Dimension;
import meteordevelopment.orbit.EventHandler;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * A prettier sky: a color that slowly drifts between two blues, a bluish fog tint, soft swirling nebula clouds,
 * twinkling stars and shooting stars. Everything is client side and drawn as plain colored shapes on a dome around
 * the camera, so it works without shaders and the terrain hides it where it should.
 *
 * The sky color and the fog tint come from two small mixins (StarrySkyRenderingMixin, StarrySkyFogMixin), the rest is
 * drawn in the 3D render event.
 */
public class StarrySky extends Module {
    private static final int RING = 22; // segments of one puff outline
    private static final int PUFFS = 9; // puffs per smoke mass

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgSky = settings.createGroup("Sky color");
    private final SettingGroup sgNebula = settings.createGroup("Nebula");
    private final SettingGroup sgStars = settings.createGroup("Stars");
    private final SettingGroup sgShooting = settings.createGroup("Shooting stars");
    private final SettingGroup sgDay = settings.createGroup("Day mode");

    // -------- general
    private final Setting<Boolean> overworldOnly = sgGeneral.add(new BoolSetting.Builder()
        .name("overworld-only")
        .description("Only change the overworld sky.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> domeRadius = sgGeneral.add(new IntSetting.Builder()
        .name("dome-size")
        .description("How far away the sky is drawn, in percent of your render distance. Lower it if stars cut through far mountains.")
        .defaultValue(75)
        .range(40, 95)
        .sliderRange(40, 95)
        .build()
    );

    private final Setting<Double> rotationSpeed = sgGeneral.add(new DoubleSetting.Builder()
        .name("rotation-speed")
        .description("How fast the whole sky turns, in radians per second. 0 keeps it still.")
        .defaultValue(0.01)
        .range(0, 0.2)
        .sliderRange(0, 0.1)
        .build()
    );

    // -------- sky color
    private final Setting<SettingColor> skyColorA = sgSky.add(new ColorSetting.Builder()
        .name("sky-color")
        .description("Main color of the sky.")
        .defaultValue(new SettingColor(40, 150, 235, 255))
        .build()
    );

    private final Setting<SettingColor> skyColorB = sgSky.add(new ColorSetting.Builder()
        .name("sky-color-2")
        .description("The sky slowly and smoothly blends between this color and the main one (try two shades of the same color for the softest look).")
        .defaultValue(new SettingColor(25, 70, 175, 255))
        .build()
    );

    private final Setting<Boolean> colorShift = sgSky.add(new BoolSetting.Builder()
        .name("color-shift")
        .description("Let the sky drift between the two colors.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> colorShiftSpeed = sgSky.add(new DoubleSetting.Builder()
        .name("color-shift-speed")
        .description("How fast the sky color drifts.")
        .defaultValue(0.12)
        .range(0.01, 1.0)
        .sliderRange(0.01, 1.0)
        .visible(colorShift::get)
        .build()
    );

    private final Setting<Boolean> fogTint = sgSky.add(new BoolSetting.Builder()
        .name("fog-tint")
        .description("Change the color of the game fog. Off = normal Minecraft fog (the safe default).")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> fogFollowsSky = sgSky.add(new BoolSetting.Builder()
        .name("fog-follows-sky")
        .description("The fog takes the current sky color, so the horizon melts into the sky instead of showing a different color.")
        .defaultValue(true)
        .visible(fogTint::get)
        .build()
    );

    private final Setting<SettingColor> fogColor = sgSky.add(new ColorSetting.Builder()
        .name("fog-color")
        .description("Color of the haze when it does not follow the sky.")
        .defaultValue(new SettingColor(110, 175, 240, 255))
        .visible(() -> fogTint.get() && !fogFollowsSky.get())
        .build()
    );

    private final Setting<Double> fogStrength = sgSky.add(new DoubleSetting.Builder()
        .name("fog-strength")
        .description("How much of the normal fog color is replaced (0 = none, 1 = all).")
        .defaultValue(0.7)
        .range(0, 1)
        .sliderRange(0, 1)
        .visible(fogTint::get)
        .build()
    );

    // -------- nebula
    private final Setting<Boolean> nebula = sgNebula.add(new BoolSetting.Builder()
        .name("nebula")
        .description("Soft glowing clouds that drift across the sky.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> nebulaCount = sgNebula.add(new IntSetting.Builder()
        .name("cloud-count")
        .description("How many big smoke masses drift across the sky (each one is made of several soft puffs).")
        .defaultValue(6)
        .range(1, 20)
        .sliderRange(2, 14)
        .visible(nebula::get)
        .build()
    );

    private final Setting<Double> nebulaOpacity = sgNebula.add(new DoubleSetting.Builder()
        .name("cloud-opacity")
        .description("How strong the smoke is.")
        .defaultValue(0.5)
        .range(0.05, 1.0)
        .sliderRange(0.05, 1.0)
        .visible(nebula::get)
        .build()
    );

    private final Setting<SettingColor> nebulaColor1 = sgNebula.add(new ColorSetting.Builder()
        .name("cloud-color-1")
        .description("Bright cloud color.")
        .defaultValue(new SettingColor(70, 190, 255, 255))
        .visible(nebula::get)
        .build()
    );

    private final Setting<SettingColor> nebulaColor2 = sgNebula.add(new ColorSetting.Builder()
        .name("cloud-color-2")
        .description("Second (deeper) smoke color.")
        .defaultValue(new SettingColor(40, 100, 210, 255))
        .visible(nebula::get)
        .build()
    );

    private final Setting<SettingColor> nebulaDark = sgNebula.add(new ColorSetting.Builder()
        .name("dark-cloud-color")
        .description("Dark smoke that adds depth.")
        .defaultValue(new SettingColor(6, 16, 52, 255))
        .visible(nebula::get)
        .build()
    );

    private final Setting<Double> cloudSpeed = sgNebula.add(new DoubleSetting.Builder()
        .name("cloud-speed")
        .description("How fast the smoke masses wander across the sky. 1 is calm, higher is faster.")
        .defaultValue(1.0)
        .range(0.0, 6.0)
        .sliderRange(0.0, 4.0)
        .visible(nebula::get)
        .build()
    );

    private final Setting<Integer> tendrilCount = sgNebula.add(new IntSetting.Builder()
        .name("tendril-count")
        .description("Optional thin curling strands of smoke. Off (0) by default, the sky uses soft smoke masses only.")
        .defaultValue(0)
        .range(0, 120)
        .sliderRange(0, 80)
        .visible(nebula::get)
        .build()
    );

    private final Setting<Double> tendrilWidth = sgNebula.add(new DoubleSetting.Builder()
        .name("tendril-width")
        .description("Thickness of the strands.")
        .defaultValue(1.0)
        .range(0.3, 3.0)
        .sliderRange(0.3, 2.5)
        .visible(nebula::get)
        .build()
    );

    private final Setting<Boolean> swirl = sgNebula.add(new BoolSetting.Builder()
        .name("swirl")
        .description("Twist the clouds slowly so they look like they swirl.")
        .defaultValue(true)
        .visible(nebula::get)
        .build()
    );

    // -------- stars
    private final Setting<Boolean> stars = sgStars.add(new BoolSetting.Builder()
        .name("stars")
        .description("Twinkling stars.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> starCount = sgStars.add(new IntSetting.Builder()
        .name("star-count")
        .description("How many stars are drawn.")
        .defaultValue(600)
        .range(20, 3000)
        .sliderRange(100, 1500)
        .visible(stars::get)
        .build()
    );

    private final Setting<Double> starSize = sgStars.add(new DoubleSetting.Builder()
        .name("star-size")
        .description("Size of the stars.")
        .defaultValue(1.0)
        .range(0.3, 4.0)
        .sliderRange(0.3, 3.0)
        .visible(stars::get)
        .build()
    );

    private final Setting<SettingColor> starColor = sgStars.add(new ColorSetting.Builder()
        .name("star-color")
        .description("Color of the stars.")
        .defaultValue(new SettingColor(225, 238, 255, 255))
        .visible(stars::get)
        .build()
    );

    private final Setting<Boolean> twinkle = sgStars.add(new BoolSetting.Builder()
        .name("twinkle")
        .description("Let the stars flicker.")
        .defaultValue(true)
        .visible(stars::get)
        .build()
    );

    private final Setting<Double> twinkleSpeed = sgStars.add(new DoubleSetting.Builder()
        .name("twinkle-speed")
        .description("How fast the stars flicker.")
        .defaultValue(1.2)
        .range(0.1, 5.0)
        .sliderRange(0.1, 5.0)
        .visible(() -> stars.get() && twinkle.get())
        .build()
    );

    private final Setting<Integer> minElevation = sgStars.add(new IntSetting.Builder()
        .name("min-elevation")
        .description("Lowest angle above the horizon (in degrees) where stars and clouds appear.")
        .defaultValue(3)
        .range(-10, 60)
        .sliderRange(-10, 40)
        .build()
    );

    // -------- shooting stars
    private final Setting<Boolean> shooting = sgShooting.add(new BoolSetting.Builder()
        .name("shooting-stars")
        .description("Shooting stars now and then.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> shootingInterval = sgShooting.add(new DoubleSetting.Builder()
        .name("interval")
        .description("Average seconds between shooting stars.")
        .defaultValue(2.5)
        .range(0.5, 30)
        .sliderRange(0.5, 15)
        .visible(shooting::get)
        .build()
    );

    private final Setting<SettingColor> shootingColor = sgShooting.add(new ColorSetting.Builder()
        .name("shooting-color")
        .description("Color of the shooting stars.")
        .defaultValue(new SettingColor(170, 220, 255, 255))
        .visible(shooting::get)
        .build()
    );

    // -------- day mode
    public enum TimeMode { World_Time, Real_Clock, Always_Day, Always_Night }

    private final Setting<Boolean> dayMode = sgDay.add(new BoolSetting.Builder()
        .name("day-mode")
        .description("When it is morning/day the sky turns bright blue with fluffy white living clouds and a rainbow, then fades back to the starry night.")
        .defaultValue(true)
        .build()
    );

    private final Setting<TimeMode> timeMode = sgDay.add(new EnumSetting.Builder<TimeMode>()
        .name("time-source")
        .description("World_Time follows the Minecraft clock, Real_Clock follows your computer clock, or force day / night.")
        .defaultValue(TimeMode.World_Time)
        .visible(dayMode::get)
        .build()
    );

    private final Setting<SettingColor> daySkyColorA = sgDay.add(new ColorSetting.Builder()
        .name("day-sky-color")
        .description("Main color of the day sky.")
        .defaultValue(new SettingColor(52, 158, 255, 255))
        .visible(dayMode::get)
        .build()
    );

    private final Setting<SettingColor> daySkyColorB = sgDay.add(new ColorSetting.Builder()
        .name("day-sky-color-2")
        .description("The day sky slowly blends between this color and the main one.")
        .defaultValue(new SettingColor(120, 205, 255, 255))
        .visible(dayMode::get)
        .build()
    );

    private final Setting<SettingColor> dawnTint = sgDay.add(new ColorSetting.Builder()
        .name("sunrise-tint")
        .description("Warm color the sky gets for a moment during sunrise and sunset.")
        .defaultValue(new SettingColor(255, 170, 150, 255))
        .visible(dayMode::get)
        .build()
    );

    private final Setting<Double> dawnStrength = sgDay.add(new DoubleSetting.Builder()
        .name("sunrise-strength")
        .description("How strong the warm sunrise/sunset tint is. 0 turns it off.")
        .defaultValue(0.35)
        .range(0, 1)
        .sliderRange(0, 1)
        .visible(dayMode::get)
        .build()
    );

    private final Setting<Boolean> dayClouds = sgDay.add(new BoolSetting.Builder()
        .name("day-clouds")
        .description("Fluffy white clouds that slowly drift, grow and change shape.")
        .defaultValue(true)
        .visible(dayMode::get)
        .build()
    );

    private final Setting<Integer> dayCloudCount = sgDay.add(new IntSetting.Builder()
        .name("day-cloud-count")
        .description("How many cloud masses are in the day sky.")
        .defaultValue(12)
        .range(1, 20)
        .sliderRange(3, 20)
        .visible(() -> dayMode.get() && dayClouds.get())
        .build()
    );

    private final Setting<Double> dayCloudOpacity = sgDay.add(new DoubleSetting.Builder()
        .name("day-cloud-opacity")
        .description("How solid the white clouds are.")
        .defaultValue(0.95)
        .range(0.1, 1.0)
        .sliderRange(0.1, 1.0)
        .visible(() -> dayMode.get() && dayClouds.get())
        .build()
    );

    private final Setting<Double> dayCloudSize = sgDay.add(new DoubleSetting.Builder()
        .name("day-cloud-size")
        .description("Size of the day clouds.")
        .defaultValue(1.25)
        .range(0.5, 3.0)
        .sliderRange(0.5, 2.5)
        .visible(() -> dayMode.get() && dayClouds.get())
        .build()
    );

    private final Setting<Double> dayCloudSpeed = sgDay.add(new DoubleSetting.Builder()
        .name("day-cloud-speed")
        .description("How fast the day clouds drift and change shape.")
        .defaultValue(1.4)
        .range(0.0, 6.0)
        .sliderRange(0.0, 4.0)
        .visible(() -> dayMode.get() && dayClouds.get())
        .build()
    );

    private final Setting<SettingColor> dayCloudColor = sgDay.add(new ColorSetting.Builder()
        .name("day-cloud-color")
        .description("Color of the bright part of the clouds.")
        .defaultValue(new SettingColor(255, 255, 255, 255))
        .visible(() -> dayMode.get() && dayClouds.get())
        .build()
    );

    private final Setting<SettingColor> dayCloudShade = sgDay.add(new ColorSetting.Builder()
        .name("day-cloud-shade")
        .description("Color of the soft shadowed part of the clouds.")
        .defaultValue(new SettingColor(196, 214, 238, 255))
        .visible(() -> dayMode.get() && dayClouds.get())
        .build()
    );

    private final Setting<Boolean> rainbow = sgDay.add(new BoolSetting.Builder()
        .name("rainbow")
        .description("A soft shimmering rainbow in the day sky (fades away when it rains).")
        .defaultValue(true)
        .visible(dayMode::get)
        .build()
    );

    private final Setting<Double> rainbowOpacity = sgDay.add(new DoubleSetting.Builder()
        .name("rainbow-opacity")
        .description("How strong the rainbow is.")
        .defaultValue(0.6)
        .range(0.05, 1.0)
        .sliderRange(0.05, 1.0)
        .visible(() -> dayMode.get() && rainbow.get())
        .build()
    );

    private final Setting<Integer> rainbowDirection = sgDay.add(new IntSetting.Builder()
        .name("rainbow-direction")
        .description("Which way the rainbow is, in degrees around you.")
        .defaultValue(210)
        .range(0, 360)
        .sliderRange(0, 360)
        .visible(() -> dayMode.get() && rainbow.get())
        .build()
    );

    private final Setting<Integer> rainbowHeight = sgDay.add(new IntSetting.Builder()
        .name("rainbow-height")
        .description("Lower value = the rainbow arc sits lower on the horizon.")
        .defaultValue(-12)
        .range(-35, 10)
        .sliderRange(-35, 10)
        .visible(() -> dayMode.get() && rainbow.get())
        .build()
    );

    // -------- state
    private static class Star {
        double x, y, z;       // direction on the sphere
        double size;          // half size as an angle in radians
        double phase, speed;  // twinkle
        boolean halo;
    }

    private static class Cloud {
        double az0, elMid, elAmp;   // center of the smoke mass it belongs to
        double velAz, elFreq;       // how the mass wanders (random per mass)
        double offDist, offAng, offSpin; // where this puff sits inside the mass
        double orient, stretch, w1, w2, w3; // irregular, stretched, constantly changing outline
        double radius;              // angular radius in radians
        double alpha;
        double phase, speed;
        int kind;                   // 0 bright, 1 second color, 2 dark
        int mass;                   // index of the smoke mass this puff belongs to
    }

    private static class Strand {
        double az0, elMid, elAmp, velAz, elFreq;
        double heading, len, curl, freq, wiggle, width;
        double alpha, phase, speed;
        int kind;
    }

    private static class Meteor {
        double x, y, z;     // start direction
        double tx, ty, tz;  // unit tangent, the way it travels
        double speed;       // radians per second
        double life, age;
        double width;
    }

    private final List<Star> starList = new ArrayList<>();
    private final List<Cloud> cloudList = new ArrayList<>();
    private final List<Strand> strandList = new ArrayList<>();
    private int builtStrands = -1;
    private final List<Meteor> meteors = new ArrayList<>();
    private final Random random = new Random();
    private int builtStars = -1, builtClouds = -1;
    private double nextMeteor;
    private long lastNanos;
    private double tendrilFade = 1;
    private double dayCur = -1;      // 0 = night, 1 = full day, eased
    private long lastDayNanos;
    private double cloudTime;        // own clock for clouds so changing speed never makes them jump

    private final SettingColor tmpCloudColor = new SettingColor(255, 255, 255, 255);
    private final Color c1 = new Color(), c2 = new Color(), c3 = new Color(), c4 = new Color();
    private final Color[] ringCols = {new Color(), new Color(), new Color(), new Color(), new Color()};

    public StarrySky() {
        super(BlockOutlinesAddon.COSMETICS, "starry-sky", "A prettier sky: drifting sky color, bluish haze, swirling clouds, twinkling stars and shooting stars.");
    }

    @Override
    public void onActivate() {
        builtStars = -1;
        builtClouds = -1;
        meteors.clear();
        lastNanos = System.nanoTime();
        nextMeteor = 1.0;
        dayCur = -1;
        cloudTime = 0;
    }

    // ------------------------------------------------------------------ used by the mixins

    private boolean applies() {
        if (!isActive() || mc.world == null) return false;
        return !overworldOnly.get() || PlayerUtils.getDimension() == Dimension.Overworld;
    }

    private static double smooth(double a, double b, double x) {
        double t = Math.max(0, Math.min(1, (x - a) / (b - a)));
        return t * t * (3 - 2 * t);
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    /** Where the sky is between night (0) and day (1) right now, before easing. */
    private double targetDay() {
        if (!dayMode.get() || mc.world == null) return 0;
        switch (timeMode.get()) {
            case Always_Day: return 1;
            case Always_Night: return 0;
            case Real_Clock: {
                java.time.LocalTime now = java.time.LocalTime.now();
                double h = now.getHour() + now.getMinute() / 60.0;
                return h < 12 ? smooth(5.0, 7.5, h) : 1 - smooth(18.0, 20.5, h);
            }
            default: {
                double tt = mc.world.getTimeOfDay() % 24000L;
                if (tt < 0) tt += 24000;
                if (tt < 18000) return 1 - smooth(11500, 13500, tt);
                return smooth(22000, 24000, tt);
            }
        }
    }

    /** Eased day factor (0..1). Safe to call many times per frame. */
    private double day() {
        long now = System.nanoTime();
        double target = targetDay();
        if (dayCur < 0) {
            dayCur = target;
        } else {
            double dt = Math.min(0.25, (now - lastDayNanos) / 1.0e9);
            if (dt > 0) dayCur += (target - dayCur) * Math.min(1.0, dt * 1.6);
        }
        lastDayNanos = now;
        return dayCur;
    }

    private double rain() {
        if (mc.world == null) return 0;
        try {
            return Math.max(0, Math.min(1, mc.world.getRainGradient(1f)));
        } catch (Throwable e) {
            return 0;
        }
    }

    /** Current sky color (r, g, b as 0..255): night colors blended with the day colors by how much day it is. */
    private int[] skyRgb() {
        double st = System.nanoTime() / 1.0e9 * colorShiftSpeed.get();
        double t = 0;
        if (colorShift.get()) {
            t = 0.5 + 0.5 * Math.sin(st);
            t = t * t * (3 - 2 * t); // smoothstep: lingers on each color, blends gently between them
        }
        SettingColor a = skyColorA.get(), b = skyColorB.get();
        double r = lerp(a.r, b.r, t), g = lerp(a.g, b.g, t), bl = lerp(a.b, b.b, t);

        double d = day();
        if (d > 0.002) {
            SettingColor da = daySkyColorA.get(), db = daySkyColorB.get();
            double dr = lerp(da.r, db.r, t), dg = lerp(da.g, db.g, t), dbl = lerp(da.b, db.b, t);

            // warm glow during the change between night and day
            double dawn = Math.min(1, 4 * d * (1 - d)) * dawnStrength.get();
            SettingColor w = dawnTint.get();
            dr = lerp(dr, w.r, dawn); dg = lerp(dg, w.g, dawn); dbl = lerp(dbl, w.b, dawn);

            // rain makes the sky grey-blue
            double rn = rain() * 0.55;
            dr = lerp(dr, 118, rn); dg = lerp(dg, 130, rn); dbl = lerp(dbl, 150, rn);

            r = lerp(r, dr, d); g = lerp(g, dg, d); bl = lerp(bl, dbl, d);
        }
        return new int[]{(int) r, (int) g, (int) bl};
    }

    /** The sky color, or -1 when the sky should not be changed. Packed like Meteor does it. */
    public int skyColorPacked() {
        if (!applies()) return -1;
        int[] c = skyRgb();
        return new Color(c[0], c[1], c[2], 255).getPacked();
    }

    /** The fog color: the normal one mixed with the haze color, or the same one when the fog should not change. */
    public Vector4f fogColor(Vector4f original) {
        if (!applies() || !fogTint.get()) return original;

        float fr, fg, fb;
        if (fogFollowsSky.get()) {
            int[] c = skyRgb();
            fr = c[0] / 255f; fg = c[1] / 255f; fb = c[2] / 255f;
        } else {
            SettingColor f = fogColor.get();
            fr = f.r / 255f; fg = f.g / 255f; fb = f.b / 255f;
        }
        float s = fogStrength.get().floatValue();
        return new Vector4f(
            original.x + (fr - original.x) * s,
            original.y + (fg - original.y) * s,
            original.z + (fb - original.z) * s,
            original.w
        );
    }

    // ------------------------------------------------------------------ building the sky

    private void build() {
        double minEl = Math.toRadians(minElevation.get());

        if (builtStars != starCount.get()) {
            builtStars = starCount.get();
            starList.clear();
            Random r = new Random(7919);
            for (int i = 0; i < builtStars; i++) {
                Star s = new Star();
                // uniform on the part of the sphere above the minimum angle
                double sinMin = Math.sin(minEl);
                double y = sinMin + r.nextDouble() * (1 - sinMin);
                double az = r.nextDouble() * Math.PI * 2;
                double h = Math.sqrt(1 - y * y);
                s.x = h * Math.cos(az);
                s.y = y;
                s.z = h * Math.sin(az);
                double bright = r.nextDouble();
                s.size = 0.0013 + 0.0022 * bright * bright;
                s.phase = r.nextDouble() * Math.PI * 2;
                s.speed = 0.6 + r.nextDouble() * 1.4;
                s.halo = bright > 0.82;
                starList.add(s);
            }
        }

        int wantClouds = Math.max(nebulaCount.get(), dayCloudCount.get());
        if (builtClouds != wantClouds) {
            builtClouds = wantClouds;
            cloudList.clear();
            Random r = new Random(104729);
            for (int m = 0; m < builtClouds; m++) {
                double az0 = r.nextDouble() * Math.PI * 2;
                double elMid = Math.max(minEl + 0.25, 0.35) + r.nextDouble() * 0.75;
                double elAmp = 0.1 + r.nextDouble() * 0.25;
                // every mass picks its own random direction and speed
                double velAz = (r.nextBoolean() ? 1 : -1) * (0.012 + r.nextDouble() * 0.035);
                double elFreq = 0.02 + r.nextDouble() * 0.05;
                int massKind = m % 3;
                for (int k = 0; k < PUFFS; k++) {
                    Cloud c = new Cloud();
                    c.mass = m;
                    c.az0 = az0; c.elMid = elMid; c.elAmp = elAmp;
                    c.velAz = velAz; c.elFreq = elFreq;
                    c.offDist = 0.05 + r.nextDouble() * 0.75;
                    c.offAng = r.nextDouble() * Math.PI * 2;
                    c.offSpin = (r.nextBoolean() ? 1 : -1) * (0.03 + r.nextDouble() * 0.09);
                    c.radius = 0.4 + r.nextDouble() * 0.7;
                    c.alpha = 0.35 + r.nextDouble() * 0.4;
                    c.phase = r.nextDouble() * Math.PI * 2;
                    c.speed = 0.08 + r.nextDouble() * 0.12;
                    c.kind = k == PUFFS - 1 ? (massKind + 1) % 3 : massKind;
                    c.orient = r.nextDouble() * Math.PI * 2;
                    c.stretch = 1.0 + r.nextDouble() * 1.5;
                    c.w1 = r.nextDouble() * Math.PI * 2;
                    c.w2 = r.nextDouble() * Math.PI * 2;
                    c.w3 = r.nextDouble() * Math.PI * 2;
                    cloudList.add(c);
                }
            }
        }
    }

    private void buildStrands() {
        if (builtStrands == tendrilCount.get()) return;
        builtStrands = tendrilCount.get();
        strandList.clear();
        double minEl = Math.toRadians(minElevation.get());
        Random r = new Random(15485863);
        for (int i = 0; i < builtStrands; i++) {
            Strand st = new Strand();
            st.az0 = r.nextDouble() * Math.PI * 2;
            st.elMid = Math.max(minEl + 0.2, 0.3) + r.nextDouble() * 0.95;
            st.elAmp = 0.08 + r.nextDouble() * 0.25;
            st.velAz = (r.nextBoolean() ? 1 : -1) * (0.01 + r.nextDouble() * 0.04);
            st.elFreq = 0.02 + r.nextDouble() * 0.05;
            st.heading = r.nextDouble() * Math.PI * 2;
            st.len = 0.5 + r.nextDouble() * 1.0;
            st.curl = 0.12 + r.nextDouble() * 0.3;
            st.freq = 0.8 + r.nextDouble() * 1.8;
            st.wiggle = (r.nextBoolean() ? 1 : -1) * (0.1 + r.nextDouble() * 0.3);
            st.width = 0.035 + r.nextDouble() * 0.09;
            st.alpha = 0.5 + r.nextDouble() * 0.5;
            st.phase = r.nextDouble() * Math.PI * 2;
            st.speed = 0.08 + r.nextDouble() * 0.15;
            st.kind = i % 3;
            strandList.add(st);
        }
    }

    // ------------------------------------------------------------------ drawing

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (!applies() || mc.player == null) return;

        long now = System.nanoTime();
        double dt = Math.min(0.1, (now - lastNanos) / 1.0e9);
        lastNanos = now;
        double t = now / 1.0e9;

        build();
        buildStrands();

        double d = day();
        double nightFade = 1 - d;
        cloudTime += dt * lerp(cloudSpeed.get(), dayCloudSpeed.get(), d);

        double cx = event.offsetX, cy = event.offsetY, cz = event.offsetZ;
        double radius = Math.max(48, Math.min(480, mc.options.getViewDistance().getValue() * 16 * (domeRadius.get() / 100.0)));
        double spin = t * rotationSpeed.get();
        double cs = Math.cos(spin), sn = Math.sin(spin);

        Renderer3D r = event.depthRenderer;

        if (rainbow.get() && dayMode.get() && d > 0.05) drawRainbow(r, t, d, cx, cy, cz, radius);
        if (nebula.get() || (dayMode.get() && dayClouds.get())) {
            drawClouds(r, d, cx, cy, cz, radius, cs, sn);
            if (nebula.get() && tendrilCount.get() > 0 && nightFade > 0.02) {
                this.tendrilFade = nightFade;
                drawTendrils(r, t, cx, cy, cz, radius, cs, sn);
            }
        }
        if (stars.get() && nightFade > 0.02) drawStars(r, t, nightFade, cx, cy, cz, radius, cs, sn);
        if (shooting.get() && nightFade > 0.02) drawMeteors(r, dt, nightFade, cx, cy, cz, radius);
    }

    private void drawStars(Renderer3D r, double t, double nightFade, double cx, double cy, double cz, double radius, double cs, double sn) {
        SettingColor base = starColor.get();
        double sizeScale = starSize.get();
        boolean flicker = twinkle.get();
        double speed = twinkleSpeed.get();

        for (Star s : starList) {
            // the sky turns around the vertical axis
            double dx = s.x * cs - s.z * sn;
            double dz = s.x * sn + s.z * cs;
            double dy = s.y;

            double a = 1.0;
            if (flicker) a = 0.35 + 0.65 * (0.5 + 0.5 * Math.sin(t * s.speed * speed + s.phase));
            a *= nightFade;

            // sideways and up vectors that face the camera
            double ux, uy, uz;
            if (Math.abs(dy) > 0.98) { ux = 1; uy = 0; uz = 0; }
            else {
                ux = -dz; uy = 0; uz = dx;
                double l = Math.sqrt(ux * ux + uz * uz);
                ux /= l; uz /= l;
            }
            double vx = dy * uz - dz * uy, vy = dz * ux - dx * uz, vz = dx * uy - dy * ux;

            double px = cx + dx * radius, py = cy + dy * radius, pz = cz + dz * radius;
            double hs = s.size * sizeScale * radius;

            c1.set(base.r, base.g, base.b, (int) (255 * a));
            quad(r, px - ux * hs, py - uy * hs - 0, pz - uz * hs, px + vx * hs, py + vy * hs, pz + vz * hs,
                px + ux * hs, py + uy * hs, pz + uz * hs, px - vx * hs, py - vy * hs, pz - vz * hs, c1, c1, c1, c1);

            if (s.halo) {
                double hh = hs * 3.2;
                c2.set(base.r, base.g, base.b, (int) (70 * a));
                c3.set(base.r, base.g, base.b, 0);
                // a soft glow: bright in the middle, clear at the corners
                quad(r, px - ux * hh, py - uy * hh, pz - uz * hh, px + vx * hh, py + vy * hh, pz + vz * hh,
                    px + ux * hh, py + uy * hh, pz + uz * hh, px - vx * hh, py - vy * hh, pz - vz * hh, c3, c3, c3, c3);
                quad(r, px - ux * hs * 1.6, py - uy * hs * 1.6, pz - uz * hs * 1.6, px + vx * hs * 1.6, py + vy * hs * 1.6, pz + vz * hs * 1.6,
                    px + ux * hs * 1.6, py + uy * hs * 1.6, pz + uz * hs * 1.6, px - vx * hs * 1.6, py - vy * hs * 1.6, pz - vz * hs * 1.6, c2, c2, c2, c2);
            }
        }
    }

    private void drawClouds(Renderer3D r, double d, double cx, double cy, double cz, double radius, double cs, double sn) {
        double opacity = lerp(nebulaOpacity.get(), dayCloudOpacity.get(), d);
        boolean twist = swirl.get() || d > 0.5;
        double tm = cloudTime;
        double sizeMul = lerp(1.0, dayCloudSize.get(), d);
        boolean nightOn = nebula.get(), dayOn = dayMode.get() && dayClouds.get();
        int nightCount = nebulaCount.get(), dayCount = dayCloudCount.get();
        double rn = rain();
        SettingColor dWhite = dayCloudColor.get(), dShade = dayCloudShade.get();
        double minEl = Math.toRadians(minElevation.get()) + 0.05;

        // three rings per puff: the alpha fades 1 -> 0.6 -> 0.25 -> 0, which gives a soft smoky edge
        final double[] ringScale = {0.2, 0.4, 0.6, 0.8, 1.0};
        // smooth bell-shaped fade from the middle to nothing, so there is no visible edge
        final double[] ringAlpha = {0.88, 0.62, 0.34, 0.12, 0.0};
        double[][] ring = new double[5][(RING + 1) * 3];

        for (Cloud c : cloudList) {
            // how visible this puff is: night clouds fade out and day clouds fade in as the sun comes up
            double nightVis = nightOn && c.mass < nightCount ? 1 : 0;
            double dayVis = dayOn && c.mass < dayCount ? 1 : 0;
            double vis = lerp(nightVis, dayVis, d);
            if (vis < 0.01) continue;

            SettingColor nc = c.kind == 0 ? nebulaColor1.get() : c.kind == 1 ? nebulaColor2.get() : nebulaDark.get();
            double shadeAmt = c.kind == 0 ? 0 : c.kind == 1 ? 0.4 : 1.0;
            shadeAmt = Math.min(1, shadeAmt + rn * 0.45); // rainy clouds go grey
            double dr = lerp(dWhite.r, dShade.r, shadeAmt), dg = lerp(dWhite.g, dShade.g, shadeAmt), db = lerp(dWhite.b, dShade.b, shadeAmt);
            dr = lerp(dr, 135, rn * 0.5); dg = lerp(dg, 140, rn * 0.5); db = lerp(db, 150, rn * 0.5);
            SettingColor col = tmpCloudColor;
            col.r = (int) lerp(nc.r, dr, d); col.g = (int) lerp(nc.g, dg, d); col.b = (int) lerp(nc.b, db, d);

            // the whole mass wanders: it moves along its own direction and slowly rises and sinks
            double elc = c.elMid + c.elAmp * Math.sin(tm * c.elFreq * 6 + c.az0);
            double azc = c.az0 + tm * c.velAz;
            // the puffs orbit inside the mass, so its shape keeps changing
            double oa = c.offAng + tm * c.offSpin;
            double el = Math.max(minEl, Math.min(1.45, elc + Math.sin(oa) * c.offDist * 0.6));
            double az = azc + Math.cos(oa) * c.offDist / Math.max(0.3, Math.cos(el));

            double ce = Math.cos(el);
            double dx0 = ce * Math.cos(az), dy = Math.sin(el), dz0 = ce * Math.sin(az);
            double dx = dx0 * cs - dz0 * sn, dz = dx0 * sn + dz0 * cs;

            double rad = c.radius * sizeMul * (1 + 0.12 * Math.sin(tm * c.speed * 1.7 + c.phase));
            double a0 = c.alpha * opacity * (c.kind == 2 ? lerp(1.15, 0.8, d) : 0.9) * lerp(1.0, 1.35, d) * (0.8 + 0.2 * Math.sin(tm * c.speed * 2 + c.phase)) * vis;

            double ux, uy, uz;
            if (Math.abs(dy) > 0.98) { ux = 1; uy = 0; uz = 0; }
            else {
                ux = -dz; uy = 0; uz = dx;
                double l = Math.sqrt(ux * ux + uz * uz);
                ux /= l; uz /= l;
            }
            double vx = dy * uz - dz * uy, vy = dz * ux - dx * uz, vz = dx * uy - dy * ux;

            double twistAngle = twist ? 0.8 * Math.sin(tm * c.speed * 0.8 + c.phase) : 0;

            double orient = c.orient + (twist ? twistAngle * 0.5 : 0);
            double co = Math.cos(orient), so = Math.sin(orient);
            double sx = Math.sqrt(c.stretch), sy = 1.0 / Math.sqrt(c.stretch);
            for (int ri = 0; ri < 5; ri++) {
                double rr = Math.tan(rad * ringScale[ri]);
                for (int i = 0; i <= RING; i++) {
                    double ang = (i % RING) * (Math.PI * 2 / RING);
                    // irregular outline that keeps changing, so the puff never looks like a disc
                    double wob = 1 + 0.30 * Math.sin(2 * ang + c.w1 + tm * 0.11)
                        + 0.22 * Math.sin(3 * ang + c.w2 - tm * 0.08)
                        + 0.14 * Math.sin(5 * ang + c.w3 + tm * 0.15);
                    wob = Math.max(0.3, wob);
                    double lx = Math.cos(ang) * rr * wob * sx, ly = Math.sin(ang) * rr * wob * sy;
                    double ca = lx * co - ly * so, sa = lx * so + ly * co;
                    sphere(ring[ri], i, dx + ux * ca + vx * sa, dy + uy * ca + vy * sa, dz + uz * ca + vz * sa, cx, cy, cz, radius);
                }
            }

            double mx = cx + dx * radius, my = cy + dy * radius, mz = cz + dz * radius;
            c1.set(col.r, col.g, col.b, (int) (255 * Math.min(1, a0)));
            Color[] rc = ringCols;
            for (int ri = 0; ri < 5; ri++) rc[ri].set(col.r, col.g, col.b, (int) (255 * Math.min(1, a0 * ringAlpha[ri])));

            for (int i = 0; i < RING; i++) {
                int j = i + 1;
                double[] A = ring[0];
                quad(r, mx, my, mz, mx, my, mz, A[j * 3], A[j * 3 + 1], A[j * 3 + 2], A[i * 3], A[i * 3 + 1], A[i * 3 + 2], c1, c1, ringCols[0], ringCols[0]);
                for (int ri = 0; ri < 4; ri++) {
                    double[] P = ring[ri], Q = ring[ri + 1];
                    Color from = rc[ri], to = rc[ri + 1];
                    quad(r, P[i * 3], P[i * 3 + 1], P[i * 3 + 2], P[j * 3], P[j * 3 + 1], P[j * 3 + 2],
                        Q[j * 3], Q[j * 3 + 1], Q[j * 3 + 2], Q[i * 3], Q[i * 3 + 1], Q[i * 3 + 2], from, from, to, to);
                }
            }
        }
    }

    private void drawTendrils(Renderer3D r, double t, double cx, double cy, double cz, double radius, double cs, double sn) {
        double opacity = nebulaOpacity.get() * tendrilFade;
        double wScale = tendrilWidth.get();
        double tm = t * cloudSpeed.get();
        double minEl = Math.toRadians(minElevation.get()) + 0.03;

        final int SEG = 18;
        final double[] rowOff = {-1, -0.5, 0, 0.5, 1};
        final double[] rowAlpha = {0, 0.5, 1, 0.5, 0};
        double[][][] P = new double[SEG + 1][5][3];
        double[] pa = new double[SEG + 1], pp = new double[SEG + 1], fade = new double[SEG + 1];
        double[] tmp = new double[3];

        for (Strand st : strandList) {
            SettingColor col = st.kind == 0 ? nebulaColor1.get() : st.kind == 1 ? nebulaColor2.get() : nebulaDark.get();

            // the strand's anchor wanders over the sky like the smoke masses do
            double elc = Math.max(minEl, Math.min(1.45, st.elMid + st.elAmp * Math.sin(tm * st.elFreq * 6 + st.az0)));
            double azc = st.az0 + tm * st.velAz;
            double ce = Math.cos(elc);
            double dx0 = ce * Math.cos(azc), dy = Math.sin(elc), dz0 = ce * Math.sin(azc);
            double dx = dx0 * cs - dz0 * sn, dz = dx0 * sn + dz0 * cs;

            double ux, uy, uz;
            if (Math.abs(dy) > 0.98) { ux = 1; uy = 0; uz = 0; }
            else {
                ux = -dz; uy = 0; uz = dx;
                double l = Math.sqrt(ux * ux + uz * uz);
                ux /= l; uz /= l;
            }
            double vx = dy * uz - dz * uy, vy = dz * ux - dx * uz, vz = dx * uy - dy * ux;

            double heading = st.heading + 0.25 * Math.sin(tm * 0.07 + st.phase);
            double ch = Math.cos(heading), sh = Math.sin(heading);
            double a0 = st.alpha * opacity * (st.kind == 2 ? 1.25 : 1.0) * (0.75 + 0.25 * Math.sin(tm * st.speed * 2 + st.phase));

            // path of the strand: a long wriggling curve in the plane around its anchor
            for (int k = 0; k <= SEG; k++) {
                double f = k / (double) SEG;
                double along = (f - 0.5) * st.len;
                double curl = st.curl * st.len * (Math.sin(f * st.freq * Math.PI * 2 + st.phase + tm * st.wiggle)
                    + 0.5 * Math.sin(f * st.freq * 3.7 * Math.PI * 2 + st.phase * 1.7 - tm * st.wiggle * 0.7));
                pa[k] = along * ch - curl * sh;
                pp[k] = along * sh + curl * ch;
                fade[k] = Math.pow(Math.sin(Math.PI * f), 1.2);
            }

            for (int k = 0; k <= SEG; k++) {
                int k0 = Math.max(0, k - 1), k1 = Math.min(SEG, k + 1);
                double ta = pa[k1] - pa[k0], tp = pp[k1] - pp[k0];
                double tl = Math.max(1e-6, Math.sqrt(ta * ta + tp * tp));
                double na = -tp / tl, np = ta / tl;

                double f = k / (double) SEG;
                double w = st.width * wScale * Math.pow(Math.sin(Math.PI * f), 0.6) * (1 + 0.35 * Math.sin(f * 9 + st.phase + tm * 0.5));

                for (int j = 0; j < 5; j++) {
                    double a = pa[k] + na * w * rowOff[j];
                    double p = pp[k] + np * w * rowOff[j];
                    sphere(tmp, 0, dx + ux * a + vx * p, dy + uy * a + vy * p, dz + uz * a + vz * p, cx, cy, cz, radius);
                    P[k][j][0] = tmp[0]; P[k][j][1] = tmp[1]; P[k][j][2] = tmp[2];
                }
            }

            for (int k = 0; k < SEG; k++) {
                for (int j = 0; j < 4; j++) {
                    c1.set(col.r, col.g, col.b, (int) (255 * Math.min(1, a0 * fade[k] * rowAlpha[j])));
                    c2.set(col.r, col.g, col.b, (int) (255 * Math.min(1, a0 * fade[k] * rowAlpha[j + 1])));
                    c3.set(col.r, col.g, col.b, (int) (255 * Math.min(1, a0 * fade[k + 1] * rowAlpha[j + 1])));
                    c4.set(col.r, col.g, col.b, (int) (255 * Math.min(1, a0 * fade[k + 1] * rowAlpha[j])));
                    quad(r, P[k][j][0], P[k][j][1], P[k][j][2], P[k][j + 1][0], P[k][j + 1][1], P[k][j + 1][2],
                        P[k + 1][j + 1][0], P[k + 1][j + 1][1], P[k + 1][j + 1][2], P[k + 1][j][0], P[k + 1][j][1], P[k + 1][j][2],
                        c1, c2, c3, c4);
                }
            }
        }
    }

    private static final int[][] RAINBOW = {
        {255, 70, 70}, {255, 150, 55}, {255, 232, 80}, {100, 225, 110}, {75, 205, 235}, {90, 125, 255}, {165, 95, 235}
    };

    /** A soft rainbow arc: seven colors fading in at the edges, shimmering a little, fixed in the sky. */
    private void drawRainbow(Renderer3D r, double t, double d, double cx, double cy, double cz, double radius) {
        double vis = d * d * (1 - rain()) * rainbowOpacity.get();
        if (vis < 0.01) return;

        double az = Math.toRadians(rainbowDirection.get()) + 0.05 * Math.sin(t * 0.03);
        double e0 = Math.toRadians(rainbowHeight.get());
        double c0x = Math.cos(e0) * Math.cos(az), c0y = Math.sin(e0), c0z = Math.cos(e0) * Math.sin(az);
        double ux = -Math.sin(az), uy = 0, uz = Math.cos(az);
        double vx = c0y * uz - c0z * uy, vy = c0z * ux - c0x * uz, vz = c0x * uy - c0y * ux;

        final int ROWS = 9, SEG = 72;
        double rho0 = 0.64, w = 0.014; // angular radius of the inside edge and width of one color band
        double[][][] P = new double[SEG + 1][ROWS][3];
        double[] aY = new double[SEG + 1];
        double[] tmp = new double[3];
        for (int k = 0; k <= SEG; k++) {
            double phi = k * Math.PI * 2 / SEG;
            double cp = Math.cos(phi), sp = Math.sin(phi);
            for (int j = 0; j < ROWS; j++) {
                double rho = rho0 + j * w;
                double sr = Math.sin(rho), cr = Math.cos(rho);
                double x = c0x * cr + sr * (cp * ux + sp * vx);
                double y = c0y * cr + sr * (cp * uy + sp * vy);
                double z = c0z * cr + sr * (cp * uz + sp * vz);
                sphere(tmp, 0, x, y, z, cx, cy, cz, radius * 1.004);
                P[k][j][0] = tmp[0]; P[k][j][1] = tmp[1]; P[k][j][2] = tmp[2];
                if (j == ROWS / 2) aY[k] = y;
            }
        }

        for (int k = 0; k < SEG; k++) {
            double y0 = aY[k], y1 = aY[k + 1];
            if (y0 < 0.0 && y1 < 0.0) continue;
            // fade near the horizon and shimmer slowly along the arc
            double f0 = smooth(0.0, 0.22, y0) * (0.85 + 0.15 * Math.sin(t * 0.7 + k * 0.35));
            double f1 = smooth(0.0, 0.22, y1) * (0.85 + 0.15 * Math.sin(t * 0.7 + (k + 1) * 0.35));
            for (int j = 0; j < ROWS - 1; j++) {
                setBand(c1, j, f0 * vis);
                setBand(c2, j, f1 * vis);
                setBand(c3, j + 1, f1 * vis);
                setBand(c4, j + 1, f0 * vis);
                quad(r, P[k][j][0], P[k][j][1], P[k][j][2], P[k + 1][j][0], P[k + 1][j][1], P[k + 1][j][2],
                    P[k + 1][j + 1][0], P[k + 1][j + 1][1], P[k + 1][j + 1][2], P[k][j + 1][0], P[k][j + 1][1], P[k][j + 1][2],
                    c1, c2, c3, c4);
            }
        }
    }

    /** Color of one row of the rainbow: the first and last rows are clear so the edges are soft. */
    private static void setBand(Color c, int row, double a) {
        int band = Math.max(0, Math.min(RAINBOW.length - 1, 7 - row)); // outer edge is red
        double edge = (row == 0 || row == 8) ? 0 : 1;
        int[] col = RAINBOW[band];
        c.set(col[0], col[1], col[2], (int) (255 * Math.max(0, Math.min(1, a * edge))));
    }

    private static void sphere(double[] out, int i, double x, double y, double z, double cx, double cy, double cz, double radius) {
        double l = Math.sqrt(x * x + y * y + z * z);
        out[i * 3] = cx + x / l * radius;
        out[i * 3 + 1] = cy + y / l * radius;
        out[i * 3 + 2] = cz + z / l * radius;
    }

    private void drawMeteors(Renderer3D r, double dt, double nightFade, double cx, double cy, double cz, double radius) {
        // spawn
        nextMeteor -= dt;
        if (nextMeteor <= 0 && meteors.size() < 6 && nightFade > 0.5) {
            Meteor m = new Meteor();
            double minEl = Math.max(Math.toRadians(minElevation.get()), Math.toRadians(20));
            double el = minEl + random.nextDouble() * (Math.toRadians(80) - minEl);
            double az = random.nextDouble() * Math.PI * 2;
            m.x = Math.cos(el) * Math.cos(az);
            m.y = Math.sin(el);
            m.z = Math.cos(el) * Math.sin(az);

            // a random direction along the sky, perpendicular to the start point
            double ang = random.nextDouble() * Math.PI * 2; // any direction
            double ux = -m.z, uz = m.x, ul = Math.sqrt(ux * ux + uz * uz);
            ux /= ul; uz /= ul;
            double vx = m.y * uz, vy = m.z * ux - m.x * uz, vz = -m.y * ux;
            m.tx = ux * Math.cos(ang) + vx * Math.sin(ang);
            m.ty = vy * Math.sin(ang);
            m.tz = uz * Math.cos(ang) + vz * Math.sin(ang);
            double tl = Math.sqrt(m.tx * m.tx + m.ty * m.ty + m.tz * m.tz);
            m.tx /= tl; m.ty /= tl; m.tz /= tl;

            m.speed = 0.4 + random.nextDouble() * 0.7;
            m.life = 0.9 + random.nextDouble() * 0.7;
            m.width = 0.0035 + random.nextDouble() * 0.002;
            meteors.add(m);

            nextMeteor = shootingInterval.get() * (0.4 + random.nextDouble() * 1.2);
        }

        SettingColor base = shootingColor.get();
        int steps = 10;
        double step = 0.018; // angle between samples of the tail

        for (int k = meteors.size() - 1; k >= 0; k--) {
            Meteor m = meteors.get(k);
            m.age += dt;
            if (m.age >= m.life) { meteors.remove(k); continue; }

            double fade = Math.sin(Math.PI * m.age / m.life) * nightFade;
            double head = m.speed * m.age;

            double[] px = new double[steps + 1], py = new double[steps + 1], pz = new double[steps + 1];
            double[] nx = new double[steps + 1], ny = new double[steps + 1], nz = new double[steps + 1];
            for (int i = 0; i <= steps; i++) {
                double a = head - i * step;
                double ca = Math.cos(a), sa = Math.sin(a);
                double dx = m.x * ca + m.tx * sa, dy = m.y * ca + m.ty * sa, dz = m.z * ca + m.tz * sa;
                // moving direction at this point
                double mx = -m.x * sa + m.tx * ca, my = -m.y * sa + m.ty * ca, mz = -m.z * sa + m.tz * ca;
                // sideways: perpendicular to both
                double sx = dy * mz - dz * my, sy = dz * mx - dx * mz, sz = dx * my - dy * mx;
                double taper = 1.0 - (double) i / steps;
                double w = m.width * taper * radius;
                px[i] = cx + dx * radius; py[i] = cy + dy * radius; pz[i] = cz + dz * radius;
                nx[i] = sx * w; ny[i] = sy * w; nz[i] = sz * w;
            }

            for (int i = 0; i < steps; i++) {
                double a0 = fade * (1.0 - (double) i / steps), a1 = fade * (1.0 - (double) (i + 1) / steps);
                c1.set(base.r, base.g, base.b, (int) (255 * a0));
                c2.set(base.r, base.g, base.b, (int) (255 * a1));
                quad(r, px[i] - nx[i], py[i] - ny[i], pz[i] - nz[i], px[i] + nx[i], py[i] + ny[i], pz[i] + nz[i],
                    px[i + 1] + nx[i + 1], py[i + 1] + ny[i + 1], pz[i + 1] + nz[i + 1], px[i + 1] - nx[i + 1], py[i + 1] - ny[i + 1], pz[i + 1] - nz[i + 1], c1, c1, c2, c2);
            }

            // bright head with a soft glow
            double hw = m.width * 2.4 * radius;
            c1.set(255, 255, 255, (int) (255 * fade));
            c2.set(base.r, base.g, base.b, 0);
            double hx = px[0], hy = py[0], hz = pz[0];
            double ax = nx[0] / Math.max(1e-6, m.width * radius) * hw, ay = ny[0] / Math.max(1e-6, m.width * radius) * hw, az2 = nz[0] / Math.max(1e-6, m.width * radius) * hw;
            // up vector of the glow is the moving direction
            double mdx = px[0] - px[1], mdy = py[0] - py[1], mdz = pz[0] - pz[1];
            double ml = Math.max(1e-6, Math.sqrt(mdx * mdx + mdy * mdy + mdz * mdz));
            mdx = mdx / ml * hw; mdy = mdy / ml * hw; mdz = mdz / ml * hw;
            quad(r, hx - ax, hy - ay, hz - az2, hx + mdx, hy + mdy, hz + mdz, hx + ax, hy + ay, hz + az2, hx - mdx, hy - mdy, hz - mdz, c2, c1, c2, c1);
        }
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
