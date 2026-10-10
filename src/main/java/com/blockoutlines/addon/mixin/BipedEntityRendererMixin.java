package com.blockoutlines.addon.mixin;

import com.blockoutlines.addon.modules.FakeElytra;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.item.ItemModelManager;
import net.minecraft.client.render.entity.BipedEntityRenderer;
import net.minecraft.client.render.entity.state.BipedEntityRenderState;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** When the disguised item is worn in the chest slot, your body is drawn with elytra wings (client side only). */
@Mixin(BipedEntityRenderer.class)
public abstract class BipedEntityRendererMixin {
    @Inject(method = "updateBipedRenderState", at = @At("RETURN"), require = 0)
    private static void blockoutlines$elytraBody(LivingEntity entity, BipedEntityRenderState state, float tickProgress,
                                                 ItemModelManager itemModelManager, CallbackInfo ci) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (entity != mc.player || Modules.get() == null) return;

        FakeElytra module = Modules.get().get(FakeElytra.class);
        if (module == null || !module.isActive()) return;

        ItemStack chest = entity.getEquippedStack(EquipmentSlot.CHEST);
        if (module.isTarget(chest)) state.equippedChestStack = module.elytra();
    }
}
