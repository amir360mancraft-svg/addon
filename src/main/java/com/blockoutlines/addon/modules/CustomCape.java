package com.blockoutlines.addon.modules;

import com.blockoutlines.addon.BlockOutlinesAddon;
import com.blockoutlines.addon.gui.CapeEditorScreen;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WVerticalList;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.entity.player.SkinTextures;
import net.minecraft.util.AssetInfo;
import net.minecraft.util.Identifier;
import org.lwjgl.BufferUtils;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

/**
 * Your own cape (and matching elytra), client side only: other players cannot see it.
 * Pick one of 10 preset colours, or upload a normal Minecraft cape PNG (the same file draws the cape and the elytra).
 */
public class CustomCape extends Module {
    public enum Source { Preset, File }

    public enum Preset {
        Red(214, 48, 49), Orange(240, 128, 32), Yellow(245, 205, 48), Green(60, 175, 80), Cyan(40, 190, 200),
        Blue(50, 100, 220), Purple(140, 70, 210), Pink(238, 100, 170), White(235, 235, 240), Black(28, 28, 34);

        final int r, g, b;
        Preset(int r, int g, int b) { this.r = r; this.g = g; this.b = b; }
    }

    public enum Style { Gradient, Solid, Border, Stripe }

    private static final String CAPE_FILE = "meteor-client/better-cape.png";
    private static final Identifier ID = Identifier.of("blockoutlines", "custom_cape");
    /** The game turns an asset id into the texture path textures/<id>.png, so the texture is registered there. */
    private static final Identifier TEXTURE_PATH = Identifier.of("blockoutlines", "textures/custom_cape.png");

    private final SettingGroup sg = settings.getDefaultGroup();

    private final Setting<Source> source = sg.add(new EnumSetting.Builder<Source>()
        .name("source").description("Preset = one of the built-in colours. File = the cape PNG you uploaded with the button below.")
        .defaultValue(Source.Preset).build());
    private final Setting<Preset> preset = sg.add(new EnumSetting.Builder<Preset>()
        .name("color").description("Main colour of the preset cape and elytra.")
        .defaultValue(Preset.Purple).visible(() -> source.get() == Source.Preset).build());
    private final Setting<Style> style = sg.add(new EnumSetting.Builder<Style>()
        .name("style").description("Pattern of the preset cape and elytra.")
        .defaultValue(Style.Gradient).visible(() -> source.get() == Source.Preset).build());

    private boolean ready;
    private NativeImageBackedTexture texture;
    private String builtKey = "";

    public CustomCape() {
        super(BlockOutlinesAddon.COSMETICS, "custom-cape", "A custom cape and elytra for you: pick a colour or upload a cape PNG. Only you see it.");
    }

    @Override
    public void onActivate() {
        builtKey = "";
    }

    @Override
    public void onDeactivate() {
        release();
    }

    @Override
    public WWidget getWidget(GuiTheme theme) {
        WVerticalList list = theme.verticalList();
        WButton draw = list.add(theme.button("Draw cape (in game editor)")).expandX().widget();
        WButton upload = list.add(theme.button("Upload cape (.png)")).expandX().widget();
        WButton reset = list.add(theme.button("Back to preset colours")).expandX().widget();
        draw.action = this::openEditor;
        upload.action = this::upload;
        reset.action = () -> {
            source.set(Source.Preset);
            builtKey = "";
        };
        return list;
    }

    /** Called by the skin mixin for your own player: swaps in the custom cape / elytra textures. */
    public SkinTextures apply(SkinTextures original) {
        if (original == null || !ready) return original;
        try {
            return original.withOverride(SkinTextures.SkinOverride.create(
                Optional.empty(),
                Optional.of(new AssetInfo.TextureAssetInfo(ID)),
                Optional.of(new AssetInfo.TextureAssetInfo(ID)),
                Optional.empty()));
        } catch (Throwable t) {
            return original;
        }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        String key = source.get() + "|" + preset.get() + "|" + style.get();
        if (key.equals(builtKey) && texture != null) return;
        builtKey = key;
        rebuild();
    }

    // ------------------------------------------------------------------ texture

    private void release() {
        ready = false;
        if (texture != null) {
            try {
                mc.getTextureManager().destroyTexture(TEXTURE_PATH);
            } catch (Throwable ignored) {
            }
            texture = null;
        }
    }

    private void rebuild() {
        NativeImage image = null;
        try {
            if (source.get() == Source.File) image = readFile();
            if (image == null) image = generate(preset.get(), style.get());

            release();
            texture = new NativeImageBackedTexture(() -> "blockoutlines-custom-cape", image);
            mc.getTextureManager().registerTexture(TEXTURE_PATH, texture);
            ready = true;
        } catch (Throwable t) {
            error("Could not create the cape texture: " + t.getMessage());
            if (image != null && texture == null) image.close();
        }
    }

    private NativeImage readFile() {
        File f = new File(mc.runDirectory, CAPE_FILE);
        if (!f.isFile()) return null;
        try (InputStream in = Files.newInputStream(f.toPath())) {
            NativeImage img = NativeImage.read(in);
            int w = img.getWidth(), h = img.getHeight();
            if (w * 32 != h * 64) {
                // a cape PNG is 64x32 (or a multiple of it); anything else cannot be mapped onto the cape model
                error("The cape PNG must be 64x32 (or 128x64, 256x128 ...). This one is " + w + "x" + h + ".");
                img.close();
                return null;
            }
            return img;
        } catch (Exception e) {
            error("Could not read the cape PNG: " + e.getMessage());
            return null;
        }
    }

