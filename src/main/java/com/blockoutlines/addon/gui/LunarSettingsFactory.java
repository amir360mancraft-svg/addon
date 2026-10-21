package com.blockoutlines.addon.gui;

import meteordevelopment.meteorclient.gui.DefaultSettingsWidgetFactory;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WLabel;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WSection;
import meteordevelopment.meteorclient.gui.widgets.containers.WVerticalList;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.Settings;

import java.util.Locale;

/**
 * Builds the settings of a module the Dawn way: every group is a collapsible block with a title, and inside it the
 * settings sit in two columns, name on the left and the control on the right. The controls themselves are made by
 * Meteor's factory, so every kind of setting still works.
 */
public class LunarSettingsFactory extends DefaultSettingsWidgetFactory {
    public LunarSettingsFactory(GuiTheme theme) {
        super(theme);
    }

    @Override
    public WWidget create(GuiTheme theme, Settings settings, String filter) {
        WVerticalList list = theme.verticalList();
        String f = filter == null ? "" : filter.toLowerCase(Locale.ROOT);

        for (SettingGroup group : settings.groups) {
            LunarLayout.Grid grid = new LunarLayout.Grid();
            grid.theme = theme;

            for (Setting<?> setting : group) {
                if (!setting.title.toLowerCase(Locale.ROOT).contains(f)) continue;
                boolean visible = setting.isVisible();
                setting.lastWasVisible = visible;
                if (!visible) continue;
                grid.add(row(theme, setting.title, setting.description, control(theme, setting))).expandWidgetX();
            }

            if (grid.cells.isEmpty()) continue;

            WSection section = list.add(theme.section(group.name, group.sectionExpanded)).expandX().padBottom(8).widget();
            section.action = () -> group.sectionExpanded = section.isExpanded();
            section.add(grid).expandX().padVertical(8);
        }

        // keep the width steady when sections open and close
        list.calculateSize();
        list.minWidth = list.width;
        return list;
    }

    private WWidget control(GuiTheme theme, Setting<?> setting) {
        LunarLayout.ControlTable table = new LunarLayout.ControlTable();
        table.theme = theme;
        Factory factory = getFactory(setting.getClass());
        if (factory != null) factory.create(table, setting);
        return table;
    }

    /** A "name ... control" row, also used by the Bind part of the module screen. */
    static LunarLayout.Row row(GuiTheme theme, String title, String tooltip, WWidget control) {
        LunarLayout.Row row = new LunarLayout.Row();
        row.theme = theme;
        WLabel label = theme.label(title);
        label.tooltip = tooltip;
        row.add(label);
        row.add(control).expandWidgetX();
        return row;
    }
}
