package com.blockoutlines.addon.modules;

import com.blockoutlines.addon.BlockOutlinesAddon;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Purely visual: one item is drawn as an elytra (hand, hotbar, inventory, containers, item frames, third person)
 * until you turn the module off. Nothing is sent to the server and the real item is not touched.
 * Optionally the "~$ 1.5M" worth line in the tooltip of that item shows a value of your choice.
 *
 * Items have no unique id in Minecraft, so the picked item is followed by where it is:
 * a slot of your inventory, the mouse cursor, a slot of a container (ender chest...), or an item frame.
 * If it cannot tell where the item is (for example it was dropped), nothing is disguised until the item is found again,
 * so a wrong item never shows up as an elytra.
 */
public class FakeElytra extends Module {
    /** Matches the worth line of the tooltip, for example "~$ 1.5M" or "$ 374,000". */
    public static final Pattern WORTH_LINE = Pattern.compile("^\\s*~?\\s*\\$\\s*[0-9][0-9.,]*\\s*[KkMmBbTt]?\\s*$");

    private static final ItemStack ELYTRA = new ItemStack(Items.ELYTRA);

    static {
        ELYTRA.set(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true); // enchanted look: purple glint
    }

    private enum Where { SLOT, CURSOR, OUTSIDE }

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

    private final Setting<Boolean> debug = sgGeneral.add(new BoolSetting.Builder()
        .name("debug")
        .description("Prints in chat where the module thinks the item is (inventory slot, cursor, container, frame). Turn off before streaming.")
        .defaultValue(true)
        .build()
    );

    private String lastState = "";

    /** The picked item without count and damage. */
    private ItemStack fingerprint;

    private Where where = Where.OUTSIDE;
    private int slot = -1;                 // inventory slot (0-40) when where == SLOT
    private int frameId = -1;              // item frame entity id, when the item sits in a frame
    private int containerIdx = -1;         // slot index inside an open container screen (ender chest...)
    private String containerTitle = "";
    private int pending;                   // ticks left to find out where the item was put

    private List<Integer> prevInv = new ArrayList<>();
    private List<Integer> prevContainer = new ArrayList<>();
    private List<Integer> prevFrames = new ArrayList<>();
    /** What the container and the frames looked like just before the item left the inventory: whatever is new is the item. */
    private List<Integer> baseContainer = new ArrayList<>();

    /** Frames that currently hold the item, with the tick they were first seen holding it (the server tells us late). */
    private final Map<Integer, Integer> frameSeen = new HashMap<>();
    private int tickCount;
    private boolean firstFrameScan;

    public FakeElytra() {
        super(BlockOutlinesAddon.CATEGORY, "fake-elytra", "Shows the item in your hand as an elytra (visual only, client side).");
    }

    @Override
    public void onActivate() {
        fingerprint = null;
        resetTracking();
        if (mc.player == null) return;
        ItemStack held = mc.player.getMainHandStack();
        if (held.isEmpty()) {
            info("Hold the item you want to disguise, then turn the module on again (or turn on follow-hand).");
            return;
        }
        fingerprint = normalize(held);
        where = Where.SLOT;
        slot = mc.player.getInventory().getSelectedSlot();
        prevInv.add(slot);
    }

    @Override
    public void onDeactivate() {
        fingerprint = null;
        resetTracking();
    }

    private void resetTracking() {
        where = Where.OUTSIDE;
        slot = -1;
        clearMemory();
        pending = 0;
        prevInv = new ArrayList<>();
        prevContainer = new ArrayList<>();
        prevFrames = new ArrayList<>();
        baseContainer = new ArrayList<>();
        frameSeen.clear();
        firstFrameScan = true;
    }

    private void clearMemory() {
        frameId = -1;
        containerIdx = -1;
        containerTitle = "";
    }

    private static ItemStack normalize(ItemStack stack) {
        ItemStack copy = stack.copy();
        copy.setCount(1);
        copy.remove(DataComponentTypes.DAMAGE); // wearing the item down must not break the match
        return copy;
    }

