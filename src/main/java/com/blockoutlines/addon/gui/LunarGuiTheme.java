package com.blockoutlines.addon.gui;

import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.WidgetScreen;
import meteordevelopment.meteorclient.gui.renderer.packer.GuiTexture;
import meteordevelopment.meteorclient.gui.utils.CharFilter;
import meteordevelopment.meteorclient.gui.widgets.containers.WSection;
import meteordevelopment.meteorclient.gui.widgets.input.WDropdown;
import meteordevelopment.meteorclient.gui.widgets.input.WTextBox;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.gui.tabs.TabScreen;
import meteordevelopment.meteorclient.gui.themes.meteor.MeteorGuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WWindow;
import meteordevelopment.meteorclient.gui.widgets.input.WSlider;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.gui.widgets.pressable.WCheckbox;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import net.minecraft.client.gui.screen.Screen;

/**
 * A second GUI theme, "Lunar". Everything inside the windows is Meteor's own (so every setting widget keeps working),
 * restyled with rounded dark glass, and the Modules tab is replaced by a card browser in the style of Lunar Client.
 * Choose it in the GUI tab, "Theme". The colors, opacity and sizes are in the "Lunar" tab (or the GUI tab).
 */
public class LunarGuiTheme extends MeteorGuiTheme {
    /** Ready-made accent colors for the buttons of the Lunar tab: name, r, g, b. */
    public static final Object[][] PRESETS = {
        {"Blue", 64, 140, 255}, {"Cyan", 50, 200, 230}, {"Green", 60, 210, 130}, {"Purple", 150, 100, 255},
        {"Pink", 255, 100, 170}, {"Red", 255, 80, 90}, {"Orange", 255, 150, 60}, {"White", 230, 235, 245},
    };

    private final SettingGroup sgLunar = settings.createGroup("Lunar");

    public final Setting<Double> animationSpeed = sgLunar.add(new DoubleSetting.Builder()
        .name("animation-speed").description("How fast the Lunar animations play. Lower = slower and smoother.")
        .defaultValue(0.8).range(0.2, 3.0).sliderRange(0.2, 2.0).build());

    public final Setting<SettingColor> panelColor = sgLunar.add(new ColorSetting.Builder()
        .name("panel-color").description("Color of the panels and windows. The alpha is how see-through they are.")
        .defaultValue(new SettingColor(14, 17, 24, 228)).build());

    public final Setting<SettingColor> cardColor = sgLunar.add(new ColorSetting.Builder()
        .name("card-color").description("Color of the module cards. The alpha is how see-through they are.")
        .defaultValue(new SettingColor(22, 26, 36, 215)).build());

    public final Setting<SettingColor> enabledColor = sgLunar.add(new ColorSetting.Builder()
        .name("enabled-color").description("Color of the ENABLED strip.")
        .defaultValue(new SettingColor(38, 168, 100, 255)).build());

    public final Setting<SettingColor> disabledColor = sgLunar.add(new ColorSetting.Builder()
        .name("disabled-color").description("Color of the DISABLED strip.")
        .defaultValue(new SettingColor(176, 38, 64, 255)).build());

    public final Setting<Double> cornerRadius = sgLunar.add(new DoubleSetting.Builder()
        .name("corner-radius").description("How round the corners of cards, buttons and windows are.")
        .defaultValue(10.0).range(0.0, 24.0).sliderRange(0.0, 20.0).build());

    public final Setting<Double> panelSize = sgLunar.add(new DoubleSetting.Builder()
        .name("panel-size").description("Size of the module panel.")
        .defaultValue(1.0).range(0.6, 1.3).sliderRange(0.6, 1.3).build());

    public final Setting<Boolean> showIcons = sgLunar.add(new BoolSetting.Builder()
        .name("module-icons").description("Draw a small icon on the cards of this addon's modules.")
        .defaultValue(true).build());

    /** Which of the ready-made looks (Midnight, OLED, Graphite, Glass) was picked in the Lunar settings. Not shown in the list. */
    public final Setting<Integer> themePreset = sgLunar.add(new IntSetting.Builder()
        .name("theme-preset").description("The ready-made look that is selected.")
        .defaultValue(0).range(0, 3).visible(() -> false).build());

    public LunarGuiTheme() {
        super();
        rename("Lunar");
        settingsFactory = new LunarSettingsFactory(this);
        applyAccent(64, 140, 255);
        moduleBackground.set(new SettingColor(40, 52, 76));
        favoriteColor.set(new SettingColor(255, 205, 60));
    }

    /** Sets the accent color everywhere the theme uses one. */
    public void applyAccent(int r, int g, int b) {
        accentColor.set(new SettingColor(r, g, b));
        checkboxColor.set(new SettingColor(r, g, b));
        sliderLeft.set(new SettingColor(r, g, b));
    }

    /** Puts every setting of the theme back to the Lunar defaults. */
    public void resetLook() {
        settings.reset();
        applyAccent(64, 140, 255);
        moduleBackground.set(new SettingColor(40, 52, 76));
        favoriteColor.set(new SettingColor(255, 205, 60));
    }

    public int[] accent() {
        SettingColor c = accentColor.get();
        return new int[]{c.r, c.g, c.b};
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

    @Override
    public WTextBox textBox(String text, String placeholder, CharFilter filter, Class<? extends WTextBox.Renderer> renderer) {
        return w(new LunarWidgets.TextBox(text, placeholder, filter, renderer));
    }

    @Override
    public <T> WDropdown<T> dropdown(T[] values, T value) {
        return w(new LunarWidgets.Dropdown<>(values, value));
    }

    @Override
    public WSection section(String title, boolean expanded, WWidget headerWidget) {
        return w(new LunarSection(title, expanded, headerWidget));
    }

    @Override
    public WidgetScreen moduleScreen(Module module) {
        return new LunarModuleScreen(this, module);
    }
}
