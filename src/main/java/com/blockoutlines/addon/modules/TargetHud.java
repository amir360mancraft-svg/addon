package com.blockoutlines.addon.modules;

import com.blockoutlines.addon.BlockOutlinesAddon;
import com.blockoutlines.addon.util.RoundRect;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.s2c.play.EntityStatusS2CPacket;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * A small card (like Bedrock's player doll): it shows you. When you look at another player it turns into an info
 * card with the player's model, name, ping, health, held items, totem pops and every armor piece with its
 * enchantments and durability. Look away and it goes back to showing only you.
 */
public class TargetHud extends Module {
    public enum Anchor { BottomLeft, TopLeft, BottomRight, TopRight }

    private final SettingGroup sg = settings.getDefaultGroup();
    private final SettingGroup sgInfo = settings.createGroup("Info");
    private final SettingGroup sgLook = settings.createGroup("Look");

    private final Setting<Boolean> showSelf = sg.add(new BoolSetting.Builder()
        .name("show-yourself").description("Show your own character when you are not looking at a player.")
        .defaultValue(true).build());
    private final Setting<Boolean> selfHealth = sg.add(new BoolSetting.Builder()
        .name("yourself-health-bar").description("Health bar under your own character.")
        .defaultValue(true).visible(showSelf::get).build());
    private final Setting<Double> range = sg.add(new DoubleSetting.Builder()
        .name("range").description("How far away a player can be and still be picked when you look at him.")
        .defaultValue(40.0).range(3.0, 120.0).sliderRange(3.0, 100.0).build());
    private final Setting<Double> holdTime = sg.add(new DoubleSetting.Builder()
        .name("hold-time").description("Seconds the card stays after you look away from the player.")
        .defaultValue(1.5).range(0.0, 8.0).sliderRange(0.0, 5.0).build());
    private final Setting<Anchor> anchor = sg.add(new EnumSetting.Builder<Anchor>()
        .name("anchor").description("Which corner of the screen the card sits in.")
        .defaultValue(Anchor.BottomLeft).build());
    private final Setting<Integer> offsetX = sg.add(new IntSetting.Builder()
        .name("offset-x").description("Distance from the screen edge, sideways.")
        .defaultValue(6).range(0, 2000).sliderRange(0, 600).build());
    private final Setting<Integer> offsetY = sg.add(new IntSetting.Builder()
        .name("offset-y").description("Distance from the screen edge, up / down.")
        .defaultValue(40).range(0, 2000).sliderRange(0, 600).build());
    private final Setting<Boolean> keepDragged = sg.add(new BoolSetting.Builder()
        .name("use-dragged-position").description("Open the chat and drag the card to move it. Turn off to go back to the anchor.")
        .defaultValue(true).build());
    private final Setting<Integer> dragX = sg.add(new IntSetting.Builder()
        .name("dragged-x").description("Stored position (set by dragging).")
        .defaultValue(-1).range(-1, 4000).visible(() -> false).build());
    private final Setting<Integer> dragY = sg.add(new IntSetting.Builder()
        .name("dragged-y").description("Stored position (set by dragging).")
        .defaultValue(-1).range(-1, 4000).visible(() -> false).build());
    private final Setting<Double> scale = sg.add(new DoubleSetting.Builder()
        .name("scale").description("Size of the whole card.")
        .defaultValue(1.0).range(0.4, 3.0).sliderRange(0.4, 2.5).build());

    private final Setting<Boolean> showModel = sgInfo.add(new BoolSetting.Builder()
        .name("model").description("Show the character model.").defaultValue(true).build());
    private final Setting<Boolean> showPing = sgInfo.add(new BoolSetting.Builder()
        .name("ping").description("Show the ping of the player.").defaultValue(true).build());
    private final Setting<Boolean> showHealthText = sgInfo.add(new BoolSetting.Builder()
        .name("health-number").description("Show the health as a number.").defaultValue(true).build());
    private final Setting<Boolean> showHeld = sgInfo.add(new BoolSetting.Builder()
        .name("held-items").description("Show what the player holds in both hands.").defaultValue(true).build());
    private final Setting<Boolean> showPops = sgInfo.add(new BoolSetting.Builder()
        .name("totem-pops").description("Count how many totems the player has popped.").defaultValue(true).build());
    private final Setting<Boolean> showArmor = sgInfo.add(new BoolSetting.Builder()
        .name("armor").description("Show the four armor pieces.").defaultValue(true).build());
    private final Setting<Boolean> showEnchants = sgInfo.add(new BoolSetting.Builder()
        .name("enchantments").description("Write the enchantments under every armor piece (Pro 4, Unb 3 ...).")
        .defaultValue(true).visible(showArmor::get).build());

    private final Setting<Integer> rounding = sgLook.add(new IntSetting.Builder()
        .name("rounding").description("How round the corners are.")
        .defaultValue(6).range(0, 30).sliderRange(0, 30).build());
    private final Setting<SettingColor> background = sgLook.add(new ColorSetting.Builder()
        .name("background").description("Colour of the card.")
        .defaultValue(new SettingColor(14, 16, 22, 255)).build());
    private final Setting<Integer> backgroundAlpha = sgLook.add(new IntSetting.Builder()
        .name("background-alpha").description("Opacity of the card (0 = invisible, 255 = solid).")
        .defaultValue(190).range(0, 255).sliderRange(0, 255).build());
    private final Setting<Boolean> outline = sgLook.add(new BoolSetting.Builder()
        .name("outline").description("Border around the card.").defaultValue(true).build());
    private final Setting<Integer> outlineThickness = sgLook.add(new IntSetting.Builder()
        .name("outline-thickness").description("How thick the border is.")
        .defaultValue(1).range(1, 8).sliderRange(1, 6).visible(outline::get).build());
    private final Setting<SettingColor> outlineColor = sgLook.add(new ColorSetting.Builder()
        .name("outline-color").description("Colour of the border.")
        .defaultValue(new SettingColor(255, 255, 255, 60)).visible(outline::get).build());
    private final Setting<SettingColor> nameColor = sgLook.add(new ColorSetting.Builder()
        .name("name-color").description("Colour of the player name.")
        .defaultValue(new SettingColor(235, 238, 250, 255)).build());
    private final Setting<SettingColor> textColor = sgLook.add(new ColorSetting.Builder()
        .name("text-color").description("Colour of the small texts (enchantments, pops).")
        .defaultValue(new SettingColor(215, 220, 235, 255)).build());
    private final Setting<SettingColor> barColor = sgLook.add(new ColorSetting.Builder()
        .name("health-color").description("Colour of the health bar when health is full.")
        .defaultValue(new SettingColor(255, 140, 0, 255)).build());
    private final Setting<SettingColor> barLowColor = sgLook.add(new ColorSetting.Builder()
        .name("health-low-color").description("Colour of the health bar when health is nearly gone.")
        .defaultValue(new SettingColor(235, 40, 40, 255)).build());
    private final Setting<SettingColor> barBackColor = sgLook.add(new ColorSetting.Builder()
        .name("health-back-color").description("Colour behind the health bar.")
        .defaultValue(new SettingColor(0, 0, 0, 140)).build());
    private final Setting<SettingColor> absorbColor = sgLook.add(new ColorSetting.Builder()
        .name("absorption-color").description("Colour of the extra (golden hearts) part of the bar.")
        .defaultValue(new SettingColor(255, 215, 60, 255)).build());

    // layout, in card units (the whole card is drawn under one scale matrix)
    private static final int PAD = 6, MODEL_W = 46, MODEL_H = 70, BAR_H = 8, COL_W = 34, LINE_H = 7;
    private static final float SMALL = 0.65f;
    private static final int INFO_W = COL_W * 4;
    private static final int MAX_ENCHANT_LINES = 6;

    private final Map<UUID, Integer> pops = new HashMap<>();
    private PlayerEntity target;
    private long lastSeen;
    private boolean dragging;
    private double grabX, grabY;

    public TargetHud() {
        super(BlockOutlinesAddon.COMBAT, "target-hud", "Shows you - and when you look at a player, his name, ping, health, armor and totem pops.");
    }

    @Override
    public void onActivate() {
        pops.clear();
        target = null;
        dragging = false;
    }

    @EventHandler
    private void onPacket(PacketEvent.Receive event) {
        if (mc.world == null || !(event.packet instanceof EntityStatusS2CPacket p)) return;
        if (p.getStatus() != 35) return; // totem of undying used
        // network thread -> hand over to the game thread
        mc.execute(() -> {
            if (mc.world != null && p.getEntity(mc.world) instanceof PlayerEntity pl) pops.merge(pl.getUuid(), 1, Integer::sum);
        });
    }

    // ------------------------------------------------------------------ picking the player you look at

    private PlayerEntity lookedAt() {
        if (mc.player == null || mc.world == null) return null;
        Vec3d eye = mc.player.getEyePos();
        Vec3d look = mc.player.getRotationVec(1.0f);
        double max = range.get();
        Vec3d end = eye.add(look.multiply(max));

        PlayerEntity best = null;
        double bestDist = Double.MAX_VALUE;
        for (PlayerEntity p : mc.world.getPlayers()) {
            if (p == mc.player || p.isSpectator() || p.isRemoved()) continue;
            Box box = p.getBoundingBox().expand(0.25);
            Optional<Vec3d> hit = box.raycast(eye, end);
            if (hit.isEmpty()) continue;
            double d = hit.get().squaredDistanceTo(eye);
            if (d < bestDist) {
                bestDist = d;
                best = p;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ drawing

    @EventHandler
    private void onRender2D(Render2DEvent event) {
        if (mc.player == null || mc.world == null || mc.getWindow() == null || mc.options.hudHidden) return;

        long now = System.currentTimeMillis();
        PlayerEntity seen = lookedAt();
        if (seen != null) {
            target = seen;
            lastSeen = now;
        } else if (target != null && (now - lastSeen > holdTime.get() * 1000.0 || target.isRemoved()
            || !mc.world.getPlayers().contains(target))) {
            target = null;
        }

        boolean info = target != null;
        if (!info && !showSelf.get()) return;
        PlayerEntity who = info ? target : mc.player;

        boolean model = showModel.get();
        boolean armorOn = info && showArmor.get();
        boolean enchOn = armorOn && showEnchants.get();

        // size of the card
        int infoH = 0;
        if (info) {
            infoH = 14 + 20 + (armorOn ? 22 + (enchOn ? MAX_ENCHANT_LINES * LINE_H : 0) : 0);
        }
        int leftW = model ? MODEL_W : (info ? 0 : 60);
        int w = PAD * 2 + leftW + (info ? (leftW > 0 ? 8 : 0) + (armorOn ? INFO_W : 112) : 0);
        int modelBlockH = (model ? MODEL_H + 6 : 0) + (info || selfHealth.get() ? BAR_H : 0);
        int h = PAD * 2 + Math.max(modelBlockH, info ? infoH : 0);
        if (!info && !model && !selfHealth.get()) return;
        w = Math.max(w, PAD * 2 + 60);

        float s = scale.get().floatValue();
        int sw = mc.getWindow().getScaledWidth();
        int sh = mc.getWindow().getScaledHeight();
        int pw = Math.round(w * s), ph = Math.round(h * s);

        int x, y;
        if (keepDragged.get() && dragX.get() >= 0 && dragY.get() >= 0) {
            x = dragX.get();
            y = dragY.get();
        } else {
            x = switch (anchor.get()) {
                case BottomLeft, TopLeft -> offsetX.get();
                default -> sw - pw - offsetX.get();
            };
            y = switch (anchor.get()) {
                case TopLeft, TopRight -> offsetY.get();
                default -> sh - ph - offsetY.get();
            };
        }
        x = Math.max(0, Math.min(sw - pw, x));
        y = Math.max(0, Math.min(sh - ph, y));
        handleDrag(x, y, pw, ph, sw, sh);
        if (keepDragged.get() && dragX.get() >= 0 && dragY.get() >= 0) {
            x = Math.max(0, Math.min(sw - pw, dragX.get()));
            y = Math.max(0, Math.min(sh - ph, dragY.get()));
        }

        DrawContext ctx = event.drawContext;
        ctx.getMatrices().pushMatrix();
        ctx.getMatrices().translate(x, y);
        ctx.getMatrices().scale(s, s);

        // card
        int radius = Math.min(rounding.get(), Math.min(w, h) / 2);
        RoundRect.draw(ctx, 0, 0, w, h, radius, withAlpha(background.get(), backgroundAlpha.get()));
        if (outline.get()) ring(ctx, w, h, radius, outlineThickness.get(), argb(outlineColor.get()));

        // health bar under the model (or under the whole info card when there is no model)
        float hp = who.getHealth(), maxHp = Math.max(1f, who.getMaxHealth()), absorb = who.getAbsorptionAmount();
        int barX = PAD, barW = (model ? MODEL_W : (info ? 60 : 60));
        if (info && !model) barW = w - PAD * 2;
        int barY = PAD + (model ? MODEL_H + 6 : 0);
        if (info && !model) barY = h - PAD - BAR_H;
        if (info || selfHealth.get()) bar(ctx, barX, barY, barW, hp, maxHp, absorb);

        if (info) {
            int ix = PAD + (model ? MODEL_W + 8 : 0);
            int iy = PAD;

            // name, ping, health
            String name = who.getName().getString();
            text(ctx, name, ix, iy, 1.0f, argb(nameColor.get()), true);
            int cursor = ix + (int) (mc.textRenderer.getWidth(name) * 1.0f) + 4;
            if (showPing.get()) {
                PlayerListEntry entry = mc.getNetworkHandler() != null ? mc.getNetworkHandler().getPlayerListEntry(who.getUuid()) : null;
                int ping = entry != null ? entry.getLatency() : 0;
                String p = "(" + ping + "ms)";
                text(ctx, p, cursor, iy, 1.0f, pingColor(ping), true);
                cursor += (int) (mc.textRenderer.getWidth(p) * 1.0f) + 4;
            }
            if (showHealthText.get()) {
                String hs = String.format("%.1f", hp + absorb);
                text(ctx, hs, cursor, iy, 1.0f, argb(barLowColor.get()), true);
            }

            // held items + totem pops
            int rowY = iy + 14;
            int hx = ix;
            if (showHeld.get()) {
                ItemStack main = who.getMainHandStack(), off = who.getOffHandStack();
                if (!main.isEmpty()) { ctx.drawItem(main, hx, rowY); ctx.drawStackOverlay(mc.textRenderer, main, hx, rowY); hx += 20; }
                if (!off.isEmpty()) { ctx.drawItem(off, hx, rowY); ctx.drawStackOverlay(mc.textRenderer, off, hx, rowY); hx += 20; }
            }
            if (showPops.get()) {
                int n = pops.getOrDefault(who.getUuid(), 0);
                text(ctx, "Pops: " + n, hx + 2, rowY + 4, 0.85f, argb(textColor.get()), false);
            }

            // armor
            if (armorOn) {
                int colY = rowY + 22;
                EquipmentSlot[] slots = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
                for (int i = 0; i < 4; i++) {
                    ItemStack piece = who.getEquippedStack(slots[i]);
                    int cx = ix + i * COL_W;
                    if (piece.isEmpty()) continue;
                    ctx.drawItem(piece, cx + (COL_W - 16) / 2 - 2, colY);
                    ctx.drawStackOverlay(mc.textRenderer, piece, cx + (COL_W - 16) / 2 - 2, colY);
                    if (enchOn) {
                        List<String> lines = enchantLines(piece);
                        for (int l = 0; l < lines.size() && l < MAX_ENCHANT_LINES; l++) {
                            String t = lines.get(l);
                            float tw = mc.textRenderer.getWidth(t) * SMALL;
                            text(ctx, t, cx + (COL_W - 4 - tw) / 2f, colY + 19 + l * LINE_H, SMALL, argb(textColor.get()), false);
                        }
                    }
                }
            }
        }
        ctx.getMatrices().popMatrix();

        // the model is drawn in real screen coordinates (after the scaled part), so it can not drift out of the card
        if (model) {
            int mx1 = Math.round(x + PAD * s), my1 = Math.round(y + PAD * s);
            int mx2 = Math.round(x + (PAD + MODEL_W) * s), my2 = Math.round(y + (PAD + MODEL_H) * s);
            float cx = (mx1 + mx2) / 2f, cy = (my1 + my2) / 2f;
            try {
                InventoryScreen.drawEntity(ctx, mx1, my1, mx2, my2, Math.round(30 * s), 0.0625f, cx, cy - 18 * s, (net.minecraft.entity.LivingEntity) who);
            } catch (Throwable ignored) {
            }
        }
    }

    private void bar(DrawContext ctx, int x, int y, int w, float hp, float maxHp, float absorb) {
        int r = BAR_H / 2;
        RoundRect.draw(ctx, x, y, w, BAR_H, r, argb(barBackColor.get()));
        float ratio = Math.max(0f, Math.min(1f, hp / maxHp));
        int fill = Math.round(w * ratio);
        if (fill > 0) {
            SettingColor a = barColor.get(), b = barLowColor.get();
            float t = ratio;
            int red = (int) (b.r + (a.r - b.r) * t), green = (int) (b.g + (a.g - b.g) * t), blue = (int) (b.b + (a.b - b.b) * t);
            RoundRect.draw(ctx, x, y, Math.max(fill, 2), BAR_H, Math.min(r, fill / 2), (255 << 24) | (red << 16) | (green << 8) | blue);
        }
        if (absorb > 0) {
            int extra = Math.round(w * Math.min(1f, absorb / maxHp));
            if (extra > 0) {
                SettingColor g = absorbColor.get();
                ctx.fill(x + 1, y + BAR_H - 2, x + 1 + Math.min(w - 2, extra), y + BAR_H - 1, argb(g));
            }
        }
    }

    private List<String> enchantLines(ItemStack stack) {
        List<String> out = new ArrayList<>();
        if (!stack.hasEnchantments()) return out;
        for (Object2IntMap.Entry<RegistryEntry<Enchantment>> e : stack.getEnchantments().getEnchantmentEntries()) {
            String id = e.getKey().getIdAsString();
            String path = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
            out.add(abbreviate(path) + " " + e.getIntValue());
        }
        return out;
    }

    private static String abbreviate(String path) {
        return switch (path) {
            case "protection" -> "Pro";
            case "blast_protection" -> "Bla";
            case "fire_protection" -> "Fir";
            case "projectile_protection" -> "Prj";
            case "unbreaking" -> "Unb";
            case "mending" -> "Men";
            case "feather_falling" -> "Fea";
            case "thorns" -> "Thr";
            case "respiration" -> "Res";
            case "aqua_affinity" -> "Aqu";
            case "depth_strider" -> "Dep";
            case "soul_speed" -> "Sou";
            case "swift_sneak" -> "Swi";
            case "frost_walker" -> "Fro";
            case "binding_curse" -> "Bin";
            case "vanishing_curse" -> "Van";
            default -> path.isEmpty() ? "?" : Character.toUpperCase(path.charAt(0)) + path.substring(1, Math.min(3, path.length()));
        };
    }

    private static int pingColor(int ping) {
        if (ping < 60) return 0xFF55FF55;
        if (ping < 120) return 0xFFFFFF55;
        if (ping < 200) return 0xFFFFAA00;
        return 0xFFFF5555;
    }

    private void text(DrawContext ctx, String value, float x, float y, float s, int argb, boolean shadow) {
        if (value == null || value.isEmpty()) return;
        ctx.getMatrices().pushMatrix();
        ctx.getMatrices().translate(x, y);
        ctx.getMatrices().scale(s, s);
        ctx.drawText(mc.textRenderer, value, 0, 0, argb, shadow);
        ctx.getMatrices().popMatrix();
    }

    // ------------------------------------------------------------------ border as a ring (so a transparent card stays clean)

    private static int inset(int row, int h, int r) {
        if (r <= 0) return 0;
        int d = row < h / 2 ? row : h - 1 - row;
        if (d >= r) return 0;
        double dy = r - d - 0.5;
        return (int) Math.round(r - Math.sqrt(Math.max(0.0, (double) r * r - dy * dy)));
    }

    private void ring(DrawContext ctx, int w, int h, int r, int t, int argb) {
        t = Math.max(1, Math.min(t, Math.min(w, h) / 2));
        int ir = Math.max(0, r - t);
        for (int row = 0; row < h; row++) {
            int oi = inset(row, h, r);
            if (row < t || row >= h - t) {
                ctx.fill(oi, row, w - oi, row + 1, argb);
            } else {
                int ii = Math.max(oi, t + inset(row - t, h - 2 * t, ir));
                if (ii > oi) {
                    ctx.fill(oi, row, ii, row + 1, argb);
                    ctx.fill(w - ii, row, w - oi, row + 1, argb);
                }
            }
        }
    }

    // ------------------------------------------------------------------ dragging (chat open)

    private void handleDrag(int x, int y, int w, int h, int sw, int sh) {
        if (!(mc.currentScreen instanceof ChatScreen) || !keepDragged.get()) {
            dragging = false;
            return;
        }
        double mx = mc.mouse.getX() * sw / (double) mc.getWindow().getWidth();
        double my = mc.mouse.getY() * sh / (double) mc.getWindow().getHeight();
        boolean down = GLFW.glfwGetMouseButton(mc.getWindow().getHandle(), GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
        if (!down) {
            dragging = false;
            return;
        }
        if (!dragging) {
            if (mx < x || mx > x + w || my < y || my > y + h) return;
            dragging = true;
            grabX = mx - x;
            grabY = my - y;
        }
        dragX.set(Math.max(0, Math.min(sw - w, (int) (mx - grabX))));
        dragY.set(Math.max(0, Math.min(sh - h, (int) (my - grabY))));
    }

    private static int argb(SettingColor c) {
        return (c.a << 24) | (c.r << 16) | (c.g << 8) | c.b;
    }

    private static int withAlpha(SettingColor c, int alpha) {
        return (Math.max(0, Math.min(255, alpha)) << 24) | (c.r << 16) | (c.g << 8) | c.b;
    }
}
