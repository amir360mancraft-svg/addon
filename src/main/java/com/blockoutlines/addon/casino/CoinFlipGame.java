package com.blockoutlines.addon.casino;

import com.blockoutlines.addon.modules.Casino;
import net.minecraft.client.gui.DrawContext;

/** Pick heads or tails, the coin spins, a fair 50/50 decides. Pays 2x. */
public class CoinFlipGame extends Game {
    private static final long SPIN = 1900;

    private int pick = 0;          // 0 heads, 1 tails
    private int result = -1;
    private long start = -1;
    private long staked;
    private boolean paid;
    private int lastFace = 0;

    private final UI.Btn heads = new UI.Btn("Heads", UI.PANEL2), tails = new UI.Btn("Tails", UI.PANEL2), flip = new UI.Btn("FLIP", UI.ACCENT);

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
    public void render(DrawContext c, int x, int y, int w, int h, int mx, int my) {
        long now = now();
        int cx = x + w / 2, cy = y + 112;
        UI.round(c, x, y, w, h - 52, UI.FELT);

        // finish the round when the spin is over
        if (start >= 0 && !paid && now - start >= SPIN) {
            paid = true;
            lastFace = result;
            if (result == pick) finish(staked, staked * 2, (result == 0 ? "HEADS" : "TAILS") + " - you win");
            else finish(staked, 0, (result == 0 ? "HEADS" : "TAILS") + " - you lose");
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
        UI.ellipse(c, cx, cy + 50, 40 - lift * 0.25f, 7, 0x55000000);     // shadow on the felt
        UI.ellipse(c, cx, coinY + 3, rx, 44, 0xFF9A7414);                  // edge
        UI.ellipse(c, cx, coinY, rx, 44, UI.GOLD);
        UI.ellipse(c, cx, coinY, rx * 0.82f, 36, 0xFFE9A92C);
        if (Math.abs(cs) > 0.25f) UI.scaled(c, cx, coinY, 3.4f * Math.abs(cs), 3.4f, cs > 0 ? "H" : "T", 0xFF6B4B00);

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
        flip.draw(c, mx, my);
        UI.center(c, "Pays 2x - a fair 50/50", cx, y + 6, UI.MUTED);

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
            start = now();
            paid = false;
            return true;
        }
        return false;
    }
}
