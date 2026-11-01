package com.blockoutlines.addon.util;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;

/** One GPU texture that a map is painted into (a single quad is much cheaper than thousands of small rectangles). */
public final class MapTexture {
    private final Identifier id;
    private final String name;
    private boolean has;
    public int w, h;

    public MapTexture(String path) {
        this.id = Identifier.of("blockoutlines", path);
        this.name = "blockoutlines-" + path;
    }

    public Identifier id() {
        return id;
    }

    public boolean ready() {
        return has;
    }

    /** Replaces the texture with this image (render thread only). */
    public void commit(NativeImage img, int w, int h) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (has) {
            try {
                mc.getTextureManager().destroyTexture(id);
            } catch (Throwable ignored) {
            }
        }
        mc.getTextureManager().registerTexture(id, new NativeImageBackedTexture(() -> name, img));
        this.w = w;
        this.h = h;
        has = true;
    }

    public void free() {
        if (!has) return;
        has = false;
        try {
            MinecraftClient.getInstance().getTextureManager().destroyTexture(id);
        } catch (Throwable ignored) {
        }
    }
}
