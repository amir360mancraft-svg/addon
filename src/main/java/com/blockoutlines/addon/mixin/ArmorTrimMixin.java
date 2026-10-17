package com.blockoutlines.addon.mixin;

import com.blockoutlines.addon.modules.ArmorTrimChanger;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.item.ItemModelManager;
import net.minecraft.client.render.entity.BipedEntityRenderer;
import net.minecraft.client.render.entity.state.BipedEntityRenderState;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Gives the worn armor a trim before it is drawn (client side only). */
@Mixin(BipedEntityRenderer.class)
public abstract class ArmorTrimMixin {
    @Inject(method = "updateBipedRenderState", at = @At("RETURN"), require = 0)
    private static void blockoutlines$armorTrim(LivingEntity entity, BipedEntityRenderState state, float tickProgress,
                                                ItemModelManager itemModelManager, CallbackInfo ci) {
        if (Modules.get() == null) return;
        ArmorTrimChanger m = Modules.get().get(ArmorTrimChanger.class);
        if (m == null || !m.applies()) return;

        state.equippedHeadStack = m.modify(entity, EquipmentSlot.HEAD, state.equippedHeadStack);
        state.equippedChestStack = m.modify(entity, EquipmentSlot.CHEST, state.equippedChestStack);
        state.equippedLegsStack = m.modify(entity, EquipmentSlot.LEGS, state.equippedLegsStack);
        state.equippedFeetStack = m.modify(entity, EquipmentSlot.FEET, state.equippedFeetStack);
    }
}
