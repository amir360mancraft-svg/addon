package com.blockoutlines.addon.modules;

import com.blockoutlines.addon.BlockOutlinesAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;

import java.util.Random;

/**
 * Clicks for you while you are AFK at a mob farm. Nothing is perfectly regular: the time between hits is a bell curve
 * around your setting, the attack waits for the weapon cooldown at a slightly different charge every time, some swings
 * are skipped, now and then it takes a short "break", and the view drifts a tiny bit like a hand on a mouse.
 */
public class AfkClicker extends Module {
    public enum Action { Attack, UseItem }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRandom = settings.createGroup("Human-like");
    private final SettingGroup sgSafety = settings.createGroup("Safety");

    private final Setting<Action> action = sgGeneral.add(new EnumSetting.Builder<Action>()
        .name("action").description("Attack = hit the mob under the crosshair. UseItem = right click (bow, shield, fishing rod...).")
        .defaultValue(Action.Attack).build());
    private final Setting<Boolean> useCooldown = sgGeneral.add(new BoolSetting.Builder()
        .name("wait-for-cooldown").description("Hit only when the weapon is charged (full damage). Off = click on the speed below.")
        .defaultValue(true).visible(() -> action.get() == Action.Attack).build());
    private final Setting<Double> cps = sgGeneral.add(new DoubleSetting.Builder()
        .name("clicks-per-second").description("Average speed when wait-for-cooldown is off (or for UseItem).")
        .defaultValue(6.0).range(0.5, 14.0).sliderRange(1.0, 12.0).build());
    private final Setting<Boolean> onlyTarget = sgGeneral.add(new BoolSetting.Builder()
        .name("only-with-target").description("Click only when the crosshair is on a mob. Off = also swing at nothing.")
        .defaultValue(true).visible(() -> action.get() == Action.Attack).build());
    private final Setting<Boolean> hostileOnly = sgGeneral.add(new BoolSetting.Builder()
        .name("hostile-only").description("Only hit hostile mobs, never animals, villagers or pets.")
        .defaultValue(true).visible(() -> action.get() == Action.Attack).build());
    private final Setting<Boolean> ignorePlayers = sgGeneral.add(new BoolSetting.Builder()
        .name("ignore-players").description("Never hit other players.").defaultValue(true)
        .visible(() -> action.get() == Action.Attack).build());

    // -------- human-like
    private final Setting<Integer> jitter = sgRandom.add(new IntSetting.Builder()
        .name("timing-randomness").description("How much the time between clicks varies (percent).")
        .defaultValue(25).range(0, 80).sliderRange(5, 50).build());
    private final Setting<Double> minCharge = sgRandom.add(new DoubleSetting.Builder()
        .name("min-charge").description("Lowest weapon charge a hit may start at (random between min and max each hit).")
        .defaultValue(0.88).range(0.3, 1.0).sliderRange(0.5, 1.0).visible(useCooldown::get).build());
    private final Setting<Double> maxCharge = sgRandom.add(new DoubleSetting.Builder()
        .name("max-charge").description("Highest weapon charge a hit may wait for.")
        .defaultValue(1.0).range(0.3, 1.0).sliderRange(0.5, 1.0).visible(useCooldown::get).build());
    private final Setting<Integer> reaction = sgRandom.add(new IntSetting.Builder()
        .name("reaction-delay").description("Extra random wait (ms, up to this value) after the weapon is ready, like a slow finger.")
        .defaultValue(90).range(0, 400).sliderRange(0, 250).build());
    private final Setting<Integer> missChance = sgRandom.add(new IntSetting.Builder()
        .name("skip-chance").description("Chance (percent) that a click is skipped.")
        .defaultValue(4).range(0, 30).sliderRange(0, 15).build());
    private final Setting<Boolean> breaks = sgRandom.add(new BoolSetting.Builder()
        .name("random-breaks").description("Now and then stop clicking for a few seconds, like a player who looked away.")
        .defaultValue(true).build());
    private final Setting<Integer> breakEvery = sgRandom.add(new IntSetting.Builder()
        .name("break-every").description("Average seconds between breaks.")
        .defaultValue(60).range(10, 600).sliderRange(20, 240).visible(breaks::get).build());
    private final Setting<Integer> breakLength = sgRandom.add(new IntSetting.Builder()
        .name("break-length").description("Average length of a break (seconds).")
        .defaultValue(3).range(1, 30).sliderRange(1, 12).visible(breaks::get).build());
    private final Setting<Boolean> drift = sgRandom.add(new BoolSetting.Builder()
        .name("view-drift").description("The view moves a tiny bit all the time (stays near where you started, so you keep hitting the same spot).")
        .defaultValue(true).build());
    private final Setting<Double> driftAmount = sgRandom.add(new DoubleSetting.Builder()
        .name("drift-amount").description("How far the view may wander from the start (degrees).")
        .defaultValue(1.2).range(0.1, 5.0).sliderRange(0.2, 3.0).visible(drift::get).build());

    // -------- safety
    private final Setting<Boolean> lowHealthStop = sgSafety.add(new BoolSetting.Builder()
        .name("stop-on-low-health").description("Turn the module off when your health gets low.").defaultValue(true).build());
    private final Setting<Integer> healthLimit = sgSafety.add(new IntSetting.Builder()
        .name("health-limit").description("Stop at or under this many health points (2 = one heart).")
        .defaultValue(8).range(1, 19).sliderRange(2, 14).visible(lowHealthStop::get).build());
    private final Setting<Boolean> pauseInScreens = sgSafety.add(new BoolSetting.Builder()
        .name("pause-in-screens").description("Do nothing while a menu or your inventory is open.").defaultValue(true).build());

