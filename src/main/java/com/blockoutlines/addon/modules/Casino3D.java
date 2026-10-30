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
import java.util.List;

/**
 * The casino as a hologram in the world: the Mines table floats in front of you, you look at a tile or a button and
 * press the left mouse button. It is the same game as in the casino window (same fair draws, same chips).
 */
public class Casino3D extends Module {
    private static final double TILE = 0.44, PITCH = 0.5, GRID_U = -1.1, GRID_V = 0.05;

    private final SettingGroup sg = settings.getDefaultGroup();
    private final Setting<Double> distance = sg.add(new DoubleSetting.Builder().name("distance")
        .description("How far in front of you the table appears.").defaultValue(3.4).range(1.5, 8).sliderRange(2, 6).build());
    private final Setting<Double> scale = sg.add(new DoubleSetting.Builder().name("scale")
        .description("Size of the table.").defaultValue(1.0).range(0.5, 2.5).sliderRange(0.6, 1.8).build());
    private final Setting<Boolean> throughWalls = sg.add(new BoolSetting.Builder().name("through-walls")
        .description("Show the table even behind blocks.").defaultValue(false).build());
    private final Setting<Keybind> placeKey = sg.add(new KeybindSetting.Builder().name("place-key")
        .description("Puts the table in front of you again.").defaultValue(Keybind.none()).action(this::place).build());

    private static final class El {
        int id;
        double u0, v0, u1, v1;
        String label;
        int color;
        boolean enabled;
        Runnable action;
        double text;
    }

    private MinesGame mines;
    private boolean placed;
    private double ax, ay, az, rx, rz, nx, nz, s = 1;
    private final List<El> els = new ArrayList<>();
    private El hover;
    private double hu, hv;
    private boolean hit;

    public Casino3D() {
        super(BlockOutlinesAddon.CATEGORY, "casino-3d", "Mines as a hologram in the world: look at a tile or button and click.");
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
        Vec3d eye = mc.player.getEyePos();
        ax = eye.x + fx * distance.get();
        az = eye.z + fz * distance.get();
        ay = eye.y + 0.15;
        placed = true;
    }

    // ------------------------------------------------------------------ world <-> board

    private double wx(double u, int layer) {
        return ax + rx * u * s + nx * layer * 0.004;
    }

    private double wz(double u, int layer) {
        return az + rz * u * s + nz * layer * 0.004;
    }

    private double wy(double v) {
        return ay + v * s;
    }

    private static Color col(int argb) {
        return new Color((argb >> 16) & 255, (argb >> 8) & 255, argb & 255, (argb >>> 24) & 255);
    }

    private void rect(Renderer3D r, double u0, double v0, double u1, double v1, int layer, int argb) {
        if (((argb >>> 24) & 255) < 3) return;
        Color c = col(argb);
        r.quad(wx(u0, layer), wy(v1), wz(u0, layer), wx(u1, layer), wy(v1), wz(u1, layer),
            wx(u1, layer), wy(v0), wz(u1, layer), wx(u0, layer), wy(v0), wz(u0, layer), c);
    }

    private void poly(Renderer3D r, double[] us, double[] vs, int layer, int argb) {
        Color c = col(argb);
        r.quad(wx(us[0], layer), wy(vs[0]), wz(us[0], layer), wx(us[1], layer), wy(vs[1]), wz(us[1], layer),
            wx(us[2], layer), wy(vs[2]), wz(us[2], layer), wx(us[3], layer), wy(vs[3]), wz(us[3], layer), c);
    }

    private void text(Renderer3D r, String str, double u, double v, double size, int layer, int argb) {
        WorldText.draw(r, str, wx(u, layer), wy(v), wz(u, layer), rx, rz, size * s, argb);
    }

    private void frame(Renderer3D r, double u0, double v0, double u1, double v1, double th, int layer, int argb) {
        rect(r, u0, v1 - th, u1, v1, layer, argb);
        rect(r, u0, v0, u1, v0 + th, layer, argb);
        rect(r, u0, v0, u0 + th, v1, layer, argb);
        rect(r, u1 - th, v0, u1, v1, layer, argb);
    }

    // ------------------------------------------------------------------ the table

    private void add(int id, double u0, double v0, double u1, double v1, String label, int color, boolean enabled, double text, Runnable action) {
        El e = new El();
        e.id = id;
        e.u0 = u0;
        e.v0 = v0;
        e.u1 = u1;
        e.v1 = v1;
        e.label = label;
        e.color = color;
        e.enabled = enabled;
        e.text = text;
        e.action = action;
        els.add(e);
    }

