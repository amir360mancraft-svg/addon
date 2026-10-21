package com.blockoutlines.addon.gui;

import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.tabs.TabScreen;
import meteordevelopment.meteorclient.gui.tabs.Tabs;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.misc.NbtUtils;

/** The Modules tab of the Lunar theme: one big panel with a category list, filters, search and a grid of module cards. */
public class LunarModulesScreen extends TabScreen {
    private WLunarBrowser browser;

    public LunarModulesScreen(GuiTheme theme) {
        super(theme, Tabs.get().getFirst());
    }

    @Override
    public void initWidgets() {
        browser = add(new WLunarBrowser(this)).center().padTop(theme.scale(26)).widget();
    }

    /** Escape: let the panel fade out first, then really close. */
    @Override
    public void close() {
        if (browser != null && browser.beginClose()) return;
        super.close();
    }

    void finishClose() {
        super.close();
    }

    @Override
    public boolean toClipboard() {
        return NbtUtils.toClipboard(Modules.get());
    }

    @Override
    public boolean fromClipboard() {
        return NbtUtils.fromClipboard(Modules.get());
    }

    @Override
    public void reload() {
    }
}
