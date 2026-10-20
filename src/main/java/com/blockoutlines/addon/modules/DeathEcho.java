package com.blockoutlines.addon.modules;

import com.blockoutlines.addon.BlockOutlinesAddon;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.Renderer3D;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.packet.s2c.play.EntityStatusS2CPacket;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * When someone dies, a see-through ghost of them stays where they stood, with the pose they had, then breaks up into
 * glowing dust that floats away. Everything is drawn on your screen only.
 */
public class DeathEcho extends Module {
    private final SettingGroup sgWho = settings.getDefaultGroup();
    private final SettingGroup sgLook = settings.createGroup("Look");

    private final Setting<Boolean> players = sgWho.add(new BoolSetting.Builder()
        .name("players").description("Leave an echo when a player dies.").defaultValue(true).build());
    private final Setting<Boolean> mobs = sgWho.add(new BoolSetting.Builder()
        .name("mobs").description("Also leave an echo when a mob dies (a plain ghost box of its size).").defaultValue(false).build());
    private final Setting<Boolean> includeSelf = sgWho.add(new BoolSetting.Builder()
        .name("include-self").description("Also leave an echo when you die.").defaultValue(false).build());
    private final Setting<Integer> range = sgWho.add(new IntSetting.Builder()
        .name("range").description("Only for deaths this close to you (blocks).").defaultValue(64).range(8, 256).sliderRange(8, 128).build());
    private final Setting<Integer> maxEchoes = sgWho.add(new IntSetting.Builder()
        .name("max-echoes").description("Most ghosts on screen at once.").defaultValue(8).range(1, 40).sliderRange(1, 20).build());

    private final Setting<SettingColor> color = sgLook.add(new ColorSetting.Builder()
        .name("color").description("Color of the ghost.").defaultValue(new SettingColor(130, 210, 255, 255)).build());
    private final Setting<Integer> opacity = sgLook.add(new IntSetting.Builder()
        .name("opacity").description("How strong the ghost is (percent).").defaultValue(55).range(5, 100).sliderRange(10, 100).build());
    private final Setting<Boolean> outline = sgLook.add(new BoolSetting.Builder()
        .name("outline").description("Draw glowing edges around the ghost.").defaultValue(true).build());
    private final Setting<Integer> holdMs = sgLook.add(new IntSetting.Builder()
        .name("stay-time").description("How long the ghost stands there before it breaks up (ms).").defaultValue(1600).range(200, 8000).sliderRange(500, 4000).build());
    private final Setting<Integer> dissolveMs = sgLook.add(new IntSetting.Builder()
        .name("dissolve-time").description("How long the break-up takes (ms).").defaultValue(1700).range(300, 6000).sliderRange(500, 4000).build());
    private final Setting<Double> rise = sgLook.add(new DoubleSetting.Builder()
        .name("rise").description("How high the ghost floats while it fades (blocks).").defaultValue(0.8).range(0.0, 4.0).sliderRange(0.0, 2.0).build());
    private final Setting<Integer> dust = sgLook.add(new IntSetting.Builder()
        .name("dust").description("Dust pieces per body part (0 = none).").defaultValue(14).range(0, 60).sliderRange(0, 40).build());
    private final Setting<Boolean> throughWalls = sgLook.add(new BoolSetting.Builder()
        .name("through-walls").description("Show the ghost through walls.").defaultValue(false).build());
    private final Setting<Boolean> sound = sgLook.add(new BoolSetting.Builder()
        .name("sound").description("A soft ghostly sound when it breaks up.").defaultValue(true).build());
    private final Setting<Double> volume = sgLook.add(new DoubleSetting.Builder()
        .name("volume").description("Volume of that sound.").defaultValue(0.8).range(0.0, 2.0).sliderRange(0.0, 1.5).build());

    // one body part of the ghost, in the ghost's own space (x = its left, z = its front, y up from its feet)
    private static final class Part {
        final double cx, y0, y1, cz, hx, hz;
        final boolean head;
        final double delay; // when this part starts to break up (0..1 of the break-up time)
        boolean burst;
        Part(double cx, double y0, double y1, double cz, double hx, double hz, boolean head, double delay) {
            this.cx = cx; this.y0 = y0; this.y1 = y1; this.cz = cz; this.hx = hx; this.hz = hz; this.head = head; this.delay = delay;
        }
    }

