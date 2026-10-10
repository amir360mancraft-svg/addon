package com.blockoutlines.addon.mixin;

import com.blockoutlines.addon.modules.FakeElytra;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

/** Makes the tooltip of the fake elytra item look like the tooltip of a real enchanted elytra. */
@Mixin(ItemStack.class)
public abstract class ItemStackMixin {
    private static final int ELYTRA_DURABILITY = 432;

    /** The name shown above the hotbar (and anywhere else the name is read) is Elytra too. */
    @Inject(method = "getName", at = @At("RETURN"), cancellable = true, require = 1)
    private void blockoutlines$fakeName(CallbackInfoReturnable<Text> cir) {
        FakeElytra module = Modules.get() != null ? Modules.get().get(FakeElytra.class) : null;
        if (module != null && module.tooltipEnabled() && module.isTarget((ItemStack) (Object) this)) {
            cir.setReturnValue(Text.translatable("item.minecraft.elytra"));
        }
    }

    @Inject(method = "getTooltip", at = @At("RETURN"), require = 1)
    private void blockoutlines$fakeTooltip(CallbackInfoReturnable<List<Text>> cir) {
        FakeElytra module = Modules.get() != null ? Modules.get().get(FakeElytra.class) : null;
        ItemStack self = (ItemStack) (Object) this;
        if (module == null || !module.isTarget(self)) return;

        List<Text> original = cir.getReturnValue();
        if (original == null || original.isEmpty()) return;

        Text worthLine = Text.literal("~").formatted(Formatting.DARK_GRAY)
            .append(Text.literal("$ ").formatted(Formatting.GREEN))
            .append(Text.literal(module.worth.get()).formatted(Formatting.WHITE));

        List<Text> out = new ArrayList<>();

        if (!module.tooltipEnabled()) {
            // only the worth line is replaced
            if (!module.worthEnabled()) return;
            boolean changed = false;
            for (Text line : original) {
                if (FakeElytra.WORTH_LINE.matcher(line.getString()).matches()) { out.add(worthLine); changed = true; }
                else out.add(line);
            }
            if (changed) cir.setReturnValue(out);
            return;
        }

        out.add(Text.translatable("item.minecraft.elytra").formatted(Formatting.LIGHT_PURPLE)); // enchanted = epic color

        List<Text> late = new ArrayList<>();
        for (int i = 1; i < original.size(); i++) {
            Text line = original.get(i);
            String s = line.getString();
            if (s.startsWith("Hold ")) out.add(line);                                  // "Hold J to show NBT" and similar
            else if (s.startsWith("Durability:")) late.add(Text.literal("Durability: " + elytraDurability(self) + " / " + ELYTRA_DURABILITY).formatted(Formatting.WHITE));
            else if (s.startsWith("minecraft:")) late.add(Text.literal("minecraft:elytra").formatted(Formatting.DARK_GRAY));
            else if (s.endsWith("component(s)") || s.endsWith("component")) late.add(line);
        }

        out.add(Text.translatable("enchantment.minecraft.unbreaking").append(" ").append(Text.translatable("enchantment.level.3")).formatted(Formatting.GRAY));
        out.add(Text.translatable("enchantment.minecraft.mending").formatted(Formatting.GRAY));
        if (module.worthEnabled()) out.add(worthLine);
        out.addAll(late);
        cir.setReturnValue(out);
    }

    /** Keeps the damage ratio of the real item, scaled to the elytra's durability. */
    private static int elytraDurability(ItemStack stack) {
        int max = stack.getMaxDamage();
        if (max <= 0) return ELYTRA_DURABILITY;
        return Math.max(0, Math.round(ELYTRA_DURABILITY * (max - stack.getDamage()) / (float) max));
    }
}
