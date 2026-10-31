package com.blockoutlines.addon.modules;

import com.blockoutlines.addon.BlockOutlinesAddon;
import com.blockoutlines.addon.casino.Game;
import com.blockoutlines.addon.casino.MinesGame;
import com.blockoutlines.addon.casino.UI;
import com.blockoutlines.addon.util.Money;
import com.blockoutlines.addon.util.WorldText;
import meteordevelopment.meteorclient.events.meteor.MouseClickEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.renderer.Renderer3D;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.KeybindSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.misc.input.KeyAction;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A real arcade machine standing in the world: a cabinet with a lit marquee, a screen with the Mines field whose
 * tiles stick out like little blocks, a deck of push buttons that sink when you press them, and a lever.
 * You look at a tile / button / the lever and press the left mouse button. It is the same game (and the same fair
 * draws and chips) as in the casino window.
 *
 * Machine coordinates: u = to the right (as you see it), v = up from the floor, w = toward you from the front plane.
 */
public class Casino3D extends Module {
    private static final double SCR_U = -0.55, SCR_V = 2.18, PITCH = 0.285, TILE = 0.25, SCR_W = 0.10;

    private final SettingGroup sg = settings.getDefaultGroup();
    private final Setting<Double> distance = sg.add(new DoubleSetting.Builder().name("distance")
        .description("How far in front of you the machine stands.").defaultValue(3.3).range(1.5, 8).sliderRange(2, 6).build());
    private final Setting<Double> scale = sg.add(new DoubleSetting.Builder().name("scale")
        .description("Size of the machine.").defaultValue(0.9).range(0.5, 2.5).sliderRange(0.6, 1.6).build());
    private final Setting<Boolean> throughWalls = sg.add(new BoolSetting.Builder().name("through-walls")
        .description("Show the machine even behind blocks.").defaultValue(false).build());
    private final Setting<Keybind> placeKey = sg.add(new KeybindSetting.Builder().name("place-key")
        .description("Puts the machine in front of you again.").defaultValue(Keybind.none()).action(this::place).build());

    private static final class El {
        int id;
        double u0, v0, u1, v1, w;
        String label;
        int color;
        boolean enabled;
        Runnable action;
        double text;
    }

    private MinesGame mines;
    private boolean placed;
    private double ax, ay, az, rx, rz, nx, nz, s = 1;
    private double vu, vv, vw;                       // where you are, in machine coordinates
    private final List<El> els = new ArrayList<>();
    private final Map<Integer, Long> pressed = new HashMap<>();
    private long leverAt = -10000;
    private El hover;
    private double hu, hv;
    private boolean hit;

    public Casino3D() {
        super(BlockOutlinesAddon.CATEGORY, "casino-3d", "A Mines arcade machine standing in the world: look at a tile or button and click.");
    }

    @Override
    public void onActivate() {
        Casino c = Modules.get().get(Casino.class);
        if (c == null) return;
        c.ensureLoaded();
        mines = new MinesGame(c);
        place();
    }

    private void place() {
        if (mc.player == null) return;
        double yaw = Math.toRadians(mc.player.getYaw());
        double fx = -Math.sin(yaw), fz = Math.cos(yaw);
        rx = -fz;
        rz = fx;
        nx = -fx;
        nz = -fz;
        ax = mc.player.getX() + fx * distance.get();
        az = mc.player.getZ() + fz * distance.get();
        ay = mc.player.getY();
        placed = true;
    }

    // ------------------------------------------------------------------ machine <-> world

    private double X(double u, double w) {
        return ax + rx * u * s + nx * w * s;
    }

    private double Z(double u, double w) {
        return az + rz * u * s + nz * w * s;
    }

    private double Y(double v) {
        return ay + v * s;
    }

    private static Color col(int argb) {
        return new Color((argb >> 16) & 255, (argb >> 8) & 255, argb & 255, (argb >>> 24) & 255);
    }

