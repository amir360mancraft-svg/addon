package com.blockoutlines.addon.modules;

import com.blockoutlines.addon.BlockOutlinesAddon;
import com.blockoutlines.addon.util.CosmeticUtil;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * When you jump, a ring appears under your feet: it starts small, grows and fades out.
 * It can carry a text that lies flat on the ground inside the ring (drawn with a small built-in pixel font),
 * and the ring can be solid / dashed / double, spinning, rainbow, rising and so on.
 */
public class JumpCircle extends Module {
    public enum Style { Solid, Dashed, Double }
    public enum TextAngle { Jump, Player, Spin }

    private final SettingGroup sg = settings.getDefaultGroup();
    private final SettingGroup sgRing = settings.createGroup("Ring look");
    private final SettingGroup sgText = settings.createGroup("Text");
    private final SettingGroup sgExtra = settings.createGroup("Extras");

    // ---- general ----
    private final Setting<Double> startRadius = sg.add(new DoubleSetting.Builder()
        .name("start-radius").description("Radius of the ring when it appears.")
        .defaultValue(0.3).range(0.05, 2.0).sliderRange(0.05, 2.0).build());
    private final Setting<Double> maxRadius = sg.add(new DoubleSetting.Builder()
        .name("max-radius").description("Radius the ring grows to.")
        .defaultValue(2.2).range(0.5, 8.0).sliderRange(0.5, 6.0).build());
    private final Setting<Integer> duration = sg.add(new IntSetting.Builder()
        .name("duration").description("How long the ring lives, in milliseconds.")
        .defaultValue(900).range(200, 4000).sliderRange(200, 3000).build());
    private final Setting<Integer> segments = sg.add(new IntSetting.Builder()
        .name("segments").description("How round the ring is.")
        .defaultValue(48).range(12, 128).sliderRange(12, 96).build());
    private final Setting<Boolean> onlyGround = sg.add(new BoolSetting.Builder()
        .name("only-from-ground").description("Only spawn a ring when you jump off the ground (not mid air).")
        .defaultValue(true).build());
    private final Setting<Boolean> landing = sg.add(new BoolSetting.Builder()
        .name("landing-ring").description("Also spawn a smaller ring when you land after a fall.")
        .defaultValue(false).build());

    // ---- ring look ----
    private final Setting<Double> thickness = sgRing.add(new DoubleSetting.Builder()
        .name("ring-thickness").description("Width of the ring in blocks.")
        .defaultValue(0.08).range(0.005, 0.6).sliderRange(0.01, 0.4).build());
    private final Setting<Integer> ringAlpha = sgRing.add(new IntSetting.Builder()
        .name("ring-alpha").description("Opacity of the ring (0 = invisible, 255 = solid).")
        .defaultValue(255).range(0, 255).sliderRange(0, 255).build());
    private final Setting<Boolean> softEdge = sgRing.add(new BoolSetting.Builder()
        .name("soft-edge").description("The inner side of the ring fades out smoothly.")
        .defaultValue(true).build());
    private final Setting<Boolean> fill = sgRing.add(new BoolSetting.Builder()
        .name("fill").description("Fill the inside with a faint disc.")
        .defaultValue(true).build());
    private final Setting<Integer> fillAlpha = sgRing.add(new IntSetting.Builder()
        .name("fill-alpha").description("Opacity of the filled disc.")
        .defaultValue(60).range(0, 255).sliderRange(0, 200).visible(fill::get).build());
    private final Setting<Style> style = sgRing.add(new EnumSetting.Builder<Style>()
        .name("style").description("Solid ring, dashed ring, or two rings.")
        .defaultValue(Style.Solid).build());
    private final Setting<Integer> dashes = sgRing.add(new IntSetting.Builder()
        .name("dash-count").description("Number of dashes (Dashed style).")
        .defaultValue(14).range(4, 48).sliderRange(4, 32).visible(() -> style.get() == Style.Dashed).build());
    private final Setting<SettingColor> colorA = sgRing.add(new ColorSetting.Builder()
        .name("inner-color").description("Colour of the inside of the ring.")
        .defaultValue(new SettingColor(120, 200, 255, 255)).build());
    private final Setting<SettingColor> colorB = sgRing.add(new ColorSetting.Builder()
        .name("outer-color").description("Colour of the outer edge.")
        .defaultValue(new SettingColor(150, 90, 255, 255)).build());
    private final Setting<Boolean> rainbow = sgRing.add(new BoolSetting.Builder()
        .name("rainbow").description("Rainbow ring instead of the two colours.")
        .defaultValue(false).build());

