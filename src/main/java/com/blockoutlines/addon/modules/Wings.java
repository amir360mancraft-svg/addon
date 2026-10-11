package com.blockoutlines.addon.modules;

import com.blockoutlines.addon.BlockOutlinesAddon;
import com.blockoutlines.addon.util.CosmeticUtil;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.renderer.MeshBuilder;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Feathered wings that rest, beat when you jump or fall, spread when gliding and fold when you crouch. Visual only. */
public class Wings extends Module {
    public enum Who { Self, Everyone }
    public enum Style { Angel, Demon, Energy, Custom }

    private static final int FEATHERS = 9;
    private static final double[] LENGTH = {0.80, 1.05, 1.30, 1.50, 1.50, 1.35, 1.15, 0.95, 0.75};

    private final SettingGroup sg = settings.getDefaultGroup();

    private final Setting<Who> who = sg.add(new EnumSetting.Builder<Who>()
        .name("who").description("Everyone: other players near you get wings too (nearest 24).")
        .defaultValue(Who.Self).build());

    private final Setting<Style> style = sg.add(new EnumSetting.Builder<Style>()
        .name("style").description("Angel: white. Demon: dark with a red edge. Energy: glowing. Custom: your colors.")
        .defaultValue(Style.Angel).build());

    private final Setting<SettingColor> rootColor = sg.add(new ColorSetting.Builder()
        .name("root-color").description("Feathers at the spine.")
        .defaultValue(new SettingColor(255, 255, 255, 235))
        .visible(() -> style.get() == Style.Custom).build());

    private final Setting<SettingColor> tipColor = sg.add(new ColorSetting.Builder()
        .name("tip-color").description("Feather tips.")
        .defaultValue(new SettingColor(190, 215, 255, 235))
        .visible(() -> style.get() == Style.Custom).build());

    private final Setting<Double> size = sg.add(new DoubleSetting.Builder()
        .name("size").description("Wing size multiplier.")
        .defaultValue(1.0).min(0.4).max(2.5).sliderMin(0.5).sliderMax(2.0).build());

    private final Setting<Double> flapSpeed = sg.add(new DoubleSetting.Builder()
        .name("flap-speed").description("Wing beat speed multiplier.")
        .defaultValue(1.0).min(0.2).max(3.0).sliderMin(0.3).sliderMax(2.5).build());

    private final Map<Integer, Double> open = new HashMap<>();
    private final Map<Integer, Double> phase = new HashMap<>();

    public Wings() {
        super(BlockOutlinesAddon.COSMETICS, "wings", "Animated wings on your back (visual only).");
    }

    @Override
    public void onDeactivate() {
        open.clear();
        phase.clear();
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.world == null || mc.player == null) return;
        double dt = event.frameTime;
        if (!(dt > 0) || dt > 0.1) dt = 0.016;

