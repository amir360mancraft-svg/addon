package com.blockoutlines.addon.util;

import meteordevelopment.meteorclient.utils.render.color.Color;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Small helpers shared by the cosmetic modules (wings, china hat). */
public final class CosmeticUtil {
    private static final MinecraftClient mc = MinecraftClient.getInstance();

    /** You (not in first person), plus optionally the nearest other players. */
    public static List<AbstractClientPlayerEntity> wearers(boolean everyone, int max, double range) {
        List<AbstractClientPlayerEntity> out = new ArrayList<>();
        if (mc.world == null || mc.player == null) return out;

        if (!mc.options.getPerspective().isFirstPerson()) out.add(mc.player);

        if (everyone) {
            List<AbstractClientPlayerEntity> others = new ArrayList<>();
            for (AbstractClientPlayerEntity p : mc.world.getPlayers()) {
                if (p == mc.player || p.isInvisible() || p.isSpectator()) continue;
                if (p.squaredDistanceTo(mc.player) > range * range) continue;
                others.add(p);
            }
            others.sort(Comparator.comparingDouble(p -> p.squaredDistanceTo(mc.player)));
            for (int i = 0; i < others.size() && i < max; i++) out.add(others.get(i));
        }
        return out;
    }

    /**
     * Hue is taken as a double and wrapped to 0..1 BEFORE it becomes a float. Time in seconds is around 1.7 billion, and
     * a float cannot hold that with any decimals, so a float hue never changed and every rainbow stayed the same pale red.
     */
    public static Color hsv(double hue, float s, float v, int alpha) {
        float h = (float) (hue - Math.floor(hue));
        int i = (int) (h * 6);
        float f = h * 6 - i;
        float p = v * (1 - s), q = v * (1 - f * s), t = v * (1 - (1 - f) * s);
        float r, g, b;
        switch (i % 6) {
            case 0 -> { r = v; g = t; b = p; }
            case 1 -> { r = q; g = v; b = p; }
            case 2 -> { r = p; g = v; b = t; }
            case 3 -> { r = p; g = q; b = v; }
            case 4 -> { r = t; g = p; b = v; }
            default -> { r = v; g = p; b = q; }
        }
        return new Color((int) (r * 255), (int) (g * 255), (int) (b * 255), alpha);
    }

    public static Color lerp(Color a, Color b, double t, int alpha) {
        t = Math.max(0, Math.min(1, t));
        return new Color((int) (a.r + (b.r - a.r) * t), (int) (a.g + (b.g - a.g) * t), (int) (a.b + (b.b - a.b) * t), alpha);
    }

    /** Where the player's body is: origin, "up" along the spine, "back" out of the back, and "right". */
    public record Frame(Vec3d origin, Vec3d up, Vec3d back, Vec3d right, boolean leaning) {}

    /**
     * The body frame like vanilla renders it. Standing it is just up/back/right. While gliding with an elytra or
     * swimming/crawling the whole body is tilted forward around the feet, so things attached to the head or back
     * must follow that tilt or they float away from the player.
     */
    public static Frame frame(AbstractClientPlayerEntity p, float tickDelta) {
        Vec3d pos = p.getLerpedPos(tickDelta);
        double yaw = Math.toRadians(MathHelper.lerpAngleDegrees(tickDelta, p.lastBodyYaw, p.bodyYaw));
        Vec3d back = new Vec3d(Math.sin(yaw), 0, -Math.cos(yaw));
        Vec3d right = new Vec3d(-Math.cos(yaw), 0, -Math.sin(yaw));
        Vec3d up = new Vec3d(0, 1, 0);
        double pitch = p.getPitch(tickDelta);

        double thetaDeg = 0, swimK = 0;
        if (p.isGliding()) {
            double j = p.getGlidingTicks() + tickDelta;
            double k = MathHelper.clamp(j * j / 100.0, 0, 1);
            thetaDeg = k * (-90 - pitch);
        } else if (p.isInSwimmingPose()) {
            swimK = p.getLeaningPitch(tickDelta);
            thetaDeg = swimK * (p.isTouchingWater() ? -90 - pitch : -90);
        }
        if (Math.abs(thetaDeg) < 0.01) return new Frame(pos, up, back, right, false);

        double th = Math.toRadians(thetaDeg);
        double c = Math.cos(th), sn = Math.sin(th);
        Vec3d upR = up.multiply(c).add(back.multiply(sn));
        Vec3d backR = up.multiply(-sn).add(back.multiply(c));
        Vec3d origin = pos;
        if (swimK > 0) origin = pos.add(upR.multiply(-1).add(backR.multiply(0.3)).multiply(swimK));
        return new Frame(origin, upR, backR, right, true);
    }

    private CosmeticUtil() {}
}
