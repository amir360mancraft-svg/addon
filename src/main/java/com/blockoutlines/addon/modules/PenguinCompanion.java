package com.blockoutlines.addon.modules;

import com.blockoutlines.addon.BlockOutlinesAddon;
import com.blockoutlines.addon.util.Shapes;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.Renderer3D;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;

import java.util.Random;

/**
 * A small penguin made of cubes that behaves like a tamed cat: it sits, naps, wanders around you, sometimes comes
 * over to sit next to you, and only runs after you when you get far away. It never jumps to your back when you turn
 * around. Drawn on your screen only.
 */
public class PenguinCompanion extends Module {
    private enum State { SIT, LIE, WANDER, FOLLOW, VISIT }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgBehavior = settings.createGroup("Behavior");
    private final SettingGroup sgColor = settings.createGroup("Colors");

    private final Setting<Double> scale = sgGeneral.add(new DoubleSetting.Builder()
        .name("size").description("Size of the penguin.").defaultValue(1.0).range(0.4, 3.0).sliderRange(0.5, 2.0).build());
    private final Setting<Boolean> hat = sgGeneral.add(new BoolSetting.Builder()
        .name("scarf").description("A little scarf around its neck.").defaultValue(true).build());
    private final Setting<Boolean> slide = sgGeneral.add(new BoolSetting.Builder()
        .name("belly-slide").description("It lies on its belly and slides when it runs after you.").defaultValue(true).build());

    private final Setting<Double> followStart = sgBehavior.add(new DoubleSetting.Builder()
        .name("follow-when-farther-than").description("It starts running after you when you are farther away than this (blocks).")
        .defaultValue(9.0).range(5.0, 20.0).sliderRange(6.0, 16.0).build());
    private final Setting<Double> followStop = sgBehavior.add(new DoubleSetting.Builder()
        .name("stops-at").description("It stops running when it is this close to you (blocks).")
        .defaultValue(3.5).range(1.5, 8.0).sliderRange(2.0, 6.0).build());
    private final Setting<Double> roam = sgBehavior.add(new DoubleSetting.Builder()
        .name("roam-radius").description("How far from you it wanders when it is relaxed (blocks).")
        .defaultValue(5.5).range(2.0, 12.0).sliderRange(3.0, 9.0).build());
    private final Setting<Double> runSpeed = sgBehavior.add(new DoubleSetting.Builder()
        .name("run-speed").description("Speed when it runs after you (blocks per second).")
        .defaultValue(6.0).range(3.0, 12.0).sliderRange(4.0, 9.0).build());
    private final Setting<Boolean> sleepy = sgBehavior.add(new BoolSetting.Builder()
        .name("naps").description("Now and then it lies down and sleeps.").defaultValue(true).build());
    private final Setting<Boolean> visits = sgBehavior.add(new BoolSetting.Builder()
        .name("visits-you").description("Sometimes it walks over and sits next to you, like a cat.").defaultValue(true).build());

    private final Setting<SettingColor> bodyColor = sgColor.add(new ColorSetting.Builder()
        .name("body").description("Back and head.").defaultValue(new SettingColor(28, 32, 44, 255)).build());
    private final Setting<SettingColor> bellyColor = sgColor.add(new ColorSetting.Builder()
        .name("belly").description("Front.").defaultValue(new SettingColor(240, 242, 248, 255)).build());
    private final Setting<SettingColor> beakColor = sgColor.add(new ColorSetting.Builder()
        .name("beak-and-feet").description("Beak and feet.").defaultValue(new SettingColor(255, 150, 30, 255)).build());
    private final Setting<SettingColor> scarfColor = sgColor.add(new ColorSetting.Builder()
        .name("scarf-color").description("Color of the scarf.").defaultValue(new SettingColor(220, 40, 60, 255)).visible(hat::get).build());

    private final Random rnd = new Random();
    private final BlockPos.Mutable pos = new BlockPos.Mutable();
    private final Shapes.Batch batch = new Shapes.Batch();

    private State state = State.SIT;
    private double stateTime, stateLen;
    private double px, py, pz, pyaw;
    private double x, y, z, yaw, walk, sitFade, lieFade, pitch;
    private double goalX, goalZ;
    private boolean placed;
    private double speedNow;

