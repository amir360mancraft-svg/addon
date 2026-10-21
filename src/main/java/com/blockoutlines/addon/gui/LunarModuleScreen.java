package com.blockoutlines.addon.gui;

import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.meteor.ActiveModulesChangedEvent;
import meteordevelopment.meteorclient.events.meteor.ModuleBindChangedEvent;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.WidgetScreen;
import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.gui.utils.Cell;
import meteordevelopment.meteorclient.gui.widgets.WKeybind;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WContainer;
import meteordevelopment.meteorclient.gui.widgets.containers.WHorizontalList;
import meteordevelopment.meteorclient.gui.widgets.containers.WSection;
import meteordevelopment.meteorclient.gui.widgets.containers.WWindow;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.gui.widgets.pressable.WCheckbox;
import meteordevelopment.meteorclient.gui.widgets.pressable.WFavorite;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.misc.NbtUtils;
import meteordevelopment.meteorclient.utils.render.prompts.OkPrompt;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.MathHelper;

import java.util.Optional;

import static meteordevelopment.meteorclient.utils.Utils.getWindowWidth;

/**
 * The settings of one module, Dawn style: a clean window with the description and a "Reset Settings" button on top,
 * collapsible groups with two columns of settings, and the bind and on/off switch at the bottom. The window fades
 * and slides in when it opens and out when it closes.
 */
public class LunarModuleScreen extends WidgetScreen {
    private final Module module;
    private final WWindow window;
    private final Cell<WWindow> windowCell;

    private WContainer settingsContainer;
    private WKeybind keybind;
    private WCheckbox active;

    private double open;
    private boolean closeRequested, closeDone;

    public LunarModuleScreen(GuiTheme theme, Module module) {
        super(theme, module.title);
        this.module = module;

        WFavorite fav = theme.favorite(module.favorite);
        fav.action = () -> module.favorite = fav.checked;

        windowCell = super.add(theme.window(fav, module.title)).center();
        window = windowCell.widget();
        window.view.scrollOnlyWhenMouseOver = false;
    }

    @Override
    public <W extends WWidget> Cell<W> add(W widget) {
        return window.add(widget);
    }

    @Override
    public void clear() {
        window.clear();
    }

    @Override
    public void initWidgets() {
        // description + reset
        WHorizontalList top = add(theme.horizontalList()).expandX().widget();
        top.add(theme.label(module.description, getWindowWidth() / 2.5)).expandCellX().centerY();
        WButton resetAll = top.add(theme.button("Reset Settings")).right().centerY().widget();
        resetAll.action = () -> {
            module.settings.reset();
            reload();
        };

        if (module.addon != null && module.addon != MeteorClient.ADDON) {
            WHorizontalList addon = add(theme.horizontalList()).expandX().widget();
            addon.add(theme.label("From: ").color(theme.textSecondaryColor())).widget();
            addon.add(theme.label(module.addon.name).color(module.addon.color)).widget();
        }

        // the settings of the module
        if (!module.settings.groups.isEmpty()) {
            settingsContainer = add(theme.verticalList()).expandX().widget();
            settingsContainer.add(theme.settings(module.settings)).expandX();
        }

        // the module's own widget (test buttons and so on)
        WWidget custom = module.getWidget(theme);
        if (custom != null) {
            Cell<WWidget> cell = add(custom);
            if (custom instanceof WContainer) cell.expandX();
        }

        // bind and switches
        WSection section = add(theme.section("Module", true)).expandX().padTop(8).widget();
        LunarLayout.Grid grid = new LunarLayout.Grid();
        grid.theme = theme;

        active = theme.checkbox(module.isActive());
        active.action = () -> {
            if (module.isActive() != active.checked) module.toggle();
        };
        grid.add(LunarSettingsFactory.row(theme, "Enabled", "Turns the module on or off.", wrap(active))).expandWidgetX();

        WHorizontalList bind = theme.horizontalList();
        keybind = bind.add(theme.keybind(module.keybind)).expandX().widget();
        keybind.actionOnSet = () -> Modules.get().setModuleToBind(module);
        WButton reset = bind.add(theme.button(meteordevelopment.meteorclient.gui.renderer.GuiRenderer.RESET)).widget();
        reset.action = keybind::resetBind;
        reset.tooltip = "Reset";
        grid.add(LunarSettingsFactory.row(theme, "Keybind", "The key that toggles this module.", bind)).expandWidgetX();

        WCheckbox tobr = theme.checkbox(module.toggleOnBindRelease);
        tobr.action = () -> module.toggleOnBindRelease = tobr.checked;
        grid.add(LunarSettingsFactory.row(theme, "Toggle on bind release", null, wrap(tobr))).expandWidgetX();

        WCheckbox cf = theme.checkbox(module.chatFeedback);
        cf.action = () -> module.chatFeedback = cf.checked;
        grid.add(LunarSettingsFactory.row(theme, "Chat feedback", null, wrap(cf))).expandWidgetX();

        section.add(grid).expandX().padVertical(8);

        // copy / paste of the module's config
        WHorizontalList sharing = add(theme.horizontalList()).right().padTop(4).widget();
        WButton copy = sharing.add(theme.button(GuiRenderer.COPY)).widget();
        copy.action = () -> {
            if (toClipboard()) {
                OkPrompt.create()
                    .title("Module copied!")
                    .message("The settings for this module are now in your clipboard.")
                    .message("You can also copy settings using Ctrl+C.")
                    .message("Settings can be imported using Ctrl+V or the paste button.")
                    .id("config-sharing-guide")
                    .show();
            }
        };
        copy.tooltip = "Copy config";
        WButton paste = sharing.add(theme.button(GuiRenderer.PASTE)).widget();
        paste.action = this::fromClipboard;
        paste.tooltip = "Paste config";
    }

