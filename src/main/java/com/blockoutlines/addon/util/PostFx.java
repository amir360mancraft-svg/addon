package com.blockoutlines.addon.util;

import com.blockoutlines.addon.mixin.GameRendererInvoker;
import com.blockoutlines.addon.modules.ColorGrading;
import com.blockoutlines.addon.modules.Saturation;
import com.blockoutlines.addon.modules.WeatherPlus;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.Identifier;

/**
 * One shared full-screen shader (bloom + screen shock wave) for several modules. The game can run only one post
 * effect at a time, so modules only say what they want here and {@link #apply()} picks the matching effect.
 * It steps aside while Color Grading, Saturation or the Weather Plus wet shader is using the screen.
 */
public final class PostFx {
    /** 0 = off, 1 = soft, 2 = strong. Set by Loot Beams. */
    public static int bloom;
    /** 0 = off, 1..9 = frame of the shock wave. Set by Kill Effect. */
    public static int shockFrame;
    /** 0 = far / light, 1 = close / strong. */
    public static int shockStrong;

    /** 0 = off, 1..60 = animation frame of the hand shader. Set by Shader Hand (it wins over bloom / shock). */
    public static int handFrame;
    /** 0 = gradient, 1 = hologram. */
    public static int handMode;

    private static boolean mine;
    private static String last = "";
    private static int refresh;

    private PostFx() {}

    public static void apply() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.gameRenderer == null) return;
        Modules m = Modules.get();
        if (m == null) return;
        WeatherPlus wp = m.get(WeatherPlus.class);
        boolean blocked = m.isActive(ColorGrading.class) || m.isActive(Saturation.class) || (wp != null && wp.wetShaderRunning());
        boolean want = bloom > 0 || shockFrame > 0 || handFrame > 0;
        if (blocked || !want) {
            if (mine) {
                ((GameRendererInvoker) mc.gameRenderer).blockoutlines$clearPostProcessor();
                mine = false;
                last = "";
            }
            return;
        }
        int f = Math.max(0, Math.min(9, shockFrame));
        String id = handFrame > 0
            ? String.format("hand_%d_%02d", Math.max(0, Math.min(6, handMode)), Math.max(0, Math.min(59, handFrame - 1)))
            : String.format("fx_%d_%d_%d", Math.max(0, Math.min(2, bloom)), f, f == 0 ? 0 : Math.max(0, Math.min(1, shockStrong)));
        // set again every tick: vanilla drops the post effect when the camera entity changes
        // only when the effect changes (or now and then, in case vanilla dropped it)
        if (!id.equals(last) || !mine || ++refresh >= 20) {
            ((GameRendererInvoker) mc.gameRenderer).blockoutlines$setPostProcessor(Identifier.of("blockoutlines", id));
            refresh = 0;
        }
        mine = true;
        last = id;
    }
}
