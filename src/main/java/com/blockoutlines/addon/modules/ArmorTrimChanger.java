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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    public static final EquipmentSlot[] SLOTS = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
    public static final String[] SLOT_NAMES = {"Helmet", "Chestplate", "Leggings", "Boots"};

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> replaceExisting = sgGeneral.add(new BoolSetting.Builder()
        .name("replace-existing")
        .description("Also replace a trim that the armor already has. Off = only armor without a trim gets one.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> everyone = sgGeneral.add(new BoolSetting.Builder()
        .name("all-players")
        .description("Also show these trims on other players' armor (only on your screen).")
        .defaultValue(false)
        .build()
    );

    // every piece of armor has its own switch, pattern and material
    private final List<Setting<Boolean>> on = new ArrayList<>();
    private final List<Setting<Pattern>> patterns = new ArrayList<>();
    private final List<Setting<Material>> materials = new ArrayList<>();

    {
        for (int i = 0; i < SLOTS.length; i++) {
            SettingGroup g = settings.createGroup(SLOT_NAMES[i]);
            String n = SLOT_NAMES[i].toLowerCase();
            on.add(g.add(new BoolSetting.Builder().name(n + "-trim").description("Show a trim on the " + n + ".").defaultValue(true).build()));
            patterns.add(g.add(new EnumSetting.Builder<Pattern>().name(n + "-pattern").description("Trim pattern of the " + n + ".").defaultValue(Pattern.Silence).build()));
            materials.add(g.add(new EnumSetting.Builder<Material>().name(n + "-material").description("Trim material (color) of the " + n + ".").defaultValue(Material.Redstone).build()));
        }
    }

    public Setting<Boolean> enabled(int slot) { return on.get(slot); }
    public Setting<Pattern> pattern(int slot) { return patterns.get(slot); }
    public Setting<Material> material(int slot) { return materials.get(slot); }

    /** True while the preview screen is open, so the trim is shown even if the module is switched off. */
    public boolean previewOpen;

    private final Map<Integer, ArmorTrim> cache = new HashMap<>();
    private Object cachedWorld;

    public ArmorTrimChanger() {
        super(BlockOutlinesAddon.COSMETICS, "armor-trim-changer", "Shows any armor trim on your armor (client side only), with a preview screen.");
    }

    @Override
    public WWidget getWidget(GuiTheme theme) {
        WVerticalList list = theme.verticalList();
        WButton open = list.add(theme.button("Open preview (pick a trim for each armor piece)")).expandX().widget();
        open.action = () -> mc.setScreen(new ArmorTrimScreen(mc.currentScreen, this));
        return list;
    }

    public boolean applies() {
        return isActive() || previewOpen;
    }

    private static int slotIndex(EquipmentSlot slot) {
        for (int i = 0; i < SLOTS.length; i++) if (SLOTS[i] == slot) return i;
        return -1;
    }

    public ArmorTrim trim(Pattern p, Material m) {
        if (mc.world == null) return null;
        if (cachedWorld != mc.world) {
            cache.clear();
            cachedWorld = mc.world;
        }
        int key = p.ordinal() * 64 + m.ordinal();
        ArmorTrim hit = cache.get(key);
        if (hit != null) return hit;
        try {
            Optional<RegistryEntry.Reference<ArmorTrimPattern>> pe = mc.world.getRegistryManager().getOrThrow(RegistryKeys.TRIM_PATTERN)
                .getOptional(RegistryKey.of(RegistryKeys.TRIM_PATTERN, Identifier.ofVanilla(p.id())));
            Optional<RegistryEntry.Reference<ArmorTrimMaterial>> me = mc.world.getRegistryManager().getOrThrow(RegistryKeys.TRIM_MATERIAL)
                .getOptional(RegistryKey.of(RegistryKeys.TRIM_MATERIAL, Identifier.ofVanilla(m.id())));
            if (pe.isEmpty() || me.isEmpty()) return null;
            ArmorTrim t = new ArmorTrim(me.get(), pe.get());
            cache.put(key, t);
            return t;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Called from the render mixin for every worn piece. Returns the stack to draw (a copy with the trim) or the same stack. */
    public ItemStack modify(LivingEntity entity, EquipmentSlot slot, ItemStack stack) {
        if (stack == null || stack.isEmpty() || !applies()) return stack;
        int i = slotIndex(slot);
        if (i < 0 || !on.get(i).get()) return stack;
        if (entity != mc.player && !(everyone.get() && entity instanceof PlayerEntity)) return stack;
        if (stack.isOf(Items.ELYTRA)) return stack;

        EquippableComponent eq = stack.get(DataComponentTypes.EQUIPPABLE);
        if (eq == null || eq.slot() != slot) return stack;
        if (!replaceExisting.get() && stack.get(DataComponentTypes.TRIM) != null) return stack;

        ArmorTrim t = trim(patterns.get(i).get(), materials.get(i).get());
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
