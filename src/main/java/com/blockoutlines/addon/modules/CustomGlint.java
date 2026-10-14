package com.blockoutlines.addon.modules;

import com.blockoutlines.addon.BlockOutlinesAddon;
import com.blockoutlines.addon.mixin.AbstractTextureAccessor;
import com.blockoutlines.addon.mixin.TextureManagerAccessor;
import com.blockoutlines.addon.util.CosmeticUtil;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
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
import net.minecraft.client.render.item.ItemRenderer;
import net.minecraft.client.texture.AbstractTexture;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;

import java.util.Map;
import java.util.Random;

/**
 * Replaces the enchantment glint with an animated one: lightning bolts and light rays that flash up, fade and
 * move across every enchanted item (hand, inventory, armor). The glint texture is drawn live and uploaded
 * a few times per second, so nothing has to be added to a resource pack.
 */
public class CustomGlint extends Module {
    public enum Where { HandOnly, Everywhere }

    /** True while the first-person hand is being drawn (set by a mixin around GameRenderer.renderHand). */
    public static boolean handActive;

    // the ids vanilla itself uses (item glint = hand / inventory, entity glint = armor and mobs)
    private static final Identifier ITEM_GLINT = ItemRenderer.ITEM_ENCHANTMENT_GLINT;
    private static final Identifier ARMOR_GLINT = ItemRenderer.ENTITY_ENCHANTMENT_GLINT;
    private static final int N = 128;

    private final SettingGroup sg = settings.getDefaultGroup();
    private final SettingGroup sgBase = settings.createGroup("Base layer");
    private final SettingGroup sgBolt = settings.createGroup("Lightning");
    private final SettingGroup sgRay = settings.createGroup("Rays");

    private final Setting<Where> where = sg.add(new EnumSetting.Builder<Where>()
        .name("where").description("HandOnly: only the item in your hand (first person). Everywhere: also inventory, hotbar, armor.")
        .defaultValue(Where.HandOnly).build());
    private final Setting<SettingColor> coreColor = sg.add(new ColorSetting.Builder()
        .name("core-color").description("Colour of the bright centre of bolts and rays.")
        .defaultValue(new SettingColor(255, 255, 255, 255)).build());
    private final Setting<SettingColor> glowColor = sg.add(new ColorSetting.Builder()
        .name("glow-color").description("Colour of the glow around bolts and of the background.")
        .defaultValue(new SettingColor(170, 210, 255, 255)).build());
    private final Setting<Boolean> rainbow = sg.add(new BoolSetting.Builder()
        .name("rainbow").description("Cycle through all colours instead of using the two colours.")
        .defaultValue(false).build());
    private final Setting<Integer> brightness = sg.add(new IntSetting.Builder()
        .name("brightness").description("Overall strength of the glint, in percent.")
        .defaultValue(130).range(20, 400).sliderRange(20, 300).build());
    private final Setting<Integer> background = sg.add(new IntSetting.Builder()
        .name("background-glow").description("Soft moving glow behind the bolts (0 = only the bolts).")
        .defaultValue(0).range(0, 100).sliderRange(0, 100).build());
    private final Setting<Integer> fps = sg.add(new IntSetting.Builder()
        .name("animation-fps").description("How many times per second the glint is redrawn (lower = lighter on the PC).")
        .defaultValue(30).range(5, 60).sliderRange(5, 60).build());
    private final Setting<Boolean> armor = sg.add(new BoolSetting.Builder()
        .name("armor-glint").description("Use the same glint on enchanted armor (only in Everywhere mode).")
        .defaultValue(true).build());
    public final Setting<Boolean> allItems = sg.add(new BoolSetting.Builder()
        .name("all-items").description("Every item gets the effect, not only enchanted ones (in HandOnly mode: every item you hold).")
        .defaultValue(true).build());

