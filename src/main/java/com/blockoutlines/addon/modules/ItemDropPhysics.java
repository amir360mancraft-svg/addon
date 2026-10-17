package com.blockoutlines.addon.modules;

import com.blockoutlines.addon.BlockOutlinesAddon;
import meteordevelopment.meteorclient.events.render.RenderItemEntityEvent;
import meteordevelopment.meteorclient.mixin.ItemRenderStateAccessor;
import meteordevelopment.meteorclient.mixin.LayerRenderStateAccessor;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.BlockState;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.item.ItemRenderState;
import net.minecraft.client.render.model.json.Transformation;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.ItemEntity;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;
import org.joml.Vector3fc;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;

/**
 * Dropped items behave like real objects: they tumble in the air, hop and wobble when they land, then lie still
 * (flat items lie flat, blocks sit on one of their faces) instead of bobbing and spinning forever.
 *
 * The way an item is placed on the ground (how it is turned flat, how far it is lifted and moved so it rests on top
 * of the block instead of sinking in, how stacks are layered) follows what the ItemPhysic mod does: the normal
 * "ground" display transform of the model stays active and only a few small offsets are added in front of it.
 * Everything is drawn client side; the real item entity is untouched.
 */
public class ItemDropPhysics extends Module {
    private final SettingGroup sg = settings.getDefaultGroup();
    private final SettingGroup sgFeel = settings.createGroup("Feel");

    private final Setting<Double> size = sg.add(new DoubleSetting.Builder()
        .name("size").description("Size of the dropped items (1 = like vanilla).")
        .defaultValue(1.0).range(0.5, 2.5).sliderRange(0.5, 2.0).build());
    private final Setting<Boolean> randomTurn = sg.add(new BoolSetting.Builder()
        .name("random-turn").description("Every item rests turned in its own random direction.")
        .defaultValue(true).build());
    private final Setting<Boolean> sitOnFace = sg.add(new BoolSetting.Builder()
        .name("blocks-sit-on-face").description("Blocks come to rest on one of their faces. Off = they stay at whatever angle they stopped.")
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

    private static final double UNIQUE_OFFSET_SCALE = 0.007957747154594767; // keeps items on the same spot from flickering

    private static final class Phys {
        float pitch;               // tumble angle in degrees (turns the item over its side axis)
        float yaw;                 // the direction it rests in, degrees
        float spinDir = 1f;
        boolean wasAir;
        double lastVy;
        float bounceT = 99f, bounceAmp, wobbleAmp;
        float rest;                // 0 = flying, 1 = lying still
        long last;
    }

    private final Map<Integer, Phys> states = new HashMap<>();
    private int sweep;

    public ItemDropPhysics() {
        super(BlockOutlinesAddon.CATEGORY, "item-drop-physics",
            "Dropped items tumble, hop, wobble and settle like real objects. Turn Meteor's own item-physics off when you use this.");
    }

    @Override
    public void onDeactivate() {
        states.clear();
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
            p.yaw = r.nextFloat() * 360f;
            p.spinDir = r.nextBoolean() ? 1f : -1f;
            p.pitch = r.nextFloat() * 360f;
            p.last = now;
            p.wasAir = !item.isOnGround();
            p.rest = item.isOnGround() ? 1f : 0f;
            if (item.isOnGround()) p.pitch = 0f;
            states.put(item.getId(), p);
        }
        float dt = Math.max(0f, Math.min(0.1f, (now - p.last) / 1.0e9f));
        p.last = now;
        if ((++sweep & 255) == 0) sweepOld(now);

        ItemRenderState irs = event.renderState.itemRenderState;
        boolean block = irs.isSideLit(); // solid 3D models are lit from the side, flat sprites are not

        // size of the "ground" display transform of the model (blocks 0.25, sprites 0.5)
        float sx = 1f, sy = 1f, sz = 1f;
        try {
            ItemRenderStateAccessor acc = (ItemRenderStateAccessor) irs;
            if (acc.meteor$getLayerCount() > 0) {
                Transformation t = ((LayerRenderStateAccessor) acc.meteor$getLayers()[0]).meteor$getTransform();
                Vector3fc s = t.scale();
                sx = s.x(); sy = s.y(); sz = s.z();
            }
        } catch (Throwable ignored) {
            sx = sy = sz = block ? 0.25f : 0.5f;
        }

        Vec3d vel = item.getVelocity();
        boolean water = item.isTouchingWater();
        boolean air = !item.isOnGround() && !water;

        // ---- update the pose
        if (air) {
            double speed = vel.length();
            float rate = airSpin.get() * (float) (0.45 + Math.min(1.0, speed * 2.0));
            p.pitch += p.spinDir * rate * dt;
            p.rest = Math.max(0f, p.rest - dt * 10f);
            p.lastVy = vel.y;
        } else if (water) {
            p.pitch += p.spinDir * airSpin.get() * 0.1f * dt;
            p.rest = Math.max(0f, p.rest - dt * 6f);
        } else {
            if (p.wasAir) { // just landed
                double impact = Math.max(0.0, -p.lastVy);
                if (bounce.get()) {
                    p.bounceAmp = (float) Math.min(bounceHeight.get(), impact * 0.35);
                    p.bounceT = 0f;
                }
                if (wobble.get()) p.wobbleAmp = (float) Math.min(28.0, 6.0 + impact * 40.0);
            }
            float k = 1f - (float) Math.exp(-settleSpeed.get() * dt);
            if (block && !sitOnFace.get()) {
                // keep whatever angle it stopped at
            } else {
                // sprites lie flat (a multiple of 360), blocks come to rest on the nearest face (a multiple of 90)
                float period = block ? 90f : 360f;
                p.pitch += (nearest(p.pitch, period) - p.pitch) * k;
            }
            p.rest = Math.min(1f, p.rest + dt * 8f);
        }
        p.wasAir = air || water;
        p.bounceT += dt;
        p.wobbleAmp *= (float) Math.exp(-dt * 5.5);

        // ---- lift: a little above the ground while flying, a hop on landing, a gentle bob in water
        float lift = 0.12f * (1f - p.rest);
        if (bounce.get() && p.bounceT < 2f && p.bounceAmp > 0.002f) {
            lift += p.bounceAmp * (float) (Math.exp(-p.bounceT * 5.0) * Math.abs(Math.sin(p.bounceT * 13.0)));
        }
        if (water && floatInWater.get()) {
            lift = 0.18f + 0.04f * (float) Math.sin(now / 1.0e9 * 2.2 + item.getId());
        }
        float wob = wobble.get() && !air && !water ? p.wobbleAmp * (float) Math.sin(p.bounceT * 17.0) : 0f;

        // snow / carpets are a bit thicker than the ground, so the item sits higher there
        boolean extraOffset = false;
        if (mc.world != null) {
            try {
                BlockState st = mc.world.getBlockState(item.getBlockPos());
                String path = Registries.BLOCK.getId(st.getBlock()).getPath();
                extraOffset = path.equals("snow") || path.endsWith("carpet");
            } catch (Throwable ignored) {
            }
        }

        // ---- draw
        MatrixStack m = event.matrixStack;
        int count = modelCount(event.renderState.renderedAmount);
        Random layout = new Random(item.getId() * 7919L);
        float unique = event.renderState.uniqueOffset;
        float yaw = randomTurn.get() ? p.yaw : 0f;
        float scale = size.get().floatValue();

        m.push();
        m.translate(0.0, lift, 0.0);
        if (scale != 1f) m.scale(scale, scale, scale);

        // lay the model down, then turn it to its resting direction
        m.multiply(RotationAxis.POSITIVE_X.rotationDegrees(90f));
        m.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(yaw));

