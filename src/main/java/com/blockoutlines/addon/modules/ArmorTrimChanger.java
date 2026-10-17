package com.blockoutlines.addon.modules;

import com.blockoutlines.addon.BlockOutlinesAddon;
import com.blockoutlines.addon.gui.ArmorTrimScreen;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WVerticalList;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.EquippableComponent;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.equipment.trim.ArmorTrim;
import net.minecraft.item.equipment.trim.ArmorTrimMaterial;
import net.minecraft.item.equipment.trim.ArmorTrimPattern;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.Identifier;

import java.util.Optional;

/**
 * Puts an armor trim on the armor you wear, only on your screen (client side). Pick the pattern and the material in
 * the settings or in the preview screen. The server and other players do not see it.
 */
public class ArmorTrimChanger extends Module {
    public enum Pattern {
        Sentry, Dune, Coast, Wild, Ward, Eye, Vex, Tide, Snout, Rib, Spire, Wayfinder, Shaper, Silence, Raiser, Host, Flow, Bolt;

        public String id() { return name().toLowerCase(); }
        public String templateItem() { return id() + "_armor_trim_smithing_template"; }
    }

    public enum Material {
        Quartz("quartz"), Iron("iron_ingot"), Netherite("netherite_ingot"), Redstone("redstone"), Copper("copper_ingot"),
        Gold("gold_ingot"), Emerald("emerald"), Diamond("diamond"), Lapis("lapis_lazuli"), Amethyst("amethyst_shard"), Resin("resin_brick");

        public final String item;
        Material(String item) { this.item = item; }
        public String id() { return name().toLowerCase(); }
    }

    private final SettingGroup sg = settings.getDefaultGroup();

    public final Setting<Pattern> pattern = sg.add(new EnumSetting.Builder<Pattern>()
        .name("pattern")
        .description("Trim pattern.")
        .defaultValue(Pattern.Silence)
        .build()
    );

    public final Setting<Material> material = sg.add(new EnumSetting.Builder<Material>()
        .name("material")
        .description("Trim material (the color).")
        .defaultValue(Material.Redstone)
        .build()
    );

    private final Setting<Boolean> helmet = sg.add(new BoolSetting.Builder().name("helmet").description("Trim on the helmet.").defaultValue(true).build());
    private final Setting<Boolean> chest = sg.add(new BoolSetting.Builder().name("chestplate").description("Trim on the chestplate.").defaultValue(true).build());
    private final Setting<Boolean> legs = sg.add(new BoolSetting.Builder().name("leggings").description("Trim on the leggings.").defaultValue(true).build());
    private final Setting<Boolean> boots = sg.add(new BoolSetting.Builder().name("boots").description("Trim on the boots.").defaultValue(true).build());

    private final Setting<Boolean> replaceExisting = sg.add(new BoolSetting.Builder()
        .name("replace-existing")
        .description("Also replace a trim that the armor already has. Off = only armor without a trim gets one.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> everyone = sg.add(new BoolSetting.Builder()
        .name("all-players")
        .description("Also show this trim on other players' armor (only on your screen).")
        .defaultValue(false)
        .build()
    );

    /** True while the preview screen is open, so the trim is shown even if the module is switched off. */
    public boolean previewOpen;

    private ArmorTrim cached;
    private Pattern cachedPattern;
    private Material cachedMaterial;
    private Object cachedWorld;

    public ArmorTrimChanger() {
        super(BlockOutlinesAddon.CATEGORY, "armor-trim-changer", "Shows any armor trim on your armor (client side only), with a preview screen.");
    }

    @Override
    public WWidget getWidget(GuiTheme theme) {
        WVerticalList list = theme.verticalList();
        WButton open = list.add(theme.button("Open preview (pick pattern and material)")).expandX().widget();
        open.action = () -> mc.setScreen(new ArmorTrimScreen(mc.currentScreen, this));
        return list;
    }

    public boolean applies() {
        return isActive() || previewOpen;
    }

    private boolean slotOn(EquipmentSlot slot) {
        switch (slot) {
            case HEAD: return helmet.get();
            case CHEST: return chest.get();
            case LEGS: return legs.get();
            case FEET: return boots.get();
            default: return false;
        }
    }

    /** The trim that is picked right now, or null when the world is not ready. */
    public ArmorTrim trim() {
        return trim(pattern.get(), material.get());
    }

    public ArmorTrim trim(Pattern p, Material m) {
        if (mc.world == null) return null;
        if (cached != null && cachedPattern == p && cachedMaterial == m && cachedWorld == mc.world) return cached;
        try {
            Optional<RegistryEntry.Reference<ArmorTrimPattern>> pe = mc.world.getRegistryManager().getOrThrow(RegistryKeys.TRIM_PATTERN)
                .getOptional(RegistryKey.of(RegistryKeys.TRIM_PATTERN, Identifier.ofVanilla(p.id())));
            Optional<RegistryEntry.Reference<ArmorTrimMaterial>> me = mc.world.getRegistryManager().getOrThrow(RegistryKeys.TRIM_MATERIAL)
                .getOptional(RegistryKey.of(RegistryKeys.TRIM_MATERIAL, Identifier.ofVanilla(m.id())));
            if (pe.isEmpty() || me.isEmpty()) return null;
            cached = new ArmorTrim(me.get(), pe.get());
            cachedPattern = p;
            cachedMaterial = m;
            cachedWorld = mc.world;
            return cached;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Called from the render mixin for every worn piece. Returns the stack to draw (a copy with the trim) or the same stack. */
    public ItemStack modify(LivingEntity entity, EquipmentSlot slot, ItemStack stack) {
        if (stack == null || stack.isEmpty() || !applies() || !slotOn(slot)) return stack;
        if (entity != mc.player && !(everyone.get() && entity instanceof PlayerEntity)) return stack;
        if (stack.isOf(Items.ELYTRA)) return stack;

        EquippableComponent eq = stack.get(DataComponentTypes.EQUIPPABLE);
        if (eq == null || eq.slot() != slot) return stack;
        if (!replaceExisting.get() && stack.get(DataComponentTypes.TRIM) != null) return stack;

        ArmorTrim t = trim();
        if (t == null) return stack;
        ItemStack copy = stack.copy();
        copy.set(DataComponentTypes.TRIM, t);
        return copy;
    }

    /** A piece of armor with the picked trim, for the icons in the preview screen. */
    public ItemStack icon(net.minecraft.item.Item item, Pattern p, Material m) {
        ItemStack s = new ItemStack(item);
        ArmorTrim t = trim(p, m);
        if (t != null) s.set(DataComponentTypes.TRIM, t);
        return s;
    }
}