    private final Random rnd = new Random();
    private int wait;               // ticks until the next click is allowed
    private double nextCharge;      // charge needed for the next hit
    private long readyAt;           // millis when the reaction delay is over (0 = not started)
    private int breakLeft;          // ticks of break remaining
    private int untilBreak;         // ticks until the next break
    private float baseYaw, basePitch, driftYaw, driftPitch, goalYaw, goalPitch;
    private int goalTimer;
    private boolean haveBase;

    public AfkClicker() {
        super(BlockOutlinesAddon.CATEGORY, "afk-clicker",
            "Clicks for you at a mob farm with human-like random timing, breaks and view drift. Use only where it is allowed.");
    }

    @Override
    public void onActivate() {
        wait = 0;
        readyAt = 0;
        breakLeft = 0;
        nextCharge = pickCharge();
        untilBreak = nextBreak();
        haveBase = false;
        driftYaw = driftPitch = goalYaw = goalPitch = 0;
        goalTimer = 0;
    }

    private double pickCharge() {
        double lo = Math.min(minCharge.get(), maxCharge.get()), hi = Math.max(minCharge.get(), maxCharge.get());
        return lo + rnd.nextDouble() * (hi - lo);
    }

    private int nextBreak() {
        double s = breakEvery.get() * (0.5 + rnd.nextDouble());
        return (int) (s * 20);
    }

    /** A bell-curve wait in ticks around {@code mean} ticks. */
    private int gaussTicks(double mean) {
        double spread = mean * jitter.get() / 100.0;
        double v = mean + rnd.nextGaussian() * spread * 0.6;
        v = Math.max(mean - spread, Math.min(mean + spread, v));
        return Math.max(1, (int) Math.round(v));
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return;

        if (lowHealthStop.get() && mc.player.getHealth() <= healthLimit.get()) {
            info("Health is low, stopped.");
            toggle();
            return;
        }

        if (drift.get()) updateDrift();

        if (pauseInScreens.get() && mc.currentScreen != null) return;

        if (breaks.get()) {
            if (breakLeft > 0) { breakLeft--; return; }
            if (--untilBreak <= 0) {
                breakLeft = (int) (breakLength.get() * (0.5 + rnd.nextDouble()) * 20);
                untilBreak = nextBreak();
                readyAt = 0;
                return;
            }
        }

        if (wait > 0) { wait--; return; }

        if (action.get() == Action.UseItem) {
            mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
            mc.player.swingHand(Hand.MAIN_HAND);
            wait = gaussTicks(20.0 / cps.get());
            return;
        }

        // ---- attack
        Entity target = null;
        if (mc.crosshairTarget != null && mc.crosshairTarget.getType() == HitResult.Type.ENTITY) {
            target = ((EntityHitResult) mc.crosshairTarget).getEntity();
        }
        if (target != null && !valid(target)) target = null;
        if (target == null && onlyTarget.get()) { readyAt = 0; return; }

        if (useCooldown.get()) {
            if (mc.player.getAttackCooldownProgress(0f) < nextCharge) return;
            // the weapon is ready: wait a human reaction time first
            long now = System.currentTimeMillis();
            if (readyAt == 0) readyAt = now + (long) (Math.abs(rnd.nextGaussian()) * reaction.get() * 0.6);
            if (now < readyAt) return;
        }
        readyAt = 0;

        if (rnd.nextInt(100) < missChance.get()) {
            wait = 1 + rnd.nextInt(4);
            return;
        }

        if (target != null) mc.interactionManager.attackEntity(mc.player, target);
        mc.player.swingHand(Hand.MAIN_HAND);

        nextCharge = pickCharge();
        wait = useCooldown.get() ? rnd.nextInt(3) : gaussTicks(20.0 / cps.get());
    }

    private boolean valid(Entity e) {
        if (!(e instanceof LivingEntity le) || !le.isAlive()) return false;
        if (e == mc.player) return false;
        if (ignorePlayers.get() && e instanceof PlayerEntity) return false;
        if (hostileOnly.get() && !(e instanceof Monster)) return false;
        return true;
    }

    /** The view wanders a little around where it was when the module started. */
    private void updateDrift() {
        if (!haveBase) {
            baseYaw = mc.player.getYaw();
            basePitch = mc.player.getPitch();
            driftYaw = driftPitch = 0;
            haveBase = true;
        }
        // if the player moved the view by hand, start over from the new place
        if (Math.abs(mc.player.getYaw() - (baseYaw + driftYaw)) > 4 || Math.abs(mc.player.getPitch() - (basePitch + driftPitch)) > 4) {
            baseYaw = mc.player.getYaw();
            basePitch = mc.player.getPitch();
            driftYaw = driftPitch = goalYaw = goalPitch = 0;
        }
        if (--goalTimer <= 0) {
            double a = driftAmount.get();
            goalYaw = (float) ((rnd.nextDouble() * 2 - 1) * a);
            goalPitch = (float) ((rnd.nextDouble() * 2 - 1) * a * 0.5);
            goalTimer = 25 + rnd.nextInt(90);
        }
        driftYaw += (goalYaw - driftYaw) * 0.08f;
        driftPitch += (goalPitch - driftPitch) * 0.08f;
        mc.player.setYaw(baseYaw + driftYaw);
        mc.player.setPitch(Math.max(-90f, Math.min(90f, basePitch + driftPitch)));
    }

    @Override
    public String getInfoString() {
        return breakLeft > 0 ? "break" : null;
    }
}
