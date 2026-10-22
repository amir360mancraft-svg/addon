package com.blockoutlines.addon.gui;

import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.game.OpenScreenEvent;
import meteordevelopment.meteorclient.gui.GuiThemes;
import meteordevelopment.meteorclient.systems.hud.screens.AddHudElementScreen;
import meteordevelopment.orbit.EventHandler;

import java.lang.reflect.Field;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/** Small hooks that swap Meteor's plain screens for the Lunar ones while the Lunar theme is selected. */
public final class LunarHooks {
    private static boolean registered;

    private LunarHooks() {
    }

    static void register() {
        if (registered) return;
        registered = true;
        MeteorClient.EVENT_BUS.subscribe(new LunarHooks());
    }

    private static int intField(Object o, String name) {
        try {
            Field f = o.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.getInt(o);
        } catch (Throwable t) {
            return 0;
        }
    }

    @EventHandler
    private void onOpenScreen(OpenScreenEvent event) {
        if (!(event.screen instanceof AddHudElementScreen old)) return;
        LunarGuiTheme lt = LunarGuiTheme.INSTANCE;
        if (lt == null || GuiThemes.get() != lt) return;

        int x = intField(old, "x"), y = intField(old, "y");
        event.cancel();
        mc.execute(() -> mc.setScreen(new LunarAddHudScreen(lt, x, y)));
    }
}