        List<AbstractClientPlayerEntity> list = CosmeticUtil.wearers(who.get() == Who.Everyone, 24, 48);
        MeshBuilder mesh = event.renderer.triangles;
        for (AbstractClientPlayerEntity p : list) {
            mesh.ensureCapacity(2 * FEATHERS * 2 * 4, 2 * FEATHERS * 2 * 12);
            drawFor(event, mesh, p, dt, event.tickDelta);
        }
    }

    private void drawFor(Render3DEvent event, MeshBuilder mesh, AbstractClientPlayerEntity p, double dt, float tickDelta) {
        int id = p.getId();
        boolean sneaking = p.isSneaking();
        boolean gliding = p.isGliding();
        Vec3d vel = p.getVelocity();
        double target, amp, freq;
        if (gliding)               { target = 1.00; amp = 0.02; freq = 1.5; }
        else if (sneaking)         { target = 0.06; amp = 0.00; freq = 1.0; }
        else if (!p.isOnGround())  {
            boolean falling = vel.y < -0.08;
            target = falling ? 0.75 : 0.62; amp = falling ? 0.22 : 0.30; freq = falling ? 9.0 : 12.0;
        } else                     { target = 0.38; amp = 0.03; freq = 1.6; }

        double cur = open.getOrDefault(id, target);
        cur += (target - cur) * (1 - Math.exp(-dt * 8));
        open.put(id, cur);
        double ph = phase.getOrDefault(id, 0.0) + dt * freq * flapSpeed.get();
        phase.put(id, ph);
        double o = MathHelper.clamp(cur + amp * Math.sin(ph), 0, 1);

        Vec3d pos = p.getLerpedPos(tickDelta);
        double yaw = Math.toRadians(MathHelper.lerpAngleDegrees(tickDelta, p.lastBodyYaw, p.bodyYaw));
        Vec3d fwd = new Vec3d(-Math.sin(yaw), 0, Math.cos(yaw));
        Vec3d right = new Vec3d(-Math.cos(yaw), 0, -Math.sin(yaw));
        Vec3d back = fwd.multiply(-1);
        Vec3d up = new Vec3d(0, 1, 0);
        Vec3d pivot = pos.add(0, sneaking ? 1.12 : 1.38, 0).add(back.multiply(sneaking ? 0.02 : 0.14));

        Color root, tip, edge = null;
        int alpha = 235;
        switch (style.get()) {
            case Demon -> { root = new Color(28, 6, 12, alpha); tip = new Color(110, 0, 16, alpha); edge = new Color(235, 30, 30, 255); }
            case Energy -> {
                alpha = (int) (170 + 70 * Math.sin(ph * 0.5));
                root = new Color(0, 230, 255, alpha); tip = new Color(175, 85, 255, alpha); edge = new Color(220, 250, 255, 255);
            }
            case Custom -> { root = new Color(rootColor.get()); tip = new Color(tipColor.get()); }
            default -> { root = new Color(255, 255, 255, alpha); tip = new Color(195, 222, 255, alpha); }
        }

        drawWing(event, mesh, pivot, right, back, up, +1, o, root, tip, edge);
        drawWing(event, mesh, pivot, right, back, up, -1, o, root, tip, edge);
    }

    private void drawWing(Render3DEvent event, MeshBuilder mesh, Vec3d pivot, Vec3d right, Vec3d back, Vec3d up,
                          int side, double open, Color root, Color tip, Color edge) {
        double hinge = open * Math.toRadians(85);
        Vec3d hdir = back.multiply(Math.cos(hinge)).add(right.multiply(side * Math.sin(hinge))).normalize();
        Vec3d planeNormal = hdir.crossProduct(up).normalize();
        double scale = size.get();
        double topAngle = Math.toRadians(68), bottomAngle = Math.toRadians(-38);

        for (int layer = 0; layer < 2; layer++) {
            for (int k = 0; k < FEATHERS; k++) {
                double f = k / (double) (FEATHERS - 1);
                double a = topAngle + (bottomAngle - topAngle) * f;
                Vec3d dir = hdir.multiply(Math.cos(a)).add(up.multiply(Math.sin(a))).normalize();

                double len = LENGTH[k] * scale * (layer == 0 ? 1.0 : 0.55);
                Vec3d start = pivot.add(hdir.multiply(layer == 0 ? 0.0 : 0.03));
                Vec3d tipPos = start.add(dir.multiply(len)).add(0, -0.07 * len * (1 - open), 0);
                Vec3d mid = start.add(tipPos.subtract(start).multiply(0.55));
                Vec3d wdir = planeNormal.crossProduct(dir).normalize();
                double w = len * 0.085;
                Vec3d m1 = mid.add(wdir.multiply(w));
                Vec3d m2 = mid.subtract(wdir.multiply(w));

                Color cRoot = layer == 0 ? root : CosmeticUtil.lerp(root, tip, 0.15, root.a);
                Color cMid = CosmeticUtil.lerp(root, tip, layer == 0 ? 0.5 : 0.3, root.a);
                Color cTip = layer == 0 ? tip : CosmeticUtil.lerp(root, tip, 0.65, root.a);

                int iR = mesh.vec3(start.x, start.y, start.z).color(cRoot).next();
                int i1 = mesh.vec3(m1.x, m1.y, m1.z).color(cMid).next();
                int iT = mesh.vec3(tipPos.x, tipPos.y, tipPos.z).color(cTip).next();
                int i2 = mesh.vec3(m2.x, m2.y, m2.z).color(cMid).next();

                mesh.triangle(iR, i1, iT); mesh.triangle(iT, i1, iR);
                mesh.triangle(iR, iT, i2); mesh.triangle(i2, iT, iR);

                if (edge != null && layer == 0) {
                    event.renderer.line(start.x, start.y, start.z, m1.x, m1.y, m1.z, edge);
                    event.renderer.line(m1.x, m1.y, m1.z, tipPos.x, tipPos.y, tipPos.z, edge);
                    event.renderer.line(tipPos.x, tipPos.y, tipPos.z, m2.x, m2.y, m2.z, edge);
                    event.renderer.line(m2.x, m2.y, m2.z, start.x, start.y, start.z, edge);
                }
            }
        }
    }
}