    public PenguinCompanion() {
        super(BlockOutlinesAddon.EFFECTS, "penguin-companion", "A small penguin that acts like a tamed cat: sits, naps, wanders and follows when you go far.");
    }

    @Override
    public void onActivate() {
        placed = false;
        state = State.SIT;
        stateTime = 0;
        stateLen = 3;
        sitFade = lieFade = pitch = 0;
    }

    // ------------------------------------------------------------------ ground

    /** Top of the solid block at (bx, bz) near the height refY, or NaN when there is nothing safe to stand on. */
    private double surface(double bx, double bz, double refY) {
        int ix = (int) Math.floor(bx), iz = (int) Math.floor(bz);
        int top = (int) Math.floor(refY) + 2;
        for (int yy = top; yy >= top - 8; yy--) {
            pos.set(ix, yy, iz);
            BlockState st = mc.world.getBlockState(pos);
            if (!st.getFluidState().isEmpty()) return Double.NaN;
            VoxelShape shape = st.getCollisionShape(mc.world, pos);
            if (shape.isEmpty()) continue;
            double h = yy + shape.getMax(Direction.Axis.Y);
            pos.set(ix, yy + 1, iz);
            if (!mc.world.getBlockState(pos).getCollisionShape(mc.world, pos).isEmpty()) return Double.NaN;
            return h;
        }
        return Double.NaN;
    }

    // ------------------------------------------------------------------ behavior (runs 20 times per second)

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;
        double dt = 0.05;
        px = x; py = y; pz = z; pyaw = yaw;
        Vec3d pl = mc.player.getEntityPos();
        if (!placed) {
            double s = surface(pl.x + 1.5, pl.z + 1.5, pl.y);
            x = pl.x + 1.5; z = pl.z + 1.5;
            y = Double.isNaN(s) ? pl.y : s;
            yaw = 0;
            px = x; py = y; pz = z; pyaw = yaw;
            placed = true;
        }
        double dpx = pl.x - x, dpz = pl.z - z;
        double dist = Math.hypot(dpx, dpz);

        // far away: come back (a cat teleports when it is really lost)
        if (dist > 26.0 || Math.abs(pl.y - y) > 14.0) {
            for (int tries = 0; tries < 12; tries++) {
                double a = rnd.nextDouble() * Math.PI * 2, d = 2.0 + rnd.nextDouble() * 2.0;
                double tx = pl.x + Math.cos(a) * d, tz = pl.z + Math.sin(a) * d;
                double s = surface(tx, tz, pl.y);
                if (!Double.isNaN(s)) { x = tx; z = tz; y = s; px = x; py = y; pz = z; break; }
            }
            changeState(State.SIT, 2 + rnd.nextDouble() * 3);
            return;
        }
        if (dist > followStart.get() && state != State.FOLLOW) {
            changeState(State.FOLLOW, 60);
        }

