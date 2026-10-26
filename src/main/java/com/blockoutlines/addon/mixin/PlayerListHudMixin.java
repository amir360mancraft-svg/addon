package com.blockoutlines.addon.mixin;

import com.blockoutlines.addon.modules.BetterNameProtector;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.PlayerListHud;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Puts Better Name Protector's icon in front of your own name in the tab list. */
@Mixin(PlayerListHud.class)
public abstract class PlayerListHudMixin {
    @Inject(method = "getPlayerName", at = @At("RETURN"), cancellable = true, require = 0)
    private void blockoutlines$icon(PlayerListEntry entry, CallbackInfoReturnable<Text> cir) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.getNetworkHandler() == null || Modules.get() == null) return;
        if (mc.getNetworkHandler().getPlayerListEntry(mc.player.getUuid()) != entry) return;
        BetterNameProtector m = Modules.get().get(BetterNameProtector.class);
        if (m == null || !m.isActive()) return;
        cir.setReturnValue(m.decorateTab(cir.getReturnValue()));
    }
}
