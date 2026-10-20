package com.blockoutlines.addon.gui;

import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.renderer.packer.GuiTexture;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WWindow;
import meteordevelopment.meteorclient.gui.widgets.input.WSlider;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.gui.widgets.pressable.WCheckbox;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.gui.tabs.TabScreen;
import meteordevelopment.meteorclient.gui.themes.meteor.MeteorGuiTheme;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import net.minecraft.client.gui.screen.Screen;

/**
 * A second GUI theme, "Lunar". Everything inside the windows is Meteor's own (so every setting widget keeps working),
 * with a bluer color scheme, and the Modules tab is replaced by a card browser in the style of Lunar Client.
 * Choose it in the GUI tab, "Theme".
 */
public class LunarGuiTheme extends MeteorGuiTheme {
    public final Setting<Double> animationSpeed = settings.getDefaultGroup().add(new DoubleSetting.Builder()
        .name("animation-speed")
        .description("How fast the Lunar animations play. Lower = slower and smoother.")
        .defaultValue(0.8)
        .range(0.2, 3.0)
        .sliderRange(0.2, 2.0)
        .build());

    public LunarGuiTheme() {
        super();
        rename("Lunar");

        accentColor.set(new SettingColor(64, 140, 255));
        checkboxColor.set(new SettingColor(64, 140, 255));
        moduleBackground.set(new SettingColor(40, 52, 76));
        sliderLeft.set(new SettingColor(48, 110, 210));
        favoriteColor.set(new SettingColor(255, 205, 60));
    }

    /** The theme name is a final field of the base class and Meteor's own theme already uses "Meteor". */
    private void rename(String newName) {
        try {
            var f = GuiTheme.class.getDeclaredField("name");
            f.setAccessible(true);
            f.set(this, newName);
        } catch (Throwable t) {
            com.blockoutlines.addon.BlockOutlinesAddon.LOG.error("Could not name the Lunar theme", t);
        }
    }

    @Override
    public WWindow window(WWidget icon, String title) {
        return w(new LunarWidgets.Window(icon, title));
    }

    @Override
    protected WButton button(String text, GuiTexture texture) {
        return w(new LunarWidgets.Button(text, texture));
    }

    @Override
    public WCheckbox checkbox(boolean checked) {
        return w(new LunarWidgets.Checkbox(checked));
    }

    @Override
    public WSlider slider(double value, double min, double max) {
        return w(new LunarWidgets.Slider(value, min, max));
    }

    @Override
    public TabScreen modulesScreen() {
        return new LunarModulesScreen(this);
    }

    @Override
    public boolean isModulesScreen(Screen screen) {
        return screen instanceof LunarModulesScreen;
    }
}
