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

    /** A solid box: every face that looks toward you is drawn, lit differently, so it is solid from all sides. */
    private void box(Renderer3D r, double u0, double v0, double w0, double u1, double v1, double w1, int argb) {
        if (vw > w1) face(r, argb, u0, v1, w1, u1, v1, w1, u1, v0, w1, u0, v0, w1);
        if (vw < w0) face(r, shade(argb, 0.72f), u0, v1, w0, u1, v1, w0, u1, v0, w0, u0, v0, w0);
        if (vu > u1) face(r, shade(argb, 0.86f), u1, v1, w0, u1, v1, w1, u1, v0, w1, u1, v0, w0);
        if (vu < u0) face(r, shade(argb, 0.62f), u0, v1, w0, u0, v1, w1, u0, v0, w1, u0, v0, w0);
        if (vv > v1) face(r, shade(argb, 1.2f), u0, v1, w0, u1, v1, w0, u1, v1, w1, u0, v1, w1);
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

        // ---- the solid body, farthest part first
        double[][] parts = {
            {-1.05, 0.0, -0.35, 1.05, 0.8, 0.9, 0xFF6B4423},          // pedestal under the desk
            {-1.12, 0.8, -0.4, 1.12, 0.9, 0.95, 0xFFA9743F},          // desk slab
            {-1.12, 0.9, -0.4, 1.12, 2.55, 0.0, 0xFFE05A9A},          // the pink wall with the screen
            {-1.18, 2.55, -0.45, 1.18, 2.85, 0.12, 0xFF2A1A3A},       // sign on top
            {-1.06, 1.14, 0.0, 1.06, 2.452, 0.04, 0xFF14141E},        // frame of the screen
        };
        Integer[] order = {0, 1, 2, 3, 4};
        java.util.Arrays.sort(order, (a, b) -> Double.compare(
            dist(parts[b][0], parts[b][1], parts[b][2], parts[b][3], parts[b][4], parts[b][5]),
            dist(parts[a][0], parts[a][1], parts[a][2], parts[a][3], parts[a][4], parts[a][5])));
        for (int i : order) {
            double[] p = parts[i];
            box(r, p[0], p[1], p[2], p[3], p[4], p[5], (int) (long) p[6]);
        }

        // ---- the sign: text and chasing bulbs, front and back
        sign(r, now, true);
        sign(r, now, false);
        // back of the cabinet
        if (vw < -0.4) {
            WorldText.drawFit(r, "CASINO", X(0, -0.402), Y(1.85), Z(0, -0.402), -rx, -rz, 0.42 * s, 1.9 * s, 0xFFFFE08A);
            WorldText.drawFit(r, "BETTER RENDER", X(0, -0.402), Y(1.45), Z(0, -0.402), -rx, -rz, 0.16 * s, 1.9 * s, 0xFFFFFFFF);
            WorldText.drawFit(r, "practice chips - not real money", X(0, -0.402), Y(1.22), Z(0, -0.402), -rx, -rz, 0.09 * s, 1.9 * s, 0xFFFFD0E6);
        }

        // ---- screen and desk (canvas planes)
        scr.plane(X(-1.0, 0.046), Y(2.392), Z(-1.0, 0.046), rx, 0, rz, 0, -1, 0, 2.0 * s / SW, SW, SH);
        desk.plane(X(-0.92, 0.05), Y(0.905), Z(-0.92, 0.05), rx, 0, rz, nx, 0, nz, 1.84 * s / DW, DW, DH);
        boolean showScr = vw > 0.046;
        boolean showDesk = vv > 0.9 && (vw > 0.0 || Math.abs(vu) > 1.15);

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
        if (showDesk) {
            double[] h = desk.pick(o.x, o.y, o.z, d.x, d.y, d.z);
            if (h != null && h[0] >= 0 && h[0] < DW && h[1] >= 0 && h[1] < DH && h[2] < bestT) {
                target = 2;
                tx = (int) h[0];
                ty = (int) h[1];
            }
        }

        if (showScr) {
            scr.begin(r);
            UI.rect(scr, 0, 0, SW, SH, 0xFF0B0B12);
            games[cur].render(scr, 0, 0, SW, SH, target == 1 ? tx : -9999, target == 1 ? ty : -9999);
            if (target == 1) cursor(scr, tx, ty);
        }
        if (showDesk) {
            desk.begin(r);
            drawDesk(desk, target == 2 ? tx : -9999, target == 2 ? ty : -9999);
            if (target == 2) cursor(desk, tx, ty);
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
        WorldText.drawFit(r, "CASINO", X(0, w), Y(2.70), Z(0, w), rx * dir, rz * dir, 0.30 * s, 1.6 * s, 0xFFFFD25A);
        int n = 14;
        for (int i = 0; i < n; i++) {
            double u = (-1.1 + i * (2.2 / (n - 1))) * dir;
            boolean lit = ((now / 140 + i) % 3) == 0;
            int c = lit ? 0xFFFFF2A8 : 0xFF8A6A1E;
            for (double vb : new double[]{2.59, 2.81}) {
                face(r, c, u - 0.035, vb + 0.035, w, u + 0.035, vb + 0.035, w, u + 0.035, vb - 0.035, w, u - 0.035, vb - 0.035, w);
            }
        }
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
