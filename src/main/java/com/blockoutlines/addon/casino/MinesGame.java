package com.blockoutlines.addon.casino;

import com.blockoutlines.addon.modules.Casino;
import com.blockoutlines.addon.util.Money;
import net.minecraft.client.gui.DrawContext;

import java.util.Arrays;

/** 5x5 field with hidden mines. Every safe tile raises the multiplier, cash out any time. The multiplier is the fair one (no house edge). */
public class MinesGame extends Game {
    private static final int N = 25, TILE = 40, GAP = 4;

    private enum Phase { IDLE, PLAY, OVER }

    private Phase phase = Phase.IDLE;
    private int mines = 3;
    private final boolean[] mine = new boolean[N];
    private final long[] revT = new long[N];
    private final boolean[] dim = new boolean[N];
    private int safe;
    private long staked;
    private int gx, gy;

    private final UI.Btn minus = new UI.Btn("-", UI.PANEL2), plus = new UI.Btn("+", UI.PANEL2),
        start = new UI.Btn("START", UI.ACCENT), cash = new UI.Btn("CASH OUT", UI.GOOD);

    public MinesGame(Casino casino) {
        super(casino);
        Arrays.fill(revT, -1);
    }

    // ---- the same game for the 3D table
    public static int tiles() {
        return N;
    }

    public boolean playing() {
        return phase == Phase.PLAY;
    }

    public int minesCount() {
        return mines;
    }

    public void minesDelta(int d) {
        if (phase != Phase.PLAY) mines = Math.max(1, Math.min(24, mines + d));
    }

    public int safeCount() {
        return safe;
    }

    public double multiplier() {
        return phase == Phase.IDLE ? 1.0 : mult(safe);
    }

    public double nextMultiplier() {
        return mult(safe + 1);
    }

    public long winNow() {
        return payoutNow();
    }

    /** -1 while the tile is hidden, otherwise how far it has turned (0..1). */
    public float tileFlip(int i) {
        if (revT[i] < 0) return -1f;
        return Math.max(0, Math.min(1, (now() - revT[i]) / 260f));
    }

    public boolean tileMine(int i) {
        return mine[i];
    }

    public boolean tileDim(int i) {
        return dim[i];
    }

    public void startRound() {
        if (phase != Phase.PLAY) begin();
    }

    public void pickTileNow(int i) {
        pickTile(i);
    }

    public void cashOutNow() {
        if (phase == Phase.PLAY && safe > 0) cashOut("Cashed out " + String.format("%.2fx", mult(safe)));
    }

    @Override
    public String name() {
        return "Mines";
    }

    @Override
    public boolean busy() {
        return phase == Phase.PLAY;
    }

    /** Fair multiplier after k safe tiles: the inverse of the chance to survive k picks. */
    private double mult(int k) {
        double m = 1;
        for (int i = 0; i < k; i++) m *= (N - i) / (double) (N - mines - i);
        return m;
    }

    private long payoutNow() {
        return (long) Math.floor(staked * mult(safe));
    }

    private void begin() {
        if (!takeBet(bet)) return;
        staked = bet;
        Arrays.fill(mine, false);
        Arrays.fill(revT, -1);
        Arrays.fill(dim, false);
        int placed = 0;
        while (placed < mines) {
            int i = rng.nextInt(N);
            if (!mine[i]) {
                mine[i] = true;
                placed++;
            }
        }
        safe = 0;
        phase = Phase.PLAY;
    }

    private void cashOut(String title) {
        phase = Phase.OVER;
        long back = payoutNow();
        revealRest();
        finish(staked, back, title);
    }

    private void revealRest() {
        long t = now() + 250;
        int order = 0;
        for (int i = 0; i < N; i++) {
            if (revT[i] < 0) {
                revT[i] = t + order * 45L;
                dim[i] = true;
                order++;
            }
        }
    }

    private void pickTile(int i) {
        if (phase != Phase.PLAY || revT[i] >= 0) return;
        revT[i] = now();
        if (mine[i]) {
            phase = Phase.OVER;
            revealRest();
            Sfx.boom();
            finish(staked, 0, "BOOM");
        } else {
            safe++;
            Sfx.good(safe);
            if (safe == N - mines) cashOut("Cleared the field");
        }
    }

