package com.blockoutlines.addon.casino;

import com.blockoutlines.addon.modules.Casino;

import java.util.ArrayList;
import java.util.List;

/**
 * Roulette as a spinning strip: 12 red, 12 black and a few green pockets (set in the module). One pocket is picked
 * with SecureRandom before the strip starts to move, and the strip is only the show that lands on it.
 * Red and black pay 2x, green pays what the setting says (4x by default). The win chances are printed on the buttons.
 */
public class RouletteGame extends Game {
    private static final int TW = 40, TH = 54, LEAD = 14, TARGET = 58, TOTAL = 80;
    private static final long SPIN = 5200;
    private static final int RED = 0xFFD94A4A, BLACK = 0xFF23232E, GREEN = 0xFF2EBF5E;

    private int pick = 0;                         // 0 red, 1 black, 2 green
    private boolean spinning, paid;
    private long start, staked;
    private int result;
    private int[] tileColor = new int[TOTAL + LEAD];   // 0 red 1 black 2 green
    private int[] tileNum = new int[TOTAL + LEAD];
    private float jitter;
    private final List<Integer> hist = new ArrayList<>();

    private final UI.Btn red = new UI.Btn("", 0xFF8E2F2F), black = new UI.Btn("", 0xFF2E2E3C), green = new UI.Btn("", 0xFF1D7A3F),
        spin = new UI.Btn("SPIN", UI.ACCENT);

    public RouletteGame(Casino casino) {
        super(casino);
        for (int i = 0; i < tileColor.length; i++) {
            tileColor[i] = i % 7 == 3 ? 2 : (i % 2);
            tileNum[i] = i % 24 + 1;
        }
    }

    @Override
    public String name() {
        return "Roulette";
    }

    @Override
    public boolean busy() {
        return spinning;
    }

    private int greens() {
        return casino.greenPockets();
    }

    private int pockets() {
        return 24 + greens();
    }

    /** 0 = red, 1 = black, 2 = green for a pocket number 0 .. pockets()-1. */
    private int colorOf(int pocket) {
        if (pocket < 12) return 0;
        if (pocket < 24) return 1;
        return 2;
    }

    private int randomColor() {
        return colorOf(rng.nextInt(pockets()));
    }

    private void begin() {
        if (!takeBet(bet)) return;
        staked = bet;
        int pocket = rng.nextInt(pockets());        // the only place where the result is decided
        result = colorOf(pocket);
        for (int i = 0; i < tileColor.length; i++) {
            tileColor[i] = randomColor();
            tileNum[i] = tileColor[i] == 2 ? 0 : rng.nextInt(24) + 1;
        }
        tileColor[TARGET + LEAD] = result;
        tileNum[TARGET + LEAD] = result == 2 ? 0 : rng.nextInt(24) + 1;
        jitter = (rng.nextFloat() - 0.5f) * (TW - 10);
        spinning = true;
        paid = false;
        start = now();
        Sfx.tick();
    }

    private static float easeOutQuart(float t) {
        t = Math.max(0, Math.min(1, t));
        float u = 1 - t;
        return 1 - u * u * u * u;
    }

    private static String colName(int c) {
        return c == 0 ? "RED" : c == 1 ? "BLACK" : "GREEN";
    }