    // ---- text ----
    private final Setting<Boolean> textOn = sgText.add(new BoolSetting.Builder()
        .name("text").description("Write a text on the ground inside the ring.")
        .defaultValue(true).build());
    private final Setting<String> textValue = sgText.add(new StringSetting.Builder()
        .name("text-value").description("The text. Letters, numbers and ! ? . - + : _ / < > * ' are supported. # draws a heart.")
        .defaultValue("JUMP").visible(textOn::get).build());
    private final Setting<SettingColor> textColor = sgText.add(new ColorSetting.Builder()
        .name("text-color").description("Colour of the text.")
        .defaultValue(new SettingColor(255, 255, 255, 255)).visible(textOn::get).build());
    private final Setting<Boolean> textRainbow = sgText.add(new BoolSetting.Builder()
        .name("text-rainbow").description("Rainbow text.")
        .defaultValue(false).visible(textOn::get).build());
    private final Setting<Integer> textAlpha = sgText.add(new IntSetting.Builder()
        .name("text-alpha").description("Opacity of the text.")
        .defaultValue(255).range(0, 255).sliderRange(0, 255).visible(textOn::get).build());
    private final Setting<Double> textSize = sgText.add(new DoubleSetting.Builder()
        .name("text-size").description("Size of one text pixel in blocks (bigger = bigger text).")
        .defaultValue(0.1).range(0.02, 0.5).sliderRange(0.02, 0.3).visible(textOn::get).build());
    private final Setting<Boolean> fit = sgText.add(new BoolSetting.Builder()
        .name("fit-inside").description("Shrink the text so it always stays inside the ring (it grows with the ring).")
        .defaultValue(true).visible(textOn::get).build());
    private final Setting<Boolean> textFade = sgText.add(new BoolSetting.Builder()
        .name("text-fade").description("Text fades out together with the ring.")
        .defaultValue(true).visible(textOn::get).build());
    private final Setting<TextAngle> textAngle = sgText.add(new EnumSetting.Builder<TextAngle>()
        .name("text-direction").description("Jump: readable from the direction you were facing when you jumped. Player: turns with you. Spin: rotates.")
        .defaultValue(TextAngle.Jump).visible(textOn::get).build());
    private final Setting<Double> textSpin = sgText.add(new DoubleSetting.Builder()
        .name("text-spin-speed").description("Degrees per second (Spin direction).")
        .defaultValue(90.0).range(-720, 720).sliderRange(-360, 360)
        .visible(() -> textOn.get() && textAngle.get() == TextAngle.Spin).build());

    // ---- extras ----
    private final Setting<Double> ringSpin = sgExtra.add(new DoubleSetting.Builder()
        .name("ring-spin-speed").description("Degrees per second the dashes / rainbow turn around the ring.")
        .defaultValue(0.0).range(-720, 720).sliderRange(-360, 360).build());
    private final Setting<Double> rainbowSpeed = sgExtra.add(new DoubleSetting.Builder()
        .name("rainbow-speed").description("How fast the rainbow colours cycle.")
        .defaultValue(0.5).range(0.0, 5.0).sliderRange(0.0, 3.0).visible(() -> rainbow.get() || textRainbow.get()).build());
    private final Setting<Double> rise = sgExtra.add(new DoubleSetting.Builder()
        .name("rise").description("The ring floats up this many blocks while it fades.")
        .defaultValue(0.0).range(0.0, 3.0).sliderRange(0.0, 2.0).build());
    private final Setting<Boolean> pulse = sgExtra.add(new BoolSetting.Builder()
        .name("pulse").description("The ring brightness pulses while it grows.")
        .defaultValue(false).build());
    private final Setting<Integer> maxRings = sgExtra.add(new IntSetting.Builder()
        .name("max-rings").description("How many rings can exist at the same time.")
        .defaultValue(12).range(1, 40).sliderRange(1, 30).build());

    private static final class Ring {
        final double x, y, z, scale;
        final float yaw;
        final long start;
        Ring(double x, double y, double z, double scale, float yaw, long start) {
            this.x = x; this.y = y; this.z = z; this.scale = scale; this.yaw = yaw; this.start = start;
        }
    }

    private final List<Ring> rings = new ArrayList<>();
    private boolean wasOnGround;
    private double lastVy;
    private int lastSpawnAge = -100;

    public JumpCircle() {
        super(BlockOutlinesAddon.COSMETICS, "jump-circle", "A ring (with a text inside) grows under your feet every time you jump.");
    }

