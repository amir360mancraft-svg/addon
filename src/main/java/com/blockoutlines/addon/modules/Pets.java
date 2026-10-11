package com.blockoutlines.addon.modules;

import com.blockoutlines.addon.BlockOutlinesAddon;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.passive.AllayEntity;
import net.minecraft.entity.passive.BeeEntity;
import net.minecraft.entity.passive.PassiveEntity;
import net.minecraft.entity.passive.TurtleEntity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;

/**
 * Pets that follow you: a bee circling your head, a turtle behind you and an allay (the blue spirit) at your
 * shoulder. They exist only on YOUR screen: nothing is sent to the server, they cannot be hit, pushed or
 * targeted, and they disappear when you leave the world or turn the module off.
 */
public class Pets extends Module {
    private enum Kind { BEE, TURTLE, ALLAY }

    private static final class Pet {
        final Kind kind;
        final Entity entity;
        double x, y, z;
        float yaw;

        Pet(Kind kind, Entity entity) {
            this.kind = kind;
            this.entity = entity;
            this.x = entity.getX();
            this.y = entity.getY();
            this.z = entity.getZ();
        }
    }

    // canHit() = false so the crosshair never targets them (the server does not know these entities).
    private static final class PetBee extends BeeEntity {
        PetBee(EntityType<? extends BeeEntity> type, World world) { super(type, world); }
        @Override public boolean canHit() { return false; }
        @Override public boolean isPushable() { return false; }
    }

    private static final class PetTurtle extends TurtleEntity {
        PetTurtle(EntityType<? extends TurtleEntity> type, World world) { super(type, world); }
        @Override public boolean canHit() { return false; }
        @Override public boolean isPushable() { return false; }
    }

    private static final class PetAllay extends AllayEntity {
        PetAllay(EntityType<? extends AllayEntity> type, World world) { super(type, world); }
        @Override public boolean canHit() { return false; }
        @Override public boolean isPushable() { return false; }
    }

    private final SettingGroup sg = settings.getDefaultGroup();

    private final Setting<Boolean> bee = sg.add(new BoolSetting.Builder()
        .name("bee").description("A bee that circles above your head.").defaultValue(true).build());
    private final Setting<Boolean> turtle = sg.add(new BoolSetting.Builder()
        .name("turtle").description("A turtle that follows you on the ground.").defaultValue(true).build());
    private final Setting<Boolean> allay = sg.add(new BoolSetting.Builder()
        .name("allay").description("The blue spirit hovering at your shoulder.").defaultValue(true).build());
    private final Setting<Boolean> baby = sg.add(new BoolSetting.Builder()
        .name("baby").description("Baby bee and baby turtle (the allay has no baby form).").defaultValue(false).build());

    private final Setting<Double> distance = sg.add(new DoubleSetting.Builder()
        .name("distance").description("How far the pets stay from you.")
        .defaultValue(2.0).min(1.0).max(6.0).sliderMin(1.0).sliderMax(4.0).build());

    private final Setting<Double> smoothing = sg.add(new DoubleSetting.Builder()
        .name("follow-speed").description("How quickly the pets catch up with you.")
        .defaultValue(0.18).min(0.04).max(0.6).sliderMin(0.05).sliderMax(0.4).build());

    private final List<Pet> pets = new ArrayList<>();
    private ClientWorld world;
    private int ticks;
    private int nextId = 2_000_000_000;
    private double groundY;

    public Pets() {
        super(BlockOutlinesAddon.COSMETICS, "pets", "Pets that follow you: bee, turtle and allay (visual only).");
    }

    @Override
    public void onActivate() {
        pets.clear();
        world = null;
        if (mc.player != null) groundY = mc.player.getY();
    }

    @Override
    public void onDeactivate() {
        if (mc.world != null && mc.world == world) {
            for (Pet pet : pets) mc.world.removeEntity(pet.entity.getId(), Entity.RemovalReason.DISCARDED);
        }
        pets.clear();
        world = null;
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        pets.clear();
        world = null;
    }

    private boolean wanted(Kind k) {
        return switch (k) {
            case BEE -> bee.get();
            case TURTLE -> turtle.get();
            case ALLAY -> allay.get();
        };
    }

