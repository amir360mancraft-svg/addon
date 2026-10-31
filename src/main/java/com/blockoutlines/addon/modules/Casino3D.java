package com.blockoutlines.addon.modules;

import com.blockoutlines.addon.BlockOutlinesAddon;
import com.blockoutlines.addon.casino.BaccaratGame;
import com.blockoutlines.addon.casino.BlackjackGame;
import com.blockoutlines.addon.casino.CoinFlipGame;
import com.blockoutlines.addon.casino.CrashGame;
import com.blockoutlines.addon.casino.Game;
import com.blockoutlines.addon.casino.MinesGame;
import com.blockoutlines.addon.casino.RouletteGame;
import com.blockoutlines.addon.casino.Sfx;
import com.blockoutlines.addon.casino.UI;
import com.blockoutlines.addon.casino.WorldGfx;
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

/**
 * A solid casino station standing in the world, visible from every side: a pink cabinet with a lit CASINO sign,
 * a wooden desk and a big screen. On the DESK you pick the game and the bet; on the SCREEN the real game is played
 * (the very same games, fair draws and chips as the casino window). Look at a button / the screen and click.
 *
 * Machine coordinates: u = to the right (as seen from the front), v = up from the floor, w = toward the front.
 */
public class Casino3D extends Module {
    private static final int SW = 500, SH = 298, DW = 460, DH = 200;

    private final SettingGroup sg = settings.getDefaultGroup();
    private final Setting<Double> distance = sg.add(new DoubleSetting.Builder().name("distance")
        .description("How far in front of you the station stands.").defaultValue(3.2).range(1.5, 8).sliderRange(2, 6).build());
    private final Setting<Double> scale = sg.add(new DoubleSetting.Builder().name("scale")
        .description("Size of the station.").defaultValue(1.0).range(0.5, 2.5).sliderRange(0.6, 1.6).build());
    private final Setting<Boolean> throughWalls = sg.add(new BoolSetting.Builder().name("through-walls")
        .description("Show the station even behind blocks.").defaultValue(false).build());
    private final Setting<Keybind> placeKey = sg.add(new KeybindSetting.Builder().name("place-key")
        .description("Puts the station in front of you again.").defaultValue(Keybind.none()).action(this::place).build());

    private Casino casino;
    private Game[] games;
    private int cur = 3;
    private boolean placed;
    private double ax, ay, az, rx, rz, nx, nz, s = 1;
    private double vu, vv, vw;                                   // where you are, in machine coordinates

    private final WorldGfx scr = new WorldGfx(), desk = new WorldGfx();
    private int target;                                          // 0 nothing, 1 screen, 2 desk
    private int tx, ty;

    private final UI.Btn[] pick = new UI.Btn[6];
    private final UI.Btn clear = new UI.Btn("Clear", UI.PANEL2), half = new UI.Btn("1/2", UI.PANEL2),
        dbl = new UI.Btn("x2", UI.PANEL2), max = new UI.Btn("Max", UI.PANEL2), reset = new UI.Btn("Reset chips", 0xFF4A2C2C);
    private static final int[] CHIP_COL = {0xFF8C8CA0, 0xFFD94A4A, 0xFF3F6BE0, 0xFF2A2A35};
    private static final String[] CHIP_TXT = {"100", "1K", "10K", "100K"};
    private final int[] chipX = new int[4];
    private int chipY = 80;
    private static final int CHIP_R = 15;

    public Casino3D() {
        super(BlockOutlinesAddon.CATEGORY, "casino-3d", "A casino station standing in the world: choose the game and the bet on the desk, play on the screen.");
    }

