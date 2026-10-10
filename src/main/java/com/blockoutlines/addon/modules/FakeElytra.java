package com.blockoutlines.addon.modules;

import com.blockoutlines.addon.BlockOutlinesAddon;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

import java.util.regex.Pattern;

/**
 * Purely visual: the item in your hotbar slot is drawn as an elytra (hand, hotbar, inventory, third person)
 * until you turn the module off. Nothing is sent to the server and the real item is not touched.
 * Optionally the "~$ 1.5M" worth line in the tooltip of that item shows a value of your choice.
 */
public class FakeElytra extends Module {
    /** Matches the worth line of the tooltip, for example "~$ 1.5M" or "$ 374,000". */
    public static final Pattern WORTH_LINE = Pattern.compile("^\\s*~?\\s*\\$\\s*[0-9][0-9.,]*\\s*[KkMmBbTt]?\\s*$");

    private static final ItemStack ELYTRA = new ItemStack(Items.ELYTRA);

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> followHand = sgGeneral.add(new BoolSetting.Builder()
        .name("follow-hand")
        .description("Off: the slot you hold when you turn the module on stays the elytra. On: whatever you hold right now looks like an elytra.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> fakeTooltip = sgGeneral.add(new BoolSetting.Builder()
        .name("fake-tooltip")
        .description("Tooltip of that item looks like a real elytra: name Elytra, only Unbreaking III and Mending, no attack stats.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> fakeWorth = sgGeneral.add(new BoolSetting.Builder()
        .name("fake-worth")
        .description("Replaces the worth line (~$ ...) in the tooltip of that item with the value below. Only you see it.")
        .defaultValue(true)
        .build()
    );

    public final Setting<String> worth = sgGeneral.add(new StringSetting.Builder()
        .name("worth")
        .description("Text shown after the $ sign, for example 374M.")
        .defaultValue("374M")
        .visible(fakeWorth::get)
        .build()
    );

    private int lockedSlot;

    public FakeElytra() {
        super(BlockOutlinesAddon.CATEGORY, "fake-elytra", "Shows the item in your hand as an elytra (visual only, client side).");
    }

    @Override
    public void onActivate() {
        lockedSlot = mc.player != null ? mc.player.getInventory().getSelectedSlot() : 0;
    }

    /** True when this exact stack is the one that should look like an elytra. */
    public boolean isTarget(ItemStack stack) {
        if (!isActive() || mc.player == null || stack == null || stack.isEmpty()) return false;
        int slot = followHand.get() ? mc.player.getInventory().getSelectedSlot() : lockedSlot;
        if (slot < 0 || slot >= mc.player.getInventory().size()) return false;
        return stack == mc.player.getInventory().getStack(slot);
    }

    public ItemStack elytra() {
        return ELYTRA;
    }

    public boolean tooltipEnabled() {
        return fakeTooltip.get();
    }

    public boolean worthEnabled() {
        return fakeWorth.get();
    }
}