    private boolean matches(ItemStack st) {
        ItemStack fp = fingerprint;
        return fp != null && st != null && !st.isEmpty() && st.getItem() == fp.getItem()
            && ItemStack.areItemsAndComponentsEqual(normalize(st), fp);
    }

    private static int firstNew(List<Integer> now, List<Integer> before) {
        for (int v : now) if (!before.contains(v)) return v;
        return -1;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        tickCount++;
        update(true);
    }

    /** Also runs every frame, before the screen is drawn, so a moved item never shows its real look for a frame. */
    @EventHandler
    private void onRender2D(Render2DEvent event) {
        update(false);
    }

    private void update(boolean tick) {
        if (mc.player == null || mc.world == null || fingerprint == null || followHand.get()) return;

        var inv = mc.player.getInventory();
        ScreenHandler handler = mc.player.currentScreenHandler;

        List<Integer> invM = new ArrayList<>();
        int n = Math.min(41, inv.size());
        for (int i = 0; i < n; i++) if (matches(inv.getStack(i))) invM.add(i);

        ItemStack cursorStack = handler.getCursorStack();
        boolean cursor = matches(cursorStack);
        // same item type on the cursor, even if some data differs: good enough while the item just left its slot
        boolean cursorLoose = cursor || (!cursorStack.isEmpty() && cursorStack.getItem() == fingerprint.getItem());

        List<Integer> contM = new ArrayList<>();
        String title = "";
        if (mc.currentScreen != null) {
            title = mc.currentScreen.getTitle().getString();
            for (int i = 0; i < handler.slots.size(); i++) {
                Slot s = handler.slots.get(i);
                if (s.inventory != inv && matches(s.getStack())) contM.add(i);
            }
        }

        List<Integer> frameM = new ArrayList<>();
        for (Entity e : mc.world.getEntities()) {
            if (e instanceof ItemFrameEntity f && e.squaredDistanceTo(mc.player) < 144 && matches(f.getHeldItemStack())) frameM.add(e.getId());
        }

        for (int id : frameM) frameSeen.putIfAbsent(id, firstFrameScan ? -1000 : tickCount);
        frameSeen.keySet().removeIf(id -> !frameM.contains(id));
        firstFrameScan = false;

        switch (where) {
            case SLOT -> {
                if (!invM.contains(slot)) {
                    int moved = firstNew(invM, prevInv);
                    if (cursorLoose) where = Where.CURSOR;                  // picked up with the mouse
                    else if (moved >= 0) slot = moved;                      // hotkey swap or shift click inside the inventory
                    else lose(contM, frameM, title);                        // gone: container, item frame, dropped
                }
            }
            case CURSOR -> {
                if (!cursorLoose) {
                    int placed = firstNew(invM, prevInv);
                    if (placed >= 0) { where = Where.SLOT; slot = placed; }
                    else lose(contM, frameM, title);
                }
            }
            case OUTSIDE -> {
                int back = firstNew(invM, prevInv);
                boolean stillThere = (containerIdx >= 0 && contM.contains(containerIdx)) || (frameId >= 0 && frameM.contains(frameId));
                if (back >= 0) { where = Where.SLOT; slot = back; clearMemory(); }
                else if (cursor && !stillThere) { where = Where.CURSOR; clearMemory(); } // taken out with the mouse
                else if (containerIdx >= 0) {
                    if (mc.currentScreen != null && title.equals(containerTitle) && !contM.contains(containerIdx)) {
                        int moved = firstNew(contM, prevContainer); // moved to another slot of the same container
                        if (moved >= 0) containerIdx = moved;
                    }
                } else if (frameId < 0) {
                    if (pending > 0) {
                        if (tick) pending--;
                        tryBind(contM, frameM, title);
                    } else if (invM.isEmpty() && !cursor) {
                        // searched long enough: bind only if exactly one matching stack is visible
                        if (mc.currentScreen != null && contM.size() == 1) { containerIdx = contM.get(0); containerTitle = title; }
                        else if (mc.currentScreen == null && frameM.size() == 1 && contM.isEmpty()) frameId = frameM.get(0);
                    }
                }
            }
        }

        prevInv = invM;
        prevContainer = contM;
        prevFrames = frameM;

        if (debug.get()) {
            String state = describe();
            if (!state.equals(lastState)) {
                info("elytra: " + state);
                lastState = state;
            }
        }
    }

