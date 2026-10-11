package com.blockoutlines.addon.modules;

import com.blockoutlines.addon.BlockOutlinesAddon;
import com.blockoutlines.addon.util.CosmeticUtil;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.render.Ambience;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Animated sky. Drives Meteor's own Ambience module: sky (and optionally cloud) colors slowly flow through
 * three colors of your choice. Your original Ambience settings are restored when you turn this off.
 */
public class AnimatedSky extends Module {
    public enum Motion { Cycle, Pulse, Static }

    private final SettingGroup sg = settings.getDefaultGroup();

    private final Setting<Motion> motion = sg.add(new EnumSetting.Builder<Motion>()
        .name("motion").description("Cycle: flow through all three colors. Pulse: breathe between primary and secondary. Static: primary only.")
        .defaultValue(Motion.Cycle).build());

    private final Setting<SettingColor> primary = sg.add(new ColorSetting.Builder()
        .name("primary").description("Main sky color.")
        .defaultValue(new SettingColor(120, 60, 255, 255)).build());

    private final Setting<SettingColor> secondary = sg.add(new ColorSetting.Builder()
        .name("secondary").description("Deeper color the sky fades toward.")
        .defaultValue(new SettingColor(10, 20, 90, 255)).build());

    private final Setting<SettingColor> accent = sg.add(new ColorSetting.Builder()
        .name("accent").description("Highlight color.")
        .defaultValue(new SettingColor(255, 90, 190, 255)).build());

    private final Setting<Double> speed = sg.add(new DoubleSetting.Builder()
        .name("speed").description("Animation speed.")
        .defaultValue(1.0).min(0.1).max(5.0).sliderMin(0.2).sliderMax(3.0).build());

    private final Setting<Boolean> clouds = sg.add(new BoolSetting.Builder()
        .name("tint-clouds").description("Also tints the clouds.").defaultValue(true).build());

    private Ambience ambience;
    private boolean weTurnedItOn;
    private final List<Setting<SettingColor>> skyColors = new ArrayList<>();
    private final List<Setting<SettingColor>> cloudColors = new ArrayList<>();
    private final Map<Setting<?>, Object> saved = new HashMap<>();

    public AnimatedSky() {
        super(BlockOutlinesAddon.COSMETICS, "animated-sky", "Flowing sky colors (drives Meteor's Ambience, visual only).");
    }

    @Override
    @SuppressWarnings("unchecked")
    public void onActivate() {
        skyColors.clear();
        cloudColors.clear();
        saved.clear();
        weTurnedItOn = false;

        ambience = Modules.get().get(Ambience.class);
        if (ambience == null) {
            error("Meteor's Ambience module was not found.");
            toggle();
            return;
        }
        if (!ambience.isActive()) {
            ambience.toggle();
            weTurnedItOn = true;
        }

        // Settings are found by name so a renamed field in Meteor cannot break the build.
        for (SettingGroup group : ambience.settings) {
            for (Setting<?> s : group) {
                String n = s.name;
                Object v = s.get();
                boolean sky = n.contains("sky"), cloud = n.contains("cloud");
                if (v instanceof Boolean b && n.contains("custom") && (sky || cloud)) {
                    saved.put(s, b);
                    ((Setting<Boolean>) s).set(true);
                } else if (v instanceof SettingColor c && (sky || cloud)) {
                    saved.put(s, new int[]{c.r, c.g, c.b, c.a});
                    if (sky) skyColors.add((Setting<SettingColor>) s);
                    else cloudColors.add((Setting<SettingColor>) s);
                }
            }
        }
        if (skyColors.isEmpty()) info("No sky color setting was found in Ambience, so nothing will change.");
    }

    @Override
    @SuppressWarnings("unchecked")
    public void onDeactivate() {
        for (Map.Entry<Setting<?>, Object> e : saved.entrySet()) {
            Object v = e.getValue();
            if (v instanceof Boolean b) {
                ((Setting<Boolean>) e.getKey()).set(b);
            } else if (v instanceof int[] c && e.getKey().get() instanceof SettingColor sc) {
                sc.set(c[0], c[1], c[2], c[3]);
            }
        }
        if (ambience != null && weTurnedItOn && ambience.isActive()) ambience.toggle();
        saved.clear();
        skyColors.clear();
        cloudColors.clear();
        ambience = null;
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (skyColors.isEmpty() && cloudColors.isEmpty()) return;
        double t = System.currentTimeMillis() / 1000.0 * speed.get() * 0.12;
        Color sky = pick(t);
        for (Setting<SettingColor> s : skyColors) s.get().set(sky.r, sky.g, sky.b, 255);
        if (clouds.get()) {
            Color cloud = CosmeticUtil.lerp(sky, new Color(255, 255, 255, 255), 0.45, 255);
            for (Setting<SettingColor> s : cloudColors) s.get().set(cloud.r, cloud.g, cloud.b, 255);
        }
    }

    private Color pick(double t) {
        SettingColor a = primary.get(), b = secondary.get(), c = accent.get();
        switch (motion.get()) {
            case Static:
                return new Color(a.r, a.g, a.b, 255);
            case Pulse: {
                double w = 0.5 + 0.5 * Math.sin(t * Math.PI * 2);
                return CosmeticUtil.lerp(new Color(a), new Color(b), w, 255);
            }
            default: {
                Color[] stops = {new Color(a), new Color(b), new Color(c)};
                double u = (t % 1.0 + 1.0) % 1.0 * 3.0;
                int i = (int) u;
                double f = u - i;
                f = f * f * (3 - 2 * f);
                return CosmeticUtil.lerp(stops[i % 3], stops[(i + 1) % 3], f, 255);
            }
        }
    }
}
