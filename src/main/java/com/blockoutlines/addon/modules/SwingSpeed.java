package com.blockoutlines.addon.modules;

import com.blockoutlines.addon.BlockOutlinesAddon;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;

/**
 * Changes how long your hand swing animation takes (visual only: attack cooldown and damage are not touched).
 * The work is done by SwingDurationMixin, which asks {@link #duration(int)} for the length of your own swing.
 */
public class SwingSpeed extends Module {
    private final SettingGroup sg = settings.getDefaultGroup();

    private final Setting<Integer> ticks = sg.add(new IntSetting.Builder()
        .name("swing-duration").description("Length of one swing in ticks (20 ticks = 1 second). Vanilla is 6. Lower = faster, higher = slower.")
        .defaultValue(12).range(1, 40).sliderRange(1, 30).build());
    private final Setting<Boolean> onlyFirstPerson = sg.add(new BoolSetting.Builder()
        .name("only-first-person").description("Only change the swing in first person view (others still see the normal speed anyway).")
        .defaultValue(false).build());

    public SwingSpeed() {
        super(BlockOutlinesAddon.CATEGORY, "swing-speed",
            "Makes your hand swing faster or slower (visual only).");
    }

    /** Called from the mixin with the vanilla swing length; returns the length to use. */
    public int duration(int original) {
        if (onlyFirstPerson.get() && mc.options != null && !mc.options.getPerspective().isFirstPerson()) return original;
        return ticks.get();
    }
}
