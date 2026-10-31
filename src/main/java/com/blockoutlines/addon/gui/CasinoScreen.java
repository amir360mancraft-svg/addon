package com.blockoutlines.addon.gui;

import com.blockoutlines.addon.casino.BaccaratGame;
import com.blockoutlines.addon.casino.BlackjackGame;
import com.blockoutlines.addon.casino.CoinFlipGame;
import com.blockoutlines.addon.casino.CrashGame;
import com.blockoutlines.addon.casino.RouletteGame;
import com.blockoutlines.addon.casino.Game;
import com.blockoutlines.addon.casino.Gfx;
import com.blockoutlines.addon.casino.GuiGfx;
import com.blockoutlines.addon.casino.MinesGame;
import com.blockoutlines.addon.casino.UI;
import com.blockoutlines.addon.modules.Casino;
import com.blockoutlines.addon.util.Money;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
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
        games.add(new RouletteGame(casino));
        games.add(new CrashGame(casino));
        for (Game g : games) tabs.add(new UI.Btn(g.name(), UI.PANEL2));
    }

    @Override
    protected void init() {
        left = (width - W) / 2;
        top = Math.max(4, (height - H) / 2);
        betField = new TextFieldWidget(textRenderer, 0, 0, 104, 12, Text.literal("Bet"));
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
    public void render(DrawContext raw, int mouseX, int mouseY, float delta) {
        Gfx ctx = new GuiGfx(raw);
        ctx.fill(0, 0, width, height, 0xB0000000);
        // the window
        UI.round(ctx, left + 1, top + 3, W, H, 0x66000000);
        UI.round(ctx, left, top, W, H, UI.LINE);
        UI.grad(ctx, left + 1, top + 1, W - 2, H - 2, 0xFF191926, 0xFF0F0F18);
        // header
        UI.grad(ctx, left + 1, top + 1, W - 2, HEAD - 1, 0xFF262640, 0xFF16162A);
        UI.rect(ctx, left + 1, top + HEAD - 1, W - 2, 1, UI.alpha(UI.ACCENT, 0.7f));
        UI.item(ctx, UI.diamond(), left + 18, top + 15, 1f, 1f);
        UI.scaled(ctx, left + 74, top + 15, 1.5f, 1.5f, "CASINO", UI.GOLD);
        String chips = Money.pretty(casino.balance);
        int pw = UI.tr().getWidth(chips) + 30;
        UI.round(ctx, left + W - 12 - pw, top + 5, pw, 20, 0xFF0B0B13);
        UI.ellipse(ctx, left + W - 12 - pw + 11, top + 15, 6, 6, 0xFF9A7414);
        UI.ellipse(ctx, left + W - 12 - pw + 11, top + 15, 5, 5, UI.GOLD);
        UI.right(ctx, chips, left + W - 18, top + 11, casino.balance >= 0 ? UI.TEXT : UI.BAD);
        UI.right(ctx, "Rounds " + casino.rounds + "  Won " + casino.wins + "  Best +" + Money.pretty(casino.best), left + W - 24 - pw, top + 11, UI.MUTED);

        Game g = games.get(cur);
        boolean busy = g.busy();
        // sidebar
        UI.rect(ctx, left + SIDE - 1, top + HEAD, 1, H - HEAD - 1, UI.alpha(UI.LINE, 0.8f));
        for (int i = 0; i < tabs.size(); i++) {
            UI.Btn b = tabs.get(i);
            b.at(left + 8, top + HEAD + 10 + i * 32, SIDE - 16, 26);
            b.color = i == cur ? UI.ACCENT : UI.PANEL2;
            b.enabled = !busy || i == cur;
            b.label = "      " + games.get(i).name();
            b.draw(ctx, mouseX, mouseY);
            UI.item(ctx, icon(i), b.x + 15, b.y + 14, 1f, 1f);
        }
        UI.text(ctx, "Fair draws only.", left + 10, top + H - 74, UI.MUTED);
        UI.text(ctx, "Practice chips,", left + 10, top + H - 62, UI.MUTED);
        UI.text(ctx, "not real money.", left + 10, top + H - 50, UI.MUTED);
        reset.at(left + 8, top + H - 34, SIDE - 16, 20);
        reset.enabled = !busy;
        reset.draw(ctx, mouseX, mouseY);
        closeBtn.at(left + W - 66, top + H - 22, 58, 16);
        closeBtn.enabled = !busy;

        int gx = left + SIDE, gy = top + HEAD + 8, gw = W - SIDE - 8, gh = H - HEAD - 8 - 24;
        // the amount box: it follows the bet when the buttons change it, and changes the bet when you type
        int fx = gx + 28, fy = gy + gh - 46 + 3;
        betField.setX(fx + 4);
        betField.setY(fy + 4);
        betField.setEditable(!busy);
        betField.setDrawsBackground(false);
        if (g.bet() != shownBet) {
            syncing = true;
            betField.setText(boxText(g.bet()));
            syncing = false;
            shownBet = g.bet();
        }
        g.render(ctx, gx, gy, gw, gh, mouseX, mouseY);
        UI.round(ctx, fx, fy, 112, 20, betField.isFocused() ? UI.ACCENT : UI.LINE);
        UI.round(ctx, fx + 1, fy + 1, 110, 18, 0xFF0B0B13);
        betField.render(raw, mouseX, mouseY, delta);
        closeBtn.draw(ctx, mouseX, mouseY);
    }

    private ItemStack icon(int i) {
        return switch (i) {
            case 0 -> UI.stack(Items.PAPER);
            case 1 -> UI.stack(Items.BOOK);
            case 2 -> UI.stack(Items.GOLD_INGOT);
            case 4 -> UI.stack(Items.RED_DYE);
            case 5 -> UI.stack(Items.FIREWORK_ROCKET);
            default -> UI.tnt();
        };
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