    @Override
    public void onActivate() {
        casino = Modules.get().get(Casino.class);
        if (casino == null) return;
        casino.ensureLoaded();
        games = new Game[]{new BlackjackGame(casino), new BaccaratGame(casino), new CoinFlipGame(casino),
            new MinesGame(casino), new RouletteGame(casino), new CrashGame(casino)};
        for (int i = 0; i < games.length; i++) {
            games[i].world = true;
            pick[i] = new UI.Btn(games[i].name(), UI.PANEL2);
        }
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

    private void face(Renderer3D r, int argb, double u0, double v0, double w0, double u1, double v1, double w1,
                      double u2, double v2, double w2, double u3, double v3, double w3) {
        r.quad(X(u0, w0), Y(v0), Z(u0, w0), X(u1, w1), Y(v1), Z(u1, w1), X(u2, w2), Y(v2), Z(u2, w2), X(u3, w3), Y(v3), Z(u3, w3), col(argb));
    }

    // ---- surface patterns (the quads are plain colours, so the look is built from many small ones)

    private static double[] pt(double[] p0, double[] p1, double[] p3, double fa, double fb) {
        return new double[]{p0[0] + (p1[0] - p0[0]) * fa + (p3[0] - p0[0]) * fb,
            p0[1] + (p1[1] - p0[1]) * fa + (p3[1] - p0[1]) * fb,
            p0[2] + (p1[2] - p0[2]) * fa + (p3[2] - p0[2]) * fb};
    }

    private static double len(double[] a, double[] b) {
        return Math.sqrt((b[0] - a[0]) * (b[0] - a[0]) + (b[1] - a[1]) * (b[1] - a[1]) + (b[2] - a[2]) * (b[2] - a[2]));
    }

    private static double hash(int i, int j) {
        int h = i * 73856093 ^ j * 19349663;
        h ^= h >>> 13;
        h *= 0x5bd1e995;
        h ^= h >>> 15;
        return (h & 1023) / 1023.0;
    }

    /** A sub-rectangle of a face, given in blocks from its corner p0 along a (toward p1) and b (toward p3). */
    private void patch(Renderer3D r, double[] p0, double[] p1, double[] p3, double la, double lb,
                       double a0, double a1, double b0, double b1, int argb) {
        if (a1 <= a0 || b1 <= b0) return;
        double[] q0 = pt(p0, p1, p3, a0 / la, b0 / lb), q1 = pt(p0, p1, p3, a1 / la, b0 / lb),
            q2 = pt(p0, p1, p3, a1 / la, b1 / lb), q3 = pt(p0, p1, p3, a0 / la, b1 / lb);
        face(r, argb, q0[0], q0[1], q0[2], q1[0], q1[1], q1[2], q2[0], q2[1], q2[2], q3[0], q3[1], q3[2]);
    }

    private void styled(Renderer3D r, int style, int argb, double[] p0, double[] p1, double[] p2, double[] p3) {
        double la = len(p0, p1), lb = len(p0, p3);
        if (style == 1 && la > 0.2 && lb > 0.2) {                          // wooden boards
            patch(r, p0, p1, p3, la, lb, 0, la, 0, lb, shade(argb, 0.58f));
            boolean alongA = la >= lb;
            double length = alongA ? la : lb, thick = alongA ? lb : la;
            int n = Math.max(1, (int) Math.round(thick / 0.11));
            double st = thick / n;
            for (int i = 0; i < n; i++) {
                double t0 = i * st + 0.006, t1 = (i + 1) * st - 0.006, pos = 0;
                int j = 0;
                while (pos < length) {
                    double seg = j == 0 ? 0.2 + (i * 29 % 7) * 0.12 : 0.55 + ((i * 7 + j * 13) % 5) * 0.17;
                    double e = Math.min(length, pos + seg);
                    int c = shade(argb, (float) (0.9 + 0.2 * hash(i, j)));
                    double x0 = pos + (j == 0 ? 0 : 0.006), x1 = e - (e >= length ? 0 : 0.006);
                    if (alongA) patch(r, p0, p1, p3, la, lb, x0, x1, t0, t1, c);
                    else patch(r, p0, p1, p3, la, lb, t0, t1, x0, x1, c);
                    if (x1 - x0 > 0.3) {                                       // a grain line
                        double g = (t0 + t1) / 2 + (hash(j, i) - 0.5) * 0.03;
                        int gc = shade(c, 0.86f);
                        if (alongA) patch(r, p0, p1, p3, la, lb, x0 + 0.04, x1 - 0.04, g, g + 0.007, gc);
                        else patch(r, p0, p1, p3, la, lb, g, g + 0.007, x0 + 0.04, x1 - 0.04, gc);
                    }
                    pos = e;
                    j++;
                }
            }
        } else if (style == 2 && la > 0.3 && lb > 0.3) {                   // padded pink panels
            patch(r, p0, p1, p3, la, lb, 0, la, 0, lb, shade(argb, 0.66f));
            int na = Math.max(1, (int) Math.round(la / 0.42)), nb = Math.max(1, (int) Math.round(lb / 0.42));
            double ca = la / na, cb = lb / nb, g = 0.014;
            for (int i = 0; i < na; i++) {
                for (int j = 0; j < nb; j++) {
                    double a0 = i * ca + g, a1 = (i + 1) * ca - g, b0 = j * cb + g, b1 = (j + 1) * cb - g;
                    patch(r, p0, p1, p3, la, lb, a0, a1, b0, b1, shade(argb, (float) (0.97 + 0.06 * hash(i, j))));
                    patch(r, p0, p1, p3, la, lb, a0, a1, b0, b0 + 0.03, shade(argb, 1.2f));
                    patch(r, p0, p1, p3, la, lb, a0, a1, b1 - 0.03, b1, shade(argb, 0.84f));
                }
            }
        } else if ((style == 3 || style == 4) && la > 0.8 && lb > 0.25) {  // flat with a thin trim line near the edge
            face(r, argb, p0[0], p0[1], p0[2], p1[0], p1[1], p1[2], p2[0], p2[1], p2[2], p3[0], p3[1], p3[2]);
            int tc = style == 3 ? 0xFFC9962A : 0xFF4A4A68;
            double in = 0.03, w = 0.016;
            patch(r, p0, p1, p3, la, lb, in, la - in, in, in + w, tc);
            patch(r, p0, p1, p3, la, lb, in, la - in, lb - in - w, lb - in, tc);
            patch(r, p0, p1, p3, la, lb, in, in + w, in, lb - in, tc);
            patch(r, p0, p1, p3, la, lb, la - in - w, la - in, in, lb - in, tc);
        } else {
            face(r, argb, p0[0], p0[1], p0[2], p1[0], p1[1], p1[2], p2[0], p2[1], p2[2], p3[0], p3[1], p3[2]);
        }
    }

    private static double[] P(double u, double v, double w) {
        return new double[]{u, v, w};
    }

    /** A solid box: every face that looks toward you is drawn, lit differently and with a surface pattern, so it is solid from all sides. */
    private void box(Renderer3D r, double u0, double v0, double w0, double u1, double v1, double w1, int argb, boolean topFace, int style) {
        if (vw > w1) styled(r, style, argb, P(u0, v1, w1), P(u1, v1, w1), P(u1, v0, w1), P(u0, v0, w1));
        if (vw < w0) styled(r, style, shade(argb, 0.72f), P(u1, v1, w0), P(u0, v1, w0), P(u0, v0, w0), P(u1, v0, w0));
        if (vu > u1) styled(r, style, shade(argb, 0.86f), P(u1, v1, w1), P(u1, v1, w0), P(u1, v0, w0), P(u1, v0, w1));
        if (vu < u0) styled(r, style, shade(argb, 0.62f), P(u0, v1, w0), P(u0, v1, w1), P(u0, v0, w1), P(u0, v0, w0));
        if (topFace && vv > v1) styled(r, style, shade(argb, 1.2f), P(u0, v1, w1), P(u1, v1, w1), P(u1, v1, w0), P(u0, v1, w0));
        if (vv < v0) face(r, shade(argb, 0.45f), u0, v0, w0, u1, v0, w0, u1, v0, w1, u0, v0, w1);
    }

    private double dist(double u0, double v0, double w0, double u1, double v1, double w1) {
        double du = vu - (u0 + u1) / 2, dv = vv - (v0 + v1) / 2, dw = vw - (w0 + w1) / 2;
        return du * du + dv * dv + dw * dw;
    }

    // ------------------------------------------------------------------ drawing

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (!placed || games == null || mc.player == null || mc.world == null) return;
        s = scale.get();
        Renderer3D r = throughWalls.get() ? event.renderer : event.depthRenderer;
        long now = System.currentTimeMillis();

        Vec3d o = mc.player.getCameraPosVec(event.tickDelta);
        Vec3d d = mc.player.getRotationVec(event.tickDelta);
        vu = ((o.x - ax) * rx + (o.z - az) * rz) / s;
        vw = ((o.x - ax) * nx + (o.z - az) * nz) / s;
        vv = (o.y - ay) / s;

        // ---- planes of the screen and the desk
        scr.plane(X(-1.0, 0.046), Y(2.392), Z(-1.0, 0.046), rx, 0, rz, 0, -1, 0, 2.0 * s / SW, SW, SH);
        desk.plane(X(-0.92, 0.05), Y(0.905), Z(-0.92, 0.05), rx, 0, rz, nx, 0, nz, 1.84 * s / DW, DW, DH);
        boolean showScr = vw > 0.046;
        boolean showDesk = vv > 0.9;

        // what are you pointing at? the nearer plane wins
        target = 0;
        double bestT = 6.5;
        if (showScr) {
            double[] h = scr.pick(o.x, o.y, o.z, d.x, d.y, d.z);
            if (h != null && h[0] >= 0 && h[0] < SW && h[1] >= 0 && h[1] < SH && h[2] < bestT) {
                bestT = h[2];
                target = 1;
                tx = (int) h[0];
                ty = (int) h[1];
            }
        }
        if (showDesk && (vw > 0.0 || Math.abs(vu) > 1.15)) {
            double[] h = desk.pick(o.x, o.y, o.z, d.x, d.y, d.z);
            if (h != null && h[0] >= 0 && h[0] < DW && h[1] >= 0 && h[1] < DH && h[2] < bestT) {
                target = 2;
                tx = (int) h[0];
                ty = (int) h[1];
            }
        }

        // ---- the solid parts, farthest first; what sits on a part (desk panel, screen, sign, text) is drawn right after it
        double[][] parts = {
            {-1.05, 0.0, -0.35, 1.05, 0.8, 0.9, 0xFF6B4423, 1},          // 0 pedestal under the desk
            {-1.12, 0.8, 0.0, 1.12, 0.9, 0.95, 0xFFA9743F, 1},           // 1 desk slab
            {-1.12, 0.8, -0.4, 1.12, 2.55, 0.0, 0xFFE05A9A, 2},          // 2 the pink wall
            {-1.18, 2.55, -0.45, 1.18, 2.95, 0.12, 0xFF2A1A3A, 3},       // 3 sign on top
            {-1.06, 1.14, 0.0, 1.06, 2.452, 0.04, 0xFF14141E, 4},        // 4 frame of the screen
        };
        Integer[] order = {0, 1, 2, 3, 4};
        java.util.Arrays.sort(order, (a2, b2) -> Double.compare(
            dist(parts[b2][0], parts[b2][1], parts[b2][2], parts[b2][3], parts[b2][4], parts[b2][5]),
            dist(parts[a2][0], parts[a2][1], parts[a2][2], parts[a2][3], parts[a2][4], parts[a2][5])));
        for (int i : order) {
            double[] p = parts[i];
            box(r, p[0], p[1], p[2], p[3], p[4], p[5], (int) (long) p[6], i != 0, (int) p[7]);
            if (i == 1 && showDesk) {
                desk.begin(r);
                drawDesk(desk, target == 2 ? tx : -9999, target == 2 ? ty : -9999);
                if (target == 2) cursor(desk, tx, ty);
            } else if (i == 2 && vw < -0.4) {
                WorldText.drawFit(r, "CASINO", X(0, -0.402), Y(1.85), Z(0, -0.402), -rx, -rz, 0.42 * s, 1.9 * s, 0xFFFFE08A);
                WorldText.drawFit(r, "BETTER RENDER", X(0, -0.402), Y(1.45), Z(0, -0.402), -rx, -rz, 0.16 * s, 1.9 * s, 0xFFFFFFFF);
                WorldText.drawFit(r, "practice chips - not real money", X(0, -0.402), Y(1.22), Z(0, -0.402), -rx, -rz, 0.09 * s, 1.9 * s, 0xFFFFD0E6);
            } else if (i == 3) {
                sign(r, now, true);
                sign(r, now, false);
            } else if (i == 4 && showScr) {
                scr.begin(r);
                UI.rect(scr, 0, 0, SW, SH, 0xFF0B0B12);
                games[cur].render(scr, 0, 0, SW, SH, target == 1 ? tx : -9999, target == 1 ? ty : -9999);
                if (target == 1) cursor(scr, tx, ty);
            }
        }
    }

