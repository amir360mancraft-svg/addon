package com.blockoutlines.addon.mixin;

import com.blockoutlines.addon.modules.CustomCrosshair;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.render.RenderTickCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Hides the vanilla crosshair while Custom Crosshair is on (and set to hide it). */
@Mixin(InGameHud.class)
public abstract class InGameHudMixin {
    @Inject(method = "renderCrosshair", at = @At("HEAD"), cancellable = true)
    private void blockoutlines$hideCrosshair(DrawContext context, RenderTickCounter tickCounter, CallbackInfo ci) {
        CustomCrosshair module = Modules.get().get(CustomCrosshair.class);
        if (module != null && module.isActive() && module.hideVanilla.get()) ci.cancel();
    }
}