    private Pet spawn(Kind kind) {
        Entity e = switch (kind) {
            case BEE -> new PetBee(EntityType.BEE, mc.world);
            case TURTLE -> new PetTurtle(EntityType.TURTLE, mc.world);
            case ALLAY -> new PetAllay(EntityType.ALLAY, mc.world);
        };
        e.setId(nextId--);
        e.setPosition(mc.player.getX(), mc.player.getY(), mc.player.getZ());
        e.setNoGravity(true);
        e.setSilent(true);
        e.setInvulnerable(true);
        if (baby.get() && e instanceof PassiveEntity pe) pe.setBaby(true);
        mc.world.addEntity(e);
        return new Pet(kind, e);
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null || mc.player == null) return;

        if (mc.world != world) { // new world or dimension: old entities are gone
            pets.clear();
            world = mc.world;
            groundY = mc.player.getY();
        }
        ticks++;

        for (int i = pets.size() - 1; i >= 0; i--) {
            Pet p = pets.get(i);
            boolean isBaby = p.entity instanceof LivingEntity le && le.isBaby();
            boolean mismatch = p.kind != Kind.ALLAY && isBaby != baby.get();
            if (!wanted(p.kind) || mismatch || p.entity.isRemoved()) {
                mc.world.removeEntity(p.entity.getId(), Entity.RemovalReason.DISCARDED);
                pets.remove(i);
            }
        }
        for (Kind k : Kind.values()) {
            if (!wanted(k)) continue;
            boolean have = false;
            for (Pet p : pets) if (p.kind == k) { have = true; break; }
            if (!have) pets.add(spawn(k));
        }

        int index = 0;
        for (Pet pet : pets) update(pet, index++);
    }

    private void update(Pet pet, int index) {
        double px = mc.player.getX(), py = mc.player.getY(), pz = mc.player.getZ();
        double yawRad = Math.toRadians(mc.player.getYaw());
        double fx = -Math.sin(yawRad), fz = Math.cos(yawRad);
        double rx = -Math.cos(yawRad), rz = -Math.sin(yawRad);
        double d = distance.get();

        double tx, ty, tz;
        switch (pet.kind) {
            case BEE -> {
                double a = ticks * 0.07 + index;
                tx = px + Math.cos(a) * d * 0.5;
                tz = pz + Math.sin(a) * d * 0.5;
                ty = py + mc.player.getHeight() + 0.35 + Math.sin(ticks * 0.12) * 0.15;
            }
            case ALLAY -> {
                tx = px + rx * 0.85 - fx * 0.2;
                tz = pz + rz * 0.85 - fz * 0.2;
                ty = py + 1.75 + Math.sin(ticks * 0.08) * 0.12;
            }
            default -> {
                tx = px - fx * d * 0.8 - rx * 0.9;
                tz = pz - fz * d * 0.8 - rz * 0.9;
                if (mc.player.isOnGround()) groundY = py;
                ty = groundY;
            }
        }

        double dx = tx - pet.x, dy = ty - pet.y, dz = tz - pet.z;
        double nx, ny, nz;
        if (Math.sqrt(dx * dx + dy * dy + dz * dz) > 24) { // teleported: snap next to you
            nx = tx; ny = ty; nz = tz;
        } else {
            double k = smoothing.get();
            nx = pet.x + dx * k;
            nz = pet.z + dz * k;
            ny = pet.y + dy * (pet.kind == Kind.TURTLE ? 0.5 : k);
        }

        double mx = nx - pet.x, mz = nz - pet.z;
        float targetYaw = (mx * mx + mz * mz > 0.0002) ? (float) Math.toDegrees(Math.atan2(-mx, mz)) : mc.player.getYaw();
        pet.yaw = MathHelper.lerpAngleDegrees(0.25f, pet.yaw, targetYaw);

        Entity e = pet.entity;
        e.lastX = e.getX(); e.lastY = e.getY(); e.lastZ = e.getZ();
        e.setPosition(nx, ny, nz);
        e.lastYaw = e.getYaw();
        e.setYaw(pet.yaw);
        e.setPitch(0);
        e.setOnGround(pet.kind == Kind.TURTLE);
        if (e instanceof LivingEntity le) {
            le.lastBodyYaw = le.bodyYaw;
            le.bodyYaw = pet.yaw;
            le.lastHeadYaw = le.headYaw;
            le.headYaw = pet.yaw;
        }
        pet.x = nx; pet.y = ny; pet.z = nz;
    }
}
