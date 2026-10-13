package com.blockoutlines.addon.mixin;

import com.blockoutlines.addon.modules.NoWater;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.block.BlockState;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.block.FluidRenderer;
import net.minecraft.fluid.FluidState;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.BlockRenderView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** No Water: skip building the water mesh when the module is on. */
@Mixin(FluidRenderer.class)
public abstract class FluidRendererMixin {
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void blockoutlines$hideWater(BlockRenderView world, BlockPos pos, VertexConsumer vertexConsumer,
                                         BlockState blockState, FluidState fluidState, CallbackInfo ci) {
        NoWater module = Modules.get().get(NoWater.class);
        if (module != null && module.isActive() && fluidState.isIn(FluidTags.WATER)) ci.cancel();
    }
}
