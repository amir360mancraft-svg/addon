package com.blockoutlines.addon.util;

import com.blockoutlines.addon.gui.LunarHubScreen;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.screen.Screen;

import java.util.LinkedHashMap;
import java.util.Map;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Lets the HUD panels of the addon be moved from the Right Shift hub (and from the chat, like before).
 * Every panel calls {@link #mark} while it is drawn, the hub draws a labelled frame around each mark and the panels
 * move themselves when you drag them while {@link #editing()} is true.
 */
public final class HudHook {
    private HudHook() {
    }

    public record Mark(String name, int x, int y, int w, int h, long time) {
    }

    private static final Map<String, Mark> MARKS = new LinkedHashMap<>();

    /** True while the player may drag the panels: the chat is open or the hub is. */
    public static boolean editing() {
        Screen s = mc.currentScreen;
        return s instanceof ChatScreen || s instanceof LunarHubScreen;
    }

    /** Called by a panel every frame it is drawn. Coordinates are GUI-scaled pixels. */
    public static void mark(String name, int x, int y, int w, int h) {
        if (!(mc.currentScreen instanceof LunarHubScreen)) return;
        MARKS.put(name, new Mark(name, x, y, w, h, System.currentTimeMillis()));
    }

    /** The marks drawn in the last moments. */
    public static Iterable<Mark> recent() {
        long now = System.currentTimeMillis();
        MARKS.values().removeIf(m -> now - m.time() > 400);
        return MARKS.values();
    }
}
