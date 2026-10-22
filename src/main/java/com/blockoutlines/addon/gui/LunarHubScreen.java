package com.blockoutlines.addon.gui;

import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.tabs.TabScreen;
import meteordevelopment.meteorclient.gui.tabs.Tabs;
import meteordevelopment.meteorclient.systems.hud.screens.HudEditorScreen;
import meteordevelopment.meteorclient.utils.Utils;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.input.KeyInput;
import com.blockoutlines.addon.util.HudHook;
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
        // elements only show in the game while the HUD is switched on
        Hud hud = Hud.get();
        if (!hud.active && hud.iterator().hasNext()) hud.active = true;
    }

    /** An element you switched off is deleted: nothing stays behind as a red box. */
    private void removeSwitchedOff() {
        List<HudElement> gone = new ArrayList<>();
        for (HudElement e : Hud.get()) if (!e.isActive()) gone.add(e);
        for (HudElement e : gone) e.remove();
    }

    /** A frame and a name around every HUD panel of the addon (Target HUD, Spotify ...), so you see what you can drag. */
    private void drawMarks(DrawContext context) {
        int[] ac = LunarGuiTheme.INSTANCE != null ? LunarGuiTheme.INSTANCE.accent() : new int[]{64, 140, 255};
        int line = (220 << 24) | (ac[0] << 16) | (ac[1] << 8) | ac[2];
        var tr = net.minecraft.client.MinecraftClient.getInstance().textRenderer;
        for (HudHook.Mark m : HudHook.recent()) {
            context.fill(m.x() - 1, m.y() - 1, m.x() + m.w() + 1, m.y(), line);
            context.fill(m.x() - 1, m.y() + m.h(), m.x() + m.w() + 1, m.y() + m.h() + 1, line);
            context.fill(m.x() - 1, m.y(), m.x(), m.y() + m.h(), line);
            context.fill(m.x() + m.w(), m.y(), m.x() + m.w() + 1, m.y() + m.h(), line);
            int tw = tr.getWidth(m.name());
            int ly = m.y() >= 12 ? m.y() - 12 : m.y() + m.h() + 2;
            context.fill(m.x() - 1, ly, m.x() + tw + 7, ly + 11, line);
            context.drawText(tr, m.name(), m.x() + 3, ly + 2, 0xFFFFFFFF, false);
        }
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
        drawMarks(context);
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
