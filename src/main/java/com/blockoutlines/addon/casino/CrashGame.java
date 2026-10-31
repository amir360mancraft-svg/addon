package com.blockoutlines.addon.casino;

import com.blockoutlines.addon.modules.Casino;
import com.blockoutlines.addon.util.Money;
import net.minecraft.item.Items;

import java.util.ArrayList;
import java.util.List;

/**
 * Crash: the multiplier climbs and you must cash out before it crashes. The crash point comes from SecureRandom
 * as 1 / (1 - U), so the chance to reach any multiplier m is exactly 1 / m and there is no house edge.
 * The point is picked when the round starts, before anything moves.
 */
public class CrashGame extends Game {
    private static final double RATE = 0.2;                  // the multiplier is e^(RATE * seconds)
    private static final double[] AUTO = {0, 1.5, 2, 3, 5, 10};

    private enum Phase { IDLE, COUNT, RUN, CRASH }

    private Phase phase = Phase.IDLE;
    private long staked, startAt, crashedAt;
    private double crashAt = 1;
    private boolean cashed;
    private double cashMult;
    private int auto = 0;
    private final List<Double> hist = new ArrayList<>();

    private final UI.Btn go = new UI.Btn("START", UI.ACCENT), autoBtn = new UI.Btn("Auto: off", UI.PANEL2), cash = new UI.Btn("", UI.GOOD);

    public CrashGame(Casino casino) {
        super(casino);
    }

    @Override
    public String name() {
        return "Crash";
    }

    @Override
    public boolean busy() {
        return phase == Phase.COUNT || (phase == Phase.RUN && !cashed);
    }

    private void begin() {
        if (!takeBet(bet)) return;
        staked = bet;
        double u = rng.nextDouble();                      // the only place where the crash point is decided
        crashAt = Math.min(1000.0, 1.0 / (1.0 - u));
        phase = Phase.COUNT;
        startAt = now() + 1300;
        cashed = false;
        Sfx.tick();
    }

    private void cashOut(double m) {
        if (phase != Phase.RUN || cashed) return;
        cashed = true;
        cashMult = m;
        long back = (long) Math.floor(staked * m);
        finish(staked, back, String.format("Cashed out %.2fx", m));
    }

    private void crash() {
        phase = Phase.CRASH;
        crashedAt = now();
        hist.add(crashAt);
        if (hist.size() > 10) hist.remove(0);
        if (!cashed) {
            Sfx.boom();
            finish(staked, 0, String.format("CRASHED at %.2fx", crashAt));
        }
    }

    private double mult(double t) {
        return Math.exp(RATE * Math.max(0, t));
    }