    @Override
    public void render(DrawContext c, int x, int y, int w, int h, int mx, int my) {
        long now = now();
        UI.panel(c, x, y, w, h - 52, UI.PANEL);
        gx = x + 24;
        gy = y + 14;
        for (int i = 0; i < N; i++) {
            int tx = gx + (i % 5) * (TILE + GAP), ty = gy + (i / 5) * (TILE + GAP);
            drawTile(c, i, tx, ty, now, mx, my);
        }

        int rx = x + 290, rw = w - 290 - 14;
        boolean idle = phase != Phase.PLAY;
        UI.text(c, "Mines", rx, y + 14, UI.MUTED);
        minus.at(rx, y + 28, 24, 20);
        plus.at(rx + 70, y + 28, 24, 20);
        minus.enabled = idle && mines > 1;
        plus.enabled = idle && mines < 24;
        minus.draw(c, mx, my);
        plus.draw(c, mx, my);
        UI.center(c, String.valueOf(mines), rx + 47, y + 34, UI.GOLD);

        UI.text(c, "Multiplier", rx, y + 64, UI.MUTED);
        UI.text(c, String.format("%.2fx", phase == Phase.IDLE ? 1.0 : mult(safe)), rx, y + 76, UI.GOOD);
        if (phase == Phase.PLAY) {
            UI.text(c, "Next tile", rx, y + 98, UI.MUTED);
            UI.text(c, String.format("%.2fx", mult(safe + 1)), rx, y + 110, UI.TEXT);
            double risk = mines / (double) (N - safe) * 100.0;
            UI.text(c, String.format("Risk %.0f%%", risk), rx, y + 126, risk > 40 ? UI.BAD : UI.GOLD);
            UI.text(c, "Win now", rx, y + 148, UI.MUTED);
            UI.text(c, Money.pretty(payoutNow()), rx, y + 160, UI.GOLD);
        } else {
            UI.text(c, "Safe tiles raise the multiplier.", rx, y + 100, UI.MUTED);
            UI.text(c, "Fair odds, no edge.", rx, y + 112, UI.MUTED);
        }

        if (phase == Phase.PLAY) {
            cash.label = "CASH OUT";
            cash.at(rx, y + h - 52 - 34, rw, 24);
            cash.enabled = safe > 0;
            cash.draw(c, mx, my);
        } else {
            start.at(rx, y + h - 52 - 34, rw, 24);
            start.enabled = true;
            start.draw(c, mx, my);
        }
        drawBetPile(c, x + 388, y + h - 52 - 66, phase == Phase.PLAY ? staked : bet);
        drawBetBar(c, x, y + h - 46, w, mx, my);
        drawBanner(c, gx + 5 * (TILE + GAP) / 2, y + 100);
    }

    private void drawTile(DrawContext c, int i, int tx, int ty, long now, int mx, int my) {
        float f = revT[i] < 0 ? 0 : Math.max(0, Math.min(1, (now - revT[i]) / 260f));
        float s = Math.abs(1 - 2 * f);
        float cx = tx + TILE / 2f, cy = ty + TILE / 2f;
        int w = Math.max(2, Math.round(TILE * s));
        int l = Math.round(cx - w / 2f);
        if (f < 0.5f) {
            boolean hover = phase == Phase.PLAY && revT[i] < 0 && UI.in(mx, my, tx, ty, TILE, TILE);
            UI.round(c, l, ty, w, TILE, hover ? 0xFF4A4A68 : 0xFF33334A);
            if (w > 8) UI.rect(c, l + 3, ty + 3, w - 6, 3, 0x22FFFFFF);
        } else {
            float a = dim[i] ? 0.6f : 1f;
            if (mine[i]) {
                UI.round(c, l, ty, w, TILE, UI.alpha(0xFF4A1A1F, a));
                if (w > 8) UI.item(c, UI.tnt(), cx, cy, 2.0f * s * (dim[i] ? 0.85f : 1f), dim[i] ? 1.7f : 2.0f);
            } else {
                UI.round(c, l, ty, w, TILE, UI.alpha(0xFF123D2B, a));
                if (w > 8) UI.item(c, UI.diamond(), cx, cy, 2.0f * s * (dim[i] ? 0.85f : 1f), dim[i] ? 1.7f : 2.0f);
            }
        }
    }

    @Override
    public boolean click(int mx, int my) {
        if (clickBetBar(mx, my)) return true;
        if (phase == Phase.PLAY) {
            for (int i = 0; i < N; i++) {
                int tx = gx + (i % 5) * (TILE + GAP), ty = gy + (i / 5) * (TILE + GAP);
                if (UI.in(mx, my, tx, ty, TILE, TILE)) {
                    pickTile(i);
                    return true;
                }
            }
            if (cash.hit(mx, my) && safe > 0) {
                cashOut("Cashed out " + String.format("%.2fx", mult(safe)));
                return true;
            }
            return false;
        }
        if (minus.hit(mx, my)) {
            mines--;
            return true;
        }
        if (plus.hit(mx, my)) {
            mines++;
            return true;
        }
        if (start.hit(mx, my)) {
            begin();
            return true;
        }
        return false;
    }
}
