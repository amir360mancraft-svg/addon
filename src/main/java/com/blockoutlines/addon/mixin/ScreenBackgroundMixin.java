package com.blockoutlines.addon.mixin;

import com.blockoutlines.addon.modules.MenuBackground;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Lets the Menu Background module draw its own picture instead of the spinning panorama. */
@Mixin(Screen.class)
public abstract class ScreenBackgroundMixin {
    @Inject(method = "renderPanoramaBackground", at = @At("HEAD"), cancellable = true, require = 0)
    private void blockoutlines$background(DrawContext context, float deltaTicks, CallbackInfo ci) {
        if (Modules.get() == null) return;
        MenuBackground module = Modules.get().get(MenuBackground.class);
        if (module == null || !module.isActive()) return;
        if (module.draw(context, (Screen) (Object) this)) ci.cancel();
    }
}