    @Override
    public void render(Gfx c, int x, int y, int w, int h, int mx, int my) {
        long now = now();
        int cx = x + w / 2;
        UI.felt(c, x, y, w, h - 52);

        // the round
        double t = (now - startAt) / 1000.0;
        if (phase == Phase.COUNT && now >= startAt) phase = Phase.RUN;
        if (phase == Phase.RUN) {
            double m = mult(t);
            if (!cashed && auto > 0 && m >= AUTO[auto] && AUTO[auto] < crashAt) cashOut(AUTO[auto]);
            if (m >= crashAt) crash();
        }
        double tNow = phase == Phase.CRASH ? Math.log(crashAt) / RATE : Math.max(0, t);
        double mCur = phase == Phase.CRASH ? crashAt : phase == Phase.RUN ? mult(t) : 1.0;

        // the graph
        int gx = x + 16, gy = y + 14, gw = w - 32, gh = 124;
        UI.rect(c, gx, gy, gw, gh, 0xFF0B0B13);
        double tView = Math.max(8.0, tNow / 0.85);
        double mView = Math.max(2.0, mCur * 1.25);
        int lineCol = phase == Phase.CRASH ? (cashed ? UI.GOLD : UI.BAD) : UI.GOOD;
        // grid lines
        double[] marks = {2, 3, 5, 10, 20, 50, 100, 200, 500};
        for (double mk : marks) {
            if (mk >= mView) break;
            int ly = gy + gh - (int) ((mk - 1) / (mView - 1) * gh);
            UI.rect(c, gx, ly, gw, 1, UI.alpha(0xFFFFFFFF, 0.08f));
            UI.text(c, (mk == Math.floor(mk) ? String.valueOf((int) mk) : String.valueOf(mk)) + "x", gx + 3, ly - 9, UI.alpha(UI.MUTED, 0.7f));
        }
        float tipX = gx, tipY = gy + gh;
        if (phase == Phase.RUN || phase == Phase.CRASH) {
            int cols = (int) Math.min(gw, tNow / tView * gw);
            int prevTop = gy + gh;
            for (int px = 0; px <= cols; px += 2) {
                double tp = px / (double) gw * tView;
                double mp = mult(tp);
                int hgt = (int) Math.min(gh, (mp - 1) / (mView - 1) * gh);
                int top = gy + gh - hgt;
                int fillCol = UI.alpha(lineCol, 0.22f);
                c.fill(gx + px, top, gx + px + 2, gy + gh, fillCol);
                c.fill(gx + px, top - 1, gx + px + 2, top + 2, lineCol);
                tipX = gx + px;
                tipY = top;
            }
        }
        // the rocket or the boom at the tip of the line
        if (phase == Phase.RUN) UI.item(c, UI.stack(Items.FIREWORK_ROCKET), tipX, tipY - 6, 1.0f, 1.0f);
        if (phase == Phase.CRASH) {
            float k = Math.min(1f, (now - crashedAt) / 400f);
            UI.item(c, UI.tnt(), tipX, tipY - 6, 1f + k, 1f + k);
            if (now - crashedAt < 500) UI.rect(c, gx, gy, gw, gh, UI.alpha(0xFFFF5C5C, 0.28f * (1f - (now - crashedAt) / 500f)));
        }
        UI.outline(c, gx, gy, gw, gh, UI.LINE);

        // the big number
        int bigCol = phase == Phase.CRASH ? (cashed ? UI.GOLD : UI.BAD) : (cashed ? UI.GOLD : UI.TEXT);
        if (phase == Phase.COUNT) {
            double left = (startAt - now) / 1000.0;
            UI.scaled(c, cx, gy + gh / 2f - 4, 2.2f, 2.2f, String.format("Starting in %.1f", Math.max(0, left)), UI.MUTED);
        } else if (phase == Phase.IDLE) {
            UI.scaled(c, cx, gy + gh / 2f - 4, 2.4f, 2.4f, "1.00x", UI.alpha(UI.TEXT, 0.35f));
            UI.center(c, "Cash out before it crashes. Chance to reach m is exactly 1/m.", cx, gy + gh / 2 + 16, UI.alpha(UI.MUTED, 0.9f));
        } else {
            UI.scaled(c, cx, gy + gh / 2f - 4, 3.2f, 3.2f, String.format("%.2fx", mCur), bigCol);
            if (phase == Phase.CRASH) UI.scaled(c, cx, gy + gh / 2f + 22, 1.2f, 1.2f, cashed ? String.format("you cashed at %.2fx", cashMult) : "CRASHED", cashed ? UI.GOLD : UI.BAD);
            else if (cashed) UI.scaled(c, cx, gy + gh / 2f + 22, 1.2f, 1.2f, String.format("you cashed at %.2fx", cashMult), UI.GOLD);
            else UI.scaled(c, cx, gy + gh / 2f + 22, 1.1f, 1.1f, "win now " + Money.pretty((long) Math.floor(staked * mCur)), UI.GOOD);
        }

        // the last crashes
        for (int i = 0; i < hist.size(); i++) {
            double v = hist.get(i);
            int col = v >= 10 ? UI.GOLD : v >= 2 ? UI.GOOD : UI.BAD;
            String s = String.format("%.2fx", v);
            int px = gx + 4 + i * 44;
            UI.round(c, px, y + 146, 40, 14, 0xFF0B0B13);
            UI.center(c, s, px + 20, y + 149, col);
        }

        // buttons
        int by = y + h - 52 - 34;
        auto = Math.max(0, Math.min(AUTO.length - 1, auto));
        autoBtn.label = auto == 0 ? "Auto cash out: off" : String.format("Auto cash out: %.1fx", AUTO[auto]);
        autoBtn.at(cx - 215, by, 140, 22);
        autoBtn.enabled = !busy();
        autoBtn.draw(c, mx, my);
        boolean running = phase == Phase.RUN && !cashed;
        if (running) {
            cash.label = "CASH OUT  " + Money.pretty((long) Math.floor(staked * mCur));
            cash.at(cx - 65, by, 200, 22);
            cash.enabled = true;
            cash.draw(c, mx, my);
        } else {
            go.at(cx - 65, by, 200, 22);
            go.enabled = phase != Phase.COUNT && !running;
            go.draw(c, mx, my);
        }
        drawBetPile(c, x + 62, y + 192, busy() ? staked : bet);
        drawBetBar(c, x, y + h - 46, w, mx, my);
        drawBanner(c, cx, gy + gh - 22);
    }

    @Override
    public boolean click(int mx, int my) {
        if (clickBetBar(mx, my)) return true;
        if (phase == Phase.RUN && !cashed && cash.hit(mx, my)) {
            double m = Math.min(crashAt, mult((now() - startAt) / 1000.0));
            cashOut(m);
            return true;
        }
        if (!busy()) {
            if (autoBtn.hit(mx, my)) {
                auto = (auto + 1) % AUTO.length;
                Sfx.tick();
                return true;
            }
            if (go.hit(mx, my) && phase != Phase.COUNT) {
                begin();
                return true;
            }
        }
        return false;
    }
}
