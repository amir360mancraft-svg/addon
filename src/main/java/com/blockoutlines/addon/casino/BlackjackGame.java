package com.blockoutlines.addon.casino;

import com.blockoutlines.addon.modules.Casino;

import java.util.ArrayList;
import java.util.List;

/** Blackjack against the dealer: 6 decks, dealer stands on every 17, blackjack pays 3:2, double on two cards. No split. */
public class BlackjackGame extends Game {
    private enum Phase { IDLE, DEAL, PLAY, DEALER, DONE }

    private final Cards.Shoe shoe = new Cards.Shoe(6);
    private final List<Cards.Card> player = new ArrayList<>(), dealer = new ArrayList<>();
    private Phase phase = Phase.IDLE;
    private long staked;
    private float deckX, deckY;
    private int resultCol;
    private long resultAt = -10000;

    private final UI.Btn deal = new UI.Btn("DEAL", UI.ACCENT), hit = new UI.Btn("Hit", UI.GOOD),
        stand = new UI.Btn("Stand", UI.BAD), dbl = new UI.Btn("Double", UI.GOLD);

    public BlackjackGame(Casino casino) {
        super(casino);
    }

    @Override
    public String name() {
        return "Blackjack";
    }

    @Override
    public boolean busy() {
        return phase != Phase.IDLE && phase != Phase.DONE;
    }

    private static int total(List<Cards.Card> hand, boolean onlyUp) {
        int sum = 0, aces = 0;
        for (Cards.Card k : hand) {
            if (onlyUp && !k.faceUp) continue;
            int r = Cards.rank(k.id);
            if (r == 0) {
                aces++;
                sum += 11;
            } else sum += Math.min(10, r + 1);
        }
        while (sum > 21 && aces > 0) {
            sum -= 10;
            aces--;
        }
        return sum;
    }

    private void give(List<Cards.Card> hand, boolean up) {
        hand.add(new Cards.Card(shoe.draw(), now(), up, deckX, deckY));
        Sfx.card();
    }

    private void start() {
        if (!takeBet(bet)) return;
        staked = bet;
        player.clear();
        dealer.clear();
        phase = Phase.DEAL;
        after(0, () -> give(player, true));
        after(380, () -> give(dealer, true));
        after(380, () -> give(player, true));
        after(380, () -> give(dealer, false));
        after(600, () -> {
            boolean pbj = total(player, false) == 21, dbj = total(dealer, false) == 21;
            if (pbj || dbj) {
                revealHole();
                after(700, this::resolve);
            } else phase = Phase.PLAY;
        });
    }

    private void revealHole() {
        for (Cards.Card k : dealer) if (!k.faceUp) Sfx.flip();
        for (Cards.Card k : dealer) k.reveal(now());
    }

    private void dealerPlay() {
        phase = Phase.DEALER;
        after(350, this::revealHole);
        after(700, this::dealerStep);
    }

    private void dealerStep() {
        if (total(dealer, false) < 17) {
            give(dealer, true);
            after(750, this::dealerStep);
        } else after(300, this::resolve);
    }

    private void resolve() {
        revealHole();
        int p = total(player, false), d = total(dealer, false);
        boolean pbj = player.size() == 2 && p == 21, dbj = dealer.size() == 2 && d == 21;
        if (p > 21) finish(staked, 0, "BUST");
        else if (pbj && !dbj) finish(staked, staked + staked * 3 / 2, "BLACKJACK");
        else if (d > 21) finish(staked, staked * 2, "Dealer busts");
        else if (p > d) finish(staked, staked * 2, "You win");
        else if (p == d) finish(staked, staked, "Push");
        else finish(staked, 0, "Dealer wins");
        int net = p > 21 ? -1 : (pbj && !dbj) ? 1 : d > 21 ? 1 : p > d ? 1 : p == d ? 0 : -1;
        resultCol = net > 0 ? UI.GOOD : (net < 0 ? UI.BAD : UI.GOLD);
        resultAt = now();
        phase = Phase.DONE;
    }

    private void afterPlayerCard() {
        int p = total(player, false);
        phase = Phase.DEALER;
        if (p > 21) {
            after(500, this::resolve);
        } else if (p == 21) {
            dealerPlay();
        } else {
            after(350, () -> phase = Phase.PLAY);
        }
    }

    @Override
    public void render(Gfx c, int x, int y, int w, int h, int mx, int my) {
        runTasks();
        long now = now();
        int cx = x + w / 2;
        UI.felt(c, x, y, w, h - 52);
        deckX = x + w - Cards.W - 14;
        deckY = y + 12;
        Cards.pile(c, deckX, deckY);

        UI.text(c, "Dealer" + (dealer.isEmpty() ? "" : "  " + total(dealer, true)), x + 16, y + 12, UI.TEXT);
        UI.center(c, "Blackjack pays 3:2  -  dealer stands on 17", cx, y + 8, UI.alpha(UI.TEXT, 0.5f));
        drawHand(c, dealer, cx, y + 20, now);
        UI.text(c, "You" + (player.isEmpty() ? "" : "  " + total(player, false)), x + 16, y + 104, UI.TEXT);
        drawHand(c, player, cx, y + 110, now);
        UI.glow(c, cx - 130, y + 104, 260, Cards.H + 12, resultCol, resultAt);
        drawBetPile(c, x + 62, y + 190, busy() ? staked : bet);

        int by = y + h - 52 - 34;
        boolean play = phase == Phase.PLAY;
        boolean idle = phase == Phase.IDLE || phase == Phase.DONE;
        deal.at(cx - 45, by, 90, 22);
        hit.at(cx - 135, by, 85, 22);
        stand.at(cx - 42, by, 85, 22);
        dbl.at(cx + 50, by, 85, 22);
        deal.enabled = idle;
        hit.enabled = stand.enabled = play;
        dbl.enabled = play && player.size() == 2 && (casino.unlimited() || casino.balance >= staked);
        if (idle) deal.draw(c, mx, my);
        else {
            hit.draw(c, mx, my);
            stand.draw(c, mx, my);
            dbl.draw(c, mx, my);
        }
        drawBetBar(c, x, y + h - 46, w, mx, my);
        drawBanner(c, cx, y + 98);
    }

    private void drawHand(Gfx c, List<Cards.Card> hand, int cx, int top, long now) {
        int n = hand.size();
        int step = n > 5 ? 32 : 44;
        float left = cx - ((n - 1) * step + Cards.W) / 2f;
        for (int i = 0; i < n; i++) Cards.draw(c, hand.get(i), left + i * step, top, now);
    }

    @Override
    public boolean click(int mx, int my) {
        if (clickBetBar(mx, my)) return true;
        if (phase == Phase.IDLE || phase == Phase.DONE) {
            if (deal.hit(mx, my)) {
                start();
                return true;
            }
            return false;
        }
        if (phase != Phase.PLAY) return false;
        if (hit.hit(mx, my)) {
            give(player, true);
            afterPlayerCard();
            return true;
        }
        if (stand.hit(mx, my)) {
            phase = Phase.DEALER;
            dealerPlay();
            return true;
        }
        if (dbl.hit(mx, my)) {
            casino.balance -= staked;
            staked *= 2;
            give(player, true);
            int p = total(player, false);
            phase = Phase.DEALER;
            if (p > 21) after(500, this::resolve);
            else dealerPlay();
            return true;
        }
        return false;
    }
}
