package com.blockoutlines.addon.modules;

import com.blockoutlines.addon.util.SvgIcon;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WVerticalList;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
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
import org.lwjgl.BufferUtils;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.tinyfd.TinyFileDialogs;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.shape.VoxelShape;

import java.util.ArrayList;
import java.util.List;

/**
 * Smooth, coloured outline around the block you look at. When that block breaks, a black and white
 * icon (assets/blockoutlines/icon.svg) pops up on top of it and spins for a moment.
 */
public class BlockOutlines extends Module {
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

    public final Setting<SettingColor> iconColor = sgRender.add(new ColorSetting.Builder()
        .name("icon-color")
        .description("Color of the light (white) parts of the icon.")
        .defaultValue(new SettingColor(255, 255, 255, 255))
        .build()
    );

    public final Setting<SettingColor> iconDarkColor = sgRender.add(new ColorSetting.Builder()
        .name("icon-dark-color")
        .description("Color of the dark (black) parts of the icon.")
        .defaultValue(new SettingColor(0, 0, 0, 255))
        .build()
    );

    public final Setting<Double> iconAlpha = sgRender.add(new DoubleSetting.Builder()
        .name("icon-alpha")
        .description("The opacity of the spinning icon on top of the block (0 hides it).")
        .defaultValue(1.0)
        .range(0.0, 1.0)
        .sliderRange(0.0, 1.0)
        .build()
    );

    private final SettingGroup sgBreak = settings.createGroup("Break ESP");

    public final Setting<Boolean> breakEsp = sgBreak.add(new BoolSetting.Builder()
        .name("break-esp")
        .description("Shows a box where a block was just broken, then fades it out.")
        .defaultValue(true)
        .build()
    );

    public final Setting<SettingColor> breakColor = sgBreak.add(new ColorSetting.Builder()
        .name("break-color")
        .description("The color of the broken-block box.")
        .defaultValue(new SettingColor(255, 255, 255, 255))
        .visible(breakEsp::get)
        .build()
    );

    public final Setting<Double> breakFillAlpha = sgBreak.add(new DoubleSetting.Builder()
        .name("break-fill-alpha")
        .description("The opacity of the filled sides of the broken-block box (0 hides them).")
        .defaultValue(0.25)
        .range(0.0, 1.0)
        .sliderRange(0.0, 1.0)
        .visible(breakEsp::get)
        .build()
    );

    public final Setting<Double> breakLineAlpha = sgBreak.add(new DoubleSetting.Builder()
        .name("break-line-alpha")
        .description("The opacity of the outline of the broken-block box (0 hides it).")
        .defaultValue(1.0)
        .range(0.0, 1.0)
        .sliderRange(0.0, 1.0)
        .visible(breakEsp::get)
        .build()
    );