        // small moves so the model rests ON the ground
        if (block) m.translate(0.0, -0.2, -0.08);
        else if (extraOffset) m.translate(0.0, 0.0, -0.14 - unique * UNIQUE_OFFSET_SCALE);
        else m.translate(0.0, 0.0, -0.04 - unique * UNIQUE_OFFSET_SCALE);

        // tumble / rest angle (turn over the item's side axis; blocks turn around their own middle)
        if (block) m.translate(0.0, sy, 0.0);
        m.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(p.pitch + wob));
        if (block) m.translate(0.0, -sy, 0.0);

        boolean layered = spreadStacks.get();
        if (!block && layered) m.translate(0.0, 0.0, -0.09375 * (count - 1) * 0.5);

        for (int i = 0; i < count; i++) {
            m.push();
            if (i > 0 && block && layered) {
                m.translate((layout.nextFloat() * 2f - 1f) * sx, (layout.nextFloat() * 2f - 1f) * sy, (layout.nextFloat() * 2f - 1f) * sz);
            }
            irs.render(m, event.renderCommandQueue, event.light, OverlayTexture.DEFAULT_UV, event.renderState.outlineColor);
            m.pop();
            if (!block && layered) m.translate(0.0, 0.0, 0.09375 * sz);
        }
        m.pop();
    }

    // ------------------------------------------------------------------ helpers

    /** How many copies of the item are drawn for a stack of this size. */
    private static int modelCount(int amount) {
        if (amount > 48) return 5;
        if (amount > 32) return 4;
        if (amount > 16) return 3;
        if (amount > 1) return 2;
        return 1;
    }

    /** The multiple of {@code period} that is closest to {@code a}. */
    private static float nearest(float a, float period) {
        return Math.round(a / period) * period;
    }

    private void sweepOld(long now) {
        Iterator<Map.Entry<Integer, Phys>> it = states.entrySet().iterator();
        while (it.hasNext()) {
            if (now - it.next().getValue().last > 4_000_000_000L) it.remove();
        }
    }
}
