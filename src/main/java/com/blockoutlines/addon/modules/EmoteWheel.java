package com.blockoutlines.addon.modules;

import com.blockoutlines.addon.BlockOutlinesAddon;
import com.blockoutlines.addon.gui.RadialWheelScreen;
import com.blockoutlines.addon.util.Shapes;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.KeybindSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * Hold a key to open a round menu of emotes, move the mouse to one and let go to play it. The first emote is "67":
 * a 6 in one hand and a 7 in the other, both hands going up and down in turns. The hands and numbers are drawn next to
 * you on your own screen (your own arms do not move).
 */
public class EmoteWheel extends Module {
    private static final String[] SIX = {"XXX", "X..", "XXX", "X.X", "XXX"};
    private static final String[] SEVEN = {"XXX", "..X", "..X", ".X.", ".X."};

    private final SettingGroup sg = settings.getDefaultGroup();

    private final Setting<Keybind> key = sg.add(new KeybindSetting.Builder()
        .name("key").description("Hold this key to open the emote wheel, move the mouse to an emote and let go.")
        .defaultValue(Keybind.fromKey(GLFW.GLFW_KEY_R)).build());
    private final Setting<Double> duration = sg.add(new DoubleSetting.Builder()
        .name("duration").description("How long an emote plays (seconds).").defaultValue(4.0).range(1.0, 20.0).sliderRange(2.0, 10.0).build());
    private final Setting<Double> speed = sg.add(new DoubleSetting.Builder()
        .name("speed").description("How fast the hands go up and down (times per second).").defaultValue(2.2).range(0.5, 5.0).sliderRange(1.0, 4.0).build());
    private final Setting<Double> scale = sg.add(new DoubleSetting.Builder()
        .name("size").description("Size of the hands and numbers.").defaultValue(1.0).range(0.5, 2.5).sliderRange(0.6, 2.0).build());
    private final Setting<Boolean> cancelOnMove = sg.add(new BoolSetting.Builder()
        .name("stop-when-moving").description("The emote stops when you walk or jump.").defaultValue(true).build());
    private final Setting<SettingColor> handColor = sg.add(new ColorSetting.Builder()
        .name("hand-color").description("Color of the hands.").defaultValue(new SettingColor(240, 200, 165, 255)).build());
    private final Setting<SettingColor> numberColor = sg.add(new ColorSetting.Builder()
        .name("number-color").description("Color of the numbers.").defaultValue(new SettingColor(255, 255, 255, 255)).build());
    private final Setting<SettingColor> accent = sg.add(new ColorSetting.Builder()
        .name("wheel-color").description("Color of the slice under the mouse.").defaultValue(new SettingColor(236, 72, 153, 255)).build());

    private final Shapes.Batch batch = new Shapes.Batch();
    private boolean wasDown;
    private long startedAt;
    private boolean playing;
    private double yawRad;

    public EmoteWheel() {
        super(BlockOutlinesAddon.COSMETICS, "emote-wheel", "Hold a key for a round menu of emotes. First emote: 67.");
    }

    @Override
    public void onDeactivate() {
        playing = false;
    }

    // ------------------------------------------------------------------ wheel

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) { playing = false; return; }
        boolean down = key.get().isPressed();
        if (down && !wasDown && mc.currentScreen == null) openWheel();
        wasDown = down;

        if (playing) {
            double t = (System.currentTimeMillis() - startedAt) / 1000.0;
            boolean moved = cancelOnMove.get() && (mc.player.getVelocity().horizontalLength() > 0.08 || !mc.player.isOnGround());
            if (t > duration.get() || moved) playing = false;
        }
    }

    private void openWheel() {
        List<RadialWheelScreen.Entry> entries = new ArrayList<>();
        entries.add(new RadialWheelScreen.Entry("67", "6 and 7", (ctx, cx, cy, sz) ->
            ctx.drawCenteredTextWithShadow(MinecraftClient.getInstance().textRenderer, "6 7", cx, cy - 4, 0xFFFFFFFF),
            this::start67));
        int a = (255 << 24) | (accent.get().r << 16) | (accent.get().g << 8) | accent.get().b;
        mc.setScreen(new RadialWheelScreen("Emotes", entries, key.get(), null, 95, a, "Release to play"));
    }

    private void start67() {
        if (mc.player == null) return;
        playing = true;
        startedAt = System.currentTimeMillis();
        yawRad = Math.toRadians(mc.player.getYaw());
    }

    // ------------------------------------------------------------------ the "67" emote

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (!playing || mc.player == null) return;
        double t = (System.currentTimeMillis() - startedAt) / 1000.0;
        double total = duration.get();
        // grow in, shrink out
        double fade = Math.min(1.0, Math.min(t / 0.25, (total - t) / 0.3));
        if (fade <= 0.01) return;
        double ease = 1.0 - Math.pow(1.0 - Math.max(0.0, Math.min(1.0, fade)), 3.0);
        double s = scale.get() * ease;

        Vec3d p = mc.player.getLerpedPos(event.tickDelta);
        double cy = Math.cos(yawRad), sy = Math.sin(yawRad);
        Shapes.Tf tf = (lx, ly, lz) -> new double[]{p.x - lx * cy - lz * sy, p.y + ly, p.z - lx * sy + lz * cy};

        double phase = t * speed.get() * Math.PI * 2.0;
        double swing = 0.3 * Math.sin(phase);
        double baseY = 1.15 * scale.get();
        double fwd = 0.6 * scale.get();
        double sideX = 0.72 * scale.get();

        SettingColor hc = handColor.get(), nc = numberColor.get();
        // left hand holds the 6 (it goes up first), right hand holds the 7 (down first)
        drawHand(tf, -sideX, baseY + swing * s, fwd, s, hc, nc, SIX);
        drawHand(tf, sideX, baseY - swing * s, fwd, s, hc, nc, SEVEN);

        batch.flush(event.depthRenderer, event.offsetX, event.offsetY, event.offsetZ);
    }

    private void drawHand(Shapes.Tf tf, double x, double y, double z, double s, SettingColor hand, SettingColor num, String[] digit) {
        // fist and wrist
        batch.add(tf, x, y, z, 0.17 * s, 0.17 * s, 0.2 * s, 0, hand.r, hand.g, hand.b, 255);
        batch.add(tf, x, y - 0.13 * s, z, 0.13 * s, 0.1 * s, 0.14 * s, 0, hand.r * 3 / 4, hand.g * 3 / 4, hand.b * 3 / 4, 255);
        // dark plate and the number above the hand
        double px = 0.065 * s;
        double plateW = 3 * px + 0.06 * s, plateH = 5 * px + 0.06 * s;
        double topY = y + 0.16 * s + plateH;
        batch.add(tf, x, topY - plateH / 2, z - 0.03 * s, plateW, plateH, 0.03 * s, 0, 25, 25, 35, 255);
        for (int row = 0; row < 5; row++) {
            for (int col = 0; col < 3; col++) {
                if (digit[row].charAt(col) != 'X') continue;
                double lx = x + (col - 1) * px;
                double ly = topY - 0.03 * s - px / 2 - row * px;
                batch.add(tf, lx, ly, z, px, px, 0.04 * s, 0, num.r, num.g, num.b, 255);
            }
        }
    }
}