    /** Opens the pixel editor, starting from your saved cape or from the current preset. */
    private void openEditor() {
        int[] start = null;
        if (source.get() == Source.File) start = readPixels();
        if (start == null) start = presetPixels();
        mc.setScreen(new CapeEditorScreen(mc.currentScreen, start, this::presetPixels, this::savePixels));
    }

    private int[] presetPixels() {
        NativeImage img = generate(preset.get(), style.get());
        int[] out = new int[64 * 32];
        for (int y = 0; y < 32; y++) for (int x = 0; x < 64; x++) out[y * 64 + x] = img.getColorArgb(x, y);
        img.close();
        return out;
    }

    /** Reads the saved cape PNG as 64x32 pixels (bigger files are sampled down). */
    private int[] readPixels() {
        NativeImage img = readFile();
        if (img == null) return null;
        int w = img.getWidth(), h = img.getHeight();
        int[] out = new int[64 * 32];
        for (int y = 0; y < 32; y++) for (int x = 0; x < 64; x++) out[y * 64 + x] = img.getColorArgb(x * w / 64, y * h / 32);
        img.close();
        return out;
    }

    private void savePixels(int[] pixels) {
        try (NativeImage img = new NativeImage(64, 32, true)) {
            for (int y = 0; y < 32; y++) for (int x = 0; x < 64; x++) img.setColorArgb(x, y, pixels[y * 64 + x]);
            File target = new File(mc.runDirectory, CAPE_FILE);
            target.getParentFile().mkdirs();
            img.writeTo(target);
            source.set(Source.File);
            builtKey = "";
            info("Cape saved.");
        } catch (Exception e) {
            error("Could not save the cape: " + e.getMessage());
        }
    }

    private void upload() {
        try {
            PointerBuffer filters = BufferUtils.createPointerBuffer(1);
            filters.put(MemoryUtil.memASCII("*.png"));
            filters.rewind();
            String path = TinyFileDialogs.tinyfd_openFileDialog("Select cape PNG", null, filters, "PNG images", false);
            if (path == null) return;

            File target = new File(mc.runDirectory, CAPE_FILE);
            target.getParentFile().mkdirs();
            Files.copy(java.nio.file.Path.of(path), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            source.set(Source.File);
            builtKey = "";
            info("Cape updated.");
        } catch (Exception e) {
            error("Could not load that file: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ preset textures

    private static int argb(int r, int g, int b) {
        return 0xFF000000 | (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }

    private static int mix(int r, int g, int b, double k) {
        // k < 0 darkens, k > 0 lightens
        if (k < 0) return argb((int) (r * (1 + k)), (int) (g * (1 + k)), (int) (b * (1 + k)));
        return argb((int) (r + (255 - r) * k), (int) (g + (255 - g) * k), (int) (b + (255 - b) * k));
    }

    /** Paints one face of the model: a pattern in the preset colour. u/v are the position inside the face. */
    private static int pixel(Preset p, Style s, int u, int v, int w, int h, boolean inner, boolean wing) {
        double t = h <= 1 ? 0 : v / (double) (h - 1);
        boolean edge = u == 0 || v == 0 || u == w - 1 || v == h - 1;
        double k;
        switch (s) {
            case Solid -> k = 0;
            case Border -> k = edge ? -0.45 : 0.05;
            case Stripe -> {
                int mid = w / 2;
                k = (u == mid || u == mid - 1) ? 0.55 : -0.05 + 0.15 * (1 - t);
            }
            default -> k = 0.30 - 0.60 * t; // Gradient: light on top, dark at the bottom
        }
        // feather ribs on the elytra wings
        if (wing && (u + v) % 4 == 0) k -= 0.12;
        if (inner) k -= 0.30;
        return mix(p.r, p.g, p.b, Math.max(-0.9, Math.min(0.9, k)));
    }

    private static void face(NativeImage img, Preset p, Style s, int x, int y, int w, int h, boolean inner, boolean wing) {
        for (int v = 0; v < h; v++) for (int u = 0; u < w; u++) img.setColorArgb(x + u, y + v, pixel(p, s, u, v, w, h, inner, wing));
    }

    private static void edge(NativeImage img, Preset p, int x, int y, int w, int h) {
        int c = mix(p.r, p.g, p.b, -0.55);
        for (int v = 0; v < h; v++) for (int u = 0; u < w; u++) img.setColorArgb(x + u, y + v, c);
    }

    /** A 64x32 cape texture: cape faces at the top left, elytra faces next to them (the layout the game expects). */
    private static NativeImage generate(Preset p, Style s) {
        NativeImage img = new NativeImage(64, 32, true);
        for (int y = 0; y < 32; y++) for (int x = 0; x < 64; x++) img.setColorArgb(x, y, 0);

        // cape (10 x 16 x 1): outside (1,1), inside (12,1), edges
        face(img, p, s, 1, 1, 10, 16, false, false);
        face(img, p, s, 12, 1, 10, 16, true, false);
        edge(img, p, 1, 0, 10, 1);
        edge(img, p, 11, 0, 10, 1);
        edge(img, p, 0, 1, 1, 16);
        edge(img, p, 11, 1, 1, 16);

        // elytra wing (10 x 20 x 2) at (22,0): top (24,0), bottom (34,0), right (22,2), front (24,2), left (34,2), back (36,2)
        face(img, p, s, 24, 2, 10, 20, false, true);
        face(img, p, s, 36, 2, 10, 20, true, true);
        edge(img, p, 24, 0, 10, 2);
        edge(img, p, 34, 0, 10, 2);
        edge(img, p, 22, 2, 2, 20);
        edge(img, p, 34, 2, 2, 20);
        return img;
    }
}
