package com.blockoutlines.addon.mixin;

import net.minecraft.client.gl.GpuSampler;
import net.minecraft.client.texture.AbstractTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lets Custom Glint give its own texture the repeating sampler of the vanilla glint texture. */
@Mixin(AbstractTexture.class)
public interface AbstractTextureAccessor {
    @Mutable
    @Accessor("sampler")
    void blockoutlines$setSampler(GpuSampler sampler);
}
