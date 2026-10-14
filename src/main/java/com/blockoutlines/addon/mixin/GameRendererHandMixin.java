package com.blockoutlines.addon.mixin;

import com.blockoutlines.addon.modules.CustomGlint;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.render.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Tells Custom Glint when the first-person hand is being drawn, so the glint can be limited to it. */
@Mixin(GameRenderer.class)
public abstract class GameRendererHandMixin {
    @Inject(method = "renderHand", at = @At("HEAD"), require = 0)
    private void blockoutlines$handBegin(CallbackInfo ci) {
        CustomGlint g = Modules.get() != null ? Modules.get().get(CustomGlint.class) : null;
        if (g != null && g.isActive()) g.handBegin();
    }

    @Inject(method = "renderHand", at = @At("RETURN"), require = 0)
    private void blockoutlines$handEnd(CallbackInfo ci) {
        CustomGlint g = Modules.get() != null ? Modules.get().get(CustomGlint.class) : null;
        if (g != null && g.isActive()) g.handEnd();
    }
}
