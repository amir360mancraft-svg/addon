package com.blockoutlines.addon.gui;

import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.tabs.TabScreen;
import meteordevelopment.meteorclient.gui.tabs.Tabs;
import meteordevelopment.meteorclient.systems.hud.screens.HudEditorScreen;
import meteordevelopment.meteorclient.utils.Utils;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.input.KeyInput;
import meteordevelopment.meteorclient.systems.hud.Hud;
import meteordevelopment.meteorclient.systems.hud.HudElement;

import java.util.ArrayList;
import java.util.List;

/**
 * What Right Shift opens: your HUD with its elements (drag them to move them, right click for their settings, right click
 * on empty space to add one) and, in the middle, the buttons that lead to the module list and the other tabs.
 * The HUD editing is Meteor's own editor, drawn and fed with the mouse from here.
 */
public class LunarHubScreen extends TabScreen {
    private final HudEditorScreen editor;
    private int lastMouseX, lastMouseY;

    public LunarHubScreen(GuiTheme theme) {
        super(theme, Tabs.get().getFirst());
        editor = new HudEditorScreen(theme);
    }

    @Override
    public void initWidgets() {
        add(new WLunarHub(this)).center();
    }

    @Override
    protected void init() {
        super.init();
        editor.init(width, height);
        removeSwitchedOff();
    }

    /** An element you switched off is deleted: nothing stays behind as a red box. */
    private void removeSwitchedOff() {
        List<HudElement> gone = new ArrayList<>();
        for (HudElement e : Hud.get()) if (!e.isActive()) gone.add(e);
        for (HudElement e : gone) e.remove();
    }

    @Override
    public void tick() {
        super.tick();
        removeSwitchedOff();
    }

    @Override
    public void resize(int w, int h) {
        super.resize(w, h);
        editor.resize(w, h);
    }

    @Override
    public void removed() {
        super.removed();
        editor.removed();
    }

    @Override
    public void renderCustom(DrawContext context, int mouseX, int mouseY, float delta) {
        lastMouseX = mouseX;
        lastMouseY = mouseY;
        super.renderCustom(context, mouseX, mouseY, delta);
    }

    @Override
    protected void onRenderBefore(DrawContext context, float delta) {
        editor.render(context, lastMouseX, lastMouseY, delta);
        // the editor leaves the normal projection on, the widgets of this screen need the pixel one
        Utils.unscaledProjection();
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (super.mouseClicked(click, doubled)) return true;
        return editor.mouseClicked(click, doubled);
    }

    @Override
    public void mouseMoved(double mouseX, double mouseY) {
        super.mouseMoved(mouseX, mouseY);
        editor.mouseMoved(mouseX, mouseY);
    }

    @Override
    public boolean mouseReleased(Click click) {
        boolean used = super.mouseReleased(click);
        editor.mouseReleased(click);
        return used;
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        if (super.keyPressed(input)) return true;
        return editor.keyPressed(input);
    }

    @Override
    public void reload() {
    }
}
