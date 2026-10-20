package com.blockoutlines.addon.mixin;

import com.blockoutlines.addon.modules.SwingSpeed;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Lets the Swing Speed module change the length of your own hand swing (client side only). */
@Mixin(LivingEntity.class)
public abstract class SwingDurationMixin {
    @Inject(method = "getHandSwingDuration", at = @At("RETURN"), cancellable = true, require = 0)
    private void blockoutlines$swingDuration(CallbackInfoReturnable<Integer> cir) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || (Object) this != mc.player || Modules.get() == null) return;

        SwingSpeed module = Modules.get().get(SwingSpeed.class);
        if (module == null || !module.isActive()) return;
        cir.setReturnValue(module.duration(cir.getReturnValue()));
    }
}
