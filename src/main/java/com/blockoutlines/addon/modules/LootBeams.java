package com.blockoutlines.addon.modules;

import com.blockoutlines.addon.BlockOutlinesAddon;
import com.blockoutlines.addon.util.CosmeticUtil;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.renderer.Renderer3D;
import meteordevelopment.meteorclient.settings.BoolSetting;
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
import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * A column of light over every valuable item on the ground, like loot in an action RPG, with a turning glowing ring on
 * the floor and little lights rising inside the beam. The color tells the value: gold and purple for totems and
 * netherite, turquoise for diamond, purple for epic loot, blue / green for the rest.
 * Drawn on your screen only, nothing is sent to the server.
 */
public class LootBeams extends Module {
    public enum Tier { Common, Uncommon, Rare, Epic, Legendary }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgLook = settings.createGroup("Look");
    private final SettingGroup sgColor = settings.createGroup("Colors");

    // -------- general
    private final Setting<Tier> minTier = sgGeneral.add(new EnumSetting.Builder<Tier>()
        .name("minimum-value").description("The least valuable items that get a beam. Common = every item, Uncommon = enchanted and special items, Rare = diamond and up.")
        .defaultValue(Tier.Uncommon).build());
    private final Setting<Integer> range = sgGeneral.add(new IntSetting.Builder()
        .name("range").description("How far away items still get a beam (blocks).").defaultValue(48).range(8, 128).sliderRange(8, 96).build());
    private final Setting<Integer> maxBeams = sgGeneral.add(new IntSetting.Builder()
        .name("max-beams").description("Most beams drawn at once (the nearest ones). Keeps the game fast next to big piles of items.")
        .defaultValue(40).range(5, 200).sliderRange(10, 120).build());
    private final Setting<Boolean> onlyLanded = sgGeneral.add(new BoolSetting.Builder()
        .name("only-when-landed").description("The beam grows when the item has landed, not while it is still flying through the air.").defaultValue(true).build());
    private final Setting<Boolean> throughWalls = sgGeneral.add(new BoolSetting.Builder()
        .name("through-walls").description("Also show the beams that are behind blocks.").defaultValue(false).build());

    // -------- look
    private final Setting<Double> height = sgLook.add(new DoubleSetting.Builder()
        .name("height").description("How high the beam goes (blocks).").defaultValue(4.5).range(1.0, 16.0).sliderRange(1.0, 10.0).build());
    private final Setting<Double> width = sgLook.add(new DoubleSetting.Builder()
        .name("width").description("How wide the beam is (blocks).").defaultValue(0.35).range(0.1, 1.2).sliderRange(0.1, 0.9).build());
    private final Setting<Integer> brightness = sgLook.add(new IntSetting.Builder()
        .name("brightness").description("How bright the beam is at the bottom.").defaultValue(200).range(30, 255).sliderRange(60, 255).build());
    private final Setting<Boolean> ring = sgLook.add(new BoolSetting.Builder()
        .name("ground-ring").description("A turning glowing ring on the floor around the item.").defaultValue(true).build());
    private final Setting<Double> ringSize = sgLook.add(new DoubleSetting.Builder()
        .name("ring-size").description("Size of the ring (blocks).").defaultValue(0.7).range(0.3, 2.0).sliderRange(0.3, 1.5).visible(ring::get).build());
    private final Setting<Boolean> sparkles = sgLook.add(new BoolSetting.Builder()
        .name("sparkles").description("Little lights that rise inside the beam.").defaultValue(true).build());
    private final Setting<Double> spin = sgLook.add(new DoubleSetting.Builder()
        .name("spin-speed").description("How fast the beam and the ring turn.").defaultValue(1.0).range(0.2, 3.0).sliderRange(0.2, 2.5).build());
    private final Setting<Double> camFade = sgLook.add(new DoubleSetting.Builder()
        .name("camera-fade").description("Beams fade out when you stand this close, so they never cover your view (blocks).")
        .defaultValue(1.2).range(0.0, 5.0).sliderRange(0.0, 4.0).build());

