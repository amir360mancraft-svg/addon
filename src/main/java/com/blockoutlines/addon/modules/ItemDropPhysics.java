package com.blockoutlines.addon.modules;

import com.blockoutlines.addon.BlockOutlinesAddon;
import meteordevelopment.meteorclient.events.render.ApplyTransformationEvent;
import meteordevelopment.meteorclient.events.render.RenderItemEntityEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.ItemEntity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;

/**
 * Dropped items behave like real objects: they tumble through the air, hit the ground with a little hop and a
 * wobble, then settle (flat items lie down, blocks sit on a face) with a random turn instead of bobbing and
 * spinning forever. Everything is drawn client side; the real item entity is untouched.
 */
public class ItemDropPhysics extends Module {
    private final SettingGroup sg = settings.getDefaultGroup();
    private final SettingGroup sgFeel = settings.createGroup("Feel");

    private final Setting<Double> size = sg.add(new DoubleSetting.Builder()
        .name("size").description("Size of the dropped items (1 = like vanilla).")
        .defaultValue(1.0).range(0.5, 2.5).sliderRange(0.5, 2.0).build());
    private final Setting<Boolean> lieFlat = sg.add(new BoolSetting.Builder()
        .name("lie-flat").description("Flat items (swords, ingots ...) lie on the ground when they land.")
        .defaultValue(true).build());
    private final Setting<Boolean> randomTurn = sg.add(new BoolSetting.Builder()
        .name("random-turn").description("Every item rests turned in its own random direction.")
        .defaultValue(true).build());
    private final Setting<Boolean> floatInWater = sg.add(new BoolSetting.Builder()
        .name("float-in-water").description("Items drift slowly and bob up and down in water.")
        .defaultValue(true).build());
    private final Setting<Boolean> spreadStacks = sg.add(new BoolSetting.Builder()
        .name("spread-stacks").description("Big stacks show several copies lying a little apart.")
        .defaultValue(true).build());

    private final Setting<Integer> airSpin = sgFeel.add(new IntSetting.Builder()
        .name("air-spin").description("How fast an item tumbles while it is in the air (degrees per second).")
        .defaultValue(420).range(0, 1500).sliderRange(0, 1000).build());
    private final Setting<Double> settleSpeed = sgFeel.add(new DoubleSetting.Builder()
        .name("settle-speed").description("How quickly an item turns into its resting pose after landing.")
        .defaultValue(12.0).range(2.0, 40.0).sliderRange(2.0, 30.0).build());
    private final Setting<Boolean> bounce = sgFeel.add(new BoolSetting.Builder()
        .name("bounce").description("A small hop when an item lands (stronger the faster it fell).")
        .defaultValue(true).build());
    private final Setting<Double> bounceHeight = sgFeel.add(new DoubleSetting.Builder()
        .name("bounce-height").description("The biggest hop, in blocks.")
        .defaultValue(0.14).range(0.02, 0.5).sliderRange(0.02, 0.4).visible(bounce::get).build());
    private final Setting<Boolean> wobble = sgFeel.add(new BoolSetting.Builder()
        .name("wobble").description("The item rocks back and forth for a moment after landing.")
        .defaultValue(true).build());

    private static final class Phys {
        float rx, ry, rz;          // rotation in degrees
        float sx, sy, sz;          // spin direction while in the air
        boolean wasAir;
        double lastVy;
        float bounceT = 99f, bounceAmp, wobbleAmp;
        long last;
        float rest;                // 0 = flying, 1 = lying still
        float yaw;                 // the random direction it rests in
    }

    private final Map<Integer, Phys> states = new HashMap<>();
    private boolean skipTransformation;
    private int sweep;

    public ItemDropPhysics() {
        super(BlockOutlinesAddon.CATEGORY, "item-drop-physics",
            "Dropped items tumble, hop, wobble and settle like real objects. Turn Meteor's own item-physics off when you use this.");
    }

    @Override
    public void onDeactivate() {
        states.clear();
        skipTransformation = false;
    }

    @EventHandler
    private void onApplyTransformation(ApplyTransformationEvent event) {
        if (skipTransformation) event.cancel();
    }

