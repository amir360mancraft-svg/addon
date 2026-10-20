package com.blockoutlines.addon.gui;

import meteordevelopment.meteorclient.gui.GuiTheme;
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
    public TabScreen modulesScreen() {
        return new LunarModulesScreen(this);
    }

    @Override
    public boolean isModulesScreen(Screen screen) {
        return screen instanceof LunarModulesScreen;
    }
}
