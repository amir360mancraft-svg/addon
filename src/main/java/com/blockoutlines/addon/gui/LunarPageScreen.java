package com.blockoutlines.addon.gui;

import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.tabs.Tab;
import meteordevelopment.meteorclient.gui.tabs.TabScreen;

/** The "Lunar" tab: the settings page of the theme (see {@link WLunarSettingsPage}). */
public class LunarPageScreen extends TabScreen {
    private WLunarSettingsPage page;

    public LunarPageScreen(GuiTheme theme, Tab tab) {
        super(theme, tab);
    }

    @Override
    public void initWidgets() {
        page = add(new WLunarSettingsPage(this)).center().padTop(theme.scale(26)).widget();
    }

    /** Escape and the X button: let the page fade out first. */
    @Override
    public void close() {
        if (page != null && page.beginClose()) return;
        super.close();
    }

    void finishClose() {
        super.close();
    }

    @Override
    public void reload() {
    }
}
