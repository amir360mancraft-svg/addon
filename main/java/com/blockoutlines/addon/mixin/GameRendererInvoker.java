package com.blockoutlines.addon.mixin;

import net.minecraft.client.render.GameRenderer;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Lets the Saturation module switch the vanilla full-screen post effect (drawn before the GUI, so the GUI is untouched). */
@Mixin(GameRenderer.class)
public interface GameRendererInvoker {
    @Invoker("setPostProcessor")
    void blockoutlines$setPostProcessor(Identifier id);

    @Invoker("clearPostProcessor")
    void blockoutlines$clearPostProcessor();
}
