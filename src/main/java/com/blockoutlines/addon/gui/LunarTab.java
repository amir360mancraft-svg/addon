package com.blockoutlines.addon.gui;

import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.tabs.Tab;
import meteordevelopment.meteorclient.gui.tabs.TabScreen;
import net.minecraft.client.gui.screen.Screen;

/** The "Lunar" tab in the top bar: colors, opacity, corner size and animation speed of the Lunar theme. */
public class LunarTab extends Tab {
    public static final LunarTab INSTANCE = new LunarTab();

    public LunarTab() {
        super("Lunar");
    }

    @Override
    public TabScreen createScreen(GuiTheme theme) {
        return new LunarSettingsScreen(theme, this);
    }

    @Override
    public boolean isScreen(Screen screen) {
        return screen instanceof LunarSettingsScreen;
    }
}
