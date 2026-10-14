package com.blockoutlines.addon.mixin;

import net.minecraft.client.texture.AbstractTexture;
import net.minecraft.client.texture.TextureManager;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/** Lets Custom Glint swap a texture in and out without the texture manager closing the vanilla one. */
@Mixin(TextureManager.class)
public interface TextureManagerAccessor {
    @Accessor("textures")
    Map<Identifier, AbstractTexture> blockoutlines$textures();
}
