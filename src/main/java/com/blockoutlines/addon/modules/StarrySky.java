package com.blockoutlines.addon.modules;

import com.blockoutlines.addon.BlockOutlinesAddon;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.renderer.Renderer3D;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
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
    private static final int RING = 14; // segments of one nebula cloud

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgSky = settings.createGroup("Sky color");
    private final SettingGroup sgNebula = settings.createGroup("Nebula");
    private final SettingGroup sgStars = settings.createGroup("Stars");
    private final SettingGroup sgShooting = settings.createGroup("Shooting stars");

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
        .defaultValue(new SettingColor(70, 150, 255, 255))
        .build()
    );

    private final Setting<SettingColor> skyColorB = sgSky.add(new ColorSetting.Builder()
        .name("sky-color-2")
        .description("The sky slowly drifts between this color and the main one.")
        .defaultValue(new SettingColor(130, 110, 255, 255))
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
        .defaultValue(0.15)
        .range(0.01, 1.0)
        .sliderRange(0.01, 1.0)
        .visible(colorShift::get)
        .build()
    );

    private final Setting<Boolean> fogTint = sgSky.add(new BoolSetting.Builder()
        .name("fog-tint")
        .description("Tint the fog so the horizon fades into a soft blue haze.")
        .defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> fogColor = sgSky.add(new ColorSetting.Builder()
        .name("fog-color")
        .description("Color of the haze.")
        .defaultValue(new SettingColor(150, 205, 255, 255))
        .visible(fogTint::get)
        .build()
    );

    private final Setting<Double> fogStrength = sgSky.add(new DoubleSetting.Builder()
        .name("fog-strength")
        .description("How much of the normal fog color is replaced (0 = none, 1 = all).")
        .defaultValue(0.6)
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
        .description("How many clouds are drawn.")
        .defaultValue(14)
        .range(1, 40)
        .sliderRange(4, 30)
        .visible(nebula::get)
        .build()
    );

    private final Setting<Double> nebulaOpacity = sgNebula.add(new DoubleSetting.Builder()
        .name("cloud-opacity")
        .description("How strong the clouds are.")
        .defaultValue(0.35)
        .range(0.05, 1.0)
        .sliderRange(0.05, 1.0)
        .visible(nebula::get)
        .build()
    );

    private final Setting<SettingColor> nebulaColor1 = sgNebula.add(new ColorSetting.Builder()
        .name("cloud-color-1")
        .description("Bright cloud color.")
        .defaultValue(new SettingColor(80, 190, 255, 255))
        .visible(nebula::get)
        .build()
    );

    private final Setting<SettingColor> nebulaColor2 = sgNebula.add(new ColorSetting.Builder()
        .name("cloud-color-2")
        .description("Second cloud color.")
        .defaultValue(new SettingColor(150, 110, 255, 255))
        .visible(nebula::get)
        .build()
    );

    private final Setting<SettingColor> nebulaDark = sgNebula.add(new ColorSetting.Builder()
        .name("dark-cloud-color")
        .description("Dark clouds that add depth.")
        .defaultValue(new SettingColor(15, 25, 70, 255))
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
        .defaultValue(4.0)
        .range(0.5, 30)
        .sliderRange(0.5, 15)
        .visible(shooting::get)
        .build()
    );

    private final Setting<SettingColor> shootingColor = sgShooting.add(new ColorSetting.Builder()
        .name("shooting-color")
        .description("Color of the shooting stars.")
        .defaultValue(new SettingColor(190, 225, 255, 255))
        .visible(shooting::get)
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
        double azimuth, elevation; // direction
        double radius;             // angular radius in radians
        double alpha;
        double phase, speed;
        int kind;                  // 0 bright, 1 second color, 2 dark
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
    private final List<Meteor> meteors = new ArrayList<>();
    private final Random random = new Random();
    private int builtStars = -1, builtClouds = -1;
    private double nextMeteor;
    private long lastNanos;

    private final Color c1 = new Color(), c2 = new Color(), c3 = new Color(), c4 = new Color();

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
    }

    // ------------------------------------------------------------------ used by the mixins

    private boolean applies() {
        if (!isActive() || mc.world == null) return false;
        return !overworldOnly.get() || PlayerUtils.getDimension() == Dimension.Overworld;
    }

    /** The sky color, or -1 when the sky should not be changed. Packed like Meteor does it. */
    public int skyColorPacked() {
        if (!applies()) return -1;

        SettingColor a = skyColorA.get(), b = skyColorB.get();
        double t = 0;
        if (colorShift.get()) t = 0.5 + 0.5 * Math.sin(System.nanoTime() / 1.0e9 * colorShiftSpeed.get());

        int r = (int) (a.r + (b.r - a.r) * t);
        int g = (int) (a.g + (b.g - a.g) * t);
        int bl = (int) (a.b + (b.b - a.b) * t);
        return new Color(r, g, bl, 255).getPacked();
    }

    /** The fog color: the normal one mixed with the haze color, or the same one when the fog should not change. */
    public Vector4f fogColor(Vector4f original) {
        if (!applies() || !fogTint.get()) return original;

        SettingColor f = fogColor.get();
        float s = fogStrength.get().floatValue();
        return new Vector4f(
            original.x + (f.r / 255f - original.x) * s,
            original.y + (f.g / 255f - original.y) * s,
            original.z + (f.b / 255f - original.z) * s,
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

        if (builtClouds != nebulaCount.get()) {
            builtClouds = nebulaCount.get();
            cloudList.clear();
            Random r = new Random(104729);
            for (int i = 0; i < builtClouds; i++) {
                Cloud c = new Cloud();
                c.azimuth = r.nextDouble() * Math.PI * 2;
                c.elevation = minEl + 0.1 + r.nextDouble() * (Math.PI / 2 - minEl - 0.1);
                c.radius = 0.25 + r.nextDouble() * 0.45;
                c.alpha = 0.5 + r.nextDouble() * 0.5;
                c.phase = r.nextDouble() * Math.PI * 2;
                c.speed = 0.05 + r.nextDouble() * 0.12;
                c.kind = i % 3;
                cloudList.add(c);
            }
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

        double cx = event.offsetX, cy = event.offsetY, cz = event.offsetZ;
        double radius = Math.max(48, Math.min(480, mc.options.getViewDistance().getValue() * 16 * (domeRadius.get() / 100.0)));
        double spin = t * rotationSpeed.get();
        double cs = Math.cos(spin), sn = Math.sin(spin);

        Renderer3D r = event.depthRenderer;

        if (nebula.get()) drawClouds(r, t, cx, cy, cz, radius, cs, sn);
        if (stars.get()) drawStars(r, t, cx, cy, cz, radius, cs, sn);
        if (shooting.get()) drawMeteors(r, dt, cx, cy, cz, radius);
    }

    private void drawStars(Renderer3D r, double t, double cx, double cy, double cz, double radius, double cs, double sn) {
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

    private void drawClouds(Renderer3D r, double t, double cx, double cy, double cz, double radius, double cs, double sn) {
        double opacity = nebulaOpacity.get();
        boolean twist = swirl.get();

        double[] ringA = new double[(RING + 1) * 3];
        double[] ringB = new double[(RING + 1) * 3];

        for (Cloud c : cloudList) {
            SettingColor col = c.kind == 0 ? nebulaColor1.get() : c.kind == 1 ? nebulaColor2.get() : nebulaDark.get();

            // slow drift in azimuth and a gentle breathing
            double az = c.azimuth + t * c.speed * 0.2;
            double el = c.elevation + 0.04 * Math.sin(t * c.speed + c.phase);
            double ce = Math.cos(el);
            double dx0 = ce * Math.cos(az), dy = Math.sin(el), dz0 = ce * Math.sin(az);
            double dx = dx0 * cs - dz0 * sn, dz = dx0 * sn + dz0 * cs;

            double rad = c.radius * (1 + 0.1 * Math.sin(t * c.speed * 1.7 + c.phase));
            double a0 = c.alpha * opacity * (c.kind == 2 ? 1.1 : 0.9) * (0.8 + 0.2 * Math.sin(t * c.speed * 2 + c.phase));

            double ux, uy, uz;
            if (Math.abs(dy) > 0.98) { ux = 1; uy = 0; uz = 0; }
            else {
                ux = -dz; uy = 0; uz = dx;
                double l = Math.sqrt(ux * ux + uz * uz);
                ux /= l; uz /= l;
            }
            double vx = dy * uz - dz * uy, vy = dz * ux - dx * uz, vz = dx * uy - dy * ux;

            double twistAngle = twist ? 0.7 * Math.sin(t * c.speed * 0.8 + c.phase) : 0;
            double inner = Math.tan(rad * 0.5), outer = Math.tan(rad);

            for (int i = 0; i <= RING; i++) {
                double ang = (i % RING) * (Math.PI * 2 / RING);
                double ai = ang, ao = ang + twistAngle;

                // points on the sphere around the cloud center
                sphere(ringA, i, dx + (ux * Math.cos(ai) + vx * Math.sin(ai)) * inner, dy + (uy * Math.cos(ai) + vy * Math.sin(ai)) * inner,
                    dz + (uz * Math.cos(ai) + vz * Math.sin(ai)) * inner, cx, cy, cz, radius);
                sphere(ringB, i, dx + (ux * Math.cos(ao) + vx * Math.sin(ao)) * outer, dy + (uy * Math.cos(ao) + vy * Math.sin(ao)) * outer,
                    dz + (uz * Math.cos(ao) + vz * Math.sin(ao)) * outer, cx, cy, cz, radius);
            }

            double mx = cx + dx * radius, my = cy + dy * radius, mz = cz + dz * radius;
            c1.set(col.r, col.g, col.b, (int) (255 * Math.min(1, a0)));          // middle
            c2.set(col.r, col.g, col.b, (int) (255 * Math.min(1, a0 * 0.55)));   // inner ring
            c3.set(col.r, col.g, col.b, 0);                                      // clear outer edge

            for (int i = 0; i < RING; i++) {
                int j = i + 1;
                // middle -> inner ring (a triangle drawn as a flat quad)
                quad(r, mx, my, mz, mx, my, mz, ringA[j * 3], ringA[j * 3 + 1], ringA[j * 3 + 2], ringA[i * 3], ringA[i * 3 + 1], ringA[i * 3 + 2], c1, c1, c2, c2);
                // inner ring -> clear edge
                quad(r, ringA[i * 3], ringA[i * 3 + 1], ringA[i * 3 + 2], ringA[j * 3], ringA[j * 3 + 1], ringA[j * 3 + 2],
                    ringB[j * 3], ringB[j * 3 + 1], ringB[j * 3 + 2], ringB[i * 3], ringB[i * 3 + 1], ringB[i * 3 + 2], c2, c2, c3, c3);
            }
        }
    }

    private static void sphere(double[] out, int i, double x, double y, double z, double cx, double cy, double cz, double radius) {
        double l = Math.sqrt(x * x + y * y + z * z);
        out[i * 3] = cx + x / l * radius;
        out[i * 3 + 1] = cy + y / l * radius;
        out[i * 3 + 2] = cz + z / l * radius;
    }

    private void drawMeteors(Renderer3D r, double dt, double cx, double cy, double cz, double radius) {
        // spawn
        nextMeteor -= dt;
        if (nextMeteor <= 0 && meteors.size() < 4) {
            Meteor m = new Meteor();
            double minEl = Math.max(Math.toRadians(minElevation.get()), Math.toRadians(20));
            double el = minEl + random.nextDouble() * (Math.toRadians(70) - minEl);
            double az = random.nextDouble() * Math.PI * 2;
            m.x = Math.cos(el) * Math.cos(az);
            m.y = Math.sin(el);
            m.z = Math.cos(el) * Math.sin(az);

            // a direction on the sphere perpendicular to the start, pointing mostly downwards like a falling star
            double ang = Math.toRadians(200 + random.nextDouble() * 140);
            double ux = -m.z, uz = m.x, ul = Math.sqrt(ux * ux + uz * uz);
            ux /= ul; uz /= ul;
            double vx = m.y * uz, vy = m.z * ux - m.x * uz, vz = -m.y * ux;
            m.tx = ux * Math.cos(ang) + vx * Math.sin(ang);
            m.ty = vy * Math.sin(ang);
            m.tz = uz * Math.cos(ang) + vz * Math.sin(ang);
            double tl = Math.sqrt(m.tx * m.tx + m.ty * m.ty + m.tz * m.tz);
            m.tx /= tl; m.ty /= tl; m.tz /= tl;

            m.speed = 0.55 + random.nextDouble() * 0.5;
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

            double fade = Math.sin(Math.PI * m.age / m.life);
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
