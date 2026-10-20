package com.blockoutlines.addon.modules;

import com.blockoutlines.addon.BlockOutlinesAddon;
import com.blockoutlines.addon.util.CosmeticUtil;
import meteordevelopment.meteorclient.events.entity.player.AttackEntityEvent;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.Renderer3D;
import meteordevelopment.meteorclient.renderer.text.TextRenderer;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.NametagUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Hit feedback: when a target loses health, a floating damage number ("-1.6") pops out of its body and little colored
 * pixels burst away from the spot. Works from the health the client sees, so it also catches crits, arrows and so on.
 * Only drawn on your screen, nothing is sent to the server.
 */
public class HitEffect extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgNumber = settings.createGroup("Damage number");
    private final SettingGroup sgParticles = settings.createGroup("Particles");

    // -------- general
    private final Setting<Boolean> onlyMine = sgGeneral.add(new BoolSetting.Builder()
        .name("only-my-hits").description("Only show it for targets you hit yourself. Off = for everyone near you.")
        .defaultValue(true).build());
    private final Setting<Boolean> playersOnly = sgGeneral.add(new BoolSetting.Builder()
        .name("players-only").description("Only players, no mobs.").defaultValue(false).build());
    private final Setting<Boolean> showSelf = sgGeneral.add(new BoolSetting.Builder()
        .name("when-i-get-hurt").description("Also show it when you take damage.").defaultValue(false).build());
    private final Setting<Double> minDamage = sgGeneral.add(new DoubleSetting.Builder()
        .name("min-damage").description("Ignore damage smaller than this.")
        .defaultValue(0.1).range(0.0, 10.0).sliderRange(0.0, 4.0).build());
    private final Setting<Integer> range = sgGeneral.add(new IntSetting.Builder()
        .name("range").description("How far away targets still show effects.")
        .defaultValue(40).range(8, 128).sliderRange(8, 100).build());
    private final Setting<Boolean> throughWalls = sgGeneral.add(new BoolSetting.Builder()
        .name("particles-through-walls").description("Draw the pixels even behind blocks.").defaultValue(true).build());

    // -------- number
    private final Setting<Boolean> number = sgNumber.add(new BoolSetting.Builder()
        .name("damage-number").description("A floating number that comes out of the body, like -1.6.").defaultValue(true).build());
    private final Setting<Double> numSize = sgNumber.add(new DoubleSetting.Builder()
        .name("size").description("Size of the number.")
        .defaultValue(1.3).range(0.4, 4.0).sliderRange(0.5, 3.0).visible(number::get).build());
    private final Setting<Boolean> sizeByDamage = sgNumber.add(new BoolSetting.Builder()
        .name("bigger-for-big-hits").description("Hard hits make a bigger number.").defaultValue(true).visible(number::get).build());
    private final Setting<Integer> numLife = sgNumber.add(new IntSetting.Builder()
        .name("life").description("How long the number stays (milliseconds).")
        .defaultValue(1100).range(300, 4000).sliderRange(400, 2500).visible(number::get).build());
    private final Setting<Double> rise = sgNumber.add(new DoubleSetting.Builder()
        .name("rise").description("How fast the number floats up and out (blocks per second).")
        .defaultValue(1.2).range(0.0, 5.0).sliderRange(0.0, 3.0).visible(number::get).build());
    private final Setting<Double> numSpread = sgNumber.add(new DoubleSetting.Builder()
        .name("sideways").description("How far to the side numbers fly out of the body.")
        .defaultValue(0.7).range(0.0, 3.0).sliderRange(0.0, 2.0).visible(number::get).build());
    private final Setting<Integer> decimals = sgNumber.add(new IntSetting.Builder()
        .name("decimals").description("Digits after the dot (1 = -1.6).")
        .defaultValue(1).range(0, 2).sliderRange(0, 2).visible(number::get).build());
    private final Setting<Boolean> hearts = sgNumber.add(new BoolSetting.Builder()
        .name("in-hearts").description("Show hearts instead of health points (2 health = 1 heart).")
        .defaultValue(false).visible(number::get).build());
    private final Setting<Boolean> shadow = sgNumber.add(new BoolSetting.Builder()
        .name("shadow").description("Dark shadow behind the text.").defaultValue(true).visible(number::get).build());
    private final Setting<Boolean> showHeal = sgNumber.add(new BoolSetting.Builder()
        .name("show-healing").description("Also show +x when a target heals.").defaultValue(false).visible(number::get).build());
    private final Setting<SettingColor> lowColor = sgNumber.add(new ColorSetting.Builder()
        .name("small-hit-color").description("Color of a small hit.")
        .defaultValue(new SettingColor(255, 235, 120, 255)).visible(number::get).build());
    private final Setting<SettingColor> highColor = sgNumber.add(new ColorSetting.Builder()
        .name("big-hit-color").description("Color of a big hit.")
        .defaultValue(new SettingColor(255, 60, 60, 255)).visible(number::get).build());
    private final Setting<SettingColor> healColor = sgNumber.add(new ColorSetting.Builder()
        .name("healing-color").description("Color of healing numbers.")
        .defaultValue(new SettingColor(90, 255, 120, 255)).visible(() -> number.get() && showHeal.get()).build());
    private final Setting<Double> bigHit = sgNumber.add(new DoubleSetting.Builder()
        .name("big-hit-at").description("Damage that counts as a full big hit (health points).")
        .defaultValue(8.0).range(1.0, 40.0).sliderRange(2.0, 20.0).visible(number::get).build());

    // -------- particles
    private final Setting<Boolean> particles = sgParticles.add(new BoolSetting.Builder()
        .name("particles").description("Colored pixels burst out of the body.").defaultValue(true).build());
    private final Setting<Integer> count = sgParticles.add(new IntSetting.Builder()
        .name("count").description("Pixels for a normal hit.")
        .defaultValue(14).range(1, 80).sliderRange(2, 50).visible(particles::get).build());
    private final Setting<Double> perDamage = sgParticles.add(new DoubleSetting.Builder()
        .name("extra-per-damage").description("Extra pixels for every point of damage.")
        .defaultValue(1.5).range(0.0, 10.0).sliderRange(0.0, 5.0).visible(particles::get).build());
    private final Setting<Double> pSize = sgParticles.add(new DoubleSetting.Builder()
        .name("size").description("Size of one pixel in blocks.")
        .defaultValue(0.08).range(0.01, 0.5).sliderRange(0.02, 0.25).visible(particles::get).build());
    private final Setting<Double> pSpeed = sgParticles.add(new DoubleSetting.Builder()
        .name("speed").description("How fast the pixels fly out.")
        .defaultValue(1.6).range(0.1, 8.0).sliderRange(0.2, 5.0).visible(particles::get).build());
    private final Setting<Double> gravity = sgParticles.add(new DoubleSetting.Builder()
        .name("gravity").description("How fast the pixels fall.")
        .defaultValue(2.2).range(0.0, 12.0).sliderRange(0.0, 6.0).visible(particles::get).build());
    private final Setting<Integer> pLife = sgParticles.add(new IntSetting.Builder()
        .name("life").description("How long a pixel stays (milliseconds).")
        .defaultValue(750).range(150, 3000).sliderRange(200, 2000).visible(particles::get).build());
    private final Setting<Boolean> snap = sgParticles.add(new BoolSetting.Builder()
        .name("pixel-grid").description("Snap pixels to a 16x16 grid per block, like Minecraft textures.")
        .defaultValue(true).visible(particles::get).build());
    private final Setting<Boolean> glow = sgParticles.add(new BoolSetting.Builder()
        .name("glow").description("A soft glow around every pixel.").defaultValue(true).visible(particles::get).build());
    private final Setting<SettingColor> pColorA = sgParticles.add(new ColorSetting.Builder()
        .name("color-start").description("Color of a fresh pixel.")
        .defaultValue(new SettingColor(255, 70, 70, 255)).visible(particles::get).build());
    private final Setting<SettingColor> pColorB = sgParticles.add(new ColorSetting.Builder()
        .name("color-end").description("Color it fades to.")
        .defaultValue(new SettingColor(255, 190, 60, 255)).visible(particles::get).build());
    private final Setting<Boolean> rainbow = sgParticles.add(new BoolSetting.Builder()
        .name("rainbow").description("Rainbow pixels instead.").defaultValue(false).visible(particles::get).build());
    private final Setting<Integer> opacity = sgParticles.add(new IntSetting.Builder()
        .name("opacity").description("How see-through the pixels are.")
        .defaultValue(235).range(20, 255).sliderRange(60, 255).visible(particles::get).build());

    private static final int MAX_PIXELS = 2500;
    private static final int MAX_NUMBERS = 60;

    private static final class Seen { float last; long t; }

    private static final class Num {
        double x, y, z, vx, vy, vz;
        String text; Color color; float scale; long born; int life;
    }

    private static final class Px {
        double x, y, z, vx, vy, vz;
        long born; int life; float hue, size;
    }

    private final Map<Integer, Seen> seen = new HashMap<>();
    private final Map<Integer, Long> attacked = new HashMap<>();
    private final List<Num> nums = new ArrayList<>();
    private final List<Px> parts = new ArrayList<>();
    private final Random rng = new Random();
    private final Vector3d tmp = new Vector3d();

    private double rx, ry, rz, ux, uy, uz;

    public HitEffect() {
        super(BlockOutlinesAddon.COMBAT, "hit-effect",
            "A damage number (like -1.6) floats out of whoever you hit, with a burst of colored pixels.");
    }

    @Override
    public void onDeactivate() {
        seen.clear();
        attacked.clear();
        nums.clear();
        parts.clear();
    }

    @EventHandler
    private void onAttack(AttackEntityEvent event) {
        if (event.entity != null) attacked.put(event.entity.getId(), System.currentTimeMillis());
    }

    // ------------------------------------------------------------------ watching health (20 times a second)

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null || mc.player == null) return;
        long now = System.currentTimeMillis();
        double r2 = (double) range.get() * range.get();

        for (Entity e : mc.world.getEntities()) {
            if (!(e instanceof LivingEntity le)) continue;
            if (le == mc.player && !showSelf.get()) continue;
            if (playersOnly.get() && !(le instanceof net.minecraft.entity.player.PlayerEntity)) continue;
            if (le.squaredDistanceTo(mc.player) > r2) continue;

            int id = le.getId();
            float hp = le.getHealth() + le.getAbsorptionAmount();
            Seen s = seen.get(id);
            if (s == null) {
                s = new Seen();
                s.last = hp;
                s.t = now;
                seen.put(id, s);
                continue;
            }
            s.t = now;
            float diff = s.last - hp;
            s.last = hp;
            if (Math.abs(diff) < 0.001f) continue;

            if (diff > 0) {
                if (diff < minDamage.get()) continue;
                if (onlyMine.get() && le != mc.player) {
                    Long at = attacked.get(id);
                    if (at == null || now - at > 2500) continue;
                }
                hit(le, diff, now);
            } else if (showHeal.get() && number.get() && -diff >= 0.5f && (le == mc.player || !onlyMine.get() || attacked.containsKey(id))) {
                heal(le, -diff, now);
            }
        }

        // forget old entries
        if (seen.size() > 200) {
            Iterator<Map.Entry<Integer, Seen>> it = seen.entrySet().iterator();
            while (it.hasNext()) if (now - it.next().getValue().t > 5000) it.remove();
        }
        if (attacked.size() > 100) attacked.entrySet().removeIf(en -> now - en.getValue() > 5000);
    }

    private String fmt(double v, boolean minus) {
        if (hearts.get()) v /= 2.0;
        String body = String.format(java.util.Locale.ROOT, "%." + decimals.get() + "f", v);
        return (minus ? "-" : "+") + body;
    }

    private void hit(LivingEntity le, float dmg, long now) {
        double h = le.getHeight(), w = le.getWidth();
        double cx = le.getX(), cz = le.getZ();
        double by = le.getY() + h * (0.45 + rng.nextDouble() * 0.35);

        // direction away from you: the number and pixels fly out of the side facing you
        double dx = cx - mc.player.getX(), dz = cz - mc.player.getZ();
        double dl = Math.max(1e-6, Math.sqrt(dx * dx + dz * dz));
        double ax = dx / dl, az = dz / dl;
        if (le == mc.player) { ax = 0; az = 0; }

        if (number.get() && nums.size() < MAX_NUMBERS) {
            Num n = new Num();
            double sx = (rng.nextDouble() - 0.5) * w * 0.6, sz = (rng.nextDouble() - 0.5) * w * 0.6;
            n.x = cx + sx; n.y = by; n.z = cz + sz;
            // out of the body, toward the side (perpendicular to the view), then up
            double side = (rng.nextDouble() < 0.5 ? -1 : 1) * (0.4 + rng.nextDouble() * 0.6) * numSpread.get();
            n.vx = -az * side * 0.9 - ax * 0.15;
            n.vz = ax * side * 0.9 - az * 0.15;
            n.vy = rise.get() * (0.8 + rng.nextDouble() * 0.4);
            n.text = fmt(dmg, true);
            double k = Math.min(1.0, dmg / bigHit.get());
            n.color = CosmeticUtil.lerp(new Color(lowColor.get()), new Color(highColor.get()), k, 255);
            n.scale = (float) (numSize.get() * (sizeByDamage.get() ? 0.8 + 0.7 * k : 1.0));
            n.born = now;
            n.life = numLife.get();
            nums.add(n);
        }

        if (particles.get()) {
            int c = (int) Math.round(count.get() + perDamage.get() * dmg);
            for (int i = 0; i < c && parts.size() < MAX_PIXELS; i++) {
                Px p = new Px();
                double a = rng.nextDouble() * Math.PI * 2, rad = Math.sqrt(rng.nextDouble()) * w * 0.45;
                p.x = cx + Math.cos(a) * rad;
                p.z = cz + Math.sin(a) * rad;
                p.y = le.getY() + h * (0.25 + rng.nextDouble() * 0.6);
                double sp = pSpeed.get() * (0.35 + rng.nextDouble() * 0.9);
                double ra = rng.nextDouble() * Math.PI * 2;
                // mostly outward from the body, with a push away from where you stand
                p.vx = (Math.cos(a) * 0.8 + Math.cos(ra) * 0.4 + ax * 0.5) * sp;
                p.vz = (Math.sin(a) * 0.8 + Math.sin(ra) * 0.4 + az * 0.5) * sp;
                p.vy = (0.25 + rng.nextDouble() * 0.9) * sp;
                p.born = now;
                p.life = (int) (pLife.get() * (0.7 + rng.nextDouble() * 0.6));
                p.hue = rng.nextFloat();
                p.size = (float) (pSize.get() * (0.6 + rng.nextDouble() * 0.8));
                parts.add(p);
            }
        }
    }

    private void heal(LivingEntity le, float amount, long now) {
        if (nums.size() >= MAX_NUMBERS) return;
        Num n = new Num();
        n.x = le.getX() + (rng.nextDouble() - 0.5) * 0.4;
        n.y = le.getY() + le.getHeight() * 0.9;
        n.z = le.getZ() + (rng.nextDouble() - 0.5) * 0.4;
        n.vx = (rng.nextDouble() - 0.5) * 0.3;
        n.vz = (rng.nextDouble() - 0.5) * 0.3;
        n.vy = rise.get() * 0.6;
        n.text = fmt(amount, false);
        n.color = new Color(healColor.get());
        n.scale = (float) (numSize.get() * 0.85);
        n.born = now;
        n.life = numLife.get();
        nums.add(n);
    }

    // ------------------------------------------------------------------ pixels (3D)

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (parts.isEmpty() || mc.gameRenderer == null) return;

        Camera cam = mc.gameRenderer.getCamera();
        double yaw = Math.toRadians(cam.getYaw()), pitch = Math.toRadians(cam.getPitch());
        double fx = -Math.sin(yaw) * Math.cos(pitch), fy = -Math.sin(pitch), fz = Math.cos(yaw) * Math.cos(pitch);
        double rl = Math.max(1e-6, Math.sqrt(fz * fz + fx * fx));
        rx = -fz / rl; ry = 0; rz = fx / rl;
        ux = ry * fz - rz * fy; uy = rz * fx - rx * fz; uz = rx * fy - ry * fx;

        Renderer3D r = throughWalls.get() ? event.renderer : event.depthRenderer;
        long now = System.currentTimeMillis();
        double dt = Math.min(0.1, event.frameTime);
        double secs = now / 1000.0;
        int op = opacity.get();

        for (int i = parts.size() - 1; i >= 0; i--) {
            Px p = parts.get(i);
            long age = now - p.born;
            if (age >= p.life) { parts.remove(i); continue; }
            double u = age / (double) p.life;

            p.vy -= gravity.get() * dt;
            p.x += p.vx * dt; p.y += p.vy * dt; p.z += p.vz * dt;
            double damp = Math.exp(-1.5 * dt);
            p.vx *= damp; p.vz *= damp;

            double fade = Math.pow(1.0 - u, 0.8);
            int alpha = (int) (op * Math.max(0, Math.min(1, fade)));
            if (alpha <= 2) continue;

            double s = p.size * 0.5 * (0.4 + 0.6 * (1.0 - u));
            double x = p.x, y = p.y, z = p.z;
            if (snap.get()) {
                x = Math.round(x * 16.0) / 16.0; y = Math.round(y * 16.0) / 16.0; z = Math.round(z * 16.0) / 16.0;
            }
            Color c = rainbow.get()
                ? CosmeticUtil.hsv((secs * 0.6 + p.hue * 0.3), 0.75f, 1f, alpha)
                : CosmeticUtil.lerp(new Color(pColorA.get()), new Color(pColorB.get()), u, alpha);
            if (glow.get()) {
                Color hc = new Color(c.r, c.g, c.b, Math.min(255, alpha / 4));
                square(r, x, y, z, s * 2.6, hc);
            }
            square(r, x, y, z, s, c);
        }
    }

    private void square(Renderer3D r, double x, double y, double z, double s, Color c) {
        // Meteor's quad() colors are for vertices 2, 3, 4, 1; all four are the same here so the order does not matter
        r.quad(
            x - rx * s - ux * s, y - ry * s - uy * s, z - rz * s - uz * s,
            x + rx * s - ux * s, y + ry * s - uy * s, z + rz * s - uz * s,
            x + rx * s + ux * s, y + ry * s + uy * s, z + rz * s + uz * s,
            x - rx * s + ux * s, y - ry * s + uy * s, z - rz * s + uz * s,
            c, c, c, c);
    }

    // ------------------------------------------------------------------ numbers (2D, projected from the world)

    @EventHandler
    private void onRender2D(Render2DEvent event) {
        if (nums.isEmpty()) return;
        long now = System.currentTimeMillis();
        double dt = Math.min(0.1, event.frameTime);
        boolean sh = shadow.get();

        for (int i = nums.size() - 1; i >= 0; i--) {
            Num n = nums.get(i);
            long age = now - n.born;
            if (age >= n.life) { nums.remove(i); continue; }
            double u = age / (double) n.life;

            n.x += n.vx * dt; n.y += n.vy * dt; n.z += n.vz * dt;
            double damp = Math.exp(-2.2 * dt);
            n.vx *= damp; n.vz *= damp; n.vy *= Math.exp(-0.9 * dt);

            // pops in a little bigger, settles, then fades and shrinks at the end
            double pop = u < 0.12 ? 0.5 + 0.9 * (u / 0.12) : (u < 0.25 ? 1.4 - 0.4 * ((u - 0.12) / 0.13) : 1.0);
            double fadeOut = u > 0.6 ? 1.0 - (u - 0.6) / 0.4 : 1.0;
            int alpha = (int) (255 * Math.max(0, Math.min(1, fadeOut)));
            if (alpha <= 4) continue;

            tmp.set(n.x, n.y, n.z);
            if (!NametagUtils.to2D(tmp, n.scale * pop * (0.7 + 0.3 * fadeOut))) continue;

            TextRenderer text = TextRenderer.get();
            NametagUtils.begin(tmp, event.drawContext);
            text.beginBig();
            double w = text.getWidth(n.text, sh);
            Color c = new Color(n.color.r, n.color.g, n.color.b, alpha);
            text.render(n.text, -w / 2.0, -text.getHeight(sh) / 2.0, c, sh);
            text.end();
            NametagUtils.end(event.drawContext);
        }
    }
}
