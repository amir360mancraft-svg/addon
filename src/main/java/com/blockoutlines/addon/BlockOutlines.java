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
    private static final double SPIN_RAD_PER_SEC = 0.5;
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

    private static final String CUSTOM_ICON = "meteor-client/block-outlines.svg";
    private SvgIcon icon = SvgIcon.load("/assets/blockoutlines/icon.svg");

    // current (animated) outline box
    private double minX, minY, minZ, maxX, maxY, maxZ;
    private boolean hasTarget;
    private int targetX, targetY, targetZ;

    // recent targets (the crosshair moves to the next block right after a break, so remember the last few)
    private static final int HIST = 12;
    private final int[] hX = new int[HIST], hY = new int[HIST], hZ = new int[HIST];
    private final double[] hCx = new double[HIST], hTop = new double[HIST], hCz = new double[HIST];
    private final long[] hTime = new long[HIST];
    private int hHead = 0;
    private boolean hAny = false;

    // break pop
    private float popTimer;
    private double popX, popY, popZ, spin;

    public BlockOutlines() {
        super(Categories.Render, "block-outlines", "Renders a smooth, custom-colored outline around the block you are looking at.");
    }

    /** Uses .minecraft/meteor-client/block-outlines.svg when it exists, otherwise the icon built into the jar. */
    @Override
    public void onActivate() {
        SvgIcon loaded = null;
        try {
            java.io.File f = new java.io.File(mc.runDirectory, CUSTOM_ICON);
            if (f.isFile()) loaded = SvgIcon.parse(new String(java.nio.file.Files.readAllBytes(f.toPath()), java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
        icon = loaded != null && !loaded.layers.isEmpty() ? loaded : SvgIcon.load("/assets/blockoutlines/icon.svg");
    }

    @Override
    public void onDeactivate() {
        hasTarget = false;
        popTimer = 0;
    }

    @EventHandler
    private void onBlockUpdate(BlockUpdateEvent event) {
        if (popTimer > 0 || !hAny || !event.newState.isAir()) return;
        long now = System.currentTimeMillis();
        for (int i = 0; i < HIST; i++) {
            if (hTime[i] == 0 || now - hTime[i] > 3000) continue;
            if (hX[i] == event.pos.getX() && hY[i] == event.pos.getY() && hZ[i] == event.pos.getZ()) {
                popTimer = POP_SECONDS;
                return;
            }
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

            int last = (hHead + HIST - 1) % HIST;
            if (hTime[last] == 0 || hX[last] != targetX || hY[last] != targetY || hZ[last] != targetZ) {
                hX[hHead] = targetX; hY[hHead] = targetY; hZ[hHead] = targetZ;
                hCx[hHead] = (tMinX + tMaxX) / 2.0; hTop[hHead] = tMaxY + 0.005; hCz[hHead] = (tMinZ + tMaxZ) / 2.0;
                hTime[hHead] = System.currentTimeMillis();
                hHead = (hHead + 1) % HIST;
                hAny = true;
            } else {
                hTime[last] = System.currentTimeMillis();
            }

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

        // like the original star: always shown on top of the targeted block, pulses when it breaks
        boolean pulsing = popTimer > 0;
        if (pulsing) popTimer = Math.max(0, popTimer - (float) dt);
        spin += dt * SPIN_RAD_PER_SEC;
        if (hasTarget || pulsing) {
            double p = pulsing ? 1.0 - popTimer / POP_SECONDS : 1.0;
            double scale = 1.0 + (pulsing ? Math.sin(p * Math.PI) * 0.7 : 0.0);
            double fade = pulsing ? 1.0 - 0.4 * p : 1.0;
            int a = (int) Math.max(0, Math.min(255, Math.round(alpha.get() * 255.0 * fade)));
            drawIcon(event, (minX + maxX) / 2.0, maxY + 0.005, (minZ + maxZ) / 2.0, 0.45 * scale, spin - Math.PI / 2.0, a);
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
