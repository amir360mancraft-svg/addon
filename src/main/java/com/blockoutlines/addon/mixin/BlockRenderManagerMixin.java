package com.blockoutlines.addon.mixin;

import com.blockoutlines.addon.modules.BlockOutlines;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.block.BlockState;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.block.BlockRenderManager;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.BlockRenderView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Hides the vanilla break-crack overlay while Block Outlines is on, so only the custom outline shows. */
@Mixin(BlockRenderManager.class)
public abstract class BlockRenderManagerMixin {
    @Inject(method = "renderDamage", at = @At("HEAD"), cancellable = true)
    private void blockoutlines$hideCracks(BlockState state, BlockPos pos, BlockRenderView world,
                                          MatrixStack matrices, VertexConsumer vertexConsumer, CallbackInfo ci) {
        BlockOutlines module = Modules.get().get(BlockOutlines.class);
        if (module != null && module.isActive()) ci.cancel();
    }
}
