package com.blockoutlines.addon.mixin;

import com.blockoutlines.addon.modules.CinematicCamera;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/** Moves the camera for the Cinematic Camera module (same hooks Meteor's Freecam uses). */
@Mixin(Camera.class)
public abstract class CameraCinematicMixin {
    @Shadow private boolean thirdPerson;

    private static CinematicCamera blockoutlines$cam() {
        if (Modules.get() == null) return null;
        CinematicCamera c = Modules.get().get(CinematicCamera.class);
        return c != null && c.isActive() ? c : null;
    }

    @ModifyArgs(method = "update", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/Camera;setPos(DDD)V"), require = 0)
    private void blockoutlines$pos(Args args) {
        CinematicCamera c = blockoutlines$cam();
        if (c == null) return;
        double[] o = c.sample();
        if (o == null) return;
        args.set(0, o[0]);
        args.set(1, o[1]);
        args.set(2, o[2]);
    }

    @ModifyArgs(method = "update", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/Camera;setRotation(FF)V"), require = 0)
    private void blockoutlines$rot(Args args) {
        CinematicCamera c = blockoutlines$cam();
        if (c == null) return;
        double[] o = c.sample();
        if (o == null) return;
        args.set(0, (float) o[3]);
        args.set(1, (float) o[4]);
    }

    @Inject(method = "update", at = @At("TAIL"), require = 0)
    private void blockoutlines$tail(World area, Entity focusedEntity, boolean thirdPerson, boolean inverseView, float tickProgress, CallbackInfo ci) {
        if (blockoutlines$cam() != null) this.thirdPerson = true;
    }
}
