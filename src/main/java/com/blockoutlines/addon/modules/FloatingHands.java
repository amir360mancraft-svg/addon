package com.blockoutlines.addon.modules;

import com.blockoutlines.addon.BlockOutlinesAddon;
import com.blockoutlines.addon.util.Shapes;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.renderer.Renderer3D;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.render.Camera;
import net.minecraft.util.math.Vec3d;

/**
 * Two hands that float beside you, a little behind your shoulders. They bob, follow you with a soft delay, and
 * punch forward when you swing. Drawn on your screen only.
 */
public class FloatingHands extends Module {
    public enum Style { Fists, Gloves, Ghost }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Style> style = sgGeneral.add(new EnumSetting.Builder<Style>()
        .name("style").description("Fists = skin colored, Gloves = your glove color with a cuff, Ghost = see-through glowing hands.")
        .defaultValue(Style.Gloves).build());
    private final Setting<SettingColor> color = sgGeneral.add(new ColorSetting.Builder()
        .name("color").description("Color of the hands (Fists / Gloves) or of the glow (Ghost).").defaultValue(new SettingColor(235, 235, 240, 255)).build());
    private final Setting<Double> size = sgGeneral.add(new DoubleSetting.Builder()
        .name("size").description("Size of the hands.").defaultValue(1.0).range(0.5, 2.5).sliderRange(0.5, 2.0).build());
    private final Setting<Double> side = sgGeneral.add(new DoubleSetting.Builder()
        .name("side-distance").description("How far from your body they float.").defaultValue(0.95).range(0.5, 2.0).sliderRange(0.6, 1.6).build());
    private final Setting<Double> height = sgGeneral.add(new DoubleSetting.Builder()
        .name("height").description("How high above your feet.").defaultValue(1.25).range(0.3, 2.4).sliderRange(0.6, 2.0).build());
    private final Setting<Double> bob = sgGeneral.add(new DoubleSetting.Builder()
        .name("bobbing").description("How much they float up and down.").defaultValue(0.08).range(0.0, 0.4).sliderRange(0.0, 0.25).build());
    private final Setting<Double> lag = sgGeneral.add(new DoubleSetting.Builder()
        .name("follow-speed").description("How fast they follow you (small = lazy and floaty).").defaultValue(9.0).range(2.0, 30.0).sliderRange(3.0, 20.0).build());
    private final Setting<Boolean> punch = sgGeneral.add(new BoolSetting.Builder()
        .name("punch").description("The hands punch forward when you swing.").defaultValue(true).build());
    private final Setting<Boolean> glow = sgGeneral.add(new BoolSetting.Builder()
        .name("glow").description("A soft glow around each hand.").defaultValue(true).build());
    private final Setting<Boolean> firstPerson = sgGeneral.add(new BoolSetting.Builder()
        .name("in-first-person").description("Also draw them in first person (they then show at the edges of the screen).").defaultValue(false).build());

    private final double[][] pos = new double[2][3];
    private boolean placed;
    private double thrust;

    public FloatingHands() {
        super(BlockOutlinesAddon.EFFECTS, "floating-hands", "Two hands float beside you and punch when you swing.");
    }

