package com.blockoutlines.addon.modules;

import com.blockoutlines.addon.BlockOutlinesAddon;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WVerticalList;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.DrawContext;

import java.util.ArrayDeque;
import java.util.Queue;

/**
 * A glass pill at the top of the screen, like the iPhone's Dynamic Island: a small oval drops in, grows to show a short
 * message, then shrinks back and disappears. Other modules use {@link #push} to show their alerts here instead of in chat.
 */
public class DynamicIsland extends Module {
    private final SettingGroup sg = settings.getDefaultGroup();

    private final Setting<Double> hold = sg.add(new DoubleSetting.Builder()
        .name("show-seconds").description("How long the message stays open.").defaultValue(3.0).range(1.0, 15.0).sliderRange(1.0, 8.0).build());
    private final Setting<Integer> topOffset = sg.add(new IntSetting.Builder()
        .name("top-offset").description("Distance from the top of the screen.").defaultValue(8).range(0, 300).sliderRange(0, 100).build());
    private final Setting<Double> scale = sg.add(new DoubleSetting.Builder()
        .name("scale").description("Size of the island.").defaultValue(1.0).range(0.5, 3.0).sliderRange(0.5, 2.0).build());
    private final Setting<Integer> glass = sg.add(new IntSetting.Builder()
        .name("glass-opacity").description("How see-through the glass is (100 = solid black).").defaultValue(72).range(20, 100).sliderRange(20, 100).build());

    private static final class Note {
        String title, sub;
        int rgb;
        long start;
        double holdMs;
    }

    private static DynamicIsland instance;
    private final Queue<Note> queue = new ArrayDeque<>();
    private Note cur;

    // timeline in milliseconds
    private static final double T_DROP = 300, T_OPEN = 450, T_CLOSE = 380, T_GONE = 240;

    public DynamicIsland() {
        super(BlockOutlinesAddon.COMBAT, "dynamic-island",
            "A glass pill at the top of the screen that opens to show alerts (staff joined, etc.) and then closes again.");
        instance = this;
    }

    /** Shows a message on the island. Returns false when the module is off, so the caller can fall back to chat. */
    public static boolean push(String title, String sub, int rgb) {
        DynamicIsland i = instance;
        if (i == null || !i.isActive()) return false;
        if (i.queue.size() >= 6) return true;
        Note n = new Note();
        n.title = title;
        n.sub = sub == null ? "" : (sub.length() > 38 ? sub.substring(0, 37) + "…" : sub);
        n.rgb = rgb & 0xFFFFFF;
        i.queue.add(n);
        return true;
    }

    @Override
    public void onActivate() {
        queue.clear();
        cur = null;
    }

    @Override
    public void onDeactivate() {
        queue.clear();
        cur = null;
    }

    @Override
    public WWidget getWidget(GuiTheme theme) {
        WVerticalList list = theme.verticalList();
        WButton test = list.add(theme.button("Show a test message")).expandX().widget();
        test.action = () -> {
            if (!isActive()) { warning("Turn the module on first."); return; }
            push("Staff joined", "Notch is now online", 0xFF5050);
        };
        return list;
    }

    // ------------------------------------------------------------------ drawing

