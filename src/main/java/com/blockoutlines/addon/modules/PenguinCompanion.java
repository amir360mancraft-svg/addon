package com.blockoutlines.addon.modules;

import com.blockoutlines.addon.BlockOutlinesAddon;
import com.blockoutlines.addon.util.Shapes;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.renderer.Renderer3D;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.util.math.Vec3d;

/**
 * A small penguin built from cubes that follows you around. It waddles when you walk, slides on its belly when you
 * run, flaps its flippers when you jump and sits down when you stand still. Drawn on your screen only.
 */
public class PenguinCompanion extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgColor = settings.createGroup("Colors");

    private final Setting<Double> scale = sgGeneral.add(new DoubleSetting.Builder()
        .name("size").description("Size of the penguin.").defaultValue(1.0).range(0.4, 3.0).sliderRange(0.5, 2.0).build());
    private final Setting<Double> distance = sgGeneral.add(new DoubleSetting.Builder()
        .name("distance").description("How far behind you it likes to stay (blocks).").defaultValue(1.8).range(0.8, 5.0).sliderRange(1.0, 4.0).build());
    private final Setting<Double> speed = sgGeneral.add(new DoubleSetting.Builder()
        .name("speed").description("How fast it runs after you (blocks per second).").defaultValue(5.5).range(2.0, 12.0).sliderRange(3.0, 9.0).build());
    private final Setting<Boolean> slide = sgGeneral.add(new BoolSetting.Builder()
        .name("belly-slide").description("It lies on its belly and slides when it has to hurry.").defaultValue(true).build());
    private final Setting<Boolean> sit = sgGeneral.add(new BoolSetting.Builder()
        .name("sit-when-idle").description("It sits down when you stand still for a few seconds.").defaultValue(true).build());
    private final Setting<Boolean> hat = sgGeneral.add(new BoolSetting.Builder()
        .name("scarf").description("A little scarf around its neck.").defaultValue(true).build());

    private final Setting<SettingColor> bodyColor = sgColor.add(new ColorSetting.Builder()
        .name("body").description("Back and head.").defaultValue(new SettingColor(28, 32, 44, 255)).build());
    private final Setting<SettingColor> bellyColor = sgColor.add(new ColorSetting.Builder()
        .name("belly").description("Front.").defaultValue(new SettingColor(240, 242, 248, 255)).build());
    private final Setting<SettingColor> beakColor = sgColor.add(new ColorSetting.Builder()
        .name("beak-and-feet").description("Beak and feet.").defaultValue(new SettingColor(255, 150, 30, 255)).build());
    private final Setting<SettingColor> scarfColor = sgColor.add(new ColorSetting.Builder()
        .name("scarf-color").description("Color of the scarf.").defaultValue(new SettingColor(220, 40, 60, 255)).visible(hat::get).build());

    private double x, y, z, yaw, groundY, walk, sitFade, idleSecs, tilt;
    private boolean placed;

    public PenguinCompanion() {
        super(BlockOutlinesAddon.EFFECTS, "penguin-companion", "A small penguin follows you around, waddles, slides and sits down.");
    }

    @Override
    public void onActivate() {
        placed = false;
        sitFade = 0;
        idleSecs = 0;
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (mc.player == null || mc.world == null) return;
        double dt = Math.min(0.1, event.frameTime);
        double secs = System.currentTimeMillis() / 1000.0;
        Vec3d pl = mc.player.getLerpedPos(event.tickDelta);
        double pyaw = Math.toRadians(mc.player.getBodyYaw());

        if (mc.player.isOnGround()) groundY = pl.y;
        if (!placed) {
            x = pl.x - Math.sin(pyaw) * -distance.get();
            z = pl.z + Math.cos(pyaw) * -distance.get();
            y = groundY = pl.y;
            yaw = pyaw;
            placed = true;
        }

        // the spot it wants: behind your right shoulder
        double bx = pl.x - (-Math.sin(pyaw)) * distance.get() + (-Math.cos(pyaw)) * 0.6;
        double bz = pl.z - Math.cos(pyaw) * distance.get() + (-Math.sin(pyaw)) * 0.6;
        double dx = bx - x, dz = bz - z;
        double dist = Math.hypot(dx, dz);
        if (dist > 14.0) { x = bx; z = bz; y = groundY; dist = 0; dx = dz = 0; }

        double moved = 0;
        double stop = 0.35;
        if (dist > stop) {
            double want = Math.min(speed.get() * (0.4 + Math.min(1.5, dist * 0.5)), speed.get() * 1.6);
            moved = Math.min(dist - stop, want * dt);
            x += dx / dist * moved;
            z += dz / dist * moved;
            double goal = Math.atan2(-dx, dz);
            double diff = Math.atan2(Math.sin(goal - yaw), Math.cos(goal - yaw));
            yaw += diff * Math.min(1.0, dt * 9.0);
        } else {
            // standing: turn to look at you
            double goal = Math.atan2(-(pl.x - x), pl.z - z);
            double diff = Math.atan2(Math.sin(goal - yaw), Math.cos(goal - yaw));
            yaw += diff * Math.min(1.0, dt * 3.0);
        }
        double v = moved / Math.max(dt, 1e-4);   // blocks per second
        boolean moving = v > 0.6;
        y += (groundY - y) * Math.min(1.0, dt * 12.0);
        walk += dt * (moving ? 5.0 + v * 1.8 : 0.0);

        // sit down after standing still
        boolean playerStill = mc.player.getVelocity().horizontalLength() < 0.02;
        idleSecs = (!moving && playerStill) ? idleSecs + dt : 0;
        double sitGoal = (sit.get() && idleSecs > 4.0) ? 1.0 : 0.0;
        sitFade += (sitGoal - sitFade) * Math.min(1.0, dt * 4.0);

        double slideGoal = (slide.get() && v > speed.get() * 0.8) ? 1.0 : 0.0;
        tilt += (slideGoal - tilt) * Math.min(1.0, dt * 8.0);

        double s = scale.get();
        double flap = mc.player.isOnGround() ? (moving ? Math.sin(walk * 2.0) * 0.25 : Math.sin(secs * 2.2) * 0.05) : Math.sin(secs * 22.0) * 0.9;
        flap += tilt * 0.9;
        double roll = Math.sin(walk) * 0.2 * (1 - tilt);
        double bob = Math.abs(Math.sin(walk)) * 0.025 * (1 - tilt);
        double sink = -0.06 * sitFade;

        final double oy = y + bob + sink, ox = x, oz = z;
        final double cyw = Math.cos(yaw), syw = Math.sin(yaw);
        final double tl = tilt * 1.15, ct = Math.cos(tl), st = Math.sin(tl);
        final double cr = Math.cos(roll), sr = Math.sin(roll);
        final double sc = s;
        Shapes.Tf tf = (lx, ly, lz) -> {
            lx *= sc; ly *= sc; lz *= sc;
            double x1 = lx * cr - ly * sr, y1 = lx * sr + ly * cr;
            double y2 = y1 * ct - lz * st, z2 = y1 * st + lz * ct;
            return new double[] {ox - x1 * cyw - z2 * syw, oy + y2 + (tl > 0 ? 0.12 * sc * Math.sin(tl) : 0), oz - x1 * syw + z2 * cyw};
        };

        Renderer3D r = event.depthRenderer;
        SettingColor body = bodyColor.get(), belly = bellyColor.get(), beak = beakColor.get();
        double legLift = 0.02;
        // feet (they step while walking; hidden under the body when sitting)
        double step = moving ? Math.sin(walk) * 0.07 * (1 - tilt) : 0;
        double footH = 0.05 * (1 - 0.5 * sitFade);
        Shapes.box(r, tf, 0.09, legLift + footH / 2, 0.04 + step, 0.12, footH, 0.2, 0, beak.r, beak.g, beak.b, 255);
        Shapes.box(r, tf, -0.09, legLift + footH / 2, 0.04 - step, 0.12, footH, 0.2, 0, beak.r, beak.g, beak.b, 255);
        double bodyBase = legLift + footH - 0.01 - 0.04 * sitFade;
        // body, belly
        Shapes.box(r, tf, 0, bodyBase + 0.2, 0, 0.34, 0.4, 0.26, 0, body.r, body.g, body.b, 255);
        Shapes.box(r, tf, 0, bodyBase + 0.19, 0.125, 0.25, 0.33, 0.04, 0, belly.r, belly.g, belly.b, 255);
        Shapes.box(r, tf, 0, bodyBase + 0.02, 0.0, 0.3, 0.04, 0.22, 0, belly.r, belly.g, belly.b, 255);
        // head: tilts a little towards you
        double hy = bodyBase + 0.4;
        Shapes.box(r, tf, 0, hy + 0.11, 0.01, 0.27, 0.22, 0.25, 0, body.r, body.g, body.b, 255);
        Shapes.box(r, tf, 0, hy + 0.06, 0.125, 0.15, 0.1, 0.03, 0, belly.r, belly.g, belly.b, 255);
        // eyes and beak
        Shapes.box(r, tf, 0.07, hy + 0.14, 0.135, 0.06, 0.06, 0.02, 0, 255, 255, 255, 255);
        Shapes.box(r, tf, -0.07, hy + 0.14, 0.135, 0.06, 0.06, 0.02, 0, 255, 255, 255, 255);
        double blink = (secs % 4.0) > 3.85 ? 0.0 : 0.035;
        Shapes.box(r, tf, 0.07, hy + 0.14, 0.15, 0.03, Math.max(0.005, blink), 0.02, 0, 10, 10, 14, 255);
        Shapes.box(r, tf, -0.07, hy + 0.14, 0.15, 0.03, Math.max(0.005, blink), 0.02, 0, 10, 10, 14, 255);
        Shapes.box(r, tf, 0, hy + 0.09, 0.17, 0.08, 0.04, 0.1, 0, beak.r, beak.g, beak.b, 255);
        // flippers
        Shapes.box(r, tf, 0.2, bodyBase + 0.24, 0, 0.05, 0.26, 0.13, -0.15 - flap, body.r, body.g, body.b, 255);
        Shapes.box(r, tf, -0.2, bodyBase + 0.24, 0, 0.05, 0.26, 0.13, 0.15 + flap, body.r, body.g, body.b, 255);
        // scarf
        if (hat.get()) {
            SettingColor sc2 = scarfColor.get();
            Shapes.box(r, tf, 0, hy - 0.01, 0, 0.3, 0.07, 0.28, 0, sc2.r, sc2.g, sc2.b, 255);
            Shapes.box(r, tf, 0.1, hy - 0.1, 0.14, 0.07, 0.15, 0.04, 0, sc2.r, sc2.g, sc2.b, 255);
        }
    }
}
