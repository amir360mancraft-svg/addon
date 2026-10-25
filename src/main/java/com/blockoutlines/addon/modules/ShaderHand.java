package com.blockoutlines.addon.modules;

import com.blockoutlines.addon.BlockOutlinesAddon;
import com.blockoutlines.addon.util.PostFx;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;

/**
 * Gives your first-person hand and held item a shader look: a moving rainbow gradient or a blue hologram with
 * scan lines and a glowing edge. It is a full-screen shader that only touches what is very close to the camera.
 */
public class ShaderHand extends Module {
    public enum Look { Gradient, Hologram }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Look> look = sgGeneral.add(new EnumSetting.Builder<Look>()
        .name("look").description("Gradient = moving rainbow colors. Hologram = blue see-through look with scan lines and a glowing edge.")
        .defaultValue(Look.Gradient).build());
    private final Setting<Double> speed = sgGeneral.add(new DoubleSetting.Builder()
        .name("speed").description("How fast the colors move.").defaultValue(1.0).range(0.2, 4.0).sliderRange(0.3, 3.0).build());

    public ShaderHand() {
        super(BlockOutlinesAddon.EFFECTS, "shader-hand", "A shader look for your first-person hand and held item (not used while Color Grading, Saturation or the wet shader is on).");
    }

    @Override
    public void onDeactivate() {
        PostFx.handFrame = 0;
        PostFx.apply();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null || mc.player == null) {
            PostFx.handFrame = 0;
            PostFx.apply();
            return;
        }
        boolean first = mc.options.getPerspective().isFirstPerson();
        if (!first) {
            PostFx.handFrame = 0;
        } else {
            long period = (long) (4000.0 / speed.get());
            PostFx.handFrame = 1 + (int) ((System.currentTimeMillis() % period) * 24L / period);
            PostFx.handMode = look.get().ordinal();
        }
        PostFx.apply();
    }
}
