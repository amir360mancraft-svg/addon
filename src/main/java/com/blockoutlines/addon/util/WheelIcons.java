package com.blockoutlines.addon.util;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;

/** Icons for the wheels: any game item (the templates) or a PNG picture that you uploaded. */
public final class WheelIcons {
    private WheelIcons() {}

    /** The template icons you can pick in the editor. */
    public static final String[] TEMPLATES = {
        "totem_of_undying", "ender_pearl", "red_bed", "compass", "map", "gold_ingot", "diamond", "emerald",
        "netherite_ingot", "diamond_sword", "netherite_sword", "bow", "crossbow", "shield", "golden_apple", "enchanted_golden_apple",
        "end_crystal", "respawn_anchor", "obsidian", "chest", "ender_chest", "barrel", "shulker_box", "experience_bottle",
        "elytra", "firework_rocket", "trident", "mace", "cooked_beef", "potion", "clock", "name_tag",
        "anvil", "enchanting_table", "beacon", "nether_star", "paper", "book", "oak_sign", "spawner"
    };

    private static final class Tex {
        Identifier id;
        int w, h;
        NativeImageBackedTexture texture;
    }

    private static final Map<String, ItemStack> ITEMS = new HashMap<>();
    private static final Map<String, Tex> TEXTURES = new HashMap<>();

    public static File dir() {
        return new File(MinecraftClient.getInstance().runDirectory, "better-render/icons");
    }

    public static ItemStack stack(String id) {
        return ITEMS.computeIfAbsent(id, k -> {
            Item item = Registries.ITEM.get(Identifier.ofVanilla(k));
            return new ItemStack(item == Items.AIR ? Items.BARRIER : item);
        });
    }

    /** Draws an icon centered on (cx, cy) with the given size in pixels. kind = "item" or "file". */
    public static void draw(DrawContext ctx, String kind, String value, int cx, int cy, int size) {
        if ("file".equals(kind)) {
            Tex t = texture(value);
            if (t != null) {
                ctx.drawTexture(RenderPipelines.GUI_TEXTURED, t.id, cx - size / 2, cy - size / 2, 0f, 0f, size, size, t.w, t.h, t.w, t.h, 0xFFFFFFFF);
                return;
            }
            value = "barrier";
        }
        ItemStack st = stack(value == null ? "barrier" : value);
        float scale = size / 16f;
        ctx.getMatrices().pushMatrix();
        ctx.getMatrices().translate(cx - size / 2f, cy - size / 2f);
        ctx.getMatrices().scale(scale, scale);
        ctx.drawItem(st, 0, 0);
        ctx.getMatrices().popMatrix();
    }

    private static Tex texture(String fileName) {
        if (fileName == null || fileName.isEmpty()) return null;
        Tex cached = TEXTURES.get(fileName);
        if (cached != null) return cached.id == null ? null : cached;
        Tex t = new Tex();
        TEXTURES.put(fileName, t); // failed loads stay cached as "no id" so we do not retry every frame
        try {
            File f = new File(dir(), fileName);
            try (InputStream in = Files.newInputStream(f.toPath())) {
                NativeImage img = NativeImage.read(in);
                t.w = img.getWidth();
                t.h = img.getHeight();
                final String name = fileName;
                t.texture = new NativeImageBackedTexture(() -> "blockoutlines-wheel-" + name, img);
                t.id = Identifier.of("blockoutlines", "wheel/" + Integer.toHexString(fileName.hashCode()) + "_" + System.nanoTime());
                MinecraftClient.getInstance().getTextureManager().registerTexture(t.id, t.texture);
            }
        } catch (Throwable e) {
            t.id = null;
            return null;
        }
        return t;
    }

    /** Copies a picture into the icon folder and returns the file name to store. */
    public static String importFile(File src) throws Exception {
        File d = dir();
        d.mkdirs();
        String base = src.getName().replaceAll("[^A-Za-z0-9._-]", "_");
        if (!base.toLowerCase().endsWith(".png")) base = base + ".png";
        File target = new File(d, base);
        int n = 1;
        while (target.exists()) target = new File(d, (n++) + "_" + base);
        Files.copy(src.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        return target.getName();
    }
}