    @Override
    public void onActivate() {
        placed = false;
        thrust = 0;
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (mc.player == null || mc.world == null || mc.gameRenderer == null) return;
        if (!firstPerson.get() && mc.options.getPerspective().isFirstPerson()) return;

        float td = event.tickDelta;
        double dt = Math.min(0.1, event.frameTime);
        double secs = System.currentTimeMillis() / 1000.0;
        Vec3d p = mc.player.getLerpedPos(td);
        double yaw = Math.toRadians(mc.player.getBodyYaw());
        double fx = -Math.sin(yaw), fz = Math.cos(yaw);     // forward
        double rx = -Math.cos(yaw), rz = -Math.sin(yaw);    // right

        // punching: follows the swing animation of your arm
        double sw = punch.get() ? mc.player.getHandSwingProgress(td) : 0.0;
        double goalThrust = Math.sin(Math.min(1.0, sw) * Math.PI);
        thrust += (goalThrust - thrust) * Math.min(1.0, dt * 22.0);

        Camera cam = mc.gameRenderer.getCamera();
        double cy = Math.toRadians(cam.getYaw()), cp = Math.toRadians(cam.getPitch());
        double cfx = -Math.sin(cy) * Math.cos(cp), cfy = -Math.sin(cp), cfz = Math.cos(cy) * Math.cos(cp);
        double rl = Math.max(1e-6, Math.hypot(cfx, cfz));
        double crx = -cfz / rl, crz = cfx / rl;
        double cux = -crz * cfy, cuy = crz * cfx - crx * cfz, cuz = crx * cfy;

        Renderer3D r = event.depthRenderer;
        double s = size.get();
        SettingColor c = color.get();
        for (int h = 0; h < 2; h++) {
            double sg = h == 0 ? 1.0 : -1.0;
            double ph = secs * 1.6 + h * 1.9;
            double tx = p.x + rx * sg * side.get() + fx * (0.2 + thrust * 1.1);
            double ty = p.y + height.get() + Math.sin(ph) * bob.get();
            double tz = p.z + rz * sg * side.get() + fz * (0.2 + thrust * 1.1);
            if (!placed) { pos[h][0] = tx; pos[h][1] = ty; pos[h][2] = tz; }
            double k = Math.min(1.0, dt * lag.get());
            pos[h][0] += (tx - pos[h][0]) * k;
            pos[h][1] += (ty - pos[h][1]) * k;
            pos[h][2] += (tz - pos[h][2]) * k;

            final double ox = pos[h][0], oy = pos[h][1], oz = pos[h][2];
            final double tilt = -0.25 - thrust * 0.5 + Math.sin(ph * 1.3) * 0.08;   // fingers point a bit up, forward when punching
            final double roll = sg * (0.15 + Math.sin(ph * 0.9) * 0.06);
            final double cyaw = Math.cos(yaw), syaw = Math.sin(yaw);
            final double ct = Math.cos(tilt), st = Math.sin(tilt), cr = Math.cos(roll), sr = Math.sin(roll);
            Shapes.Tf tf = (lx, ly, lz) -> {
                lx *= s; ly *= s; lz *= s;
                double x1 = lx * cr - ly * sr, y1 = lx * sr + ly * cr;
                double y2 = y1 * ct - lz * st, z2 = y1 * st + lz * ct;
                return new double[] {ox - x1 * cyaw - z2 * syaw, oy + y2, oz - x1 * syaw + z2 * cyaw};
            };

            int a = style.get() == Style.Ghost ? 150 : 255;
            int br = c.r, bg = c.g, bb = c.b;
            if (style.get() == Style.Fists) { br = 232; bg = 190; bb = 156; }
            if (style.get() == Style.Ghost) { br = Math.min(255, c.r + 40); bg = Math.min(255, c.g + 40); bb = Math.min(255, c.b + 40); }
            // palm / fist, fingers, thumb
            Shapes.box(r, tf, 0, 0, 0, 0.18, 0.2, 0.15, 0, br, bg, bb, a);
            Shapes.box(r, tf, 0, 0.03, 0.12, 0.18, 0.1, 0.1, 0, br, bg, bb, a);
            Shapes.box(r, tf, -sg * 0.1, 0.0, 0.03, 0.05, 0.12, 0.1, sg * 0.4, br, bg, bb, a);
            if (style.get() == Style.Gloves) {
                Shapes.box(r, tf, 0, 0, -0.1, 0.21, 0.23, 0.07, 0, c.r / 2 + 40, c.g / 2 + 40, c.b / 2 + 40, a);
            }
            if (glow.get()) {
                Shapes.glow(r, ox, oy, oz, 0.38 * s, crx, 0, crz, cux, cuy, cuz, c.r, c.g, c.b, style.get() == Style.Ghost ? 130 : 70);
            }
        }
        placed = true;
    }
}