    private final Setting<Boolean> baseOn = sgBase.add(new BoolSetting.Builder()
        .name("base-layer").description("A steady coloured layer over the whole item (like the blue items in the video); the bolts move on top of it.")
        .defaultValue(true).build());
    private final Setting<SettingColor> baseColor = sgBase.add(new ColorSetting.Builder()
        .name("base-color").description("Colour of the layer.")
        .defaultValue(new SettingColor(0, 100, 255, 255)).visible(baseOn::get).build());
    private final Setting<Integer> baseStrength = sgBase.add(new IntSetting.Builder()
        .name("base-strength").description("How strong the layer is, in percent.")
        .defaultValue(55).range(0, 200).sliderRange(0, 150).visible(baseOn::get).build());
    private final Setting<Integer> basePulse = sgBase.add(new IntSetting.Builder()
        .name("base-pulse").description("How much the layer breathes (brighter / darker), in percent. 0 = steady.")
        .defaultValue(15).range(0, 100).sliderRange(0, 60).visible(baseOn::get).build());
    private final Setting<Integer> boltRate = sgBolt.add(new IntSetting.Builder()
        .name("bolts-per-second").description("How many lightning bolts flash every second.")
        .defaultValue(14).range(0, 80).sliderRange(0, 60).build());
    private final Setting<Integer> boltLength = sgBolt.add(new IntSetting.Builder()
        .name("bolt-length").description("Length of a bolt in texture pixels.")
        .defaultValue(40).range(8, 120).sliderRange(8, 100).build());
    private final Setting<Integer> boltThickness = sgBolt.add(new IntSetting.Builder()
        .name("bolt-thickness").description("Thickness of the bright core.")
        .defaultValue(1).range(1, 4).sliderRange(1, 4).build());
    private final Setting<Double> boltJitter = sgBolt.add(new DoubleSetting.Builder()
        .name("bolt-zigzag").description("How jagged the bolts are.")
        .defaultValue(0.9).range(0.1, 2.0).sliderRange(0.1, 2.0).build());
    private final Setting<Double> branchChance = sgBolt.add(new DoubleSetting.Builder()
        .name("branch-chance").description("Chance for a bolt to split into smaller branches.")
        .defaultValue(0.06).range(0.0, 0.3).sliderRange(0.0, 0.3).build());
    private final Setting<Integer> fadeMs = sgBolt.add(new IntSetting.Builder()
        .name("fade-time").description("How long a bolt stays visible, in milliseconds.")
        .defaultValue(450).range(80, 2500).sliderRange(80, 1500).build());

    private final Setting<Integer> rayRate = sgRay.add(new IntSetting.Builder()
        .name("rays-per-second").description("How many straight light rays appear every second.")
        .defaultValue(6).range(0, 60).sliderRange(0, 40).build());
    private final Setting<Integer> rayLength = sgRay.add(new IntSetting.Builder()
        .name("ray-length").description("Length of a ray in texture pixels.")
        .defaultValue(70).range(20, 127).sliderRange(20, 127).build());
    private final Setting<Integer> rayStrength = sgRay.add(new IntSetting.Builder()
        .name("ray-strength").description("How bright the rays are, in percent.")
        .defaultValue(70).range(10, 200).sliderRange(10, 200).build());

    private final float[] r = new float[N * N], g = new float[N * N], b = new float[N * N];
    private final Random rnd = new Random();

    private NativeImageBackedTexture itemTex, armorTex;
    private NativeImage itemImg, armorImg;
    private long lastStep;
    private double spawnCarryBolt, spawnCarryRay;
    private boolean armorApplied, installedEverywhere, samplerDone;
    private AbstractTexture saved;

    public CustomGlint() {
        super(BlockOutlinesAddon.COSMETICS, "custom-glint", "Animated lightning / ray enchantment glint with your own colours.");
    }

    @Override
    public void onActivate() {
        java.util.Arrays.fill(r, 0f);
        java.util.Arrays.fill(g, 0f);
        java.util.Arrays.fill(b, 0f);
        lastStep = System.currentTimeMillis();
        spawnCarryBolt = spawnCarryRay = 0;
        armorApplied = false;
        install();
    }

    public boolean everywhere() { return where.get() == Where.Everywhere; }

    @Override
    public void onDeactivate() {
        handEnd();
        restore();
    }

    // ------------------------------------------------------------------ textures

    private void install() {
        try {
            samplerDone = false;
            installedEverywhere = everywhere();
            itemImg = new NativeImage(N, N, true);
            itemTex = new NativeImageBackedTexture(() -> "blockoutlines-glint-item", itemImg);
            if (installedEverywhere) {
                copySampler(mc.getTextureManager().getTexture(ITEM_GLINT), itemTex);
                mc.getTextureManager().registerTexture(ITEM_GLINT, itemTex);

                if (armor.get()) {
                    armorImg = new NativeImage(N, N, true);
                    armorTex = new NativeImageBackedTexture(() -> "blockoutlines-glint-armor", armorImg);
                    copySampler(mc.getTextureManager().getTexture(ARMOR_GLINT), armorTex);
                    mc.getTextureManager().registerTexture(ARMOR_GLINT, armorTex);
                    armorApplied = true;
                }
            }
        } catch (Throwable t) {
            error("Could not create the glint texture: " + t.getMessage());
        }
    }