    private static void cursor(WorldGfx g, int x, int y) {
        UI.rect(g, x - 3, y - 1, 7, 3, 0xFFFFC857);
        UI.rect(g, x - 1, y - 3, 3, 7, 0xFFFFC857);
    }

    private void sign(Renderer3D r, long now, boolean front) {
        double w = front ? 0.125 : -0.455;
        if (front ? vw <= 0.12 : vw >= -0.45) return;
        double dir = front ? 1 : -1;
        int n = 14;
        for (int i = 0; i < n; i++) {
            double u = (-1.1 + i * (2.2 / (n - 1))) * dir;
            boolean lit = ((now / 140 + i) % 3) == 0;
            int c = lit ? 0xFFFFF2A8 : 0xFF8A6A1E;
            for (double vb : new double[]{2.595, 2.905}) {
                face(r, c, u - 0.03, vb + 0.03, w, u + 0.03, vb + 0.03, w, u + 0.03, vb - 0.03, w, u - 0.03, vb - 0.03, w);
            }
        }
        WorldText.drawFit(r, "CASINO", X(0, w), Y(2.75), Z(0, w), rx * dir, rz * dir, 0.2 * s, 1.6 * s, 0xFFFFD25A);
    }

    // ------------------------------------------------------------------ the desk

    private void drawDesk(WorldGfx g, int mx, int my) {
        Game game = games[cur];
        boolean busy = game.busy();
        UI.round(g, 0, 0, DW, DH, 0xFF3A2A18);
        UI.round(g, 3, 3, DW - 6, DH - 6, 0xFF14101C);

        UI.text(g, "GAME", 10, 8, UI.MUTED);
        for (int i = 0; i < 6; i++) {
            UI.Btn b = pick[i];
            b.at(8 + (i % 2) * 84, 22 + (i / 2) * 42, 78, 36);
            b.color = i == cur ? UI.ACCENT : UI.PANEL2;
            b.enabled = !busy || i == cur;
            b.draw(g, mx, my);
        }
        UI.text(g, "Rounds " + casino.rounds + "  Won " + casino.wins, 10, 152, UI.MUTED);
        UI.text(g, "Best +" + Money.pretty(casino.best), 10, 164, UI.MUTED);

        UI.text(g, "BET", 186, 8, UI.MUTED);
        UI.round(g, 186, 20, 266, 34, 0xFF0B0B13);
        UI.scaled(g, 186 + 133, 37, 2f, 2f, Money.pretty(game.bet()), UI.GOLD);
        for (int i = 0; i < 4; i++) {
            chipX[i] = 186 + 33 + i * 66;
            boolean hover = !busy && Math.hypot(mx - chipX[i], my - chipY) <= CHIP_R;
            if (busy) UI.ellipse(g, chipX[i], chipY, CHIP_R, CHIP_R, 0xFF2A2A33);
            else UI.chip(g, chipX[i], chipY, CHIP_R, CHIP_COL[i], CHIP_TXT[i], hover);
        }
        UI.Btn[] row = {clear, half, dbl, max};
        for (int i = 0; i < 4; i++) {
            row[i].at(186 + i * 68, 104, 62, 24);
            row[i].enabled = !busy;
            row[i].draw(g, mx, my);
        }

        UI.text(g, "CHIPS", 186, 140, UI.MUTED);
        UI.ellipse(g, 196, 163, 7, 7, 0xFF9A7414);
        UI.ellipse(g, 196, 163, 6, 6, UI.GOLD);
        UI.scaled(g, 196 + 12 + Math.max(20, UI.tr().getWidth(Money.pretty(casino.balance))) * 0.9f, 163, 1.8f, 1.8f,
            Money.pretty(casino.balance), casino.balance >= 0 ? UI.GOLD : UI.BAD);
        reset.at(372, 152, 80, 22);
        reset.enabled = !busy;
        reset.draw(g, mx, my);
        UI.center(g, "Fair draws - practice chips only", DW / 2, 184, UI.MUTED);
    }

    private void deskClick(int mx, int my) {
        Game game = games[cur];
        for (int i = 0; i < 6; i++) {
            if (pick[i].hit(mx, my)) {
                if (i != cur) {
                    cur = i;
                    Sfx.tick();
                }
                return;
            }
        }
        if (game.busy()) return;
        long[] add = Game.CHIPS;
        for (int i = 0; i < 4; i++) {
            if (Math.hypot(mx - chipX[i], my - chipY) <= CHIP_R) {
                game.betAdd(add[i]);
                return;
            }
        }
        if (clear.hit(mx, my)) game.betClear();
        else if (half.hit(mx, my)) game.betHalf();
        else if (dbl.hit(mx, my)) game.betDouble();
        else if (max.hit(mx, my)) game.betMax();
        else if (reset.hit(mx, my)) casino.resetChips();
    }

    @EventHandler
    private void onMouseClick(MouseClickEvent event) {
        if (!placed || games == null || mc.currentScreen != null || event.button() != 0 || target == 0) return;
        event.cancel();                         // do not attack or break something behind the station
        if (event.action != KeyAction.Press) return;
        if (target == 1) games[cur].click(tx, ty);
        else deskClick(tx, ty);
    }
}