    private void build() {
        els.clear();
        boolean playing = mines.playing();
        for (int i = 0; i < MinesGame.tiles(); i++) {
            final int idx = i;
            double cu = GRID_U + (i % 5 - 2) * PITCH, cv = GRID_V - (i / 5 - 2) * PITCH;
            add(i, cu - TILE / 2, cv - TILE / 2, cu + TILE / 2, cv + TILE / 2, "", 0, playing && mines.tileFlip(i) < 0, 0, () -> mines.pickTileNow(idx));
        }
        // mines count
        add(100, 0.62, 0.82, 0.94, 1.1, "-", UI.PANEL2, !playing && mines.minesCount() > 1, 0.26, () -> mines.minesDelta(-1));
        add(101, 1.66, 0.82, 1.98, 1.1, "+", UI.PANEL2, !playing && mines.minesCount() < 24, 0.26, () -> mines.minesDelta(1));
        // chips
        String[] names = {"100", "1K", "10K", "100K"};
        int[] colors = {0xFF8C8CA0, 0xFFD94A4A, 0xFF3F6BE0, 0xFF2A2A35};
        for (int i = 0; i < 4; i++) {
            final long v = Game.CHIPS[i];
            double u0 = 0.55 + i * 0.387;
            add(110 + i, u0, -0.66, u0 + 0.34, -0.38, names[i], colors[i], !playing, 0.13, () -> mines.betAdd(v));
        }
        add(120, 0.55, -1.0, 1.0, -0.74, "Clear", UI.PANEL2, !playing, 0.13, mines::betClear);
        add(121, 1.05, -1.0, 1.5, -0.74, "1/2", UI.PANEL2, !playing, 0.13, mines::betHalf);
        add(122, 1.55, -1.0, 2.0, -0.74, "x2", UI.PANEL2, !playing, 0.13, mines::betDouble);
        // start / cash out
        if (playing) {
            add(130, 0.55, -1.46, 2.0, -1.12, mines.safeCount() > 0 ? "CASH OUT " + Money.pretty(mines.winNow()) : "Pick a tile",
                mines.safeCount() > 0 ? UI.GOOD : UI.PANEL2, mines.safeCount() > 0, 0.17, mines::cashOutNow);
        } else {
            add(130, 0.55, -1.46, 2.0, -1.12, "START", UI.ACCENT, true, 0.2, mines::startRound);
        }
    }

