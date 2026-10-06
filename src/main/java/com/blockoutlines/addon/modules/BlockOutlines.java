package com.blockoutlines.addon.modules;

import com.blockoutlines.addon.util.SvgIcon;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.BlockUpdateEvent;
import meteordevelopment.meteorclient.renderer.MeshBuilder;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.BlockState;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.shape.VoxelShape;

/**
 * Smooth, coloured outline around the block you look at. When that block breaks, a black and white
 * icon (assets/blockoutlines/icon.svg) pops up on top of it and spins for a moment.
 */
public class BlockOutlines extends Module {
    private static final float POP_SECONDS = 0.6f;
    private static final double SPIN_RAD_PER_SEC = 3.0;
    private static final double SNAP_DISTANCE_SQ = 1600.0; // jump instead of gliding when the target is > 40 blocks away

    private final SettingGroup sgRender = settings.createGroup("Render");

    public final Setting<SettingColor> color = sgRender.add(new ColorSetting.Builder()
        .name("color")
        .description("The color of the block outline.")
        .defaultValue(new SettingColor(255, 60, 60, 255))
        .build()
    );

    public final Setting<Double> alpha = sgRender.add(new DoubleSetting.Builder()
        .name("alpha")
        .description("The opacity of the block outline.")
        .defaultValue(1.0)
        .range(0.1, 1.0)
        .sliderRange(0.1, 1.0)
        .build()
    );

    public final Setting<Boolean> smooth = sgRender.add(new BoolSetting.Builder()
        .name("smooth")
        .description("Smoothly animates the outline when moving between blocks.")
        .defaultValue(true)
        .build()
    );

    public final Setting<Integer> speed = sgRender.add(new IntSetting.Builder()
        .name("speed")
        .description("How fast the outline catches up to the targeted block.")
        .defaultValue(15)
        .range(2, 30)
        .sliderRange(2, 30)
        .build()
    );

    private final SvgIcon icon = SvgIcon.load("/assets/blockoutlines/icon.svg");

    // current (animated) outline box
    private double minX, minY, minZ, maxX, maxY, maxZ;
    private boolean hasTarget;
    private int targetX, targetY, targetZ;

    // break pop
    private float popTimer;
    private double popX, popY, popZ, spin;

    public BlockOutlines() {
        super(Categories.Render, "block-outlines", "Renders a smooth, custom-colored outline around the block you are looking at.");
    }

    @Override
    public void onDeactivate() {
        hasTarget = false;
        popTimer = 0;
    }

    @EventHandler
    private void onBlockUpdate(BlockUpdateEvent event) {
        if (popTimer > 0 || !hasTarget) return;
        if (event.newState.isAir()
            && event.pos.getX() == targetX && event.pos.getY() == targetY && event.pos.getZ() == targetZ) {
            popTimer = POP_SECONDS;
            popX = (minX + maxX) / 2.0;
            popY = maxY + 0.005;
            popZ = (minZ + maxZ) / 2.0;
        }
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.world == null || mc.player == null) {
            hasTarget = false;
            return;
        }

        double dt = event.frameTime;
        if (!(dt > 0) || dt > 0.1) dt = 0.016;

        if (mc.crosshairTarget instanceof BlockHitResult hit && hit.getType() != HitResult.Type.MISS) {
            BlockPos pos = hit.getBlockPos();
            targetX = pos.getX();
            targetY = pos.getY();
            targetZ = pos.getZ();

            BlockState state = mc.world.getBlockState(pos);
            VoxelShape shape = state.getOutlineShape(mc.world, pos);
            Box b = shape.isEmpty() ? new Box(0, 0, 0, 1, 1, 1) : shape.getBoundingBox();

            double tMinX = pos.getX() + b.minX, tMinY = pos.getY() + b.minY, tMinZ = pos.getZ() + b.minZ;
            double tMaxX = pos.getX() + b.maxX, tMaxY = pos.getY() + b.maxY, tMaxZ = pos.getZ() + b.maxZ;

            if (!hasTarget || !smooth.get()) {
                set(tMinX, tMinY, tMinZ, tMaxX, tMaxY, tMaxZ);
            } else {
                double dx = minX - tMinX, dy = minY - tMinY, dz = minZ - tMinZ;
                if (dx * dx + dy * dy + dz * dz > SNAP_DISTANCE_SQ) {
                    set(tMinX, tMinY, tMinZ, tMaxX, tMaxY, tMaxZ);
                } else {
                    double t = 1.0 - Math.exp(-speed.get() * dt);
                    minX += (tMinX - minX) * t;
                    minY += (tMinY - minY) * t;
                    minZ += (tMinZ - minZ) * t;
                    maxX += (tMaxX - maxX) * t;
                    maxY += (tMaxY - maxY) * t;
                    maxZ += (tMaxZ - maxZ) * t;
                }
            }
            hasTarget = true;

            SettingColor c = color.get();
            int a = Math.max(0, Math.min(255, (int) Math.round(alpha.get() * 255.0)));
            event.renderer.boxLines(minX, minY, minZ, maxX, maxY, maxZ, new Color(c.r, c.g, c.b, a), 0);
        } else {
            hasTarget = false;
        }

        if (popTimer > 0) {
            popTimer = Math.max(0, popTimer - (float) dt);
            double p = 1.0 - popTimer / POP_SECONDS;      // 0 -> 1 over the pop
            double envelope = Math.sin(p * Math.PI);       // grows, then fades out
            spin += dt * SPIN_RAD_PER_SEC;
            drawIcon(event, popX, popY, popZ, 0.2 + 0.4 * envelope, spin - Math.PI / 2.0, (int) (255 * envelope));
        }
    }

    private void set(double x1, double y1, double z1, double x2, double y2, double z2) {
        minX = x1; minY = y1; minZ = z1;
        maxX = x2; maxY = y2; maxZ = z2;
    }

    /** Draws the SVG shapes flat (facing up) around (cx, y, cz). Both windings are emitted so culling never hides them. */
    private void drawIcon(Render3DEvent event, double cx, double y, double cz, double radius, double rotation, int alpha) {
        if (alpha <= 0) return;
        MeshBuilder mesh = event.renderer.triangles;
        double cos = Math.cos(rotation), sin = Math.sin(rotation);

        double layerY = y;
        for (SvgIcon.Layer layer : icon.layers) {
            Color c = layer.white ? new Color(255, 255, 255, alpha) : new Color(0, 0, 0, alpha);
            int[] idx = new int[layer.u.length];
            for (int i = 0; i < idx.length; i++) {
                double x = cx + radius * (layer.u[i] * cos - layer.v[i] * sin);
                double z = cz + radius * (layer.u[i] * sin + layer.v[i] * cos);
                idx[i] = mesh.vec3(x, layerY, z).color(c).next();
            }
            for (int t = 0; t < layer.tris.length; t += 3) {
                mesh.triangle(idx[layer.tris[t]], idx[layer.tris[t + 1]], idx[layer.tris[t + 2]]);
                mesh.triangle(idx[layer.tris[t + 2]], idx[layer.tris[t + 1]], idx[layer.tris[t]]);
            }
            layerY += 0.002; // keep later shapes above earlier ones
        }
    }
}