    // -------- colors
    private final Setting<Boolean> smart = sgColor.add(new BoolSetting.Builder()
        .name("smart-colors").description("The color follows the value of the item. Turn off to use one color for everything.").defaultValue(true).build());
    private final Setting<SettingColor> single = sgColor.add(new ColorSetting.Builder()
        .name("single-color").description("The color of every beam.").defaultValue(new SettingColor(255, 200, 80, 255)).visible(() -> !smart.get()).build());
    private final Setting<SettingColor> legendA = sgColor.add(new ColorSetting.Builder()
        .name("legendary-1").description("Totems, netherite, elytra ... first color.").defaultValue(new SettingColor(255, 196, 56, 255)).visible(smart::get).build());
    private final Setting<SettingColor> legendB = sgColor.add(new ColorSetting.Builder()
        .name("legendary-2").description("Totems, netherite, elytra ... second color (the beam slowly changes between the two).")
        .defaultValue(new SettingColor(176, 84, 255, 255)).visible(smart::get).build());
    private final Setting<SettingColor> epic = sgColor.add(new ColorSetting.Builder()
        .name("epic").description("Epic loot.").defaultValue(new SettingColor(188, 96, 255, 255)).visible(smart::get).build());
    private final Setting<SettingColor> diamond = sgColor.add(new ColorSetting.Builder()
        .name("diamond").description("Diamond things.").defaultValue(new SettingColor(52, 226, 236, 255)).visible(smart::get).build());
    private final Setting<SettingColor> rare = sgColor.add(new ColorSetting.Builder()
        .name("rare").description("Rare loot.").defaultValue(new SettingColor(84, 140, 255, 255)).visible(smart::get).build());
    private final Setting<SettingColor> uncommon = sgColor.add(new ColorSetting.Builder()
        .name("uncommon").description("Enchanted and special items.").defaultValue(new SettingColor(150, 255, 110, 255)).visible(smart::get).build());
    private final Setting<SettingColor> common = sgColor.add(new ColorSetting.Builder()
        .name("common").description("Everything else.").defaultValue(new SettingColor(230, 232, 245, 255)).visible(smart::get).build());

    private record Loot(Tier tier, int kind) {} // kind: 0 common, 1 uncommon, 2 rare, 3 epic, 4 legendary, 5 diamond

    private static final class State {
        double grow;
        long seen;
    }

    private final Map<Integer, State> states = new HashMap<>();
    private long lastClean;

    // camera frame (set every frame)
    private double rx, ry, rz, ux, uy, uz;
    private double camX, camY, camZ;

    public LootBeams() {
        super(BlockOutlinesAddon.EFFECTS, "loot-beams",
            "A column of light and a turning ring over valuable items on the ground, colored by value, like loot in an RPG.");
    }

    @Override
    public void onDeactivate() {
        states.clear();
    }

    // ------------------------------------------------------------------ what is valuable

    private static boolean legendary(String path) {
        return path.startsWith("netherite_") || switch (path) {
            case "totem_of_undying", "elytra", "enchanted_golden_apple", "nether_star", "dragon_egg", "beacon", "mace",
                 "heavy_core", "ancient_debris", "conduit" -> true;
            default -> false;
        };
    }

    private Loot classify(ItemStack stack) {
        String path = Registries.ITEM.getId(stack.getItem()).getPath();
        if (legendary(path)) return new Loot(Tier.Legendary, 4);

        int rarity = stack.getRarity().ordinal(); // common, uncommon, rare, epic
        boolean glint = stack.hasGlint();

        if (path.contains("diamond")) return new Loot(Tier.Rare, 5);
        if (rarity >= 3) return new Loot(Tier.Epic, 3);
        if (rarity == 2) return new Loot(Tier.Rare, 2);
        if (glint || rarity == 1 || path.contains("emerald") || path.contains("shulker_box") || path.equals("ender_chest")
            || path.equals("golden_apple") || path.equals("ender_pearl") || path.equals("experience_bottle")) {
            return new Loot(glint ? Tier.Rare : Tier.Uncommon, glint ? 2 : 1);
        }
        return new Loot(Tier.Common, 0);
    }

    private Color[] colors(Loot loot, double secs) {
        if (!smart.get()) {
            SettingColor s = single.get();
            Color c = new Color(s.r, s.g, s.b, 255);
            return new Color[]{c, c};
        }
        SettingColor a, b;
        switch (loot.kind()) {
            case 4 -> {
                a = legendA.get();
                b = legendB.get();
                double t = 0.5 + 0.5 * Math.sin(secs * 0.9);
                Color c = CosmeticUtil.lerp(new Color(a.r, a.g, a.b, 255), new Color(b.r, b.g, b.b, 255), t, 255);
                Color d = CosmeticUtil.lerp(new Color(b.r, b.g, b.b, 255), new Color(a.r, a.g, a.b, 255), t, 255);
                return new Color[]{c, d};
            }
            case 5 -> a = diamond.get();
            case 3 -> a = epic.get();
            case 2 -> a = rare.get();
            case 1 -> a = uncommon.get();
            default -> a = common.get();
        }
        Color c = new Color(a.r, a.g, a.b, 255);
        Color d = CosmeticUtil.lerp(c, new Color(255, 255, 255, 255), 0.35, 255);
        return new Color[]{c, d};
    }

