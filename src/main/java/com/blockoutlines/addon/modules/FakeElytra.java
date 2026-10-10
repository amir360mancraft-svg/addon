package com.blockoutlines.addon.modules;

import com.blockoutlines.addon.BlockOutlinesAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

import java.util.ArrayList;
import java.util.List;
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

    static {
        ELYTRA.set(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true); // enchanted look: purple glint
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> followHand = sgGeneral.add(new BoolSetting.Builder()
        .name("follow-hand")
        .description("Off: the item you hold when you turn the module on stays an elytra wherever it goes (inventory, ender chest, item frame). On: whatever you hold right now looks like an elytra.")
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

    /** The picked item without count and damage. Matches that item wherever it is, as long as its data stays the same. */
    private ItemStack fingerprint;

    /** Inventory slot (0-40) of the one stack that is disguised, or -1 when the item is outside the inventory. */
    private volatile int trackedSlot = -1;
    private List<Integer> previousMatches = new ArrayList<>();

    public FakeElytra() {
        super(BlockOutlinesAddon.CATEGORY, "fake-elytra", "Shows the item in your hand as an elytra (visual only, client side).");
    }

    @Override
    public void onActivate() {
        fingerprint = null;
        trackedSlot = -1;
        previousMatches = new ArrayList<>();
        if (mc.player == null) return;
        ItemStack held = mc.player.getMainHandStack();
        if (held.isEmpty()) info("Hold the item you want to disguise, then turn the module on again (or turn on follow-hand).");
        else {
            fingerprint = normalize(held);
            trackedSlot = mc.player.getInventory().getSelectedSlot();
            previousMatches.add(trackedSlot);
        }
    }

    @Override
    public void onDeactivate() {
        fingerprint = null;
        trackedSlot = -1;
    }

    /**
     * Items have no unique id, so identical items (two totems) are told apart by position: only the stack in the
     * tracked slot is disguised. When it moves, the slot that newly matches the item becomes the tracked one.
     * While the item is outside the inventory (cursor, ender chest, item frame) every stack that matches is disguised.
     */
    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || fingerprint == null || followHand.get()) return;

        var inv = mc.player.getInventory();
        int n = Math.min(41, inv.size());
        List<Integer> matches = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            ItemStack st = inv.getStack(i);
            if (!st.isEmpty() && st.getItem() == fingerprint.getItem() && ItemStack.areItemsAndComponentsEqual(normalize(st), fingerprint)) matches.add(i);
        }

        int tracked = trackedSlot;
        if (tracked < 0 || !matches.contains(tracked)) {
            tracked = -1;
            for (int i : matches) { // a slot that did not match last tick: that is where the item was moved
                if (!previousMatches.contains(i)) { tracked = i; break; }
            }
            if (tracked < 0 && !matches.isEmpty()) tracked = matches.get(0);
        }
        trackedSlot = tracked;
        previousMatches = matches;
    }

    private static ItemStack normalize(ItemStack stack) {
        ItemStack copy = stack.copy();
        copy.setCount(1);
        copy.remove(DataComponentTypes.DAMAGE); // wearing the item down must not break the match
        return copy;
    }

    /** True when this stack is the picked item (or, with follow-hand, the item in your hand). */
    public boolean isTarget(ItemStack stack) {
        if (!isActive() || mc.player == null || stack == null || stack.isEmpty()) return false;
        if (followHand.get()) return stack == mc.player.getMainHandStack();

        ItemStack fp = fingerprint;
        if (fp == null || stack.getItem() != fp.getItem()) return false; // cheap check first
        if (!ItemStack.areItemsAndComponentsEqual(normalize(stack), fp)) return false;

        int slot = trackedSlot;
        if (slot >= 0 && slot < mc.player.getInventory().size()) return stack == mc.player.getInventory().getStack(slot);
        return true; // the item is outside the inventory
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
