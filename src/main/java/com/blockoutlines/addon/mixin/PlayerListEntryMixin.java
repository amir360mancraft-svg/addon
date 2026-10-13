package com.blockoutlines.addon.mixin;

import com.blockoutlines.addon.modules.CustomCape;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.entity.player.SkinTextures;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Swaps the cape / elytra textures of your own player for the custom ones (client side only). */
@Mixin(PlayerListEntry.class)
public abstract class PlayerListEntryMixin {
    @Inject(method = "getSkinTextures", at = @At("RETURN"), cancellable = true, require = 0)
    private void blockoutlines$cape(CallbackInfoReturnable<SkinTextures> cir) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.getNetworkHandler() == null || Modules.get() == null) return;
        if (mc.getNetworkHandler().getPlayerListEntry(mc.player.getUuid()) != (Object) this) return;

        CustomCape module = Modules.get().get(CustomCape.class);
        if (module == null || !module.isActive()) return;
        cir.setReturnValue(module.apply(cir.getReturnValue()));
    }
}
