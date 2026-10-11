package com.blockoutlines.addon.util;

import meteordevelopment.meteorclient.utils.render.color.Color;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;

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

    public static Color hsv(float h, float s, float v, int alpha) {
        h = h - (float) Math.floor(h);
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

    private CosmeticUtil() {}
}
