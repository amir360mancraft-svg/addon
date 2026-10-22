package com.blockoutlines.addon.gui;

import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import meteordevelopment.meteorclient.events.game.OpenScreenEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.gui.GuiThemes;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Properties;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Handles the first-launch setup wizard for Better Render.
 * Prompts the user on their first time reaching the TitleScreen, saves setup choices to a dedicated
 * properties file, and applies chosen module presets safely upon joining a world to prevent NPEs.
 */
public final class LunarSetup {
    public static final LunarSetup INSTANCE = new LunarSetup();
    private static final String FILE_NAME = "better-render-setup.properties";

    private static boolean registered;
    private static boolean sessionChecked;
    private static String pendingCollection;

    // Presets defined for the wizard
    public static final String[] CASUAL_MODULES = {
        "better-outline", "custom-crosshair", "item-counter-hud", "jump-circle",
        "player-trail", "item-drop-physics", "color-grading"
    };

    public static final String[] DEFAULT_MODULES = {
        "better-outline", "custom-crosshair", "hit-effect", "kill-effect",
        "target-hud", "dynamic-island", "death-echo", "color-grading"
    };

    public static final String[] COMPETITIVE_MODULES = {
        "better-outline", "custom-crosshair", "target-hud", "hit-effect",
        "target-marker", "item-counter-hud"
    };

    private LunarSetup() {
    }

    public static void register() {
        if (registered) return;
        registered = true;
        MeteorClient.EVENT_BUS.subscribe(INSTANCE);
    }

    private static File getConfigFile() {
        return new File(MeteorClient.FOLDER, FILE_NAME);
    }

    public static boolean isSetupDone() {
        File file = getConfigFile();
        if (!file.exists()) return false;
        try (FileInputStream in = new FileInputStream(file)) {
            Properties prop = new Properties();
            prop.load(in);
            return Boolean.parseBoolean(prop.getProperty("setup_completed", "false"));
        } catch (Throwable t) {
            return false;
        }
    }

    public static void markDone(String collection, int themePreset, int accentIndex) {
        File file = getConfigFile();
        try {
            Properties prop = new Properties();
            if (file.exists()) {
                try (FileInputStream in = new FileInputStream(file)) {
                    prop.load(in);
                } catch (Throwable ignored) {}
            }
            prop.setProperty("setup_completed", "true");
            prop.setProperty("collection", collection != null ? collection : "none");
            prop.setProperty("theme_preset", String.valueOf(themePreset));
            prop.setProperty("accent_index", String.valueOf(accentIndex));
            prop.setProperty("timestamp", String.valueOf(System.currentTimeMillis()));

            try (FileOutputStream out = new FileOutputStream(file)) {
                prop.store(out, "Better Render First-Launch Setup");
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }

        pendingCollection = collection;
    }

    public static void openWizard(Screen parent) {
        mc.setScreen(new LunarWizardScreen(parent));
    }

    public static void applyCollection(String collection) {
        if (collection == null || collection.isEmpty() || collection.equalsIgnoreCase("none")) return;

        String[] targetList = switch (collection.toLowerCase()) {
            case "casual" -> CASUAL_MODULES;
            case "competitive" -> COMPETITIVE_MODULES;
            default -> DEFAULT_MODULES;
        };

        Modules modules = Modules.get();
        if (modules == null) return;

        for (String name : targetList) {
            Module m = modules.get(name);
            if (m != null && !m.isActive()) {
                m.toggle();
            }
        }
    }

    private static void applyPendingPreset() {
        if (pendingCollection == null) return;
        String col = pendingCollection;
        pendingCollection = null;
        applyCollection(col);
    }

    @EventHandler
    private void onOpenScreen(OpenScreenEvent event) {
        if (sessionChecked) return;
        if (event.screen instanceof TitleScreen) {
            sessionChecked = true;
            if (!isSetupDone()) {
                event.cancel();
                mc.execute(() -> mc.setScreen(new LunarWizardScreen(event.screen)));
            }
        }
    }

    @EventHandler
    private void onGameJoined(GameJoinedEvent event) {
        applyPendingPreset();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (!sessionChecked && !isSetupDone() && mc.currentScreen instanceof TitleScreen title) {
            sessionChecked = true;
            mc.setScreen(new LunarWizardScreen(title));
            return;
        }

        if (pendingCollection != null && mc.world != null && mc.player != null) {
            applyPendingPreset();
        }
    }
}
