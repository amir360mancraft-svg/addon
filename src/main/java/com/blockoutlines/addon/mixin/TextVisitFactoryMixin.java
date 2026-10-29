package com.blockoutlines.addon.mixin;

import com.blockoutlines.addon.modules.BetterNameProtector;
import com.blockoutlines.addon.modules.DealerLuck;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.text.TextVisitFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Every string drawn on screen goes through here; Better Name Protector swaps names in it. */
@Mixin(TextVisitFactory.class)
public abstract class TextVisitFactoryMixin {
    @ModifyVariable(method = "visitFormatted(Ljava/lang/String;ILnet/minecraft/text/Style;Lnet/minecraft/text/Style;Lnet/minecraft/text/CharacterVisitor;)Z",
        at = @At("HEAD"), argsOnly = true, require = 0)
    private static String blockoutlines$names(String text) {
        Modules modules = Modules.get();
        if (modules == null) return text;
        BetterNameProtector m = modules.get(BetterNameProtector.class);
        String out = m != null && m.isActive() ? m.replace(text) : text;
        DealerLuck luck = modules.get(DealerLuck.class);
        return luck != null && luck.isActive() ? luck.apply(out) : out;
    }
}