    private int shade(int base, boolean enabled, boolean hovered) {
        int c = enabled ? base : UI.mix(base, 0xFF1C1C26, 0.7f);
        if (hovered && enabled) c = UI.mix(c, 0xFFFFFFFF, 0.18f);
        return c;
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (!placed || mines == null || mc.player == null || mc.world == null) return;
        s = scale.get();
        Renderer3D r = throughWalls.get() ? event.renderer : event.depthRenderer;

        // where am I looking at on the table
        hit = false;
        hover = null;
        Vec3d o = mc.player.getCameraPosVec(event.tickDelta);
        Vec3d d = mc.player.getRotationVec(event.tickDelta);
        if ((o.x - ax) * nx + (o.z - az) * nz < 0) return;     // behind the table: the text would be mirrored
        double denom = d.x * nx + d.z * nz;
        if (denom < -1e-4) {
            double t = ((ax - o.x) * nx + (az - o.z) * nz) / denom;
            if (t > 0 && t < 16) {
                double px = o.x + d.x * t, py = o.y + d.y * t, pz = o.z + d.z * t;
                hu = ((px - ax) * rx + (pz - az) * rz) / s;
                hv = (py - ay) / s;
                hit = true;
            }
        }
        build();
        if (hit) for (El e : els) if (hu >= e.u0 && hu <= e.u1 && hv >= e.v0 && hv <= e.v1) hover = e;

        long now = System.currentTimeMillis();
        // the panel
        rect(r, -2.55, -1.78, 2.25, 1.78, 0, 0xC8101018);
        frame(r, -2.55, -1.78, 2.25, 1.78, 0.04, 1, 0xFF7C5CFF);

        // the tiles
        for (int i = 0; i < MinesGame.tiles(); i++) {
            El e = els.get(i);
            double cu = (e.u0 + e.u1) / 2, cv = (e.v0 + e.v1) / 2;
            float f = mines.tileFlip(i);
            double half = TILE / 2 * (f < 0 ? 1 : Math.max(0.03, Math.abs(1 - 2 * f)));
            boolean hov = hover == e && e.enabled;
            if (f < 0.5f) {
                rect(r, cu - half, cv - TILE / 2, cu + half, cv + TILE / 2, 1, 0xFF1B1B2A);
                rect(r, cu - half + 0.015, cv - TILE / 2 + 0.015, cu + half - 0.015, cv + TILE / 2 - 0.015, 2, hov ? 0xFF55557A : 0xFF33334A);
                rect(r, cu - half + 0.03, cv + TILE / 2 - 0.07, cu + half - 0.03, cv + TILE / 2 - 0.035, 3, 0x26FFFFFF);
                if (hov) frame(r, cu - half, cv - TILE / 2, cu + half, cv + TILE / 2, 0.025, 4, 0xFFFFC857);
            } else {
                int a = mines.tileDim(i) ? 0x99 : 0xFF;
                boolean m = mines.tileMine(i);
                rect(r, cu - half, cv - TILE / 2, cu + half, cv + TILE / 2, 1, (a << 24) | (m ? 0x4A1A1F : 0x123D2B));
                double k = Math.max(0.03, Math.abs(1 - 2 * f));
                if (m) {
                    rect(r, cu - 0.12 * k, cv - 0.12, cu + 0.12 * k, cv + 0.12, 2, (a << 24) | 0xD94A4A);
                    rect(r, cu - 0.12 * k, cv - 0.035, cu + 0.12 * k, cv + 0.035, 3, (a << 24) | 0xFFFFFF);
                    rect(r, cu - 0.035 * k, cv + 0.12, cu + 0.035 * k, cv + 0.18, 3, (a << 24) | 0x5A5A66);
                } else {
                    poly(r, new double[]{cu, cu + 0.12 * k, cu, cu - 0.12 * k}, new double[]{cv + 0.15, cv, cv - 0.15, cv}, 2, (a << 24) | 0x58DDE8);
                    poly(r, new double[]{cu, cu + 0.05 * k, cu, cu - 0.05 * k}, new double[]{cv + 0.1, cv + 0.02, cv - 0.04, cv + 0.02}, 3, (a << 24) | 0xC9FBFF);
                }
            }
        }

        // the right side
        text(r, "MINES", 1.3, 1.5, 0.3, 3, 0xFFFFC857);
        text(r, "mines on the field", 1.3, 1.22, 0.12, 3, UI.MUTED);
        text(r, String.valueOf(mines.minesCount()), 1.3, 0.96, 0.3, 3, 0xFFFFC857);
        text(r, String.format("%.2fx", mines.multiplier()), 1.3, 0.5, 0.42, 3, 0xFF3DDC84);
        if (mines.playing()) text(r, String.format("next %.2fx", mines.nextMultiplier()), 1.3, 0.16, 0.16, 3, 0xFFFFFFFF);
        else text(r, "fair odds, no edge", 1.3, 0.16, 0.13, 3, UI.MUTED);
        text(r, "Bet " + Money.pretty(mines.bet()), 1.3, -0.17, 0.22, 3, 0xFFFFC857);

        // buttons
        for (El e : els) {
            if (e.id < 100) continue;
            boolean hov = hover == e;
            int body = shade(e.color, e.enabled, hov);
            rect(r, e.u0 + 0.012, e.v0 - 0.014, e.u1 + 0.012, e.v1 - 0.014, 1, 0x88000000);
            rect(r, e.u0, e.v0, e.u1, e.v1, 2, body);
            rect(r, e.u0 + 0.02, e.v1 - 0.045, e.u1 - 0.02, e.v1 - 0.02, 3, 0x30FFFFFF);
            if (hov && e.enabled) frame(r, e.u0, e.v0, e.u1, e.v1, 0.018, 4, 0xFFFFC857);
            text(r, e.label, (e.u0 + e.u1) / 2, (e.v0 + e.v1) / 2, e.text, 5, e.enabled ? 0xFFFFFFFF : 0xFF777788);
        }

        // chips under the grid and the result above the table
        text(r, "Chips " + Money.pretty(Modules.get().get(Casino.class).balance), GRID_U, -1.5, 0.2, 3, 0xFFFFC857);
        long age = mines.resultAge();
        if (age >= 0 && age < 3000 && !mines.resultText().isEmpty()) {
            float fade = age > 2400 ? 1f - (age - 2400) / 600f : 1f;
            float pop = UI.easeBack(age / 300f);
            int a = Math.max(0, Math.min(255, (int) (255 * fade)));
            int cc = (a << 24) | (mines.resultColor() & 0xFFFFFF);
            text(r, mines.resultText(), -0.15, 2.12, 0.46 * pop, 6, cc);
            if (!mines.resultSub().isEmpty()) text(r, mines.resultSub(), -0.15, 1.9, 0.22 * pop, 6, (a << 24) | 0xFFFFFF);
        }
        // where you are pointing
        if (hit && hu > -2.55 && hu < 2.25 && hv > -1.78 && hv < 1.78) rect(r, hu - 0.025, hv - 0.025, hu + 0.025, hv + 0.025, 7, 0xFFFFC857);
    }

    @EventHandler
    private void onMouseClick(MouseClickEvent event) {
        if (!placed || mines == null || mc.currentScreen != null || event.button() != 0) return;
        if (hover == null) return;
        event.cancel();                         // do not attack or break something behind the table
        if (event.action == KeyAction.Press && hover.enabled && hover.action != null) hover.action.run();
    }
}