    /** Called right before the first-person hand is drawn: our glint is put in place of the vanilla one. */
    public void handBegin() {
        if (itemTex == null || installedEverywhere) return;
        try {
            Map<Identifier, AbstractTexture> map = ((TextureManagerAccessor) mc.getTextureManager()).blockoutlines$textures();
            AbstractTexture cur = mc.getTextureManager().getTexture(ITEM_GLINT);
            if (cur != itemTex) {
                saved = cur;
                if (!samplerDone && cur != null) {
                    copySampler(cur, itemTex);
                    samplerDone = true;
                }
                map.put(ITEM_GLINT, itemTex);
            }
            handActive = true;
        } catch (Throwable ignored) {
        }
    }

    /** Called right after the hand: the vanilla glint goes back, so inventory, hotbar and the world keep theirs. */
    public void handEnd() {
        handActive = false;
        if (installedEverywhere || saved == null) return;
        try {
            Map<Identifier, AbstractTexture> map = ((TextureManagerAccessor) mc.getTextureManager()).blockoutlines$textures();
            if (map.get(ITEM_GLINT) == itemTex || itemTex == null) map.put(ITEM_GLINT, saved);
        } catch (Throwable ignored) {
        }
        saved = null;
    }

    /** The vanilla glint texture repeats; ours must repeat the same way, so it borrows the vanilla sampler. */
    private void copySampler(AbstractTexture from, AbstractTexture to) {
        try {
            if (from != null) ((AbstractTextureAccessor) to).blockoutlines$setSampler(from.getSampler());
        } catch (Throwable ignored) {
        }
    }

    private void restore() {
        try {
            if (itemTex != null && installedEverywhere) mc.getTextureManager().registerTexture(ITEM_GLINT);
            if (armorApplied) mc.getTextureManager().registerTexture(ARMOR_GLINT);
        } catch (Throwable ignored) {
        }
        try {
            if (itemTex != null && !installedEverywhere) itemTex.close();
        } catch (Throwable ignored) {
        }
        itemTex = null;
        armorTex = null;
        itemImg = null;
        armorImg = null;
        armorApplied = false;
    }

    // ------------------------------------------------------------------ animation

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (mc.player == null) return;
        long now = System.currentTimeMillis();
        long interval = 1000L / Math.max(1, fps.get());
        if (now - lastStep < interval) return;
        double dt = Math.min(0.2, (now - lastStep) / 1000.0);
        lastStep = now;

        // resource reload (F3+T) or armor toggled: put our textures back
        boolean every = everywhere();
        if (itemTex == null || every != installedEverywhere
            || (every && (mc.getTextureManager().getTexture(ITEM_GLINT) != itemTex || armor.get() != armorApplied))) {
            handEnd();
            restore();
            install();
            if (itemTex == null) return;
        }