    /** The item just left the inventory: remember what the surroundings looked like before, and look right away where it went. */
    private void lose(List<Integer> contM, List<Integer> frameM, String title) {
        where = Where.OUTSIDE;
        clearMemory();
        pending = 100;
        baseContainer = new ArrayList<>(prevContainer);
        tryBind(contM, frameM, title);
    }

    private void tryBind(List<Integer> contM, List<Integer> frameM, String title) {
        int inContainer = firstNew(contM, baseContainer);
        if (mc.currentScreen != null && inContainer >= 0) { containerIdx = inContainer; containerTitle = title; return; }

        // item frame: the one that started holding the item most recently (within 3 seconds)
        int best = -1, bestSeen = -1;
        for (Map.Entry<Integer, Integer> en : frameSeen.entrySet()) {
            int seen = en.getValue();
            if (seen > -1000 && tickCount - seen <= 60 && seen >= bestSeen) { best = en.getKey(); bestSeen = seen; }
        }
        if (mc.currentScreen == null && best >= 0) frameId = best;
    }

    private String describe() {
        return switch (where) {
            case SLOT -> "inventory slot " + slot;
            case CURSOR -> "on the mouse cursor";
            case OUTSIDE -> frameId >= 0 ? "item frame #" + frameId
                : containerIdx >= 0 ? "container '" + containerTitle + "' slot " + containerIdx
                : pending > 0 ? "searching" : "unknown (nothing disguised)";
        };
    }

    private boolean inAnySlot(ItemStack stack) {
        var inv = mc.player.getInventory();
        for (Slot sl : mc.player.currentScreenHandler.slots) if (sl.getStack() == stack) return true;
        for (int i = 0; i < Math.min(41, inv.size()); i++) if (inv.getStack(i) == stack) return true;
        return false;
    }

    /** True when this stack is the picked item (or, with follow-hand, the item in your hand). */
    public boolean isTarget(ItemStack stack) {
        if (!isActive() || mc.player == null || stack == null || stack.isEmpty()) return false;
        if (followHand.get()) return stack == mc.player.getMainHandStack();

        ItemStack fp = fingerprint;
        if (fp == null || stack.getItem() != fp.getItem()) return false; // cheap check first
        if (!ItemStack.areItemsAndComponentsEqual(normalize(stack), fp)) return false;

        switch (where) {
            case SLOT:
                return slot >= 0 && slot < mc.player.getInventory().size() && stack == mc.player.getInventory().getStack(slot);
            case CURSOR: {
                ItemStack c = mc.player.currentScreenHandler.getCursorStack();
                return stack == c || (ItemStack.areItemsAndComponentsEqual(stack, c) && !inAnySlot(stack));
            }
            default:
                if (frameId >= 0) {
                    return mc.world != null && mc.world.getEntityById(frameId) instanceof ItemFrameEntity f
                        && (stack == f.getHeldItemStack() || (ItemStack.areItemsAndComponentsEqual(stack, f.getHeldItemStack()) && !inAnySlot(stack)));
                }
                if (containerIdx >= 0) {
                    ScreenHandler h = mc.player.currentScreenHandler;
                    return mc.currentScreen != null && containerTitle.equals(mc.currentScreen.getTitle().getString())
                        && containerIdx < h.slots.size() && stack == h.slots.get(containerIdx).getStack();
                }
                return false; // location unknown: disguise nothing (never the wrong item) until the item is found again
        }
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
