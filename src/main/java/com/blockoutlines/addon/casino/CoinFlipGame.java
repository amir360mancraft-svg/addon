package com.blockoutlines.addon.casino;

import com.blockoutlines.addon.modules.Casino;

/** Pick heads or tails, the coin spins, a fair 50/50 decides. Pays 2x. */
public class CoinFlipGame extends Game {
    private static final long SPIN = 1900;

    private int pick = 0;          // 0 heads, 1 tails
    private int result = -1;
    private long start = -1;
    private long staked;
    private boolean paid;
    private int lastFace = 0;
    private final java.util.List<Integer> hist = new java.util.ArrayList<>();

    private final UI.Btn heads = new UI.Btn("  Diamond", UI.PANEL2), tails = new UI.Btn("  Emerald", UI.PANEL2), flip = new UI.Btn("FLIP", UI.ACCENT);

    public CoinFlipGame(Casino casino) {
        super(casino);
    }

    @Override
    public String name() {
        return "Coin flip";
    }

    @Override
    public boolean busy() {
        return start >= 0 && now() - start < SPIN + 150;
    }

    @Override
    public void render(Gfx c, int x, int y, int w, int h, int mx, int my) {
        long now = now();
        int cx = x + w / 2, cy = y + 112;
        UI.felt(c, x, y, w, h - 52);

        // finish the round when the spin is over
        if (start >= 0 && !paid && now - start >= SPIN) {
            paid = true;
            lastFace = result;
            hist.add(result);
            if (hist.size() > 14) hist.remove(0);
            if (result == pick) finish(staked, staked * 2, (result == 0 ? "DIAMOND" : "EMERALD") + " - you win");
            else finish(staked, 0, (result == 0 ? "DIAMOND" : "EMERALD") + " - you lose");
        }

        // the coin
        float t = start < 0 ? 1f : Math.min(1f, (now - start) / (float) SPIN);
        float e = UI.easeOut(t);
        float spins = 6 * (float) Math.PI + (result == 1 ? (float) Math.PI : 0);
        float theta = start < 0 ? (lastFace == 1 ? (float) Math.PI : 0) : e * spins;
        float lift = start < 0 ? 0 : (float) Math.sin(Math.PI * Math.min(1f, t * 1.15f)) * 70f;
        float cs = (float) Math.cos(theta);
        float rx = Math.max(2f, 44f * Math.abs(cs));
        float coinY = cy - lift;
        boolean headsUp = cs > 0;
        int body = headsUp ? 0xFF22B8C9 : 0xFF2EBF5E, edge = headsUp ? 0xFF14707C : 0xFF17703A, inner = headsUp ? 0xFF58DDE8 : 0xFF63E08E;
        UI.ellipse(c, cx, cy + 50, 40 - lift * 0.25f, 7, 0x55000000);     // shadow on the felt
        UI.ellipse(c, cx, coinY + 3, rx, 44, edge);
        UI.ellipse(c, cx, coinY, rx, 44, body);
        UI.ellipse(c, cx, coinY, rx * 0.82f, 36, inner);
        if (Math.abs(cs) > 0.12f) UI.item(c, headsUp ? UI.diamond() : UI.emerald(), cx, coinY, 3.6f * Math.abs(cs), 3.6f);

        // your pick and the buttons
        int by = y + h - 52 - 34;
        heads.at(cx - 150, by, 90, 22);
        tails.at(cx - 45, by, 90, 22);
        flip.at(cx + 60, by, 90, 22);
        heads.color = pick == 0 ? UI.GOOD : UI.PANEL2;
        tails.color = pick == 1 ? UI.GOOD : UI.PANEL2;
        boolean lock = busy();
        heads.enabled = tails.enabled = flip.enabled = !lock;
        heads.draw(c, mx, my);
        tails.draw(c, mx, my);
        UI.item(c, UI.diamond(), heads.x + 14, heads.y + 11, 1f, 1f);
        UI.item(c, UI.emerald(), tails.x + 14, tails.y + 11, 1f, 1f);
        flip.draw(c, mx, my);
        for (int i = 0; i < hist.size(); i++) UI.item(c, hist.get(i) == 0 ? UI.diamond() : UI.emerald(), cx - (hist.size() - 1) * 9 + i * 18, y + 28, 0.8f, 0.8f);
        drawBetPile(c, x + 62, y + h - 52 - 62, busy() ? staked : bet);
        UI.center(c, "Diamond or emerald - pays 2x - a fair 50/50", cx, y + 6, UI.MUTED);

        drawBetBar(c, x, y + h - 46, w, mx, my);
        drawBanner(c, cx, y + 56);
    }

    @Override
    public boolean click(int mx, int my) {
        if (clickBetBar(mx, my)) return true;
        if (busy()) return false;
        if (heads.hit(mx, my)) {
            pick = 0;
            return true;
        }
        if (tails.hit(mx, my)) {
            pick = 1;
            return true;
        }
        if (flip.hit(mx, my)) {
            if (!takeBet(bet)) return true;
            staked = bet;
            result = rng.nextInt(2);     // the only place where the result is decided
            Sfx.tick();
            start = now();
            paid = false;
            return true;
        }
        return false;
    }
}
