package com.blockoutlines.addon.mixin;

import com.blockoutlines.addon.modules.StarrySky;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.SkyRendering;
import net.minecraft.client.render.state.SkyRenderState;
import net.minecraft.client.world.ClientWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Lets Starry Sky recolor the vanilla sky gradient. Same hook Meteor's Ambience uses. */
@Mixin(SkyRendering.class)
public class StarrySkyRenderingMixin {
    @Inject(method = "updateRenderState", at = @At("TAIL"), require = 0)
    private void blockoutlines$starrySky(ClientWorld world, float tickProgress, Camera camera, SkyRenderState state, CallbackInfo ci) {
        if (Modules.get() == null) return;
        StarrySky m = Modules.get().get(StarrySky.class);
        if (m == null || !m.isActive()) return;
        int c = m.skyColorPacked();
        if (c != -1) state.skyColor = c;
    }
}
