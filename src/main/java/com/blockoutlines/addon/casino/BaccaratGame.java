package com.blockoutlines.addon.casino;

import com.blockoutlines.addon.modules.Casino;
import net.minecraft.client.gui.DrawContext;

import java.util.ArrayList;
import java.util.List;

/** Punto banco: bet on Player (2x), Banker (1.95x, 5% commission) or Tie (9x). The third-card rules are the standard ones. */
public class BaccaratGame extends Game {
    private final Cards.Shoe shoe = new Cards.Shoe(8);
    private final List<Cards.Card> player = new ArrayList<>(), banker = new ArrayList<>();
    private boolean running, finished;
    private int pick = 0;            // 0 player, 1 banker, 2 tie
    private long staked;
    private float deckX, deckY;

    private final UI.Btn bp = new UI.Btn("Player 2x", UI.PANEL2), bb = new UI.Btn("Banker 1.95x", UI.PANEL2),
        bt = new UI.Btn("Tie 9x", UI.PANEL2), deal = new UI.Btn("DEAL", UI.ACCENT);

    public BaccaratGame(Casino casino) {
        super(casino);
    }

    @Override
    public String name() {
        return "Baccarat";
    }

    @Override
    public boolean busy() {
        return running;
    }

    private static int value(Cards.Card k) {
        int r = Cards.rank(k.id);
        return r == 0 ? 1 : (r >= 9 ? 0 : r + 1);
    }

    private static int total(List<Cards.Card> h) {
        int s = 0;
        for (Cards.Card k : h) s += value(k);
        return s % 10;
    }

    private void give(List<Cards.Card> hand) {
        hand.add(new Cards.Card(shoe.draw(), now(), true, deckX, deckY));
    }

    private void start() {
        if (!takeBet(bet)) return;
        staked = bet;
        running = true;
        finished = false;
        player.clear();
        banker.clear();
        after(0, () -> give(player));
        after(400, () -> give(banker));
        after(400, () -> give(player));
        after(400, () -> give(banker));
        after(900, this::third);
    }

    private void third() {
        int p = total(player), b = total(banker);
        if (p >= 8 || b >= 8) {
            after(500, this::resolve);
            return;
        }
        boolean playerDraws = p <= 5;
        if (playerDraws) {
            give(player);
            int p3 = value(player.get(2));
            after(900, () -> {
                int bt2 = total(banker);
                boolean draw;
                if (bt2 <= 2) draw = true;
                else if (bt2 == 3) draw = p3 != 8;
                else if (bt2 == 4) draw = p3 >= 2 && p3 <= 7;
                else if (bt2 == 5) draw = p3 >= 4 && p3 <= 7;
                else if (bt2 == 6) draw = p3 == 6 || p3 == 7;
                else draw = false;
                if (draw) give(banker);
                after(900, this::resolve);
            });
        } else {
            if (b <= 5) give(banker);
            after(900, this::resolve);
        }
    }

    private void resolve() {
        int p = total(player), b = total(banker);
        int winner = p > b ? 0 : (b > p ? 1 : 2);
        String score = p + " - " + b;
        String title = (winner == 0 ? "PLAYER wins " : winner == 1 ? "BANKER wins " : "TIE ") + score;
        long back;
        if (pick == 2) back = winner == 2 ? staked * 9 : 0;
        else if (winner == 2) back = staked;                         // tie: Player and Banker bets are returned
        else if (pick == winner) back = pick == 0 ? staked * 2 : staked * 195 / 100;
        else back = 0;
        running = false;
        finished = true;
        finish(staked, back, title);
    }

    @Override
    public void render(DrawContext c, int x, int y, int w, int h, int mx, int my) {
        runTasks();
        long now = now();
        int cx = x + w / 2;
        UI.felt(c, x, y, w, h - 52);
        deckX = cx - Cards.W / 2f;
        deckY = y + 8;
        Cards.pile(c, deckX, deckY);

        int px = x + w / 4, bxx = x + w * 3 / 4;
        int zy = y + 92, zh = Cards.H + 16;
        UI.outline(c, px - 100, zy, 200, zh, UI.alpha(pick == 0 ? UI.GOLD : UI.TEXT, pick == 0 ? 0.7f : 0.22f));
        UI.outline(c, bxx - 100, zy, 200, zh, UI.alpha(pick == 1 ? UI.GOLD : UI.TEXT, pick == 1 ? 0.7f : 0.22f));
        UI.center(c, "PLAYER" + shown(player, now), px, y + 80, pick == 0 ? UI.GOLD : UI.TEXT);
        UI.center(c, "BANKER" + shown(banker, now), bxx, y + 80, pick == 1 ? UI.GOLD : UI.TEXT);
        if (pick == 2) UI.center(c, "TIE", cx, y + 80, UI.GOLD);
        row(c, player, px, zy + 8, now);
        row(c, banker, bxx, zy + 8, now);

        int by = y + h - 52 - 34;
        bp.at(cx - 215, by, 100, 22);
        bb.at(cx - 108, by, 106, 22);
        bt.at(cx + 5, by, 80, 22);
        deal.at(cx + 95, by, 80, 22);
        bp.color = pick == 0 ? UI.GOOD : UI.PANEL2;
        bb.color = pick == 1 ? UI.GOOD : UI.PANEL2;
        bt.color = pick == 2 ? UI.GOOD : UI.PANEL2;
        bp.enabled = bb.enabled = bt.enabled = deal.enabled = !running;
        bp.draw(c, mx, my);
        bb.draw(c, mx, my);
        bt.draw(c, mx, my);
        deal.draw(c, mx, my);
        drawBetBar(c, x, y + h - 46, w, mx, my);
        drawBanner(c, cx, y + 70);
    }

    private String shown(List<Cards.Card> hand, long now) {
        if (hand.isEmpty()) return "";
        int s = 0;
        boolean any = false;
        for (Cards.Card k : hand) {
            if (k.flip(now) >= 0.5f) {
                s += value(k);
                any = true;
            }
        }
        return any ? "  " + (s % 10) : "";
    }

    private void row(DrawContext c, List<Cards.Card> hand, int cx, int top, long now) {
        int n = hand.size();
        int step = Cards.W + 4;
        float left = cx - ((n - 1) * step + Cards.W) / 2f;
        for (int i = 0; i < n; i++) Cards.draw(c, hand.get(i), left + i * step, top, now);
    }

    @Override
    public boolean click(int mx, int my) {
        if (clickBetBar(mx, my)) return true;
        if (running) return false;
        if (bp.hit(mx, my)) {
            pick = 0;
            return true;
        }
        if (bb.hit(mx, my)) {
            pick = 1;
            return true;
        }
        if (bt.hit(mx, my)) {
            pick = 2;
            return true;
        }
        if (deal.hit(mx, my)) {
            start();
            return true;
        }
        return false;
    }
}