    private static int shade(int argb, float f) {
        int a = (argb >>> 24) & 255;
        int r = Math.min(255, Math.round(((argb >> 16) & 255) * f));
        int g = Math.min(255, Math.round(((argb >> 8) & 255) * f));
        int b = Math.min(255, Math.round((argb & 255) * f));
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /** A rectangle facing you at depth w. */
    private void flat(Renderer3D r, double u0, double v0, double u1, double v1, double w, int argb) {
        if (((argb >>> 24) & 255) < 3) return;
        r.quad(X(u0, w), Y(v1), Z(u0, w), X(u1, w), Y(v1), Z(u1, w), X(u1, w), Y(v0), Z(u1, w), X(u0, w), Y(v0), Z(u0, w), col(argb));
    }

    /** A solid box: the faces you can see are drawn, lit differently so it looks round and solid. */
    private void box(Renderer3D r, double u0, double v0, double w0, double u1, double v1, double w1, int argb) {
        if (vw > w1) flat(r, u0, v0, u1, v1, w1, argb);
        if (vv > v1) {
            Color c = col(shade(argb, 1.22f));
            r.quad(X(u0, w0), Y(v1), Z(u0, w0), X(u1, w0), Y(v1), Z(u1, w0), X(u1, w1), Y(v1), Z(u1, w1), X(u0, w1), Y(v1), Z(u0, w1), c);
        }
        if (vv < v0) {
            Color c = col(shade(argb, 0.5f));
            r.quad(X(u0, w0), Y(v0), Z(u0, w0), X(u1, w0), Y(v0), Z(u1, w0), X(u1, w1), Y(v0), Z(u1, w1), X(u0, w1), Y(v0), Z(u0, w1), c);
        }
        if (vu < u0) {
            Color c = col(shade(argb, 0.7f));
            r.quad(X(u0, w0), Y(v1), Z(u0, w0), X(u0, w1), Y(v1), Z(u0, w1), X(u0, w1), Y(v0), Z(u0, w1), X(u0, w0), Y(v0), Z(u0, w0), c);
        }
        if (vu > u1) {
            Color c = col(shade(argb, 0.7f));
            r.quad(X(u1, w0), Y(v1), Z(u1, w0), X(u1, w1), Y(v1), Z(u1, w1), X(u1, w1), Y(v0), Z(u1, w1), X(u1, w0), Y(v0), Z(u1, w0), c);
        }
    }

    private void poly(Renderer3D r, double[] us, double[] vs, double w, int argb) {
        r.quad(X(us[0], w), Y(vs[0]), Z(us[0], w), X(us[1], w), Y(vs[1]), Z(us[1], w), X(us[2], w), Y(vs[2]), Z(us[2], w), X(us[3], w), Y(vs[3]), Z(us[3], w), col(argb));
    }

    private void text(Renderer3D r, String str, double u, double v, double w, double size, double maxW, int argb) {
        WorldText.drawFit(r, str, X(u, w), Y(v), Z(u, w), rx, rz, size * s, maxW * s, argb);
    }

    // ------------------------------------------------------------------ the controls

    private void add(int id, double u0, double v0, double u1, double v1, double w, String label, int color, boolean enabled, double text, Runnable action) {
        El e = new El();
        e.id = id;
        e.u0 = u0;
        e.v0 = v0;
        e.u1 = u1;
        e.v1 = v1;
        e.w = w;
        e.label = label;
        e.color = color;
        e.enabled = enabled;
        e.text = text;
        e.action = action;
        els.add(e);
    }

    private void mainAction() {
        leverAt = System.currentTimeMillis();
        if (mines.playing()) mines.cashOutNow();
        else mines.startRound();
    }

    private void build() {
        els.clear();
        boolean playing = mines.playing();
        for (int i = 0; i < MinesGame.tiles(); i++) {
            final int idx = i;
            double cu = SCR_U + (i % 5 - 2) * PITCH, cv = SCR_V - (i / 5 - 2) * PITCH;
            add(i, cu - TILE / 2, cv - TILE / 2, cu + TILE / 2, cv + TILE / 2, SCR_W + 0.035, "", 0, playing && mines.tileFlip(i) < 0, 0, () -> mines.pickTileNow(idx));
        }
        String[] names = {"100", "1K", "10K", "100K"};
        int[] colors = {0xFF8C8CA0, 0xFFD94A4A, 0xFF3F6BE0, 0xFF2A2A35};
        for (int i = 0; i < 4; i++) {
            final long v = Game.CHIPS[i];
            double u0 = -1.2 + i * 0.33;
            add(110 + i, u0, 0.93, u0 + 0.29, 1.2, 0.06, names[i], colors[i], !playing, 0.1, () -> mines.betAdd(v));
        }
        add(120, 0.2, 0.93, 0.5, 1.2, 0.06, "Clear", 0xFF4A4A62, !playing, 0.09, mines::betClear);
        add(121, 0.53, 0.93, 0.83, 1.2, 0.06, "1/2", 0xFF4A4A62, !playing, 0.09, mines::betHalf);
        add(122, 0.86, 0.93, 1.16, 1.2, 0.06, "x2", 0xFF4A4A62, !playing, 0.09, mines::betDouble);
        add(100, -1.2, 0.56, -0.9, 0.84, 0.06, "-", 0xFF4A4A62, !playing && mines.minesCount() > 1, 0.16, () -> mines.minesDelta(-1));
        add(101, -0.6, 0.56, -0.3, 0.84, 0.06, "+", 0xFF4A4A62, !playing && mines.minesCount() < 24, 0.16, () -> mines.minesDelta(1));
        if (playing) {
            boolean can = mines.safeCount() > 0;
            add(130, 0.0, 0.56, 1.16, 0.88, 0.06, can ? "CASH OUT " + Money.pretty(mines.winNow()) : "pick a tile", can ? 0xFF2EBF5E : 0xFF4A4A62, can, 0.12, this::mainAction);
        } else {
            add(130, 0.0, 0.56, 1.16, 0.88, 0.06, "START", UI.ACCENT, true, 0.15, this::mainAction);
        }
        add(140, 1.46, 1.62, 1.68, 2.75, 0.12, "", 0, true, 0, this::mainAction);       // the lever
    }

    // ------------------------------------------------------------------ drawing

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (!placed || mines == null || mc.player == null || mc.world == null) return;
        s = scale.get();
        Renderer3D r = throughWalls.get() ? event.renderer : event.depthRenderer;
        long now = System.currentTimeMillis();

        Vec3d o = mc.player.getCameraPosVec(event.tickDelta);
        Vec3d d = mc.player.getRotationVec(event.tickDelta);
        vu = ((o.x - ax) * rx + (o.z - az) * rz) / s;
        vw = ((o.x - ax) * nx + (o.z - az) * nz) / s;
        vv = (o.y - ay) / s;
        if (vw < 0) return;                                 // behind the machine: nothing to see or touch

        build();

        // what are you pointing at? every control is tested on its own depth, the nearest one wins
        hit = false;
        hover = null;
        double denom = d.x * nx + d.z * nz;
        double best = 1e9;
        if (denom < -1e-4) {
            for (El e : els) {
                double t = ((ax + nx * e.w * s - o.x) * nx + (az + nz * e.w * s - o.z) * nz) / denom;
                if (t <= 0 || t > 14 || t >= best) continue;
                double px = o.x + d.x * t, py = o.y + d.y * t, pz = o.z + d.z * t;
                double u = ((px - ax) * rx + (pz - az) * rz) / s, v = (py - ay) / s;
                if (u >= e.u0 && u <= e.u1 && v >= e.v0 && v <= e.v1) {
                    best = t;
                    hover = e;
                    hu = u;
                    hv = v;
                    hit = true;
                }
            }
        }

        long resultAge = mines.resultAge();
        boolean celebrate = resultAge >= 0 && resultAge < 3000 && !mines.resultText().isEmpty();

        // ---------------- the cabinet
        box(r, -1.5, 0, -0.9, 1.5, 0.5, 0.35, 0xFF2A1646);                    // plinth
        flat(r, -1.5, 0.4, 1.5, 0.46, 0.355, 0xFFFFC857);                     // gold stripe
        flat(r, -0.55, 0.1, 0.55, 0.3, 0.356, 0xFF08080D);                    // coin tray
        flat(r, -0.5, 0.12, 0.5, 0.18, 0.357, 0xFF1B1B26);
        box(r, -1.35, 0.5, -0.9, 1.35, 1.3, 0.06, 0xFF3A2160);                // button deck
        box(r, -1.35, 1.3, -0.9, 1.35, 3.05, SCR_W, 0xFF171030);              // screen housing
        box(r, -1.62, 0, -0.9, -1.48, 3.6, 0.22, 0xFF4B2A7A);                 // side pillars
        box(r, 1.48, 0, -0.9, 1.62, 3.6, 0.22, 0xFF4B2A7A);
        double pulse = 0.5 + 0.5 * Math.sin(now / 260.0);
        int strip = UI.mix(0xFF1B6E8A, 0xFF58DDE8, (float) pulse);
        flat(r, -1.575, 0.1, -1.525, 3.5, 0.225, strip);
        flat(r, 1.525, 0.1, 1.575, 3.5, 0.225, strip);
        box(r, -1.5, 3.05, -0.9, 1.5, 3.6, 0.38, 0xFF241038);                 // marquee
        box(r, -0.25, 3.6, -0.5, 0.25, 3.78, 0.0, 0xFFD94A4A);                // the red crown
        flat(r, -1.25, 3.18, 1.25, 3.5, 0.385, 0xFF0B0716);
        double glow = celebrate ? 0.5 + 0.5 * Math.sin(now / 70.0) : 0.5 + 0.5 * Math.sin(now / 400.0);
        text(r, "MINES", 0, 3.34, 0.39, 0.34, 1.8, UI.mix(0xFFB88A1E, 0xFFFFE066, (float) glow));
        // the chasing bulbs
        int litCol = celebrate ? (mines.resultColor() | 0xFF000000) : 0xFFFFE066;
        for (int i = 0; i < 17; i++) {
            double bu = -1.28 + i * 0.16;
            boolean on = celebrate ? ((i + now / 90) % 2 == 0) : ((i + now / 140) % 4 == 0);
            int c = on ? litCol : 0xFF4B3C1C;
            box(r, bu - 0.035, 3.09, 0.38, bu + 0.035, 3.16, 0.42, c);
            box(r, bu - 0.035, 3.52, 0.38, bu + 0.035, 3.59, 0.42, on ? litCol : 0xFF4B3C1C);
        }

        // ---------------- the screen
        flat(r, -1.25, 1.4, 1.25, 2.98, SCR_W + 0.004, 0xFF07070E);
        flat(r, -1.25, 1.4, 1.25, 1.42, SCR_W + 0.006, 0xFF7C5CFF);
        flat(r, -1.25, 2.96, 1.25, 2.98, SCR_W + 0.006, 0xFF7C5CFF);
        flat(r, -1.25, 1.4, -1.23, 2.98, SCR_W + 0.006, 0xFF7C5CFF);
        flat(r, 1.23, 1.4, 1.25, 2.98, SCR_W + 0.006, 0xFF7C5CFF);

        for (int i = 0; i < MinesGame.tiles(); i++) {
            El e = els.get(i);
            double cu = (e.u0 + e.u1) / 2, cv = (e.v0 + e.v1) / 2;
            float f = mines.tileFlip(i);
            double half = TILE / 2 * (f < 0 ? 1 : Math.max(0.03, Math.abs(1 - 2 * f)));
            boolean hov = hover == e && e.enabled;
            if (f < 0.5f) {
                // a hidden tile is a little block that sticks out of the screen
                double h = hov ? 0.05 : 0.035;
                box(r, cu - half, cv - TILE / 2, SCR_W, cu + half, cv + TILE / 2, SCR_W + h, hov ? 0xFF5A5A82 : 0xFF3A3A55);
                if (vw > SCR_W + h) flat(r, cu - half + 0.012, cv + TILE / 2 - 0.05, cu + half - 0.012, cv + TILE / 2 - 0.03, SCR_W + h + 0.002, 0x30FFFFFF);
                if (hov) {
                    double w2 = SCR_W + h + 0.004;
                    flat(r, cu - half, cv + TILE / 2 - 0.016, cu + half, cv + TILE / 2, w2, 0xFFFFC857);
                    flat(r, cu - half, cv - TILE / 2, cu + half, cv - TILE / 2 + 0.016, w2, 0xFFFFC857);
                    flat(r, cu - half, cv - TILE / 2, cu - half + 0.016, cv + TILE / 2, w2, 0xFFFFC857);
                    flat(r, cu + half - 0.016, cv - TILE / 2, cu + half, cv + TILE / 2, w2, 0xFFFFC857);
                }
            } else {
                int a = mines.tileDim(i) ? 0x99 : 0xFF;
                boolean m = mines.tileMine(i);
                double w = SCR_W + 0.01;
                flat(r, cu - half, cv - TILE / 2, cu + half, cv + TILE / 2, w, (a << 24) | (m ? 0x4A1A1F : 0x123D2B));
                double k = Math.max(0.03, Math.abs(1 - 2 * f));
                if (m) {
                    flat(r, cu - 0.07 * k, cv - 0.07, cu + 0.07 * k, cv + 0.07, w + 0.004, (a << 24) | 0xD94A4A);
                    flat(r, cu - 0.07 * k, cv - 0.02, cu + 0.07 * k, cv + 0.02, w + 0.008, (a << 24) | 0xFFFFFF);
                    flat(r, cu - 0.02 * k, cv + 0.07, cu + 0.02 * k, cv + 0.11, w + 0.008, (a << 24) | 0x5A5A66);
                } else {
                    poly(r, new double[]{cu, cu + 0.075 * k, cu, cu - 0.075 * k}, new double[]{cv + 0.09, cv, cv - 0.09, cv}, w + 0.004, (a << 24) | 0x58DDE8);
                    poly(r, new double[]{cu, cu + 0.03 * k, cu, cu - 0.03 * k}, new double[]{cv + 0.06, cv + 0.015, cv - 0.025, cv + 0.015}, w + 0.008, (a << 24) | 0xC9FBFF);
                }
            }
        }

        // the numbers on the screen (the right part)
        double tw = SCR_W + 0.012;
        text(r, String.format("%.2fx", mines.multiplier()), 0.82, 2.78, tw, 0.2, 0.62, 0xFF3DDC84);
        if (mines.playing()) text(r, String.format("next %.2fx", mines.nextMultiplier()), 0.82, 2.55, tw, 0.085, 0.6, 0xFFFFFFFF);
        else text(r, "fair odds", 0.82, 2.55, tw, 0.085, 0.6, UI.MUTED);
        text(r, mines.minesCount() + " mines", 0.82, 2.3, tw, 0.1, 0.6, 0xFFFFC857);
        text(r, "bet " + Money.pretty(mines.bet()), 0.82, 2.08, tw, 0.1, 0.6, 0xFFFFFFFF);
        text(r, "chips", 0.82, 1.84, tw, 0.075, 0.6, UI.MUTED);
        text(r, Money.pretty(Modules.get().get(Casino.class).balance), 0.82, 1.67, tw, 0.11, 0.6, 0xFFFFC857);

        // the result, as a plate across the screen
        if (celebrate) {
            float fade = resultAge > 2400 ? 1f - (resultAge - 2400) / 600f : 1f;
            float pop = UI.easeBack(resultAge / 300f);
            int a = Math.max(0, Math.min(255, (int) (255 * fade)));
            int cc = (a << 24) | (mines.resultColor() & 0xFFFFFF);
            double pw = 0.62 * Math.max(0.05, pop);
            flat(r, SCR_U - pw, 2.0, SCR_U + pw, 2.42, SCR_W + 0.05, ((int) (0xE0 * fade) << 24) | 0x0A0A14);
            flat(r, SCR_U - pw, 2.4, SCR_U + pw, 2.42, SCR_W + 0.052, cc);
            flat(r, SCR_U - pw, 2.0, SCR_U + pw, 2.02, SCR_W + 0.052, cc);
            text(r, mines.resultText(), SCR_U, 2.27, SCR_W + 0.06, 0.15 * pop, 1.1, cc);
            if (!mines.resultSub().isEmpty()) text(r, mines.resultSub(), SCR_U, 2.08, SCR_W + 0.06, 0.1 * pop, 1.1, (a << 24) | 0xFFFFFF);
        }

        // ---------------- the buttons (they sink when you press them)
        for (El e : els) {
            if (e.id < 100 || e.id == 140) continue;
            boolean hov = hover == e;
            Long at = pressed.get(e.id);
            float dn = at == null ? 0f : Math.max(0f, 1f - (now - at) / 220f);
            double h = 0.075 * (1 - 0.8 * dn);
            int body = e.enabled ? e.color : UI.mix(e.color, 0xFF1C1C26, 0.7f);
            if (hov && e.enabled) body = UI.mix(body, 0xFFFFFFFF, 0.2f);
            box(r, e.u0, e.v0, e.w, e.u1, e.v1, e.w + h, body);
            double wt = e.w + h;
            if (vw > wt) flat(r, e.u0 + 0.015, e.v1 - 0.045, e.u1 - 0.015, e.v1 - 0.02, wt + 0.002, 0x38FFFFFF);
            if (hov && e.enabled) {
                flat(r, e.u0, e.v1 - 0.014, e.u1, e.v1, wt + 0.004, 0xFFFFC857);
                flat(r, e.u0, e.v0, e.u1, e.v0 + 0.014, wt + 0.004, 0xFFFFC857);
                flat(r, e.u0, e.v0, e.u0 + 0.014, e.v1, wt + 0.004, 0xFFFFC857);
                flat(r, e.u1 - 0.014, e.v0, e.u1, e.v1, wt + 0.004, 0xFFFFC857);
            }
            text(r, e.label, (e.u0 + e.u1) / 2, (e.v0 + e.v1) / 2 - 0.005, wt + 0.006, e.text, (e.u1 - e.u0) * 0.88, e.enabled ? 0xFFFFFFFF : 0xFF8A8A9C);
        }
        text(r, "mines", -0.75, 0.9, 0.07, 0.075, 0.4, UI.MUTED);
        text(r, String.valueOf(mines.minesCount()), -0.75, 0.7, 0.07, 0.2, 0.25, 0xFFFFC857);
        text(r, "bet " + Money.pretty(mines.bet()) + "     chips " + Money.pretty(Modules.get().get(Casino.class).balance), 0, 1.265, 0.07, 0.085, 2.4, UI.MUTED);

        // ---------------- the lever
        double pull = Math.sin(Math.PI * Math.max(0, Math.min(1, (now - leverAt) / 500.0)));
        if (now - leverAt > 500) pull = 0;
        double ballV = 2.55 - 0.7 * pull;
        box(r, 1.5, 1.55, 0.05, 1.64, 1.7, 0.18, 0xFF8C8CA0);                   // the base
        box(r, 1.55, 1.7, 0.09, 1.59, ballV, 0.13, 0xFFB8B8C8);                 // the rod
        boolean lh = hover != null && hover.id == 140;
        box(r, 1.49, ballV, 0.05, 1.65, ballV + 0.17, 0.21, lh ? 0xFFFF7A7A : 0xFFD94A4A);   // the ball
        if (vw > 0.21) flat(r, 1.51, ballV + 0.12, 1.63, ballV + 0.15, 0.212, 0x50FFFFFF);

        // ---------------- where you are pointing
        if (hit) {
            double w = hover != null ? hover.w + 0.09 : 0.2;
            flat(r, hu - 0.012, hv - 0.012, hu + 0.012, hv + 0.012, w, 0xFFFFC857);
        }
    }

    @EventHandler
    private void onMouseClick(MouseClickEvent event) {
        if (!placed || mines == null || mc.currentScreen != null || event.button() != 0) return;
        if (hover == null) return;
        event.cancel();                         // do not attack or break something behind the machine
        if (event.action == KeyAction.Press && hover.enabled && hover.action != null) {
            pressed.put(hover.id, System.currentTimeMillis());
            hover.action.run();
        }
    }
}
