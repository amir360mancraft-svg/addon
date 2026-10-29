package com.blockoutlines.addon.modules;

import com.blockoutlines.addon.BlockOutlinesAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Text;

import java.util.HashMap;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A joke for friends. When the two named items of a round drop (one "Dealer N", one "Player M"), the numbers shown
 * on YOUR screen are changed so the dealer's number is always the bigger one. Only the text on your own screen changes:
 * nothing is sent to the server and the real result of the game stays what it is.
 */
public class DealerLuck extends Module {
    private final SettingGroup sg = settings.getDefaultGroup();

    private final Setting<String> dealerName = sg.add(new StringSetting.Builder().name("dealer-name")
        .description("The word in front of the dealer's number in the item name.").defaultValue("Dealer").build());
    private final Setting<String> playerName = sg.add(new StringSetting.Builder().name("player-name")
        .description("The word in front of the player's number in the item name.").defaultValue("Player").build());
    private final Setting<Integer> maxNumber = sg.add(new IntSetting.Builder().name("highest-number").defaultValue(9).range(3, 30).sliderRange(3, 12).build());
    private final Setting<Integer> showSeconds = sg.add(new IntSetting.Builder().name("show-for-seconds")
        .description("How long the changed numbers stay after a round.").defaultValue(30).range(5, 120).sliderRange(10, 60).build());
    private final Setting<Boolean> inScreens = sg.add(new BoolSetting.Builder().name("change-in-menus")
        .description("Also change the numbers in inventories and tooltips. Off = menus always show the real names.").defaultValue(false).build());

    private record Fake(String prefix, int real, int shown) {
    }

    private final List<Fake> fakes = new ArrayList<>();
    private final Setting<Boolean> debug = sg.add(new BoolSetting.Builder().name("debug")
        .description("Prints in chat the names of items that drop and what the module decided. Use it to find out why nothing changes.").defaultValue(false).build());

    private final Random random = new Random();
    private Set<Integer> seen = new HashSet<>();
    private final Map<String, String> map = new HashMap<>();
    private int tick;
    private int expireTick;
    private int dN = -1, dTick, pN = -1, pTick;

    public DealerLuck() {
        super(BlockOutlinesAddon.COSMETICS, "dealer-luck", "A joke: on your screen the dealer's number is always bigger. Only the text on your own screen changes.");
    }

    @Override
    public void onActivate() {
        seen = new HashSet<>();
        map.clear();
        fakes.clear();
        dN = pN = -1;
    }

    @Override
    public void onDeactivate() {
        map.clear();
        fakes.clear();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.world == null) return;
        tick++;
        Pattern dp = Pattern.compile("^" + Pattern.quote(dealerName.get()) + " (\\d+)$");
        Pattern pp = Pattern.compile("^" + Pattern.quote(playerName.get()) + " (\\d+)$");

        Set<Integer> now = new HashSet<>();
        for (Entity e : mc.world.getEntities()) {
            if (!(e instanceof ItemEntity item)) continue;
            now.add(e.getId());
            if (seen.contains(e.getId())) continue;
            String name = item.getStack().getName().getString();
            if (debug.get()) info("item dropped: '" + name + "'");
            Matcher dm = dp.matcher(name);
            if (dm.matches()) {
                dN = Integer.parseInt(dm.group(1));
                dTick = tick;
                continue;
            }
            Matcher pm = pp.matcher(name);
            if (pm.matches()) {
                pN = Integer.parseInt(pm.group(1));
                pTick = tick;
            }
        }
        seen = now;

        // the two items of one round drop at almost the same time
        if (dN > 0 && tick - dTick > 80) dN = -1;
        if (pN > 0 && tick - pTick > 80) pN = -1;
        if (dN > 0 && pN > 0 && Math.abs(dTick - pTick) <= 80) {
            resolve(dN, pN);
            dN = pN = -1;
        }
        if (!map.isEmpty() && tick > expireTick) {
            map.clear();
            fakes.clear();
        }
        if (!fakes.isEmpty() && mc.currentScreen instanceof HandledScreen<?>) fixContainer();
    }

    private void resolve(int d, int p) {
        if (debug.get()) info("round: dealer " + d + ", player " + p);
        map.clear();
        fakes.clear();
        if (d > p) {   // the dealer really won, nothing to change
            if (debug.get()) info("dealer really won, nothing changed");
            return;
        }
        int max = maxNumber.get();
        int d2 = d, p2 = p;
        if (p < max) {
            d2 = p + 1 + random.nextInt(max - p);
        } else {
            p2 = 1 + random.nextInt(max - 1);
            d2 = p2 + 1 + random.nextInt(max - p2);
        }
        map.put(dealerName.get() + " " + d, dealerName.get() + " " + d2);
        map.put(playerName.get() + " " + p, playerName.get() + " " + p2);
        expireTick = tick + showSeconds.get() * 20;
        if (debug.get()) info("showing dealer " + d2 + ", player " + p2);
        if (d2 != d) fakes.add(new Fake(dealerName.get(), d, d2));
        if (p2 != p) fakes.add(new Fake(playerName.get(), p, p2));
    }

    /**
     * Makes an opened dispenser agree with the shown numbers: the item that "was taken" (the shown number) disappears
     * from its slot, and the slot that really is empty is filled with the item of the real number.
     */
    private void fixContainer() {
        if (mc.player == null) return;
        ScreenHandler handler = mc.player.currentScreenHandler;
        if (handler == null || handler.slots.size() < 9) return;
        for (Fake f : fakes) {
            int from = -1;
            int empty = -1, emptyCount = 0;
            for (int i = 0; i < 9; i++) {
                ItemStack st = handler.slots.get(i).getStack();
                if (st.isEmpty()) {
                    empty = i;
                    emptyCount++;
                } else if (st.getName().getString().equals(f.prefix() + " " + f.shown())) {
                    from = i;
                }
            }
            if (from < 0 || emptyCount != 1) continue;
            Slot src = handler.slots.get(from);
            Slot dst = handler.slots.get(empty);
            ItemStack moved = src.getStack().copy();
            moved.set(DataComponentTypes.CUSTOM_NAME, Text.literal(f.prefix() + " " + f.real()));
            src.setStack(ItemStack.EMPTY);
            dst.setStack(moved);
        }
    }

    /** Called for every string drawn on screen (see TextVisitFactoryMixin). */
    public String apply(String text) {
        if (map.isEmpty() || text == null || text.isEmpty()) return text;
        if (!inScreens.get() && mc.currentScreen != null) return text;
        String out = text;
        for (Map.Entry<String, String> en : map.entrySet()) {
            if (!out.contains(en.getKey())) continue;
            out = out.replaceAll("(?<!\\d)" + Pattern.quote(en.getKey()) + "(?!\\d)", Matcher.quoteReplacement(en.getValue()));
        }
        return out;
    }
}