    private static final class Echo {
        double x, y, z;
        double bodyYaw, headYaw;
        long start;
        boolean soundDone;
        final List<Part> parts = new ArrayList<>();
    }

    private static final class Dust {
        double x, y, z, vx, vy, vz, age, life, size;
    }

    private final List<Echo> echoes = new ArrayList<>();
    private final List<Dust> dusts = new ArrayList<>();
    private final Map<Integer, Long> handled = new HashMap<>();
    private final Random rng = new Random();
    private long lastFrame;

    public DeathEcho() {
        super(BlockOutlinesAddon.COMBAT, "death-echo",
            "A see-through ghost of whoever dies stays where they stood, then breaks up into floating dust.");
    }

    @Override
    public void onActivate() {
        echoes.clear();
        dusts.clear();
        handled.clear();
        lastFrame = 0;
    }

    @Override
    public void onDeactivate() {
        echoes.clear();
        dusts.clear();
        handled.clear();
    }

    // ------------------------------------------------------------------ detecting deaths

    @EventHandler
    private void onPacket(PacketEvent.Receive event) {
        if (!(event.packet instanceof EntityStatusS2CPacket p) || p.getStatus() != 3) return;
        // this arrives on the network thread; the world belongs to the game thread
        mc.execute(() -> {
            if (mc.world == null) return;
            Entity e = p.getEntity(mc.world);
            if (e != null) died(e);
        });
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null || mc.player == null) return;
        long now = System.currentTimeMillis();
        handled.values().removeIf(t -> now - t > 5000);
        if (!players.get()) return;
        // some servers do not send the death packet
        for (PlayerEntity pl : mc.world.getPlayers()) {
            if (pl.getHealth() <= 0f || pl.isDead()) died(pl);
        }
    }

    private void died(Entity e) {
        if (mc.player == null || !(e instanceof LivingEntity le)) return;
        boolean isPlayer = e instanceof PlayerEntity;
        if (isPlayer) {
            if (!players.get()) return;
            if (e == mc.player && !includeSelf.get()) return;
            if (((PlayerEntity) e).isSpectator()) return;
        } else if (!mobs.get() || !(e instanceof MobEntity)) {
            return;
        }
        long now = System.currentTimeMillis();
        Long seen = handled.get(e.getId());
        if (seen != null && now - seen < 4000) return;
        handled.put(e.getId(), now);
        if (mc.player.squaredDistanceTo(e) > (double) range.get() * range.get()) return;

        Echo ec = new Echo();
        ec.x = e.getX();
        ec.y = e.getY();
        ec.z = e.getZ();
        ec.bodyYaw = Math.toRadians(le.bodyYaw);
        ec.headYaw = Math.toRadians(le.getHeadYaw());
        ec.start = now;

        if (isPlayer) {
            // a simple human shape; the head turns the way the player was looking
            double sc = e.getHeight() / 1.8;
            double legTop = 0.70 * sc, torsoTop = 1.35 * sc, headTop = 1.85 * sc;
            ec.parts.add(new Part(0, torsoTop, headTop, 0, 0.25 * sc, 0.25 * sc, true, 0.00));
            ec.parts.add(new Part(-0.375 * sc, legTop, torsoTop, 0, 0.125 * sc, 0.125 * sc, false, 0.10));
            ec.parts.add(new Part(0.375 * sc, legTop, torsoTop, 0, 0.125 * sc, 0.125 * sc, false, 0.14));
            ec.parts.add(new Part(0, legTop, torsoTop, 0, 0.25 * sc, 0.125 * sc, false, 0.18));
            ec.parts.add(new Part(-0.125 * sc, 0, legTop, 0, 0.125 * sc, 0.125 * sc, false, 0.28));
            ec.parts.add(new Part(0.125 * sc, 0, legTop, 0, 0.125 * sc, 0.125 * sc, false, 0.34));
        } else {
            double hw = e.getWidth() / 2.0;
            ec.parts.add(new Part(0, 0, e.getHeight(), 0, hw, hw, false, 0.0));
        }

        echoes.add(ec);
        while (echoes.size() > maxEchoes.get()) echoes.remove(0);
    }

    // ------------------------------------------------------------------ drawing

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (echoes.isEmpty() && dusts.isEmpty()) { lastFrame = 0; return; }
        if (mc.world == null) return;

        Renderer3D r = throughWalls.get() ? event.renderer : event.depthRenderer;
        long now = System.currentTimeMillis();
        double dt = lastFrame == 0 ? 0.016 : Math.min(0.1, (now - lastFrame) / 1000.0);
        lastFrame = now;

        double hold = holdMs.get(), diss = dissolveMs.get();
        SettingColor col = color.get();
        double op = opacity.get() / 100.0;

        for (int i = echoes.size() - 1; i >= 0; i--) {
            Echo ec = echoes.get(i);
            double age = now - ec.start;
            if (age > hold + diss + 1200) { echoes.remove(i); continue; }

            double q = age <= hold ? 0 : Math.min(1, (age - hold) / diss);
            // the ghost bobs a little while it waits, then floats up as it fades
            double bob = Math.sin(age / 420.0) * 0.04;
            double lift = bob + q * q * rise.get();
            double shimmer = 0.85 + 0.15 * Math.sin(age / 90.0);

            if (q > 0 && !ec.soundDone) {
                ec.soundDone = true;
                if (sound.get() && volume.get() > 0.01 && mc.world != null) {
                    try {
                        mc.world.playSoundClient(ec.x, ec.y, ec.z, SoundEvent.of(Identifier.ofVanilla("particle.soul_escape")),
                            SoundCategory.PLAYERS, volume.get().floatValue(), 0.9f + rng.nextFloat() * 0.3f, false);
                    } catch (Throwable ignored) {
                    }
                }
            }

            double appear = Math.min(1, age / 220.0); // fades in quickly instead of popping

            for (Part pt : ec.parts) {
                double pq = q <= pt.delay ? 0 : Math.min(1, (q - pt.delay) / Math.max(0.2, 1 - pt.delay * 0.9));
                if (pq > 0 && !pt.burst) {
                    pt.burst = true;
                    spawnDust(ec, pt, lift);
                }
                if (pq >= 1) continue;

                double fade = (1 - pq) * (1 - pq) * appear * op * shimmer;
                double shrink = 1 - 0.45 * pq;
                double extraUp = pq * 0.5;
                drawPart(r, ec, pt, lift + extraUp, shrink, fade, col);
            }
        }

        updateDust(r, dt, col);
    }

    private void spawnDust(Echo ec, Part pt, double lift) {
        int n = dust.get();
        if (n <= 0) return;
        double yaw = pt.head ? ec.headYaw : ec.bodyYaw;
        double cos = Math.cos(yaw), sin = Math.sin(yaw);
        for (int i = 0; i < n; i++) {
            double lx = pt.cx + (rng.nextDouble() * 2 - 1) * pt.hx;
            double lz = pt.cz + (rng.nextDouble() * 2 - 1) * pt.hz;
            double ly = pt.y0 + rng.nextDouble() * (pt.y1 - pt.y0);
            Dust d = new Dust();
            d.x = ec.x + lx * cos - lz * sin;
            d.z = ec.z + lx * sin + lz * cos;
            d.y = ec.y + ly + lift;
            d.vx = (rng.nextDouble() - 0.5) * 0.5;
            d.vz = (rng.nextDouble() - 0.5) * 0.5;
            d.vy = 0.5 + rng.nextDouble() * 1.1;
            d.life = 0.9 + rng.nextDouble() * 1.1;
            d.size = 0.03 + rng.nextDouble() * 0.045;
            dusts.add(d);
        }
        while (dusts.size() > 1500) dusts.remove(0);
    }

    private void updateDust(Renderer3D r, double dt, SettingColor col) {
        for (int i = dusts.size() - 1; i >= 0; i--) {
            Dust d = dusts.get(i);
            d.age += dt;
            if (d.age >= d.life) { dusts.remove(i); continue; }
            d.x += d.vx * dt;
            d.y += d.vy * dt;
            d.z += d.vz * dt;
            d.vx *= 0.985;
            d.vz *= 0.985;
            d.vy *= 0.992;
            double f = 1 - d.age / d.life;
            double s = d.size * (0.4 + 0.6 * f);
            int a = (int) Math.round(255 * Math.min(1, f * 1.4));
            // a little brighter than the ghost, like glowing embers
            Color c = new Color(Math.min(255, col.r + 70), Math.min(255, col.g + 50), Math.min(255, col.b + 30), a);
            r.boxSides(d.x - s, d.y - s, d.z - s, d.x + s, d.y + s, d.z + s, c, 0);
        }
    }

    /** One body part: a box turned by the ghost's yaw, see-through and fading toward the feet. */
    private void drawPart(Renderer3D r, Echo ec, Part pt, double lift, double shrink, double alpha, SettingColor col) {
        if (alpha < 0.01) return;
        double yaw = pt.head ? ec.headYaw : ec.bodyYaw;
        double cos = Math.cos(yaw), sin = Math.sin(yaw);

        double cy = (pt.y0 + pt.y1) / 2, hy = (pt.y1 - pt.y0) / 2 * shrink;
        double hx = pt.hx * shrink, hz = pt.hz * shrink;
        double fullTop = 1.85; // used to make the feet more see-through than the head

        double[][] c = new double[8][3];
        double[][] cA = new double[8][1];
        int k = 0;
        for (int sy = 0; sy < 2; sy++) {
            for (int sx = 0; sx < 2; sx++) {
                for (int sz = 0; sz < 2; sz++) {
                    double lx = pt.cx + (sx == 0 ? -hx : hx);
                    double lz = pt.cz + (sz == 0 ? -hz : hz);
                    double ly = cy + (sy == 0 ? -hy : hy);
                    c[k][0] = ec.x + lx * cos - lz * sin;
                    c[k][2] = ec.z + lx * sin + lz * cos;
                    c[k][1] = ec.y + ly + lift;
                    cA[k][0] = alpha * (0.35 + 0.65 * Math.min(1, ly / fullTop));
                    k++;
                }
            }
        }
        // corner index = sy*4 + sx*2 + sz
        int[][] faces = {
            {0, 1, 3, 2}, // bottom
            {4, 5, 7, 6}, // top
            {0, 1, 5, 4}, {2, 3, 7, 6}, {0, 2, 6, 4}, {1, 3, 7, 5}
        };
        for (int[] f : faces) {
            Color c1 = vc(col, cA[f[0]][0]), c2 = vc(col, cA[f[1]][0]), c3 = vc(col, cA[f[2]][0]), c4 = vc(col, cA[f[3]][0]);
            r.quad(c[f[0]][0], c[f[0]][1], c[f[0]][2], c[f[1]][0], c[f[1]][1], c[f[1]][2],
                c[f[2]][0], c[f[2]][1], c[f[2]][2], c[f[3]][0], c[f[3]][1], c[f[3]][2], c2, c3, c4, c1);
        }

        if (outline.get()) {
            int[][] edges = {
                {0, 1}, {1, 3}, {3, 2}, {2, 0}, {4, 5}, {5, 7}, {7, 6}, {6, 4}, {0, 4}, {1, 5}, {2, 6}, {3, 7}
            };
            for (int[] e : edges) {
                double la = Math.min(1, (cA[e[0]][0] + cA[e[1]][0]) * 0.5 * 2.2);
                Color lc = new Color(Math.min(255, col.r + 60), Math.min(255, col.g + 40), Math.min(255, col.b + 20), (int) Math.round(255 * la));
                r.line(c[e[0]][0], c[e[0]][1], c[e[0]][2], c[e[1]][0], c[e[1]][1], c[e[1]][2], lc);
            }
        }
    }

    private static Color vc(SettingColor col, double a) {
        return new Color(col.r, col.g, col.b, (int) Math.round(255 * Math.max(0, Math.min(1, a))));
    }
}
