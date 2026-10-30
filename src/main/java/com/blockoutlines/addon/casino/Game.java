package com.blockoutlines.addon.casino;

import com.blockoutlines.addon.modules.Casino;
import com.blockoutlines.addon.util.Money;
import net.minecraft.client.gui.DrawContext;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.List;

/** One game of the casino. It draws itself inside a rectangle and answers clicks. */
public abstract class Game {
    protected final Casino casino;
    protected final SecureRandom rng = new SecureRandom();
    protected long bet = 1000;

    private final long[] adds = {100, 1_000, 10_000, 100_000};
    private final UI.Btn clear = new UI.Btn("Clear", UI.PANEL2), half = new UI.Btn("1/2", UI.PANEL2),
        dbl = new UI.Btn("x2", UI.PANEL2), max = new UI.Btn("Max", UI.PANEL2);

    private final List<float[]> parts = new ArrayList<>();   // x, y, vx, vy, life, colour index
    private boolean burst;
    private long lastParts;
    private static final int[] CONF = {0xFFFFC857, 0xFF3DDC84, 0xFFFFFFFF, 0xFF7C5CFF, 0xFF58DDE8};

    private String bannerText = "", bannerSub = "";
    private int bannerColor = UI.TEXT;
    private long bannerAt = -100000;

    protected Game(Casino casino) {
        this.casino = casino;
    }

    public long bet() {
        return bet;
    }

    public void setBet(long v) {
        bet = Math.max(0, Math.min(v, Casino.MAX_BET));
    }

    /** The most a button may raise the bet to. */
    protected long cap() {
        return casino.unlimited() ? Casino.MAX_BET : Math.max(0, casino.balance);
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
        if (!casino.unlimited() && amount > casino.balance) {
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
        if (!casino.unlimited() && bet > casino.balance && casino.balance > 0) bet = casino.balance;
        if (net > 0) burst = true;
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
        long t = now();
        if (burst) {
            burst = false;
            ThreadLocalRandom r = ThreadLocalRandom.current();
            for (int i = 0; i < 46; i++) {
                double a = r.nextDouble() * Math.PI * 2, v = 60 + r.nextDouble() * 190;
                parts.add(new float[]{cx, cy, (float) Math.cos(a) * (float) v, (float) Math.sin(a) * (float) v - 90, 1.3f + r.nextFloat() * 0.8f, r.nextInt(CONF.length)});
            }
            lastParts = t;
        }
        if (!parts.isEmpty()) {
            float dt = Math.min(0.05f, Math.max(0f, (t - lastParts) / 1000f));
            lastParts = t;
            for (int i = parts.size() - 1; i >= 0; i--) {
                float[] p = parts.get(i);
                p[0] += p[2] * dt;
                p[1] += p[3] * dt;
                p[3] += 420 * dt;
                p[4] -= dt;
                if (p[4] <= 0) {
                    parts.remove(i);
                    continue;
                }
                UI.rect(c, Math.round(p[0]), Math.round(p[1]), 3, 3, UI.alpha(CONF[(int) p[5]], Math.min(1f, p[4])));
            }
        }
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

    private static final int[] CHIP_COL = {0xFFE4E4EC, 0xFFD94A4A, 0xFF3F6BE0, 0xFF2A2A35};
    private static final String[] CHIP_TXT = {"100", "1K", "10K", "100K"};
    private final int[] chipX = new int[4];
    private int chipY, chipR = 12;

    protected void drawBetBar(DrawContext c, int x, int y, int w, int mx, int my) {
        boolean lock = busy();
        UI.rect(c, x, y, w, 1, UI.LINE);
        UI.text(c, "BET", x + 2, y + 7, UI.MUTED);   // the amount box of the screen sits right of this
        String chips = Money.pretty(casino.balance);
        UI.right(c, chips, x + w - 2, y + 7, casino.balance >= 0 ? UI.GOLD : UI.BAD);
        UI.ellipse(c, x + w - 4 - UI.tr().getWidth(chips) - 8, y + 11, 5, 5, 0xFF9A7414);
        UI.ellipse(c, x + w - 4 - UI.tr().getWidth(chips) - 8, y + 11, 4, 4, UI.GOLD);

        chipY = y + 33;
        for (int i = 0; i < 4; i++) {
            chipX[i] = x + 14 + i * 31;
            boolean hover = !lock && Math.hypot(mx - chipX[i], my - chipY) <= chipR;
            if (lock) {
                UI.ellipse(c, chipX[i], chipY, chipR, chipR, 0xFF2A2A33);
            } else UI.chip(c, chipX[i], chipY, chipR, CHIP_COL[i], CHIP_TXT[i], hover);
        }
        int sx = x + 4 * 31 + 8;
        int bw = (w - (sx - x) - 3 * 4) / 4;
        UI.Btn[] row = {clear, half, dbl, max};
        for (int i = 0; i < 4; i++) {
            row[i].at(sx + i * (bw + 4), y + 22, bw, 22);
            row[i].enabled = !lock;
            row[i].draw(c, mx, my);
        }
    }

    protected boolean clickBetBar(int mx, int my) {
        if (busy()) return false;
        for (int i = 0; i < 4; i++) {
            if (Math.hypot(mx - chipX[i], my - chipY) <= chipR) {
                bet = Math.min(cap(), bet + adds[i]);
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
            bet = Math.min(cap(), bet * 2);
            return true;
        }
        if (max.hit(mx, my)) {
            bet = casino.unlimited() ? Math.max(bet, Math.max(0, casino.balance)) : cap();
            return true;
        }
        return false;
    }
}