    // ------------------------------------------------------------------ drawing

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (mc.world == null || mc.player == null || mc.gameRenderer == null) return;

        Camera cam = mc.gameRenderer.getCamera();
        double yaw = Math.toRadians(cam.getYaw()), pitch = Math.toRadians(cam.getPitch());
        double fx = -Math.sin(yaw) * Math.cos(pitch), fy = -Math.sin(pitch), fz = Math.cos(yaw) * Math.cos(pitch);
        double rl = Math.max(1e-6, Math.sqrt(fz * fz + fx * fx));
        rx = -fz / rl;
        ry = 0;
        rz = fx / rl;
        ux = ry * fz - rz * fy;
        uy = rz * fx - rx * fz;
        uz = rx * fy - ry * fx;
        camX = event.offsetX;
        camY = event.offsetY;
        camZ = event.offsetZ;

        long now = System.currentTimeMillis();
        double secs = now / 1000.0;
        double dt = Math.min(0.1, event.frameTime);
        double maxD = range.get();

        // the nearest items first
        List<ItemEntity> items = new ArrayList<>();
        for (Entity e : mc.world.getEntities()) {
            if (!(e instanceof ItemEntity item) || item.isRemoved()) continue;
            if (item.squaredDistanceTo(mc.player) > maxD * maxD) continue;
            items.add(item);
        }
        items.sort((a, b) -> Double.compare(a.squaredDistanceTo(mc.player), b.squaredDistanceTo(mc.player)));

        Renderer3D r = throughWalls.get() ? event.renderer : event.depthRenderer;
        int drawn = 0;
        for (ItemEntity item : items) {
            if (drawn >= maxBeams.get()) break;
            Loot loot = classify(item.getStack());
            if (loot.tier().ordinal() < minTier.get().ordinal()) continue;

            State st = states.computeIfAbsent(item.getId(), k -> new State());
            st.seen = now;
            boolean landed = !onlyLanded.get() || item.isOnGround() || item.isTouchingWater();
            st.grow = Math.max(0, Math.min(1, st.grow + (landed ? 1 : -1) * dt * 2.2));
            if (st.grow <= 0.01) continue;
            double grow = st.grow * st.grow * (3 - 2 * st.grow);

            Vec3d p = item.getLerpedPos(event.tickDelta);
            double dx = p.x - camX, dy = p.y - camY, dz = p.z - camZ;
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            double far = 1.0 - Math.pow(Math.min(1.0, dist / maxD), 2.0);
            double near = camFade.get() <= 0.01 ? 1.0 : clamp((dist - camFade.get() * 0.4) / (camFade.get() * 0.8));
            double fade = grow * far * near * (0.9 + 0.1 * Math.sin(secs * 3.0 + item.getId()));
            int alpha = (int) (brightness.get() * fade);
            if (alpha < 3) continue;

            draw(r, item.getId(), p.x, p.y, p.z, colors(loot, secs), alpha, grow, secs);
            drawn++;
        }

