package com.blockoutlines.addon.casino;

import com.blockoutlines.addon.modules.Casino;
import com.blockoutlines.addon.util.Money;
import net.minecraft.client.gui.DrawContext;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/** One game of the casino. It draws itself inside a rectangle and answers clicks. */
public abstract class Game {
    protected final Casino casino;
    protected final SecureRandom rng = new SecureRandom();
    protected long bet = 1000;

    private final List<UI.Btn> betBtns = new ArrayList<>();
    private final long[] adds = {100, 1_000, 10_000, 100_000};
    private final UI.Btn clear = new UI.Btn("Clear", UI.PANEL2), half = new UI.Btn("1/2", UI.PANEL2),
        dbl = new UI.Btn("x2", UI.PANEL2), max = new UI.Btn("Max", UI.PANEL2);

    private String bannerText = "", bannerSub = "";
    private int bannerColor = UI.TEXT;
    private long bannerAt = -100000;

    protected Game(Casino casino) {
        this.casino = casino;
        String[] names = {"+100", "+1K", "+10K", "+100K"};
        for (String n : names) betBtns.add(new UI.Btn(n, UI.PANEL2));
    }

    public abstract String name();

    /** True while a round is running (the bet can not be changed then). */
    public abstract boolean busy();

    /** The game area is x,y,w,h. The bet bar is drawn at the bottom of it by this class. */
    public abstract void render(DrawContext c, int x, int y, int w, int h, int mx, int my);

    public abstract boolean click(int mx, int my);

    protected long now() {
        return System.currentTimeMillis();
    }

    // ---------------------------------------------------------------- a tiny timeline for animations

    private final List<Object[]> tasks = new ArrayList<>();
    private long cursor;

    protected void after(long delayMs, Runnable r) {
        if (tasks.isEmpty() && cursor < now()) cursor = now();
        cursor += delayMs;
        tasks.add(new Object[]{cursor, r});
    }

    protected void runTasks() {
        long n = now();
        List<Object[]> due = new ArrayList<>();
        for (Object[] t : tasks) if ((Long) t[0] <= n) due.add(t);
        tasks.removeAll(due);
        for (Object[] t : due) ((Runnable) t[1]).run();
    }

    protected boolean hasTasks() {
        return !tasks.isEmpty();
    }

    // ---------------------------------------------------------------- chips

    protected boolean takeBet(long amount) {
        if (amount <= 0) {
            banner("Put a bet first", UI.GOLD, "");
            return false;
        }
        if (amount > casino.balance) {
            banner("Not enough chips", UI.BAD, "");
            return false;
        }
        casino.balance -= amount;
        return true;
    }

    /** Ends a round: staked = what the round cost in total, returned = what comes back. */
    protected void finish(long staked, long returned, String title) {
        casino.balance += returned;
        casino.rounds++;
        long net = returned - staked;
        if (net > 0) casino.wins++;
        if (net > casino.best) casino.best = net;
        casino.save();
        if (bet > casino.balance && casino.balance > 0) bet = casino.balance;
        String sub = net > 0 ? "+" + Money.pretty(net) : (net < 0 ? "-" + Money.pretty(-net) : "money back");
        banner(title, net > 0 ? UI.GOOD : (net < 0 ? UI.BAD : UI.GOLD), sub);
    }

    protected void banner(String text, int color, String sub) {
        bannerText = text;
        bannerColor = color;
        bannerSub = sub;
        bannerAt = now();
    }

    protected void drawBanner(DrawContext c, int cx, int cy) {
        long age = now() - bannerAt;
        if (age < 0 || age > 2800) return;
        float pop = UI.easeBack(age / 320f);
        float fade = age > 2300 ? 1f - (age - 2300) / 500f : 1f;
        int bw = Math.max(UI.tr().getWidth(bannerText) * 2 + 40, 170);
        int bh = bannerSub.isEmpty() ? 34 : 50;
        int hw = (int) (bw / 2 * pop);
        UI.rect(c, cx - hw, cy - bh / 2, hw * 2, bh, UI.alpha(0xFF0B0B12, 0.82f * fade));
        UI.rect(c, cx - hw, cy - bh / 2, hw * 2, 2, UI.alpha(bannerColor, fade));
        UI.rect(c, cx - hw, cy + bh / 2 - 2, hw * 2, 2, UI.alpha(bannerColor, fade));
        UI.scaled(c, cx, cy - (bannerSub.isEmpty() ? 0 : 8), 2f * pop, 2f * pop, bannerText, UI.alpha(bannerColor, fade));
        if (!bannerSub.isEmpty()) UI.scaled(c, cx, cy + 14, 1.2f * pop, 1.2f * pop, bannerSub, UI.alpha(UI.TEXT, fade));
    }

    // ---------------------------------------------------------------- bet bar

    protected void drawBetBar(DrawContext c, int x, int y, int w, int mx, int my) {
        boolean lock = busy();
        UI.rect(c, x, y, w, 1, UI.LINE);
        UI.text(c, "Bet", x + 2, y + 6, UI.MUTED);
        UI.text(c, Money.pretty(bet), x + 24, y + 6, UI.GOLD);
        UI.right(c, "Chips  " + Money.pretty(casino.balance), x + w - 2, y + 6, casino.balance > 0 ? UI.TEXT : UI.BAD);
        int n = 8, gap = 4, bw = (w - gap * (n - 1)) / n;
        int by = y + 20;
        List<UI.Btn> all = new ArrayList<>(betBtns);
        all.add(clear);
        all.add(half);
        all.add(dbl);
        all.add(max);
        for (int i = 0; i < all.size(); i++) {
            UI.Btn b = all.get(i);
            b.at(x + i * (bw + gap), by, bw, 18);
            b.enabled = !lock;
            b.draw(c, mx, my);
        }
    }

    protected boolean clickBetBar(int mx, int my) {
        if (busy()) return false;
        for (int i = 0; i < betBtns.size(); i++) {
            if (betBtns.get(i).hit(mx, my)) {
                bet = Math.min(casino.balance, bet + adds[i]);
                return true;
            }
        }
        if (clear.hit(mx, my)) {
            bet = 0;
            return true;
        }
        if (half.hit(mx, my)) {
            bet = Math.max(0, bet / 2);
            return true;
        }
        if (dbl.hit(mx, my)) {
            bet = Math.min(casino.balance, bet * 2);
            return true;
        }
        if (max.hit(mx, my)) {
            bet = casino.balance;
            return true;
        }
        return false;
    }
}