    /** A control that has to sit at the right edge of its row. */
    private WWidget wrap(WWidget widget) {
        LunarLayout.ControlTable table = new LunarLayout.ControlTable();
        table.theme = theme;
        table.add(widget).expandCellX();
        return table;
    }

    // ------------------------------------------------------------------ open / close animation

    private double speed() {
        return theme instanceof LunarGuiTheme t ? t.animationSpeed.get() : 1.0;
    }

    private static double ease(double t) {
        t = MathHelper.clamp(t, 0, 1);
        return t * t * (3 - 2 * t);
    }

    @Override
    public void renderCustom(DrawContext context, int mouseX, int mouseY, float delta) {
        double dt = Math.min(delta / 20.0, 0.1);
        if (closeRequested) {
            open = Math.max(0, open - dt * speed() / 0.22);
            if (open <= 0 && !closeDone) {
                closeDone = true;
                super.close();
            }
        } else {
            open = Math.min(1, open + dt * speed() / 0.4);
        }

        double e = ease(open);
        // the base class adds its own small step to this value right after, so take it away first
        animProgress = e - delta / 20.0 * 14;
        if (open > 0 && open < 1 || closeRequested) {
            windowCell.padTop((1 - e) * 60);
            invalidate();
        } else if (open >= 1) {
            windowCell.padTop(0);
        }

        super.renderCustom(context, mouseX, mouseY, delta);
    }

    /** Escape and the X button: fade out first, then close. */
    @Override
    public void close() {
        if (closeDone) {
            super.close();
            return;
        }
        closeRequested = true;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return !Modules.get().isBinding();
    }

    @Override
    public void tick() {
        super.tick();
        module.settings.tick(settingsContainer, theme);
    }

    @EventHandler
    private void onModuleBindChanged(ModuleBindChangedEvent event) {
        if (keybind != null) keybind.reset();
    }

    @EventHandler
    private void onActiveModulesChanged(ActiveModulesChangedEvent event) {
        if (active != null) active.checked = module.isActive();
    }

    @Override
    public boolean toClipboard() {
        NbtCompound tag = new NbtCompound();
        tag.putString("name", module.name);
        NbtCompound settingsTag = module.settings.toTag();
        if (!settingsTag.isEmpty()) tag.put("settings", settingsTag);
        return NbtUtils.toClipboard(tag);
    }

    @Override
    public boolean fromClipboard() {
        NbtCompound tag = NbtUtils.fromClipboard();
        if (tag == null) return false;
        if (!tag.getString("name", "").equals(module.name)) return false;

        Optional<NbtCompound> settings = tag.getCompound("settings");
        if (settings.isPresent()) module.settings.fromTag(settings.get());
        else module.settings.reset();

        if (parent instanceof WidgetScreen p) p.reload();
        reload();
        return true;
    }
}
