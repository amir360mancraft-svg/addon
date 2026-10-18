package com.blockoutlines.addon.modules;

import com.blockoutlines.addon.BlockOutlinesAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.RegistryKeys;
import org.lwjgl.glfw.GLFW;

import java.util.Random;

/**
 * Auto Riptide: with a Riptide trident in your hand and rain or water around you, hold right click and the module
 * charges the trident, lets go at the right moment, waits a moment and charges again, so you keep launching forward.
 * The charge time, the gaps and the pauses are slightly different every time so it does not look like a perfect
 * machine. It only presses and releases the use key, it never turns the camera.
 */
public class AutoTrident extends Module {
    private enum St { IDLE, DELAY, CHARGE, RELEASE, COOL }

    private final SettingGroup sg = settings.getDefaultGroup();
    private final SettingGroup sgHuman = settings.createGroup("Randomness");

    private final Setting<Boolean> holdMode = sg.add(new BoolSetting.Builder()
        .name("only-while-holding").description("Works only while you hold right click. Off = it keeps going by itself whenever it can.")
        .defaultValue(true).build());
    private final Setting<Integer> minLevel = sg.add(new IntSetting.Builder()
        .name("min-riptide-level").description("The trident needs at least this Riptide level (protects you from throwing it away).")
        .defaultValue(1).range(1, 3).sliderRange(1, 3).build());
    private final Setting<Boolean> offhand = sg.add(new BoolSetting.Builder()
        .name("offhand").description("Also use a trident held in your off hand (when the main hand is empty).")
        .defaultValue(true).build());
    private final Setting<Boolean> screens = sg.add(new BoolSetting.Builder()
        .name("pause-in-menus").description("Do nothing while a menu or chat is open.")
        .defaultValue(true).build());

    private final Setting<Integer> chargeJitter = sgHuman.add(new IntSetting.Builder()
        .name("charge-jitter").description("Extra ticks of charging beyond the minimum, picked at random 0..this (1 tick = 50 ms).")
        .defaultValue(3).range(0, 8).sliderRange(0, 6).build());
    private final Setting<Integer> coolMin = sgHuman.add(new IntSetting.Builder()
        .name("pause-min").description("Shortest pause between two launches (ticks).")
        .defaultValue(2).range(0, 20).sliderRange(0, 12).build());
    private final Setting<Integer> coolMax = sgHuman.add(new IntSetting.Builder()
        .name("pause-max").description("Longest pause between two launches (ticks).")
        .defaultValue(6).range(0, 30).sliderRange(0, 16).build());
    private final Setting<Integer> startDelay = sgHuman.add(new IntSetting.Builder()
        .name("start-delay").description("Random wait 0..this ticks before the first charge after you press.")
        .defaultValue(3).range(0, 10).sliderRange(0, 8).build());
    private final Setting<Integer> longPauseChance = sgHuman.add(new IntSetting.Builder()
        .name("long-pause-chance").description("Chance in percent for an extra, longer pause now and then (like a human getting distracted).")
        .defaultValue(8).range(0, 50).sliderRange(0, 30).build());
    private final Setting<Integer> longPauseTicks = sgHuman.add(new IntSetting.Builder()
        .name("long-pause-max").description("How long such a pause can be at most (ticks).")
        .defaultValue(14).range(3, 40).sliderRange(4, 30).visible(() -> longPauseChance.get() > 0).build());

    private final Random rng = new Random();
    private St st = St.IDLE;
    private int timer, target;
    private boolean pressing;

    public AutoTrident() {
        super(BlockOutlinesAddon.COMBAT, "auto-trident",
            "Hold right click in rain or water with a Riptide trident and it charges and launches you over and over, with a bit of randomness.");
    }

    @Override
    public void onDeactivate() {
        if (st != St.IDLE && mc.options != null) release();
        st = St.IDLE;
    }

    private boolean physicalHeld() {
        try {
            return GLFW.glfwGetMouseButton(mc.getWindow().getHandle(), GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS;
        } catch (Throwable t) {
            return false;
        }
    }

    private void release() {
        mc.options.useKey.setPressed(false);
        pressing = false;
    }

    private void press() {
        mc.options.useKey.setPressed(true);
        pressing = true;
    }

    private int riptideLevel(ItemStack s) {
        try {
            return mc.world.getRegistryManager().getOrThrow(RegistryKeys.ENCHANTMENT)
                .getOptional(Enchantments.RIPTIDE)
                .map(en -> EnchantmentHelper.getLevel(en, s)).orElse(0);
        } catch (Throwable t) {
            return 0;
        }
    }

    /** True when the hand that would be used holds a good enough Riptide trident. */
    private boolean hasTrident() {
        ItemStack main = mc.player.getMainHandStack();
        if (main.isOf(Items.TRIDENT)) return riptideLevel(main) >= minLevel.get();
        if (offhand.get() && main.isEmpty()) {
            ItemStack off = mc.player.getOffHandStack();
            return off.isOf(Items.TRIDENT) && riptideLevel(off) >= minLevel.get();
        }
        return false;
    }

    private int pause() {
        int lo = Math.min(coolMin.get(), coolMax.get()), hi = Math.max(coolMin.get(), coolMax.get());
        int t = lo + (hi > lo ? rng.nextInt(hi - lo + 1) : 0);
        if (longPauseChance.get() > 0 && rng.nextInt(100) < longPauseChance.get()) t += 3 + rng.nextInt(Math.max(1, longPauseTicks.get() - 2));
        return t;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) { st = St.IDLE; pressing = false; return; }

        boolean wanted = (!holdMode.get() || physicalHeld())
            && !(screens.get() && mc.currentScreen != null)
            && !mc.player.isSpectator()
            && mc.player.isTouchingWaterOrRain()
            && hasTrident();

        if (!wanted) {
            if (st != St.IDLE) {
                // stop quietly; if we were charging, letting go also fires the trident, which is what you wanted anyway
                release();
                st = St.IDLE;
            }
            return;
        }

        switch (st) {
            case IDLE -> {
                // you just pressed: wait a random moment before taking over
                st = St.DELAY;
                timer = startDelay.get() > 0 ? rng.nextInt(startDelay.get() + 1) : 0;
            }
            case DELAY -> {
                if (mc.player.isUsingRiptide()) return;
                if (timer-- > 0) return;
                if (!mc.player.isUsingItem()) press();
                target = 10 + (chargeJitter.get() > 0 ? rng.nextInt(chargeJitter.get() + 1) : 0);
                st = St.CHARGE;
                timer = 0;
            }
            case CHARGE -> {
                if (!pressing && !mc.player.isUsingItem()) { press(); return; }
                timer++;
                if (mc.player.getItemUseTime() >= target || timer > 45) {
                    release(); // letting go launches you
                    st = St.RELEASE;
                    timer = 1 + rng.nextInt(2);
                }
            }
            case RELEASE -> {
                if (timer-- > 0) return;
                st = St.COOL;
                timer = pause();
            }
            case COOL -> {
                // wait until the launch is over (and a short random gap), then start again
                if (mc.player.isUsingRiptide()) return;
                if (timer-- > 0) return;
                st = St.DELAY;
                timer = 0;
            }
        }
    }
}
