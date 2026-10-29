package com.blockoutlines.addon.modules;

import com.blockoutlines.addon.BlockOutlinesAddon;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

/**
 * A film camera that films you. Turn it on and the camera pulls away from your head and circles you smoothly, with
 * black cinema bars and a little handheld shake. You can keep walking and fighting: only the camera moves.
 * Bind a key to the module to switch it on and off.
 */
public class CinematicCamera extends Module {
    public enum Shot { Orbit, Front, Crane, Spiral }

    private final SettingGroup sg = settings.getDefaultGroup();
    private final SettingGroup sgBars = settings.createGroup("Look");

    private final Setting<Shot> shot = sg.add(new EnumSetting.Builder<Shot>().name("shot")
        .description("Orbit: circles you. Front: stays in front of you and sways. Crane: circles while rising and sinking. Spiral: circles while moving in and out.")
        .defaultValue(Shot.Orbit).build());
    private final Setting<Double> distance = sg.add(new DoubleSetting.Builder().name("distance").defaultValue(4.0).range(1.2, 16).sliderRange(2, 10).build());
    private final Setting<Double> height = sg.add(new DoubleSetting.Builder().name("height")
        .description("How far above your eyes the camera is.").defaultValue(0.7).range(-1.5, 8).sliderRange(-1, 5).build());
    private final Setting<Double> speed = sg.add(new DoubleSetting.Builder().name("speed")
        .description("Degrees per second.").defaultValue(12).range(1, 90).sliderRange(4, 40).build());
    private final Setting<Boolean> reverse = sg.add(new BoolSetting.Builder().name("reverse").description("Circle the other way.").defaultValue(false).build());
    private final Setting<Boolean> clip = sg.add(new BoolSetting.Builder().name("avoid-walls")
        .description("The camera moves closer instead of going inside blocks.").defaultValue(true).build());

    private final Setting<Boolean> letterbox = sgBars.add(new BoolSetting.Builder().name("cinema-bars").defaultValue(true).build());
    private final Setting<Double> ratio = sgBars.add(new DoubleSetting.Builder().name("aspect-ratio")
        .description("2.39 is the cinema look.").defaultValue(2.39).range(1.5, 3.5).visible(letterbox::get).build());
    private final Setting<Double> shake = sgBars.add(new DoubleSetting.Builder().name("handheld-shake")
        .description("A very small camera sway.").defaultValue(0.35).range(0, 2).sliderRange(0, 1.5).build());

    private long startNs;
    private double a0;
    private final double[] out = new double[5];

    public CinematicCamera() {
        super(BlockOutlinesAddon.COSMETICS, "cinematic-camera", "A film camera circles you with cinema bars. Bind a key to switch it.");
    }

    @Override
    public void onActivate() {
        startNs = System.nanoTime();
        a0 = mc.player != null ? Math.toRadians(mc.player.getYaw()) + Math.PI : 0;
    }

    private static double ease(double x) {
        x = Math.max(0, Math.min(1, x));
        return 1 - Math.pow(1 - x, 3);
    }

    /** x, y, z, yaw, pitch of the camera for this frame, or null if it should stay normal. Called by the camera mixin. */
    public double[] sample() {
        if (mc.player == null || mc.world == null) return null;
        float td = mc.getRenderTickCounter().getTickProgress(true);
        double t = (System.nanoTime() - startNs) / 1e9;
        Vec3d p = mc.player.getLerpedPos(td);
        double eyeY = p.y + mc.player.getEyeHeight(mc.player.getPose()) * 0.92;
        double in = ease(t / 1.3);

        double dist = distance.get();
        double hOff = height.get();
        double dir = reverse.get() ? -1 : 1;
        double a;
        switch (shot.get()) {
            case Front -> a = Math.toRadians(mc.player.getLerpedYaw(td)) + Math.sin(t * 0.4) * Math.toRadians(35) * dir;
            case Crane -> {
                a = a0 + dir * Math.toRadians(speed.get()) * t;
                hOff += 2.2 * (0.5 - 0.5 * Math.cos(t * 0.35));
            }
            case Spiral -> {
                a = a0 + dir * Math.toRadians(speed.get()) * t;
                dist *= 1 + 0.4 * Math.sin(t * 0.6);
            }
            default -> a = a0 + dir * Math.toRadians(speed.get()) * t;
        }
        // the camera starts at your head and pulls out
        dist *= 0.12 + 0.88 * in;
        hOff *= in;

        double hx = -Math.sin(a), hz = Math.cos(a);
        Vec3d target = new Vec3d(p.x, eyeY, p.z);
        Vec3d cam = new Vec3d(p.x + hx * dist, eyeY + hOff, p.z + hz * dist);

        if (clip.get()) {
            try {
                RaycastContext ctx = new RaycastContext(target, cam, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, mc.player);
                BlockHitResult hit = mc.world.raycast(ctx);
                if (hit != null && hit.getType() != HitResult.Type.MISS) {
                    Vec3d dirv = cam.subtract(target).normalize();
                    cam = hit.getPos().subtract(dirv.multiply(0.3));
                }
            } catch (Throwable ignored) {
            }
        }

        double dx = target.x - cam.x, dy = target.y - cam.y, dz = target.z - cam.z;
        double yaw = Math.toDegrees(Math.atan2(-dx, dz));
        double pitch = Math.toDegrees(-Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        double sk = shake.get() * in;
        yaw += sk * (Math.sin(t * 1.7) * 0.6 + Math.sin(t * 3.1 + 1) * 0.4) * 0.6;
        pitch += sk * (Math.sin(t * 1.3 + 1) * 0.5 + Math.sin(t * 2.7) * 0.5) * 0.4;

        out[0] = cam.x;
        out[1] = cam.y;
        out[2] = cam.z;
        out[3] = yaw;
        out[4] = Math.max(-90, Math.min(90, pitch));
        return out;
    }

    @EventHandler
    private void onRender2D(Render2DEvent event) {
        if (!letterbox.get() || mc.getWindow() == null) return;
        int w = mc.getWindow().getScaledWidth(), h = mc.getWindow().getScaledHeight();
        double in = ease((System.nanoTime() - startNs) / 1e9 / 0.9);
        int bar = (int) (Math.max(0, (h - w / ratio.get()) / 2.0) * in);
        if (bar <= 0) return;
        event.drawContext.fill(0, 0, w, bar, 0xFF000000);
        event.drawContext.fill(0, h - bar, w, h, 0xFF000000);
    }
}