    @EventHandler
    private void onRenderItem(RenderItemEntityEvent event) {
        ItemEntity item = event.itemEntity;
        if (item == null || event.renderState.itemRenderState.isEmpty()) return; // let vanilla draw it
        event.cancel();

        long now = System.nanoTime();
        Phys p = states.get(item.getId());
        if (p == null) {
            p = new Phys();
            Random r = new Random(item.getId() * 341873128712L + 132897987541L);
            p.rx = r.nextFloat() * 360f;
            p.ry = r.nextFloat() * 360f;
            p.rz = r.nextFloat() * 360f;
            p.sx = (r.nextFloat() * 2f - 1f);
            p.sy = (r.nextFloat() * 2f - 1f);
            p.sz = (r.nextFloat() * 2f - 1f);
            p.yaw = r.nextFloat() * 360f;
            p.last = now;
            p.wasAir = !item.isOnGround();
            states.put(item.getId(), p);
        }
        float dt = Math.max(0f, Math.min(0.1f, (now - p.last) / 1.0e9f));
        p.last = now;
        if ((++sweep & 255) == 0) sweepOld(now);

        // shape of the model: thin sprite or a solid block
        boolean flat;
        try {
            Box b = event.renderState.itemRenderState.getModelBoundingBox();
            flat = b != null && b.getLengthZ() <= 0.07;
        } catch (Throwable t) {
            flat = false;
        }
        float modelScale = (flat ? 0.5f : 0.25f) * size.get().floatValue();

        Vec3d vel = item.getVelocity();
        boolean water = item.isTouchingWater();
        boolean air = !item.isOnGround() && !water;

        // ---- update the pose
        if (air) {
            double speed = vel.length();
            float rate = airSpin.get() * (float) (0.45 + Math.min(1.0, speed * 2.0));
            p.rx += p.sx * rate * dt;
            p.ry += p.sy * rate * dt;
            p.rz += p.sz * rate * dt;
            p.rest = Math.max(0f, p.rest - dt * 10f);
            p.lastVy = vel.y;
        } else if (water) {
            float rate = airSpin.get() * 0.12f;
            p.rx += p.sx * rate * dt;
            p.ry += p.sy * rate * dt;
            p.rz += p.sz * rate * dt;
            p.rest = Math.max(0f, p.rest - dt * 6f);
        } else {
            if (p.wasAir) { // just landed
                double impact = Math.max(0.0, -p.lastVy);
                if (bounce.get()) {
                    p.bounceAmp = (float) Math.min(bounceHeight.get(), impact * 0.35);
                    p.bounceT = 0f;
                }
                if (wobble.get()) p.wobbleAmp = (float) Math.min(28.0, 6.0 + impact * 40.0);
                p.rx = wrap(p.rx);
                p.rz = wrap(p.rz);
            }
            float k = 1f - (float) Math.exp(-settleSpeed.get() * dt);
            boolean lie = flat && lieFlat.get();
            float baseX = lie ? 90f : 0f, periodX = lie ? 180f : 90f;
            p.rx += (nearest(p.rx, baseX, periodX) - p.rx) * k;
            p.rz += (nearest(p.rz, 0f, lie ? 180f : 90f) - p.rz) * k;
            if (randomTurn.get()) {
                // slow down the turning around the vertical axis and stop in the item's own direction
                p.ry += (nearestToward(p.ry, p.yaw) - p.ry) * k * 0.6f;
            } else {
                p.ry += (nearest(p.ry, 0f, 90f) - p.ry) * k;
            }
            p.rest = Math.min(1f, p.rest + dt * 8f);
        }
        p.wasAir = air || water;
        p.bounceT += dt;
        p.wobbleAmp *= (float) Math.exp(-dt * 5.5);

        // ---- height
        boolean lying = flat && lieFlat.get();
        float yRest = lying ? 0.02f : 0.5f * modelScale;
        float yAir = Math.max(yRest, 0.15f);
        float y = yAir + (yRest - yAir) * p.rest;
        if (bounce.get() && p.bounceT < 2f && p.bounceAmp > 0.002f) {
            y += p.bounceAmp * (float) (Math.exp(-p.bounceT * 5.0) * Math.abs(Math.sin(p.bounceT * 13.0)));
        }
        if (water && floatInWater.get()) {
            y = 0.22f + 0.04f * (float) Math.sin(now / 1.0e9 * 2.2 + item.getId());
        }

        float wob = wobble.get() && !air && !water ? p.wobbleAmp * (float) Math.sin(p.bounceT * 17.0) : 0f;

        // ---- draw
        MatrixStack m = event.matrixStack;
        int copies = Math.max(1, event.renderState.renderedAmount);
        Random layout = new Random(item.getId() * 89748956L);

        skipTransformation = true;
        try {
            for (int i = 0; i < copies; i++) {
                m.push();
                float ox = 0f, oz = 0f, oy = 0f;
                if (i > 0 && spreadStacks.get()) {
                    ox = (layout.nextFloat() * 2f - 1f) * 0.16f;
                    oz = (layout.nextFloat() * 2f - 1f) * 0.16f;
                    oy = i * (lying ? 0.012f : 0f);
                }
                m.translate(ox, y + oy, oz);
                m.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(p.ry + i * 17f));
                m.multiply(RotationAxis.POSITIVE_X.rotationDegrees(p.rx + wob));
                m.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(p.rz));
                m.scale(modelScale, modelScale, modelScale);
                event.renderState.itemRenderState.render(m, event.renderCommandQueue, event.light,
                    OverlayTexture.DEFAULT_UV, event.renderState.outlineColor);
                m.pop();
            }
        } finally {
            skipTransformation = false;
        }
    }

    // ------------------------------------------------------------------ helpers

    private static float wrap(float a) {
        a %= 360f;
        return a < 0 ? a + 360f : a;
    }

    /** The angle closest to {@code a} that equals base + n * period. */
    private static float nearest(float a, float base, float period) {
        float n = Math.round((a - base) / period);
        return base + n * period;
    }

    /** The angle equal to {@code target} (mod 360) that is closest to {@code a}. */
    private static float nearestToward(float a, float target) {
        float n = Math.round((a - target) / 360f);
        return target + n * 360f;
    }

    private void sweepOld(long now) {
        Iterator<Map.Entry<Integer, Phys>> it = states.entrySet().iterator();
        while (it.hasNext()) {
            if (now - it.next().getValue().last > 4_000_000_000L) it.remove();
        }
    }
}