    public final Setting<Double> breakTime = sgBreak.add(new DoubleSetting.Builder()
        .name("break-time")
        .description("How many seconds the broken-block box stays before it is gone.")
        .defaultValue(0.6)
        .range(0.1, 3.0)
        .sliderRange(0.1, 3.0)
        .visible(breakEsp::get)
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
    private final double[][] hBox = new double[HIST][6];
    private final long[] hTime = new long[HIST];
    private int hHead = 0;
    private boolean hAny = false;

    private static final class BreakFx {
        final int x, y, z;
        final double[] box;
        double age;

        BreakFx(int x, int y, int z, double[] box) {
            this.x = x; this.y = y; this.z = z; this.box = box;
        }
    }

    private final List<BreakFx> fx = new ArrayList<>();
    private double spin;

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

    /** Two buttons at the bottom of the module window: pick your own SVG, or go back to the built-in one. */
    @Override
    public WWidget getWidget(GuiTheme theme) {
        WVerticalList list = theme.verticalList();
        WButton upload = list.add(theme.button("Upload icon (.svg)")).expandX().widget();
        WButton reset = list.add(theme.button("Reset icon")).expandX().widget();
        upload.action = this::uploadIcon;
        reset.action = this::resetIcon;
        return list;
    }

    private void uploadIcon() {
        try {
            PointerBuffer filters = BufferUtils.createPointerBuffer(1);
            filters.put(MemoryUtil.memASCII("*.svg"));
            filters.rewind();
            String path = TinyFileDialogs.tinyfd_openFileDialog("Select SVG icon", null, filters, "SVG files", false);
            if (path == null) return;

            String text = new String(java.nio.file.Files.readAllBytes(java.nio.file.Path.of(path)), java.nio.charset.StandardCharsets.UTF_8);
            SvgIcon loaded = SvgIcon.parse(text);
            if (loaded.layers.isEmpty()) {
                error("That SVG has no shapes I can draw (polygon, rect or path).");
                return;
            }

            java.io.File target = new java.io.File(mc.runDirectory, CUSTOM_ICON);
            target.getParentFile().mkdirs();
            java.nio.file.Files.write(target.toPath(), text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            icon = loaded;
            info("Icon updated.");
        } catch (Exception e) {
            error("Could not load that SVG: " + e.getMessage());
        }
    }

    private void resetIcon() {
        try {
            java.nio.file.Files.deleteIfExists(new java.io.File(mc.runDirectory, CUSTOM_ICON).toPath());
        } catch (Exception ignored) {
        }
        icon = SvgIcon.load("/assets/blockoutlines/icon.svg");
        info("Icon reset.");
    }

    @Override
    public void onDeactivate() {
        hasTarget = false;
        fx.clear();
    }

    @EventHandler
    private void onBlockUpdate(BlockUpdateEvent event) {
        if (!breakEsp.get() || !hAny || !event.newState.isAir()) return;
        int x = event.pos.getX(), y = event.pos.getY(), z = event.pos.getZ();
        for (BreakFx f : fx) {
            if (f.x == x && f.y == y && f.z == z && f.age < 0.3) return; // already showing this one
        }
        long now = System.currentTimeMillis();
        for (int i = 0; i < HIST; i++) {
            if (hTime[i] == 0 || now - hTime[i] > 3000) continue;
            if (hX[i] == x && hY[i] == y && hZ[i] == z) {
                fx.add(new BreakFx(x, y, z, hBox[i].clone()));
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
                hBox[hHead][0] = tMinX; hBox[hHead][1] = tMinY; hBox[hHead][2] = tMinZ;
                hBox[hHead][3] = tMaxX; hBox[hHead][4] = tMaxY; hBox[hHead][5] = tMaxZ;
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

        // broken-block boxes: fade out, then disappear
        if (!fx.isEmpty()) {
            double life = breakTime.get();
            SettingColor bc = breakColor.get();
            for (int i = fx.size() - 1; i >= 0; i--) {
                BreakFx f = fx.get(i);
                f.age += dt;
                if (f.age >= life) { fx.remove(i); continue; }
                double fade = 1.0 - f.age / life;
                double[] b = f.box;
                if (breakFillAlpha.get() > 0) {
                    event.renderer.boxSides(b[0], b[1], b[2], b[3], b[4], b[5], new Color(bc.r, bc.g, bc.b, a255(breakFillAlpha.get() * fade)), 0);
                }
                if (breakLineAlpha.get() > 0) {
                    event.renderer.boxLines(b[0], b[1], b[2], b[3], b[4], b[5], new Color(bc.r, bc.g, bc.b, a255(breakLineAlpha.get() * fade)), 0);
                }
            }
        }

        // spinning icon on top of the targeted block
        spin += dt * SPIN_RAD_PER_SEC;
        if (hasTarget && iconAlpha.get() > 0) {
            drawIcon(event, (minX + maxX) / 2.0, maxY + 0.005, (minZ + maxZ) / 2.0, 0.45, spin - Math.PI / 2.0, a255(iconAlpha.get()));
        }
    }

    private static int a255(double v) {
        return (int) Math.max(0, Math.min(255, Math.round(v * 255.0)));
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

        // The mesh buffers are only allocated on demand - reserve room first or the write hits a null pointer (native crash).
        int vertexTotal = 0, indexTotal = 0;
        for (SvgIcon.Layer layer : icon.layers) {
            vertexTotal += layer.u.length;
            indexTotal += layer.tris.length * 2;
        }
        mesh.ensureCapacity(vertexTotal, indexTotal);

        double layerY = y;
        for (SvgIcon.Layer layer : icon.layers) {
            SettingColor src = layer.white ? iconColor.get() : iconDarkColor.get();
            Color c = new Color(src.r, src.g, src.b, alpha);
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