        if (now - lastClean > 2000) {
            lastClean = now;
            for (Iterator<State> it = states.values().iterator(); it.hasNext(); ) {
                if (now - it.next().seen > 3000) it.remove();
            }
        }
    }

    private void draw(Renderer3D r, int id, double x, double y, double z, Color[] col, int alpha, double grow, double secs) {
        Color c = col[0], c2 = col[1];
        double sp = spin.get();
        double h = height.get() * (0.35 + 0.65 * grow);
        double w = width.get() * 0.5;

        // soft light on the floor under the item
        disc(r, x, y + 0.03, z, w * 4.0, new Color(c.r, c.g, c.b, (int) (alpha * 0.35)));

        // the beam: three turning planes, bright at the bottom, clear at the top and at the sides
        int segs = 5;
        for (int k = 0; k < 3; k++) {
            double ang = secs * 0.55 * sp + k * Math.PI / 3 + id * 0.7;
            double ax = Math.cos(ang), az = Math.sin(ang);
            plane(r, x, y, z, ax, az, w, h, segs, c, alpha, 0.55);
        }
        // a thin hot core
        for (int k = 0; k < 2; k++) {
            double ang = secs * 0.8 * sp + k * Math.PI / 2;
            Color hot = CosmeticUtil.lerp(c2, new Color(255, 255, 255, 255), 0.55, 255);
            plane(r, x, y, z, Math.cos(ang), Math.sin(ang), w * 0.16, h * 0.9, segs, hot, alpha, 0.9);
        }

        if (ring.get()) groundRing(r, x, y + 0.04, z, ringSize.get(), c, c2, alpha, secs * sp);
        if (sparkles.get()) motes(r, id, x, y, z, h, c2, alpha, secs, sp);
    }

    /** One vertical glowing plane through (x, z) in the horizontal direction (ax, az), half width w. */
    private void plane(Renderer3D r, double x, double y, double z, double ax, double az, double w, double h, int segs,
                       Color c, int alpha, double strength) {
        for (int i = 0; i < segs; i++) {
            double t0 = i / (double) segs, t1 = (i + 1) / (double) segs;
            double y0 = y + h * t0, y1 = y + h * t1;
            double w0 = w * (1.0 - 0.45 * t0), w1 = w * (1.0 - 0.45 * t1);
            Color m0 = new Color(c.r, c.g, c.b, (int) (alpha * strength * Math.pow(1.0 - t0, 1.5)));
            Color m1 = new Color(c.r, c.g, c.b, (int) (alpha * strength * Math.pow(1.0 - t1, 1.5)));
            Color e0 = new Color(c.r, c.g, c.b, 0);
            quad(r,
                x - ax * w0, y0, z - az * w0, x, y0, z, x, y1, z, x - ax * w1, y1, z - az * w1,
                e0, m0, m1, e0);
            quad(r,
                x, y0, z, x + ax * w0, y0, z + az * w0, x + ax * w1, y1, z + az * w1, x, y1, z,
                m0, e0, e0, m1);
        }
    }

    /** A flat soft disc on the floor. */
    private void disc(Renderer3D r, double x, double y, double z, double rad, Color c) {
        int n = 14, rings = 3;
        for (int i = 0; i < n; i++) {
            double a0 = Math.PI * 2 * i / n, a1 = Math.PI * 2 * (i + 1) / n;
            double c0 = Math.cos(a0), s0 = Math.sin(a0), c1 = Math.cos(a1), s1 = Math.sin(a1);
            for (int k = 0; k < rings; k++) {
                double t0 = k / (double) rings, t1 = (k + 1) / (double) rings;
                Color in = fall(c, t0), out = fall(c, t1);
                quad(r,
                    x + c0 * rad * t0, y, z + s0 * rad * t0, x + c1 * rad * t0, y, z + s1 * rad * t0,
                    x + c1 * rad * t1, y, z + s1 * rad * t1, x + c0 * rad * t1, y, z + s0 * rad * t1,
                    in, in, out, out);
            }
        }
    }

    private static Color fall(Color c, double t) {
        double k = Math.exp(-3.0 * t * t) - 0.04 * t;
        return new Color(c.r, c.g, c.b, (int) (c.a * Math.max(0.0, Math.min(1.0, k))));
    }

    /** Two thin turning rings lying on the floor. Bright arcs run around them, so they look like they spin. */
    private void groundRing(Renderer3D r, double x, double y, double z, double size, Color c, Color c2, int alpha, double t) {
        double pulse = 1.0 + 0.06 * Math.sin(t * 3.0);
        ringBand(r, x, y, z, size * pulse, c, alpha, t * 0.9, 3);
        ringBand(r, x, y, z, size * 0.62 * pulse, c2, (int) (alpha * 0.8), -t * 1.3, 2);
    }

    private void ringBand(Renderer3D r, double x, double y, double z, double rad, Color c, int alpha, double rot, int lobes) {
        int n = 28;
        double rin = rad * 0.82, rout = rad * 1.18;
        for (int i = 0; i < n; i++) {
            double a0 = Math.PI * 2 * i / n, a1 = Math.PI * 2 * (i + 1) / n;
            double m0 = 0.25 + 0.75 * (0.5 + 0.5 * Math.cos(lobes * (a0 - rot)));
            double m1 = 0.25 + 0.75 * (0.5 + 0.5 * Math.cos(lobes * (a1 - rot)));
            Color k0 = new Color(c.r, c.g, c.b, (int) (alpha * 0.9 * m0));
            Color k1 = new Color(c.r, c.g, c.b, (int) (alpha * 0.9 * m1));
            Color e = new Color(c.r, c.g, c.b, 0);
            double c0 = Math.cos(a0), s0 = Math.sin(a0), c1 = Math.cos(a1), s1 = Math.sin(a1);
            // inside half
            quad(r,
                x + c0 * rin, y, z + s0 * rin, x + c1 * rin, y, z + s1 * rin,
                x + c1 * rad, y, z + s1 * rad, x + c0 * rad, y, z + s0 * rad,
                e, e, k1, k0);
            // outside half
            quad(r,
                x + c0 * rad, y, z + s0 * rad, x + c1 * rad, y, z + s1 * rad,
                x + c1 * rout, y, z + s1 * rout, x + c0 * rout, y, z + s0 * rout,
                k0, k1, e, e);
        }
    }

    /** Small lights that rise through the beam and fade out at the top. */
    private void motes(Renderer3D r, int id, double x, double y, double z, double h, Color c, int alpha, double secs, double sp) {
        for (int j = 0; j < 5; j++) {
            double phase = (secs * 0.3 * sp + j / 5.0 + id * 0.137) % 1.0;
            double yy = y + 0.15 + phase * (h - 0.3);
            double ang = secs * 1.4 * sp + j * 1.26 + id;
            double rr = 0.12 + 0.10 * Math.sin(secs * 1.1 + j * 2.0);
            double px = x + Math.cos(ang) * rr, pz = z + Math.sin(ang) * rr;
            int a = (int) (alpha * Math.sin(Math.PI * phase));
            if (a < 3) continue;
            double s = 0.035;
            Color hot = new Color(Math.min(255, c.r + 70), Math.min(255, c.g + 70), Math.min(255, c.b + 70), a);
            Color halo = new Color(c.r, c.g, c.b, 0);
            Color mid = new Color(c.r, c.g, c.b, (int) (a * 0.5));
            // tiny soft glow, then the bright dot
            double g = s * 3.2;
            quad(r,
                px - rx * g - ux * g, yy - ry * g - uy * g, pz - rz * g - uz * g,
                px + rx * g - ux * g, yy + ry * g - uy * g, pz + rz * g - uz * g,
                px + rx * g + ux * g, yy + ry * g + uy * g, pz + rz * g + uz * g,
                px - rx * g + ux * g, yy - ry * g + uy * g, pz - rz * g + uz * g,
                halo, halo, halo, halo);
            quad(r,
                px - rx * s * 1.6 - ux * s * 1.6, yy - ry * s * 1.6 - uy * s * 1.6, pz - rz * s * 1.6 - uz * s * 1.6,
                px + rx * s * 1.6 - ux * s * 1.6, yy + ry * s * 1.6 - uy * s * 1.6, pz + rz * s * 1.6 - uz * s * 1.6,
                px + rx * s * 1.6 + ux * s * 1.6, yy + ry * s * 1.6 + uy * s * 1.6, pz + rz * s * 1.6 + uz * s * 1.6,
                px - rx * s * 1.6 + ux * s * 1.6, yy - ry * s * 1.6 + uy * s * 1.6, pz - rz * s * 1.6 + uz * s * 1.6,
                mid, mid, mid, mid);
            quad(r,
                px - rx * s - ux * s, yy - ry * s - uy * s, pz - rz * s - uz * s,
                px + rx * s - ux * s, yy + ry * s - uy * s, pz + rz * s - uz * s,
                px + rx * s + ux * s, yy + ry * s + uy * s, pz + rz * s + uz * s,
                px - rx * s + ux * s, yy - ry * s + uy * s, pz - rz * s + uz * s,
                hot, hot, hot, hot);
        }
    }

    private static double clamp(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    /** Meteor's quad() wants the colors as top-left, top-right, bottom-right, bottom-left (= vertices 2, 3, 4, 1). */
    private static void quad(Renderer3D r,
                             double x1, double y1, double z1, double x2, double y2, double z2,
                             double x3, double y3, double z3, double x4, double y4, double z4,
                             Color cv1, Color cv2, Color cv3, Color cv4) {
        r.quad(x1, y1, z1, x2, y2, z2, x3, y3, z3, x4, y4, z4, cv2, cv3, cv4, cv1);
    }
}