    @Override
    public void onActivate() {
        rings.clear();
        wasOnGround = mc.player != null && mc.player.isOnGround();
        lastVy = 0;
        lastSpawnAge = -100;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null) return;
        boolean ground = mc.player.isOnGround();
        double vy = mc.player.getVelocity().y;
        boolean rising = vy > 0.1;
        boolean blocked = mc.player.isGliding() || mc.player.isTouchingWater() || mc.player.isClimbing()
            || mc.player.hasVehicle() || mc.player.getAbilities().flying || mc.player.isSpectator();
        boolean cooldown = mc.player.age - lastSpawnAge < 6;
        // a real jump: we were on the ground, now we are not, going up, and the jump key is held
        boolean realJump = wasOnGround && !ground && rising && mc.options.jumpKey.isPressed();
        boolean airJump = !onlyGround.get() && !ground && rising && mc.options.jumpKey.isPressed() && mc.player.age % 8 == 0;
        if (!blocked && !cooldown && (realJump || airJump)) spawn(1.0);
        else if (landing.get() && !blocked && ground && !wasOnGround && lastVy < -0.6) spawn(0.65);
        wasOnGround = ground;
        lastVy = vy;
    }

    private void spawn(double scale) {
        lastSpawnAge = mc.player.age;
        rings.add(new Ring(mc.player.getX(), mc.player.getY() + 0.02, mc.player.getZ(), scale, mc.player.getYaw(), System.currentTimeMillis()));
        while (rings.size() > maxRings.get()) rings.remove(0);
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (rings.isEmpty()) return;
        long now = System.currentTimeMillis();
        int life = duration.get();
        rings.removeIf(r -> now - r.start >= life);

        int n = segments.get();
        double th = thickness.get();
        double r0 = startRadius.get();
        double r1 = Math.max(r0 + 0.01, maxRadius.get());
        double seconds = now / 1000.0;
        double spinRad = Math.toRadians(ringSpin.get() * seconds);

        for (Ring ring : rings) {
            double t = (now - ring.start) / (double) life;
            double ease = 1.0 - Math.pow(1.0 - t, 3.0);
            double radius = (r0 + (r1 - r0) * ease) * ring.scale;
            double fade = Math.pow(1.0 - t, 1.3);
            double y = ring.y + rise.get() * ease;
            double bright = pulse.get() ? 0.65 + 0.35 * Math.sin(t * Math.PI * 6.0) : 1.0;
            int a = clamp(ringAlpha.get() * fade * bright);

            if (fill.get() && fillAlpha.get() > 0) drawDisc(event, ring, y, radius, n, clamp(fillAlpha.get() * fade * bright), seconds);

            if (a > 0) {
                switch (style.get()) {
                    case Solid -> annulus(event, ring, y, radius, th, n, a, spinRad, 1, seconds, 1.0);
                    case Dashed -> annulus(event, ring, y, radius, th, dashes.get() * 2, a, spinRad, 2, seconds, 1.0);
                    case Double -> {
                        annulus(event, ring, y, radius, th, n, a, spinRad, 1, seconds, 1.0);
                        double inner = radius - th * 2.5 - 0.06;
                        if (inner > th) annulus(event, ring, y, inner, th * 0.6, n, (int) (a * 0.75), -spinRad, 1, seconds, 0.4);
                    }
                }
            }

            if (textOn.get() && !textValue.get().isEmpty()) drawText(event, ring, y + 0.012, radius, fade, t, seconds);
        }
    }

    private static int clamp(double v) { return Math.max(0, Math.min(255, (int) v)); }

    private Color ringColor(double mix, int alpha, double seconds, double hueShift) {
        if (rainbow.get()) return CosmeticUtil.hsv((float) (seconds * rainbowSpeed.get() + hueShift), 0.75f, 1f, alpha);
        return CosmeticUtil.lerp(new Color(colorA.get()), new Color(colorB.get()), mix, alpha);
    }

    /** A flat band between (radius - width) and radius. {@code step} 2 skips every second piece (dashes). */
    private void annulus(Render3DEvent event, Ring ring, double y, double radius, double width, int n, int alpha,
                         double offset, int step, double seconds, double mixBase) {
        double inner = Math.max(0.005, radius - width);
        int innerAlpha = softEdge.get() ? 0 : alpha;
        for (int i = 0; i < n; i += step) {
            double a0 = offset + Math.PI * 2.0 * i / n;
            double a1 = offset + Math.PI * 2.0 * (i + 1) / n;
            double c0 = Math.cos(a0), s0 = Math.sin(a0), c1 = Math.cos(a1), s1 = Math.sin(a1);
            double hue = (double) i / n;
            Color outer = ringColor(1.0 * mixBase, alpha, seconds, hue);
            Color in = ringColor(0.0, innerAlpha, seconds, hue);
            event.renderer.quad(
                ring.x + c0 * radius, y, ring.z + s0 * radius,
                ring.x + c1 * radius, y, ring.z + s1 * radius,
                ring.x + c1 * inner, y, ring.z + s1 * inner,
                ring.x + c0 * inner, y, ring.z + s0 * inner,
                outer, outer, in, in);
        }
    }

    private void drawDisc(Render3DEvent event, Ring ring, double y, double radius, int n, int alpha, double seconds) {
        if (alpha <= 0) return;
        for (int i = 0; i < n; i++) {
            double a0 = Math.PI * 2.0 * i / n, a1 = Math.PI * 2.0 * (i + 1) / n;
            Color centre = ringColor(0.0, alpha / 3, seconds, 0.0);
            Color edge = ringColor(1.0, alpha, seconds, (double) i / n);
            event.renderer.quad(
                ring.x, y, ring.z,
                ring.x, y, ring.z,
                ring.x + Math.cos(a1) * radius, y, ring.z + Math.sin(a1) * radius,
                ring.x + Math.cos(a0) * radius, y, ring.z + Math.sin(a0) * radius,
                centre, centre, edge, edge);
        }
    }

    private void drawText(Render3DEvent event, Ring ring, double y, double radius, double fade, double t, double seconds) {
        String text = textValue.get().toUpperCase();
        if (text.length() > 16) text = text.substring(0, 16);
        int len = text.length();
        int widthPx = len * 6 - 1;

        double s = textSize.get() * ring.scale;
        if (fit.get()) {
            double half = Math.sqrt((widthPx / 2.0) * (widthPx / 2.0) + 3.5 * 3.5);
            s = Math.min(s, radius * 0.85 / half);
        }
        if (s < 0.004) return;

        int alpha = clamp(textAlpha.get() * (textFade.get() ? fade : 1.0));
        if (alpha <= 0) return;

        double yawDeg = switch (textAngle.get()) {
            case Jump -> ring.yaw;
            case Player -> mc.player != null ? mc.player.getYaw() : ring.yaw;
            case Spin -> ring.yaw + textSpin.get() * seconds;
        };
        double yaw = Math.toRadians(yawDeg);
        // "up" of the text points where the player looked (forward), "right" is to the player's right
        double fx = -Math.sin(yaw), fz = Math.cos(yaw);
        double rx = -Math.cos(yaw), rz = -Math.sin(yaw);

        Color base = new Color(textColor.get());
        for (int ci = 0; ci < len; ci++) {
            int[] rows = glyph(text.charAt(ci));
            if (rows == null) continue;
            Color c = textRainbow.get()
                ? CosmeticUtil.hsv((float) (seconds * rainbowSpeed.get() + ci * 0.08), 0.7f, 1f, alpha)
                : new Color(base.r, base.g, base.b, alpha);
            for (int row = 0; row < 7; row++) {
                int bits = rows[row];
                for (int col = 0; col < 5; col++) {
                    if ((bits & (1 << (4 - col))) == 0) continue;
                    double u0 = (ci * 6 + col - widthPx / 2.0) * s, u1 = u0 + s;
                    double v1 = (3.5 - row) * s, v0 = v1 - s;
                    event.renderer.quad(
                        ring.x + rx * u0 + fx * v0, y, ring.z + rz * u0 + fz * v0,
                        ring.x + rx * u1 + fx * v0, y, ring.z + rz * u1 + fz * v0,
                        ring.x + rx * u1 + fx * v1, y, ring.z + rz * u1 + fz * v1,
                        ring.x + rx * u0 + fx * v1, y, ring.z + rz * u0 + fz * v1,
                        c);
                }
            }
        }
    }

    // ------------------------------------------------------------------ 5x7 pixel font

    private static final Map<Character, int[]> FONT = new HashMap<>();

    private static void g(char ch, int... rows) { FONT.put(ch, rows); }

    static {
        g('A', 0x0E, 0x11, 0x11, 0x1F, 0x11, 0x11, 0x11);
        g('B', 0x1E, 0x11, 0x11, 0x1E, 0x11, 0x11, 0x1E);
        g('C', 0x0E, 0x11, 0x10, 0x10, 0x10, 0x11, 0x0E);
        g('D', 0x1E, 0x11, 0x11, 0x11, 0x11, 0x11, 0x1E);
        g('E', 0x1F, 0x10, 0x10, 0x1E, 0x10, 0x10, 0x1F);
        g('F', 0x1F, 0x10, 0x10, 0x1E, 0x10, 0x10, 0x10);
        g('G', 0x0E, 0x11, 0x10, 0x17, 0x11, 0x11, 0x0F);
        g('H', 0x11, 0x11, 0x11, 0x1F, 0x11, 0x11, 0x11);
        g('I', 0x0E, 0x04, 0x04, 0x04, 0x04, 0x04, 0x0E);
        g('J', 0x07, 0x02, 0x02, 0x02, 0x02, 0x12, 0x0C);
        g('K', 0x11, 0x12, 0x14, 0x18, 0x14, 0x12, 0x11);
        g('L', 0x10, 0x10, 0x10, 0x10, 0x10, 0x10, 0x1F);
        g('M', 0x11, 0x1B, 0x15, 0x15, 0x11, 0x11, 0x11);
        g('N', 0x11, 0x19, 0x15, 0x13, 0x11, 0x11, 0x11);
        g('O', 0x0E, 0x11, 0x11, 0x11, 0x11, 0x11, 0x0E);
        g('P', 0x1E, 0x11, 0x11, 0x1E, 0x10, 0x10, 0x10);
        g('Q', 0x0E, 0x11, 0x11, 0x11, 0x15, 0x12, 0x0D);
        g('R', 0x1E, 0x11, 0x11, 0x1E, 0x14, 0x12, 0x11);
        g('S', 0x0F, 0x10, 0x10, 0x0E, 0x01, 0x01, 0x1E);
        g('T', 0x1F, 0x04, 0x04, 0x04, 0x04, 0x04, 0x04);
        g('U', 0x11, 0x11, 0x11, 0x11, 0x11, 0x11, 0x0E);
        g('V', 0x11, 0x11, 0x11, 0x11, 0x11, 0x0A, 0x04);
        g('W', 0x11, 0x11, 0x11, 0x15, 0x15, 0x15, 0x0A);
        g('X', 0x11, 0x11, 0x0A, 0x04, 0x0A, 0x11, 0x11);
        g('Y', 0x11, 0x11, 0x0A, 0x04, 0x04, 0x04, 0x04);
        g('Z', 0x1F, 0x01, 0x02, 0x04, 0x08, 0x10, 0x1F);
        g('0', 0x0E, 0x11, 0x13, 0x15, 0x19, 0x11, 0x0E);
        g('1', 0x04, 0x0C, 0x04, 0x04, 0x04, 0x04, 0x0E);
        g('2', 0x0E, 0x11, 0x01, 0x02, 0x04, 0x08, 0x1F);
        g('3', 0x1F, 0x02, 0x04, 0x02, 0x01, 0x11, 0x0E);
        g('4', 0x02, 0x06, 0x0A, 0x12, 0x1F, 0x02, 0x02);
        g('5', 0x1F, 0x10, 0x1E, 0x01, 0x01, 0x11, 0x0E);
        g('6', 0x06, 0x08, 0x10, 0x1E, 0x11, 0x11, 0x0E);
        g('7', 0x1F, 0x01, 0x02, 0x04, 0x08, 0x08, 0x08);
        g('8', 0x0E, 0x11, 0x11, 0x0E, 0x11, 0x11, 0x0E);
        g('9', 0x0E, 0x11, 0x11, 0x0F, 0x01, 0x02, 0x0C);
        g('!', 0x04, 0x04, 0x04, 0x04, 0x04, 0x00, 0x04);
        g('?', 0x0E, 0x11, 0x01, 0x02, 0x04, 0x00, 0x04);
        g('.', 0x00, 0x00, 0x00, 0x00, 0x00, 0x0C, 0x0C);
        g('-', 0x00, 0x00, 0x00, 0x1F, 0x00, 0x00, 0x00);
        g('+', 0x00, 0x04, 0x04, 0x1F, 0x04, 0x04, 0x00);
        g(':', 0x00, 0x0C, 0x0C, 0x00, 0x0C, 0x0C, 0x00);
        g('_', 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x1F);
        g('*', 0x00, 0x11, 0x0A, 0x1F, 0x0A, 0x11, 0x00);
        g('<', 0x02, 0x04, 0x08, 0x10, 0x08, 0x04, 0x02);
        g('>', 0x08, 0x04, 0x02, 0x01, 0x02, 0x04, 0x08);
        g('/', 0x01, 0x01, 0x02, 0x04, 0x08, 0x10, 0x10);
        g('\'', 0x04, 0x04, 0x08, 0x00, 0x00, 0x00, 0x00);
        g('#', 0x0A, 0x1F, 0x1F, 0x1F, 0x0E, 0x04, 0x00); // heart
        g(' ', 0, 0, 0, 0, 0, 0, 0);
    }

    private static int[] glyph(char ch) {
        int[] r = FONT.get(ch);
        return r != null ? r : FONT.get('?');
    }
}