        step(dt, now / 1000.0);
        upload();
    }

    private void step(double dt, double seconds) {
        float decay = (float) Math.exp(-dt * 4.0 / (fadeMs.get() / 1000.0));
        int count = N * N;
        for (int i = 0; i < count; i++) {
            r[i] *= decay;
            g[i] *= decay;
            b[i] *= decay;
        }

        spawnCarryBolt += boltRate.get() * dt;
        while (spawnCarryBolt >= 1.0) {
            spawnCarryBolt -= 1.0;
            bolt(seconds, rnd.nextDouble() * N, rnd.nextDouble() * N, rnd.nextDouble() * Math.PI * 2.0,
                boltLength.get() * (0.6 + rnd.nextDouble() * 0.8), boltThickness.get(), 1.0f, boltJitter.get(), 0);
        }
        spawnCarryRay += rayRate.get() * dt;
        while (spawnCarryRay >= 1.0) {
            spawnCarryRay -= 1.0;
            bolt(seconds, rnd.nextDouble() * N, rnd.nextDouble() * N, rnd.nextDouble() * Math.PI * 2.0,
                rayLength.get() * (0.7 + rnd.nextDouble() * 0.6), 1, rayStrength.get() / 100.0f, 0.0, 99);
        }
    }

    /** Draws one bolt (jagged line, wrapping around the texture edges); depth 99 = a straight ray, no branches. */
    private void bolt(double seconds, double x, double y, double ang, double length, int thick, float power, double jitter, int depth) {
        Color core = coreColor.get(), glow = glowColor.get();
        float[] cc, gc;
        if (rainbow.get()) {
            float hue = (float) (seconds * 0.25 + rnd.nextDouble() * 0.1);
            Color a = CosmeticUtil.hsv(hue, 0.35f, 1f, 255), bb = CosmeticUtil.hsv(hue, 0.9f, 1f, 255);
            cc = new float[]{a.r / 255f, a.g / 255f, a.b / 255f};
            gc = new float[]{bb.r / 255f, bb.g / 255f, bb.b / 255f};
        } else {
            cc = new float[]{core.r / 255f, core.g / 255f, core.b / 255f};
            gc = new float[]{glow.r / 255f, glow.g / 255f, glow.b / 255f};
        }

        double stepLen = 1.0;
        for (double d = 0; d < length; d += stepLen) {
            stamp(x, y, thick, power, cc, gc, 1.0f - (float) (d / length) * 0.35f);
            ang += (rnd.nextDouble() - 0.5) * jitter;
            x += Math.cos(ang) * stepLen;
            y += Math.sin(ang) * stepLen;
            if (depth < 2 && rnd.nextDouble() < branchChance.get() * 0.2) {
                bolt(seconds, x, y, ang + (rnd.nextBoolean() ? 1 : -1) * (0.5 + rnd.nextDouble()),
                    (length - d) * 0.55, Math.max(1, thick - 1), power * 0.75f, jitter, depth + 1);
            }
        }
    }

    private void stamp(double fx, double fy, int thick, float power, float[] cc, float[] gc, float taper) {
        int cx = (int) Math.floor(fx), cy = (int) Math.floor(fy);
        int halo = thick + 2;
        for (int dy = -halo; dy <= halo; dy++) {
            for (int dx = -halo; dx <= halo; dx++) {
                double dist = Math.sqrt(dx * dx + dy * dy);
                if (dist > halo) continue;
                int idx = Math.floorMod(cy + dy, N) * N + Math.floorMod(cx + dx, N);
                float k;
                float rr, gg, bb;
                if (dist <= thick - 0.5) { // bright core
                    k = power * taper;
                    rr = cc[0]; gg = cc[1]; bb = cc[2];
                } else {                   // soft halo
                    k = (float) (1.0 - (dist - (thick - 0.5)) / (halo - (thick - 0.5) + 0.01)) * 0.55f * power * taper;
                    rr = gc[0]; gg = gc[1]; bb = gc[2];
                }
                r[idx] = Math.max(r[idx], rr * k);
                g[idx] = Math.max(g[idx], gg * k);
                b[idx] = Math.max(b[idx], bb * k);
            }
        }
    }

    private void upload() {
        float gain = brightness.get() / 100.0f;
        float bg = background.get() / 100.0f * 0.35f;
        double t = System.currentTimeMillis() / 1000.0;
        Color glow = glowColor.get();
        float gr = glow.r / 255f, gg = glow.g / 255f, gb = glow.b / 255f;
        float baseR = 0f, baseG = 0f, baseB = 0f;
        if (baseOn.get()) {
            Color bc = baseColor.get();
            float k = baseStrength.get() / 100.0f * (1.0f + basePulse.get() / 100.0f * (float) Math.sin(t * 2.2));
            baseR = bc.r / 255f * k; baseG = bc.g / 255f * k; baseB = bc.b / 255f * k;
        }
        for (int y = 0; y < N; y++) {
            for (int x = 0; x < N; x++) {
                int i = y * N + x;
                float base = 0f;
                if (bg > 0f) {
                    // tileable soft moving clouds
                    double u = x / (double) N * Math.PI * 2.0, v = y / (double) N * Math.PI * 2.0;
                    base = (float) (0.5 + 0.25 * Math.sin(u * 2 + t * 0.9) + 0.25 * Math.sin(v * 3 - t * 0.7 + Math.sin(u * 2) * 1.5)) * bg;
                    base = Math.max(0f, base);
                }
                float rr, g2, bb2;
                if (rainbow.get()) {
                    Color c = CosmeticUtil.hsv((float) (t * 0.25 + x / (double) N * 0.5), 0.9f, 1f, 255);
                    rr = r[i] + base * c.r / 255f; g2 = g[i] + base * c.g / 255f; bb2 = b[i] + base * c.b / 255f;
                } else {
                    rr = r[i] + base * gr; g2 = g[i] + base * gg; bb2 = b[i] + base * gb;
                }
                rr += baseR; g2 += baseG; bb2 += baseB;
                int ri = clamp(rr * gain), gi = clamp(g2 * gain), bi = clamp(bb2 * gain);
                int argb = 0xFF000000 | (ri << 16) | (gi << 8) | bi;
                itemImg.setColorArgb(x, y, argb);
                if (armorImg != null) armorImg.setColorArgb(x, y, argb);
            }
        }
        itemTex.upload();
        if (armorTex != null) armorTex.upload();
    }

    private static int clamp(float v) {
        int i = (int) (v * 255f);
        return i < 0 ? 0 : Math.min(255, i);
    }
}
