package com.blockoutlines.addon.modules;

import com.blockoutlines.addon.BlockOutlinesAddon;
import com.blockoutlines.addon.util.CosmeticUtil;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.renderer.MeshBuilder;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.List;

/** A glowing colored cone hat on the head that follows its movement. Visual only. */
public class ChinaHat extends Module {
    public enum Who { Self, Everyone }
    public enum Paint { Rainbow, Gradient, Solid }

    private static final int SEG = 36;
    private static final int SPOKE_EVERY = 4;

    private final SettingGroup sg = settings.getDefaultGroup();

    private final Setting<Who> who = sg.add(new EnumSetting.Builder<Who>()
        .name("who").description("Everyone: other players near you get one too (nearest 24).")
        .defaultValue(Who.Self).build());

    private final Setting<Paint> paint = sg.add(new EnumSetting.Builder<Paint>()
        .name("colors").description("Rainbow, a two-color wave, or one solid color.")
        .defaultValue(Paint.Rainbow).build());

    private final Setting<SettingColor> colorA = sg.add(new ColorSetting.Builder()
        .name("color-a").description("Hat color (first color of the wave).")
        .defaultValue(new SettingColor(255, 70, 160, 255))
        .visible(() -> paint.get() != Paint.Rainbow).build());

    private final Setting<SettingColor> colorB = sg.add(new ColorSetting.Builder()
        .name("color-b").description("Second color of the wave.")
        .defaultValue(new SettingColor(90, 120, 255, 255))
        .visible(() -> paint.get() == Paint.Gradient).build());

    private final Setting<Double> brim = sg.add(new DoubleSetting.Builder()
        .name("size").description("Brim width.")
        .defaultValue(0.55).min(0.2).max(1.5).sliderMin(0.3).sliderMax(1.0).build());

    private final Setting<Double> height = sg.add(new DoubleSetting.Builder()
        .name("height").description("How tall the cone stands.")
        .defaultValue(0.4).min(0.1).max(1.2).sliderMin(0.2).sliderMax(0.8).build());

    private final Setting<Integer> opacity = sg.add(new IntSetting.Builder()
        .name("opacity").description("Cone fill in %. The rim always glows.")
        .defaultValue(55).range(0, 100).sliderRange(10, 100).build());

    private final Setting<Double> spin = sg.add(new DoubleSetting.Builder()
        .name("spin").description("How fast the colors turn.")
        .defaultValue(0.4).min(0).max(3).sliderMin(0).sliderMax(2).build());

    public ChinaHat() {
        super(BlockOutlinesAddon.COSMETICS, "china-hat", "A glowing gradient cone hat on your head (visual only).");
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.world == null || mc.player == null) return;
        double t = System.currentTimeMillis() / 1000.0;
        MeshBuilder mesh = event.renderer.triangles;
        for (AbstractClientPlayerEntity p : CosmeticUtil.wearers(who.get() == Who.Everyone, 24, 48)) {
            mesh.ensureCapacity(2 * SEG + 1, SEG * 6);
            draw(event, mesh, p, event.tickDelta, t);
        }
    }

    private Color colorAt(double u, double t, int alpha) {
        double s = spin.get();
        switch (paint.get()) {
            case Rainbow:
                return CosmeticUtil.hsv((float) (u + t * s * 0.25), 0.7f, 1f, alpha);
            case Gradient: {
                double w = 0.5 + 0.5 * Math.sin((u + t * s * 0.25) * Math.PI * 2);
                return CosmeticUtil.lerp(new Color(colorA.get()), new Color(colorB.get()), w, alpha);
            }
            default: {
                SettingColor c = colorA.get();
                return new Color(c.r, c.g, c.b, alpha);
            }
        }
    }

    private void draw(Render3DEvent event, MeshBuilder mesh, AbstractClientPlayerEntity p, float tickDelta, double t) {
        Vec3d pos = p.getLerpedPos(tickDelta);
        double yaw = Math.toRadians(MathHelper.lerpAngleDegrees(tickDelta, p.lastHeadYaw, p.headYaw));
        double pitch = Math.toRadians(p.getPitch(tickDelta) * 0.5);

        Vec3d up = new Vec3d(Math.sin(yaw) * Math.sin(pitch), Math.cos(pitch), -Math.cos(yaw) * Math.sin(pitch)).normalize();
        Vec3d e1 = new Vec3d(Math.cos(yaw), 0, Math.sin(yaw));
        Vec3d e2 = up.crossProduct(e1).normalize();

        Vec3d head = pos.add(0, p.getStandingEyeHeight(), 0);
        Vec3d base = head.add(up.multiply(0.33));
        Vec3d apex = base.add(up.multiply(height.get() + 0.05));
        double r = brim.get();

        int fill = (int) (255 * opacity.get() / 100.0);
        Vec3d[] ring = new Vec3d[SEG];
        Color[] ringColor = new Color[SEG];
        Color[] rim = new Color[SEG];
        for (int i = 0; i < SEG; i++) {
            double u = i / (double) SEG;
            double a = u * Math.PI * 2;
            ring[i] = base.add(e1.multiply(Math.cos(a) * r)).add(e2.multiply(Math.sin(a) * r));
            ringColor[i] = colorAt(u, t, fill);
            rim[i] = colorAt(u, t, 255);
        }

        if (fill > 0) {
            int iApex = mesh.vec3(apex.x, apex.y, apex.z).color(colorAt(0.0, t, Math.min(255, fill + 60))).next();
            int[] idx = new int[SEG];
            for (int i = 0; i < SEG; i++) idx[i] = mesh.vec3(ring[i].x, ring[i].y, ring[i].z).color(ringColor[i]).next();
            for (int i = 0; i < SEG; i++) {
                int n = (i + 1) % SEG;
                mesh.triangle(iApex, idx[i], idx[n]);
                mesh.triangle(idx[n], idx[i], iApex);
            }
        }

        Vec3d lift = up.multiply(0.012);
        for (int i = 0; i < SEG; i++) {
            int n = (i + 1) % SEG;
            event.renderer.line(ring[i].x, ring[i].y, ring[i].z, ring[n].x, ring[n].y, ring[n].z, rim[i]);
            event.renderer.line(ring[i].x + lift.x, ring[i].y + lift.y, ring[i].z + lift.z,
                ring[n].x + lift.x, ring[n].y + lift.y, ring[n].z + lift.z, rim[i]);
            if (i % SPOKE_EVERY == 0) {
                Color spoke = new Color(rim[i].r, rim[i].g, rim[i].b, 140);
                event.renderer.line(apex.x, apex.y, apex.z, ring[i].x, ring[i].y, ring[i].z, spoke);
            }
        }
    }
}
