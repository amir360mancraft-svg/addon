package com.blockoutlines.addon.gui;

import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.GuiThemes;
import meteordevelopment.meteorclient.gui.tabs.Tab;
import meteordevelopment.meteorclient.gui.tabs.WindowTabScreen;
import meteordevelopment.meteorclient.gui.widgets.containers.WHorizontalList;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/** Quick accent colors on top, then every setting of the theme (colors, opacity, corner radius, animation speed...). */
public class LunarSettingsScreen extends WindowTabScreen {
    public LunarSettingsScreen(GuiTheme theme, Tab tab) {
        super(theme, tab);
        theme.settings.onActivated();
    }

    @Override
    public void initWidgets() {
        if (!(theme instanceof LunarGuiTheme lunar)) {
            add(theme.label("These settings belong to the Lunar theme."));
            WButton use = add(theme.button("Use the Lunar theme")).widget();
            use.action = () -> {
                GuiThemes.select("Lunar");
                mc.setScreen(null);
                tab.openScreen(GuiThemes.get());
            };
            return;
        }

        add(theme.label("Accent color"));
        WHorizontalList presets = add(theme.horizontalList()).expandX().widget();
        for (Object[] p : LunarGuiTheme.PRESETS) {
            WButton b = presets.add(theme.button((String) p[0])).expandX().widget();
            int r = (Integer) p[1], g = (Integer) p[2], bl = (Integer) p[3];
            b.action = () -> {
                lunar.applyAccent(r, g, bl);
                reload();
            };
        }

        WButton reset = add(theme.button("Reset all Lunar colors")).expandX().widget();
        reset.action = () -> {
            lunar.resetLook();
            reload();
        };

        add(theme.settings(theme.settings)).expandX();
    }
}
