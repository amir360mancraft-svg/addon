package com.blockoutlines.addon.gui;

import com.blockoutlines.addon.casino.BaccaratGame;
import com.blockoutlines.addon.casino.BlackjackGame;
import com.blockoutlines.addon.casino.CoinFlipGame;
import com.blockoutlines.addon.casino.Game;
import com.blockoutlines.addon.casino.MinesGame;
import com.blockoutlines.addon.casino.UI;
import com.blockoutlines.addon.modules.Casino;
import com.blockoutlines.addon.util.Money;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/** The casino window: a list of games on the left and the chosen game on the right. */
public class CasinoScreen extends Screen {
    private static final int W = 640, H = 360, SIDE = 112, HEAD = 30;

    private final Casino casino;
    private final List<Game> games = new ArrayList<>();
    private final List<UI.Btn> tabs = new ArrayList<>();
    private final UI.Btn reset = new UI.Btn("Reset chips", 0xFF4A2C2C), closeBtn = new UI.Btn("Close", UI.PANEL2);
    private int cur;
    private int left, top;
    private TextFieldWidget betField;
    private long shownBet = -1;
    private boolean syncing;

    public CasinoScreen(Casino casino) {
        super(Text.literal("Casino"));
        this.casino = casino;
        games.add(new BlackjackGame(casino));
        games.add(new BaccaratGame(casino));
        games.add(new CoinFlipGame(casino));
        games.add(new MinesGame(casino));
        for (Game g : games) tabs.add(new UI.Btn(g.name(), UI.PANEL2));
    }

    @Override
    protected void init() {
        left = (width - W) / 2;
        top = Math.max(4, (height - H) / 2);
        betField = new TextFieldWidget(textRenderer, 0, 0, 110, 16, Text.literal("Bet"));
        betField.setMaxLength(14);
        betField.setChangedListener(t -> {
            if (syncing) return;
            long v = Money.parse(t);
            if (v >= 0) {
                games.get(cur).setBet(v);
                shownBet = games.get(cur).bet();
            }
        });
        addDrawableChild(betField);
        shownBet = -1;
    }

    /** The text that goes in the amount box for this bet: 1000 -> 1k, 2500 -> 2500. */
    private static String boxText(long v) {
        if (v >= 1_000_000_000L && v % 1_000_000_000L == 0) return (v / 1_000_000_000L) + "b";
        if (v >= 1_000_000L && v % 1_000_000L == 0) return (v / 1_000_000L) + "m";
        if (v >= 1_000L && v % 1_000L == 0) return (v / 1_000L) + "k";
        return String.valueOf(v);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void close() {
        if (games.get(cur).busy()) return;       // a round is running: finish it first
        casino.save();
        if (client != null) client.setScreen(null);
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        ctx.fill(0, 0, width, height, 0xA0000000);
        UI.rect(ctx, left, top, W, H, UI.BG);
        UI.rect(ctx, left, top, W, HEAD, 0xFF0E0E15);
        UI.rect(ctx, left, top + HEAD - 1, W, 1, UI.LINE);
        UI.scaled(ctx, left + 52, top + 15, 1.4f, 1.4f, "CASINO", UI.GOLD);
        UI.right(ctx, "Chips  " + Money.pretty(casino.balance), left + W - 10, top + 6, UI.TEXT);
        UI.right(ctx, "Rounds " + casino.rounds + "   Won " + casino.wins + "   Best +" + Money.pretty(casino.best), left + W - 10, top + 17, UI.MUTED);

        Game g = games.get(cur);
        boolean busy = g.busy();
        for (int i = 0; i < tabs.size(); i++) {
            UI.Btn b = tabs.get(i);
            b.at(left + 8, top + HEAD + 10 + i * 30, SIDE - 16, 24);
            b.color = i == cur ? UI.ACCENT : UI.PANEL2;
            b.enabled = !busy || i == cur;
            b.draw(ctx, mouseX, mouseY);
        }
        UI.text(ctx, "Fair draws.", left + 10, top + H - 74, UI.MUTED);
        UI.text(ctx, "Practice chips,", left + 10, top + H - 62, UI.MUTED);
        UI.text(ctx, "not real money.", left + 10, top + H - 50, UI.MUTED);
        reset.at(left + 8, top + H - 34, SIDE - 16, 18);
        reset.enabled = !busy;
        reset.draw(ctx, mouseX, mouseY);
        closeBtn.at(left + W - 66, top + H - 22, 58, 16);
        closeBtn.enabled = !busy;

        int gx = left + SIDE, gy = top + HEAD + 8, gw = W - SIDE - 8, gh = H - HEAD - 8 - 24;
        // the amount box: it follows the bet when the buttons change it, and changes the bet when you type
        betField.setX(gx + 26);
        betField.setY(gy + gh - 46 + 3);
        betField.setEditable(!busy);
        if (g.bet() != shownBet) {
            syncing = true;
            betField.setText(boxText(g.bet()));
            syncing = false;
            shownBet = g.bet();
        }
        g.render(ctx, gx, gy, gw, gh, mouseX, mouseY);
        betField.render(ctx, mouseX, mouseY, delta);
        closeBtn.draw(ctx, mouseX, mouseY);
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        boolean fieldHandled = super.mouseClicked(click, doubled);
        if (click.button() == 0) {
            int mx = (int) click.x(), my = (int) click.y();
            Game g = games.get(cur);
            boolean busy = g.busy();
            for (int i = 0; i < tabs.size(); i++) {
                if (tabs.get(i).hit(mx, my)) {
                    cur = i;
                    shownBet = -1;
                    return true;
                }
            }
            if (reset.hit(mx, my)) {
                casino.resetChips();
                return true;
            }
            if (closeBtn.hit(mx, my)) {
                close();
                return true;
            }
            if (g.click(mx, my)) return true;
        }
        return fieldHandled;
    }
}
