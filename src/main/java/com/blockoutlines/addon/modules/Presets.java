package com.blockoutlines.addon.modules;

import com.blockoutlines.addon.BlockOutlinesAddon;
import com.blockoutlines.addon.gui.PresetBarScreen;
import com.blockoutlines.addon.gui.PresetEditorScreen;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.KeybindSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import org.lwjgl.glfw.GLFW;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Presets: one key press switches a whole group of modules on or off. Hold the key (J), move the mouse to a card and
 * let go. Every preset has a name, an icon (any game item), a list of modules to turn ON, a list to turn OFF, and an
 * "exclusive" switch that turns off everything else. Examples: "Legit" = only cosmetics, ESP and name tags.
 */
public class Presets extends Module {
    public static final class Preset {
        public String name = "Preset";
        public String kind = "item";
        public String value = "paper";
        public List<String> on = new ArrayList<>();
        public List<String> off = new ArrayList<>();
        /** Also turn off every module that is not in the "on" list (except this module and the keep-on list). */
        public boolean exclusive;

        public Preset() {}

        public Preset(String name, String icon, boolean exclusive) {
            this.name = name;
            this.value = icon;
            this.exclusive = exclusive;
        }
    }

    private final SettingGroup sg = settings.getDefaultGroup();

    private final Setting<Keybind> key = sg.add(new KeybindSetting.Builder()
        .name("key").description("Hold this key to open the presets, move the mouse to a card and let go.")
        .defaultValue(Keybind.fromKey(GLFW.GLFW_KEY_J)).build());
    private final Setting<Keybind> editKey = sg.add(new KeybindSetting.Builder()
        .name("edit-key").description("Opens the preset editor (you can also press Edit on the preset screen).")
        .defaultValue(Keybind.none()).action(this::openEditor).build());
    private final Setting<SettingColor> accent = sg.add(new ColorSetting.Builder()
        .name("color").description("Color of the card under the mouse.").defaultValue(new SettingColor(59, 130, 246, 255)).build());
    private final Setting<String> keepOn = sg.add(new StringSetting.Builder()
        .name("keep-on").description("Module names (comma separated, like hud,gui) that an exclusive preset must never turn off.")
        .defaultValue("").build());

    private final List<Preset> presets = new ArrayList<>();
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private boolean wasDown;
    private boolean hadFile;

    public Presets() {
        super(BlockOutlinesAddon.CATEGORY, "presets", "Switch a whole group of modules on/off with one key (like a Legit preset). Hold the key, pick a card, let go.");
        load();
    }

    public List<Preset> presets() {
        return presets;
    }

    // ------------------------------------------------------------------ saving

    private File file() {
        return new File(mc.runDirectory, "better-render/presets.json");
    }

    public void save() {
        try {
            File f = file();
            f.getParentFile().mkdirs();
            Files.writeString(f.toPath(), gson.toJson(presets), StandardCharsets.UTF_8);
        } catch (Exception e) {
            error("Could not save the presets: " + e.getMessage());
        }
    }

    private void load() {
        presets.clear();
        try {
            File f = file();
            if (f.exists()) {
                hadFile = true;
                List<Preset> read = gson.fromJson(Files.readString(f.toPath(), StandardCharsets.UTF_8), new TypeToken<List<Preset>>() {}.getType());
                if (read != null) presets.addAll(read);
            }
        } catch (Throwable ignored) {
        }
    }

    /** The default presets need the module list, which is not complete yet when this module is created. */
    private void ensureDefaults() {
        if (hadFile || !presets.isEmpty()) return;
        hadFile = true;
        List<String> cosmetics = new ArrayList<>();
        for (Module m : Modules.get().getGroup(BlockOutlinesAddon.COSMETICS)) cosmetics.add(m.name);

        Preset legit = new Preset("Legit", "spyglass", true);
        legit.on.addAll(cosmetics);
        legit.on.add("esp");
        legit.on.add("nametags");
        presets.add(legit);

        Preset cos = new Preset("Cosmetics", "leather_chestplate", true);
        cos.on.addAll(cosmetics);
        presets.add(cos);

        presets.add(new Preset("All off", "barrier", true));
        save();
    }

    // ------------------------------------------------------------------ the screen

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        boolean down = key.get().isPressed();
        if (down && !wasDown && mc.currentScreen == null && mc.player != null && mc.world != null) openBar();
        wasDown = down;
    }

    private void openBar() {
        ensureDefaults();
        int accentArgb = (255 << 24) | (accent.get().r << 16) | (accent.get().g << 8) | accent.get().b;
        mc.setScreen(new PresetBarScreen(this, key.get(), accentArgb));
    }

    public void openEditor() {
        if (mc.player == null) return;
        ensureDefaults();
        mc.execute(() -> mc.setScreen(new PresetEditorScreen(this)));
    }

    // ------------------------------------------------------------------ applying

    private Set<String> keepNames() {
        Set<String> s = new HashSet<>();
        s.add(name);
        for (String p : keepOn.get().split(",")) {
            String t = p.trim().toLowerCase();
            if (!t.isEmpty()) s.add(t);
        }
        return s;
    }

    public void apply(Preset p) {
        int on = 0, off = 0;
        Set<String> wanted = new HashSet<>();
        for (String n : p.on) wanted.add(n.toLowerCase());
        Set<String> keep = keepNames();

        for (String n : p.on) {
            Module m = Modules.get().get(n);
            if (m != null && m != this && !m.isActive()) { m.toggle(); on++; }
        }
        for (String n : p.off) {
            Module m = Modules.get().get(n);
            if (m != null && m != this && m.isActive()) { m.toggle(); off++; }
        }
        if (p.exclusive) {
            for (Module m : new ArrayList<>(Modules.get().getActive())) {
                if (m == this || wanted.contains(m.name.toLowerCase()) || keep.contains(m.name.toLowerCase())) continue;
                m.toggle();
                off++;
            }
        }
        info("Preset " + p.name + ": " + on + " turned on, " + off + " turned off.");
    }

    /** True when every module of the "on" list is on and every module of the "off" list is off (shown as a dot). */
    public boolean matches(Preset p) {
        if (p.on.isEmpty() && p.off.isEmpty()) return false;
        for (String n : p.on) {
            Module m = Modules.get().get(n);
            if (m != null && !m.isActive()) return false;
        }
        for (String n : p.off) {
            Module m = Modules.get().get(n);
            if (m != null && m.isActive()) return false;
        }
        return true;
    }

    /** Remembers which modules are on right now as the "on" list of the preset. */
    public void capture(Preset p) {
        p.on.clear();
        p.off.clear();
        for (Module m : Modules.get().getActive()) {
            if (m != this) p.on.add(m.name);
        }
        save();
    }
}
