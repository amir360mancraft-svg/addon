package com.blockoutlines.addon.modules;

import com.blockoutlines.addon.BlockOutlinesAddon;
import com.blockoutlines.addon.util.PostFx;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
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
    public enum Look { Gradient, Hologram, Fire, Frost, Gold, Neon, Crystal }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Look> look = sgGeneral.add(new EnumSetting.Builder<Look>()
        .name("look").description("Gradient = moving rainbow. Hologram = blue scan lines. Fire = flickering flames. Frost = icy sparkles. Gold = shiny metal. Neon = dark hand with a glowing colored outline. Crystal = see-through blue glass with swirling glint lines.")
        .defaultValue(Look.Gradient).build());
    private final Setting<Double> speed = sgGeneral.add(new DoubleSetting.Builder()
        .name("speed").description("How fast the colors move.").defaultValue(1.0).range(0.2, 4.0).sliderRange(0.3, 3.0).build());

    private final Setting<Boolean> animate = sgGeneral.add(new BoolSetting.Builder()
        .name("animate").description("Turn off to freeze the effect on one frame.").defaultValue(true).build());
    private final Setting<Boolean> onlyWithItem = sgGeneral.add(new BoolSetting.Builder()
        .name("only-with-item").description("Only when you hold something (empty hand stays normal).").defaultValue(false).build());
    private final Setting<Integer> frozen = sgGeneral.add(new IntSetting.Builder()
        .name("frozen-frame").description("Which frame to show when animate is off (changes the colors/highlight position).")
        .defaultValue(4).range(1, 16).sliderRange(1, 16).visible(() -> !animate.get()).build());

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
        if (!first || (onlyWithItem.get() && mc.player.getMainHandStack().isEmpty())) {
            PostFx.handFrame = 0;
        } else {
            if (animate.get()) {
                long period = (long) (4000.0 / speed.get());
                PostFx.handFrame = 1 + (int) ((System.currentTimeMillis() % period) * 16L / period);
            } else PostFx.handFrame = frozen.get();
            PostFx.handMode = look.get().ordinal();
        }
        PostFx.apply();
    }
}