    @Override
    public void render(Gfx c, int x, int y, int w, int h, int mx, int my) {
        long now = now();
        int cx = x + w / 2;
        UI.felt(c, x, y, w, h - 52);

        if (spinning && !paid && now - start >= SPIN) {
            paid = true;
            spinning = false;
            hist.add(result);
            if (hist.size() > 14) hist.remove(0);
            long mult = result == pick ? (result == 2 ? casino.greenPayout() : 2) : 0;
            if (mult > 0) finish(staked, staked * mult, colName(result) + " - you win");
            else finish(staked, 0, colName(result) + " - you lose");
            start = now - SPIN;           // keep the strip where it stopped
            spinning = false;
            winGlowAt = now;
        }

        // last results
        for (int i = 0; i < hist.size(); i++) {
            int col = hist.get(i);
            UI.ellipse(c, x + 22 + i * 15, y + 22, 6, 6, col == 0 ? RED : col == 1 ? 0xFF4A4A5C : GREEN);
        }
        UI.center(c, "Red / black pay 2x  -  green pays " + casino.greenPayout() + "x", cx, y + 8, UI.alpha(UI.TEXT, 0.55f));

        // the strip
        int sy = y + 40;
        int left = x + 12, right = x + w - 12;
        UI.rect(c, left, sy - 4, right - left, TH + 8, 0xFF0B0B13);
        float t = start == 0 ? 0 : Math.min(1f, (now - start) / (float) SPIN);
        boolean everSpun = start != 0;
        float finalScroll = TARGET * TW + jitter;
        float scroll = everSpun ? finalScroll * easeOutQuart(t) : 0;
        for (int i = -LEAD; i < TOTAL; i++) {
            float tx = cx - TW / 2f + i * TW - scroll;
            if (tx + TW < left || tx > right) continue;
            int col = tileColor[i + LEAD];
            int num = tileNum[i + LEAD];
            int body = col == 0 ? RED : col == 1 ? BLACK : GREEN;
            int ix = Math.round(tx);
            // the tile, cut at the ends of the strip (no scissor needed)
            int x1 = Math.max(ix + 1, left), x2 = Math.min(ix + TW - 1, right);
            if (x2 > x1) {
                UI.rect(c, x1, sy, x2 - x1, TH, UI.mix(body, 0xFF000000, 0.35f));
                int y1 = Math.max(ix + 2, left), y2 = Math.min(ix + TW - 2, right);
                if (y2 > y1) {
                    UI.rect(c, y1, sy + 1, y2 - y1, TH - 3, body);
                    UI.rect(c, y1, sy + 3, y2 - y1, 3, UI.alpha(0xFFFFFFFF, 0.14f));
                }
            }
            if (ix >= left && ix + TW <= right) UI.scaled(c, ix + TW / 2f, sy + TH / 2f, 1.3f, 1.3f, String.valueOf(num), 0xFFFFFFFF);
            if (i == TARGET && !spinning && everSpun && paid) UI.glow(c, ix, sy - 2, TW, TH + 4, UI.GOLD, winGlowAt);
        }
        // fades at both ends
        for (int i = 0; i < 24; i++) {
            float a = 0.9f * (1 - i / 24f);
            UI.rect(c, left + i, sy - 4, 1, TH + 8, UI.alpha(0xFF0B0B13, a));
            UI.rect(c, right - 1 - i, sy - 4, 1, TH + 8, UI.alpha(0xFF0B0B13, a));
        }
        // the marker
        UI.rect(c, cx - 1, sy - 8, 3, TH + 16, UI.GOLD);
        for (int i = 0; i < 6; i++) UI.rect(c, cx - 6 + i, sy - 8 - 5 + i, 13 - 2 * i, 1, UI.GOLD);
        UI.outline(c, left, sy - 4, right - left, TH + 8, UI.LINE);

        // bets
        int by = y + h - 52 - 34;
        int total = pockets();
        red.label = "Red 2x  " + pct(12, total);
        black.label = "Black 2x  " + pct(12, total);
        green.label = "Green " + casino.greenPayout() + "x  " + pct(greens(), total);
        red.at(cx - 215, by, 100, 22);
        black.at(cx - 108, by, 100, 22);
        green.at(cx - 1, by, 100, 22);
        spin.at(cx + 106, by, 100, 22);
        red.enabled = black.enabled = green.enabled = spin.enabled = !spinning;
        for (UI.Btn b : new UI.Btn[]{red, black, green}) b.draw(c, mx, my);
        spin.draw(c, mx, my);
        UI.Btn chosen = pick == 0 ? red : pick == 1 ? black : green;
        UI.outline(c, chosen.x - 1, chosen.y - 1, chosen.w + 2, chosen.h + 2, UI.GOLD);

        drawBetPile(c, x + 62, y + 176, spinning ? staked : bet);
        drawBetBar(c, x, y + h - 46, w, mx, my);
        drawBanner(c, cx, y + 120);
    }

    private long winGlowAt = -10000;

    private static String pct(int count, int total) {
        double p = count * 100.0 / total;
        return (p >= 10 ? String.format("%.0f", p) : String.format("%.1f", p)) + "%";
    }

    @Override
    public boolean click(int mx, int my) {
        if (clickBetBar(mx, my)) return true;
        if (spinning) return false;
        if (red.hit(mx, my)) {
            pick = 0;
            Sfx.tick();
            return true;
        }
        if (black.hit(mx, my)) {
            pick = 1;
            Sfx.tick();
            return true;
        }
        if (green.hit(mx, my)) {
            pick = 2;
            Sfx.tick();
            return true;
        }
        if (spin.hit(mx, my)) {
            begin();
            return true;
        }
        return false;
    }
}