    @EventHandler
    private void onRender2D(Render2DEvent event) {
        if (mc.player == null || mc.world == null) { cur = null; queue.clear(); return; }
        long now = System.currentTimeMillis();
        if (cur == null) {
            cur = queue.poll();
            if (cur == null) return;
            cur.start = now;
            // when more messages are waiting, do not keep this one open for the full time
            cur.holdMs = queue.isEmpty() ? hold.get() * 1000 : Math.min(hold.get() * 1000, 1200);
        }

        var tr = mc.textRenderer;
        double t = now - cur.start;

        int titleW = tr.getWidth(cur.title), subW = cur.sub.isEmpty() ? 0 : tr.getWidth(cur.sub);
        boolean hasSub = !cur.sub.isEmpty();
        double fullW = Math.max(120, 30 + Math.max(titleW, subW) + 16);
        double fullH = hasSub ? 34 : 24;
        double smallW = 46, smallH = 14;

        double w, h, yOff = 0, alpha = 1, open;
        double tOpenEnd = T_DROP + T_OPEN, tHoldEnd = tOpenEnd + cur.holdMs, tCloseEnd = tHoldEnd + T_CLOSE, tEnd = tCloseEnd + T_GONE;

        if (t < T_DROP) {
            double p = easeOutCubic(t / T_DROP);
            w = lerp(8, smallW, p);
            h = lerp(8, smallH, p);
            yOff = lerp(-smallH, 0, p);
            alpha = Math.min(1, p * 1.5);
            open = 0;
        } else if (t < tOpenEnd) {
            double lin = (t - T_DROP) / T_OPEN;
            double p = easeOutBack(lin);
            w = lerp(smallW, fullW, p);
            h = lerp(smallH, fullH, p);
            open = lin;
        } else if (t < tHoldEnd) {
            // a tiny breathing motion while it is open
            double br = Math.sin((t - tOpenEnd) / 380.0) * 0.6;
            w = fullW + br;
            h = fullH + br * 0.4;
            open = 1;
        } else if (t < tCloseEnd) {
            double lin = (t - tHoldEnd) / T_CLOSE;
            double p = easeInOutCubic(lin);
            w = lerp(fullW, smallW, p);
            h = lerp(fullH, smallH, p);
            open = 1 - lin;
        } else if (t < tEnd) {
            double lin = (t - tCloseEnd) / T_GONE;
            double p = lin * lin * lin;
            w = lerp(smallW, 6, p);
            h = lerp(smallH, 6, p);
            yOff = lerp(0, -smallH, p);
            alpha = 1 - p;
            open = 0;
        } else {
            cur = null;
            return;
        }

        DrawContext ctx = event.drawContext;
        float s = scale.get().floatValue();
        int sw = mc.getWindow().getScaledWidth();
        ctx.getMatrices().pushMatrix();
        ctx.getMatrices().translate(sw / 2f, (float) topOffset.get());
        ctx.getMatrices().scale(s, s);

        int iw = (int) Math.round(w), ih = Math.max(2, (int) Math.round(h));
        int x = -iw / 2, y = (int) Math.round(yOff);
        int a = (int) Math.round(255 * alpha);
        double g = glass.get() / 100.0;

        // soft shadow
        pill(ctx, x - 2, y - 1, iw + 4, ih + 5, argb((int) (a * 0.10), 0, 0, 0), argb((int) (a * 0.10), 0, 0, 0));
        pill(ctx, x - 1, y, iw + 2, ih + 3, argb((int) (a * 0.16), 0, 0, 0), argb((int) (a * 0.16), 0, 0, 0));
        // glass edge (bright on top, faint at the bottom)
        pill(ctx, x, y, iw, ih, argb((int) (a * 0.55), 255, 255, 255), argb((int) (a * 0.12), 255, 255, 255));
        // body
        if (iw > 4 && ih > 4) {
            pill(ctx, x + 1, y + 1, iw - 2, ih - 2,
                argb((int) (a * g), 38, 42, 52), argb((int) (a * g), 8, 10, 14));
            // gloss on the upper half
            if (iw > 10 && ih > 10) {
                pill(ctx, x + 2, y + 2, iw - 4, Math.max(2, (int) ((ih - 4) * 0.45)),
                    argb((int) (a * 0.20), 255, 255, 255), argb((int) (a * 0.02), 255, 255, 255));
            }
        }

        // content, only once it is wide enough to hold it
        double ta = clamp((open - 0.55) / 0.45) * alpha;
        int tA = (int) Math.round(255 * ta);
        if (tA >= 12) {
            int cy = y + ih / 2;
            // glowing dot on the left
            double pulse = 0.75 + 0.25 * Math.sin(now / 260.0);
            int r = (cur.rgb >> 16) & 255, gg = (cur.rgb >> 8) & 255, b = cur.rgb & 255;
            pill(ctx, x + 9, cy - 8, 16, 16, argb((int) (tA * 0.22 * pulse), r, gg, b), argb((int) (tA * 0.22 * pulse), r, gg, b));
            pill(ctx, x + 12, cy - 5, 10, 10, argb(tA, r, gg, b), argb(tA, Math.max(0, r - 60), Math.max(0, gg - 60), Math.max(0, b - 60)));

            int tx = x + 30;
            if (hasSub) {
                ctx.drawText(tr, cur.title, tx, y + 7, (tA << 24) | 0xFFFFFF, false);
                ctx.drawText(tr, cur.sub, tx, y + 19, (tA << 24) | 0xB4B9C4, false);
            } else {
                ctx.drawText(tr, cur.title, tx, cy - 4, (tA << 24) | 0xFFFFFF, false);
            }
        }

        ctx.getMatrices().popMatrix();
    }

    /** A pill (fully rounded rectangle) built from one-pixel rows, with a color that fades from top to bottom. */
    private static void pill(DrawContext ctx, int x, int y, int w, int h, int top, int bottom) {
        if (w <= 0 || h <= 0) return;
        double r = Math.min(h, w) / 2.0;
        for (int i = 0; i < h; i++) {
            double yc = i + 0.5, dy = 0;
            if (yc < r) dy = r - yc;
            else if (yc > h - r) dy = yc - (h - r);
            double inset = r - Math.sqrt(Math.max(0, r * r - dy * dy));
            int x1 = x + (int) Math.round(inset), x2 = x + w - (int) Math.round(inset);
            if (x2 <= x1) continue;
            ctx.fill(x1, y + i, x2, y + i + 1, mix(top, bottom, h <= 1 ? 0 : i / (double) (h - 1)));
        }
    }

    private static int mix(int c1, int c2, double t) {
        int a = (int) Math.round(lerp((c1 >>> 24) & 255, (c2 >>> 24) & 255, t));
        int r = (int) Math.round(lerp((c1 >> 16) & 255, (c2 >> 16) & 255, t));
        int g = (int) Math.round(lerp((c1 >> 8) & 255, (c2 >> 8) & 255, t));
        int b = (int) Math.round(lerp(c1 & 255, c2 & 255, t));
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static int argb(int a, int r, int g, int b) {
        a = Math.max(0, Math.min(255, a));
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static double lerp(double a, double b, double t) { return a + (b - a) * t; }
    private static double clamp(double v) { return v < 0 ? 0 : Math.min(v, 1); }
    private static double easeOutCubic(double t) { t = clamp(t); return 1 - Math.pow(1 - t, 3); }
    private static double easeInOutCubic(double t) { t = clamp(t); return t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2; }
    private static double easeOutBack(double t) {
        t = clamp(t);
        double c1 = 1.70158, c3 = c1 + 1;
        return 1 + c3 * Math.pow(t - 1, 3) + c1 * Math.pow(t - 1, 2);
    }
}