        stateTime += dt;
        double target;
        switch (state) {
            case SIT -> {
                if (stateTime > stateLen) pickRelaxed(pl, dist);
                // look at you now and then
                faceTowards(Math.atan2(-dpx, dpz), dt * 1.2);
            }
            case LIE -> {
                if (stateTime > stateLen || dist > followStart.get()) pickRelaxed(pl, dist);
            }
            case WANDER, VISIT -> {
                double gx = goalX - x, gz = goalZ - z;
                double gd = Math.hypot(gx, gz);
                if (gd < 0.35 || stateTime > stateLen) {
                    changeState(state == State.VISIT ? State.SIT : (rnd.nextDouble() < 0.5 ? State.SIT : State.WANDER), 3 + rnd.nextDouble() * 6);
                    if (state == State.WANDER) pickGoal(pl, roam.get());
                } else {
                    if (!stepTowards(gx, gz, gd, 1.5, dt)) changeState(State.SIT, 2 + rnd.nextDouble() * 3);
                }
            }
            case FOLLOW -> {
                if (dist < followStop.get()) {
                    changeState(State.SIT, 3 + rnd.nextDouble() * 4);
                } else {
                    double gd = dist - followStop.get() * 0.8;
                    if (!stepTowards(dpx, dpz, dist, Math.min(runSpeed.get(), 2.0 + gd * 0.9), dt)) {
                        // blocked (a wall or a drop): try to go around by wandering a bit
                        changeState(State.WANDER, 3);
                        pickGoal(pl, 2.5);
                    }
                }
            }
        }
        if (state != State.WANDER && state != State.VISIT && state != State.FOLLOW) speedNow = 0;
    }

    private void changeState(State s, double len) {
        state = s;
        stateTime = 0;
        stateLen = len;
    }

    /** What a relaxed cat does next. */
    private void pickRelaxed(Vec3d pl, double dist) {
        double r = rnd.nextDouble();
        if (visits.get() && r < 0.12 && dist > 2.5) {
            double a = rnd.nextDouble() * Math.PI * 2;
            goalX = pl.x + Math.cos(a) * 1.4;
            goalZ = pl.z + Math.sin(a) * 1.4;
            changeState(State.VISIT, 10);
        } else if (sleepy.get() && r < 0.28) {
            changeState(State.LIE, 12 + rnd.nextDouble() * 18);
        } else if (r < 0.65) {
            pickGoal(pl, roam.get());
            changeState(State.WANDER, 8);
        } else {
            changeState(State.SIT, 4 + rnd.nextDouble() * 8);
        }
    }

    private void pickGoal(Vec3d pl, double radius) {
        for (int tries = 0; tries < 8; tries++) {
            double a = rnd.nextDouble() * Math.PI * 2, d = 1.0 + rnd.nextDouble() * radius;
            double tx = pl.x + Math.cos(a) * d, tz = pl.z + Math.sin(a) * d;
            double s = surface(tx, tz, y);
            if (!Double.isNaN(s) && Math.abs(s - y) < 1.6) { goalX = tx; goalZ = tz; return; }
        }
        goalX = x; goalZ = z;
    }

    /** Walks towards (gx, gz) with the given speed. Returns false when the way is blocked. */
    private boolean stepTowards(double gx, double gz, double gd, double speed, double dt) {
        double step = Math.min(gd, speed * dt);
        double nx = x + gx / gd * step, nz = z + gz / gd * step;
        double s = surface(nx, nz, y);
        if (Double.isNaN(s) || s - y > 1.1 || y - s > 2.6) return false;
        x = nx; z = nz;
        y += (s - y) * 0.5;
        speedNow = speed;
        faceTowards(Math.atan2(-gx, gz), dt * 10.0);
        return true;
    }

    private void faceTowards(double goal, double k) {
        double diff = Math.atan2(Math.sin(goal - yaw), Math.cos(goal - yaw));
        yaw += diff * Math.min(1.0, k);
    }

    // ------------------------------------------------------------------ drawing

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (mc.player == null || mc.world == null || !placed) return;
        double dt = Math.min(0.1, event.frameTime);
        double secs = System.currentTimeMillis() / 1000.0;

        boolean moving = (state == State.WANDER || state == State.VISIT || state == State.FOLLOW) && speedNow > 0.2;
        if (!moving) speedNow = Math.max(0, speedNow - dt * 8);
        walk += dt * (moving ? 4.5 + speedNow * 1.4 : 0.0);
        sitFade += ((state == State.SIT ? 1.0 : 0.0) - sitFade) * Math.min(1.0, dt * 5.0);
        lieFade += ((state == State.LIE ? 1.0 : 0.0) - lieFade) * Math.min(1.0, dt * 3.0);
        boolean sliding = slide.get() && state == State.FOLLOW && speedNow > runSpeed.get() * 0.7;
        double pitchGoal = lieFade > 0.01 ? 1.5 * lieFade : (sliding ? 1.3 : 0.0);
        pitch += (pitchGoal - pitch) * Math.min(1.0, dt * 6.0);

        double s = scale.get();
        double flap = moving ? Math.sin(walk * 2.0) * (sliding ? 0.2 : 0.25) : Math.sin(secs * 1.8) * 0.04;
        flap += pitch * 0.5;
        double roll = Math.sin(walk) * 0.2 * (1 - Math.min(1, pitch));
        double bob = Math.abs(Math.sin(walk)) * 0.025 * (1 - Math.min(1, pitch));
        double sink = -0.06 * sitFade;

        // the behaviour runs 20 times a second; draw smoothly in between
        double td = Math.max(0, Math.min(1, event.tickDelta));
        double ix = px + (x - px) * td, iy = py + (y - py) * td, iz = pz + (z - pz) * td;
        double dy = Math.atan2(Math.sin(yaw - pyaw), Math.cos(yaw - pyaw));
        double iyaw = pyaw + dy * td;
        final double oy = iy + bob + sink + 0.01, ox = ix, oz = iz;
        final double cyw = Math.cos(iyaw), syw = Math.sin(iyaw);
        final double tl = pitch, ct = Math.cos(tl), st = Math.sin(tl);
        final double cr = Math.cos(roll), sr = Math.sin(roll);
        final double sc = s;
        Shapes.Tf tf = (lx, ly, lz) -> {
            lx *= sc; ly *= sc; lz *= sc;
            double x1 = lx * cr - ly * sr, y1 = lx * sr + ly * cr;
            double y2 = y1 * ct - lz * st, z2 = y1 * st + lz * ct;
            return new double[] {ox - x1 * cyw - z2 * syw, oy + y2 + 0.2 * sc * Math.sin(tl), oz - x1 * syw + z2 * cyw};
        };

        SettingColor body = bodyColor.get(), belly = bellyColor.get(), beak = beakColor.get();
        double legLift = 0.02;
        double step = moving ? Math.sin(walk) * 0.07 * (1 - Math.min(1, pitch)) : 0;
        double footH = 0.05 * (1 - 0.5 * sitFade);
        batch.add(tf, 0.09, legLift + footH / 2, 0.04 + step, 0.12, footH, 0.2, 0, beak.r, beak.g, beak.b, 255);
        batch.add(tf, -0.09, legLift + footH / 2, 0.04 - step, 0.12, footH, 0.2, 0, beak.r, beak.g, beak.b, 255);
        double bb = legLift + footH - 0.01 - 0.04 * sitFade;
        batch.add(tf, 0, bb + 0.2, 0, 0.34, 0.4, 0.26, 0, body.r, body.g, body.b, 255);
        batch.add(tf, 0, bb + 0.19, 0.135, 0.25, 0.33, 0.02, 0, belly.r, belly.g, belly.b, 255);
        double hy = bb + 0.4;
        batch.add(tf, 0, hy + 0.11, 0.01, 0.27, 0.22, 0.25, 0, body.r, body.g, body.b, 255);
        batch.add(tf, 0, hy + 0.06, 0.135, 0.15, 0.1, 0.02, 0, belly.r, belly.g, belly.b, 255);
        boolean asleep = lieFade > 0.6;
        double blinkH = asleep || (secs % 4.0) > 3.88 ? 0.0 : 0.035;
        batch.add(tf, 0.07, hy + 0.14, 0.145, 0.06, 0.06, 0.02, 0, 255, 255, 255, 255);
        batch.add(tf, -0.07, hy + 0.14, 0.145, 0.06, 0.06, 0.02, 0, 255, 255, 255, 255);
        batch.add(tf, 0.07, hy + 0.14, 0.16, 0.03, Math.max(0.006, blinkH), 0.02, 0, 10, 10, 14, 255);
        batch.add(tf, -0.07, hy + 0.14, 0.16, 0.03, Math.max(0.006, blinkH), 0.02, 0, 10, 10, 14, 255);
        batch.add(tf, 0, hy + 0.09, 0.19, 0.08, 0.04, 0.1, 0, beak.r, beak.g, beak.b, 255);
        batch.add(tf, 0.2, bb + 0.24, 0, 0.05, 0.26, 0.13, -0.15 - flap, body.r, body.g, body.b, 255);
        batch.add(tf, -0.2, bb + 0.24, 0, 0.05, 0.26, 0.13, 0.15 + flap, body.r, body.g, body.b, 255);
        if (hat.get()) {
            SettingColor sc2 = scarfColor.get();
            batch.add(tf, 0, hy - 0.005, 0, 0.37, 0.07, 0.31, 0, sc2.r, sc2.g, sc2.b, 255);
            batch.add(tf, 0.11, hy - 0.11, 0.16, 0.07, 0.16, 0.03, 0, sc2.r, sc2.g, sc2.b, 255);
        }
        Renderer3D r = event.depthRenderer;
        batch.flush(r, event.offsetX, event.offsetY, event.offsetZ);
    }
}
