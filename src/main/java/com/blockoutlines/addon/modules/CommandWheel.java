package com.blockoutlines.addon.modules;

import com.blockoutlines.addon.BlockOutlinesAddon;
import com.blockoutlines.addon.gui.RadialWheelScreen;
import com.blockoutlines.addon.gui.WheelEditorScreen;
import com.blockoutlines.addon.util.WheelIcons;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.KeybindSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import org.lwjgl.glfw.GLFW;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * A round menu of quick commands (like the weapon wheel in GTA). Hold the key, move the mouse to a slice, let go: the
 * command is sent. Every slice has a name, a command and an icon (a game item or your own PNG). The "Edit" button on the
 * wheel (or the edit key) opens an editor to add, change, sort and delete the slices.
 */
public class CommandWheel extends Module {
    /** One slice of the wheel. kind = "item" (value = item id like totem_of_undying) or "file" (value = picture file name). */
    public static final class Item {
        public String label, command, kind, value;

        public Item() {}

        public Item(String label, String command, String kind, String value) {
            this.label = label;
            this.command = command;
            this.kind = kind;
            this.value = value;
        }
    }

    private final SettingGroup sg = settings.getDefaultGroup();

    private final Setting<Keybind> key = sg.add(new KeybindSetting.Builder()
        .name("key").description("Hold this key to open the wheel, move the mouse to a slice and let go.")
        .defaultValue(Keybind.fromKey(GLFW.GLFW_KEY_G)).build());
    private final Setting<Keybind> editKey = sg.add(new KeybindSetting.Builder()
        .name("edit-key").description("Opens the editor (you can also press Edit on the wheel).")
        .defaultValue(Keybind.none()).action(this::openEditor).build());
    private final Setting<Integer> size = sg.add(new IntSetting.Builder()
        .name("size").description("Radius of the wheel.").defaultValue(105).range(70, 160).sliderRange(80, 140).build());
    private final Setting<SettingColor> accent = sg.add(new ColorSetting.Builder()
        .name("color").description("Color of the slice under the mouse.").defaultValue(new SettingColor(59, 130, 246, 255)).build());

    private final List<Item> items = new ArrayList<>();
    private boolean wasDown;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public CommandWheel() {
        super(BlockOutlinesAddon.COMBAT, "command-wheel", "A round menu for quick commands like /home, /rtp or /ah totem, with icons and an editor.");
        load();
    }

    public List<Item> items() {
        return items;
    }

    // ------------------------------------------------------------------ saving

    private File file() {
        return new File(mc.runDirectory, "better-render/command-wheel.json");
    }

    public void save() {
        try {
            File f = file();
            f.getParentFile().mkdirs();
            Files.writeString(f.toPath(), gson.toJson(items), StandardCharsets.UTF_8);
        } catch (Exception e) {
            error("Could not save the wheel: " + e.getMessage());
        }
    }

    private void load() {
        items.clear();
        try {
            File f = file();
            if (f.exists()) {
                List<Item> read = gson.fromJson(Files.readString(f.toPath(), StandardCharsets.UTF_8), new TypeToken<List<Item>>() {}.getType());
                if (read != null) items.addAll(read);
                return;
            }
        } catch (Throwable ignored) {
        }
        // first start: a few useful ones for Donut
        items.add(new Item("Home", "/home", "item", "red_bed"));
        items.add(new Item("RTP", "/rtp", "item", "compass"));
        items.add(new Item("AH Totem", "/ah totem", "item", "totem_of_undying"));
        items.add(new Item("Spawn", "/spawn", "item", "nether_star"));
        save();
    }

    // ------------------------------------------------------------------ the wheel

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        boolean down = key.get().isPressed();
        if (down && !wasDown && mc.currentScreen == null && mc.player != null && mc.world != null) openWheel();
        wasDown = down;
    }

    private void openWheel() {
        List<RadialWheelScreen.Entry> entries = new ArrayList<>();
        for (Item it : items) {
            final Item item = it;
            entries.add(new RadialWheelScreen.Entry(it.label, it.command,
                (ctx, cx, cy, sz) -> WheelIcons.draw(ctx, item.kind, item.value, cx, cy, sz),
                () -> run(item.command)));
        }
        int accentArgb = (255 << 24) | (accent.get().r << 16) | (accent.get().g << 8) | accent.get().b;
        mc.setScreen(new RadialWheelScreen("Commands", entries, key.get(), this::openEditor, size.get(), accentArgb, "Release to send"));
    }

    private void openEditor() {
        if (mc.player == null) return;
        mc.execute(() -> mc.setScreen(new WheelEditorScreen(this)));
    }

    private void run(String command) {
        if (command == null || mc.getNetworkHandler() == null) return;
        String c = command.trim();
        if (c.isEmpty()) return;
        if (c.startsWith("/")) {
            if (c.length() > 1) mc.getNetworkHandler().sendChatCommand(c.substring(1));
        } else {
            mc.getNetworkHandler().sendChatMessage(c);
        }
    }
}
