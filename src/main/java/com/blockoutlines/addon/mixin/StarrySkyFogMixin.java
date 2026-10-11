package com.blockoutlines.addon.mixin;

import com.blockoutlines.addon.modules.StarrySky;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.render.fog.FogRenderer;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Tints the fog so the horizon blends into the Starry Sky colors. */
@Mixin(FogRenderer.class)
public abstract class StarrySkyFogMixin {
    @ModifyVariable(method = "applyFog(Ljava/nio/ByteBuffer;ILorg/joml/Vector4f;FFFFFF)V", at = @At("HEAD"), argsOnly = true, require = 0)
    private Vector4f blockoutlines$starryFog(Vector4f original) {
        if (Modules.get() == null) return original;
        StarrySky m = Modules.get().get(StarrySky.class);
        if (m == null || !m.isActive()) return original;
        return m.fogColor(original);
    }
}
