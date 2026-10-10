package com.blockoutlines.addon.mixin;

import com.blockoutlines.addon.modules.FakeElytra;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.item.ItemModelManager;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Swaps the stack that gets drawn for an elytra when Fake Elytra is on. Only the model changes, not the real item. */
@Mixin(ItemModelManager.class)
public abstract class ItemModelManagerMixin {
    @ModifyVariable(
        method = {"update", "updateForLivingEntity", "updateForNonLivingEntity", "clearAndUpdate"},
        at = @At("HEAD"), argsOnly = true, require = 1
    )
    private ItemStack blockoutlines$fakeElytra(ItemStack stack) {
        FakeElytra module = Modules.get() != null ? Modules.get().get(FakeElytra.class) : null;
        return module != null && module.isTarget(stack) ? module.elytra() : stack;
    }
}
