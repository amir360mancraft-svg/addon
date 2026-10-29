package com.blockoutlines.addon.modules;

import com.blockoutlines.addon.BlockOutlinesAddon;
import com.blockoutlines.addon.util.Money;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.KeybindSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.DrawContext;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Small fair games for when you are the host: somebody pays you, the game is played with a real 50/50 (or fair
 * number) draw, and if they win a line appears on screen with what you owe. Nothing is paid automatically: you press
 * the pay key and the command "/pay name amount" is sent, so a wrong chat parse can never send money on its own.
 * The result is never chosen or changed by the module, it only comes from SecureRandom.
 */
public class GambleGames extends Module {
    public enum Game { CoinFlip, HigherNumber }

    private static final Pattern INCOMING = Pattern.compile("(?i)(\\w{1,16})\\s+paid\\s+you\\s+\\$?\\s*([0-9][0-9,.]*\\s*[kmbt]?)(?![a-z])");
    private static final Pattern SIDE = Pattern.compile("(?i)\\b(heads?|tails?)\\b");

    private static final class Bet {
        String player;
        long amount, born;
        int side = -1;          // coin flip: 0 heads, 1 tails
        boolean done;
    }

    private static final class Owed {
        String player;
        long amount;
        boolean refund;
    }

    private static final class Line {
        String text;
        int color;
        long ms;
    }

    private final SecureRandom rng = new SecureRandom();
    private final List<Bet> bets = new ArrayList<>();
    private final List<Owed> owed = new ArrayList<>();
    private final List<Line> log = new ArrayList<>();
    private final List<long[]> chatTimes = new ArrayList<>();
    private final List<String> chatLines = new ArrayList<>();
    private String lastKey = "";
    private long lastKeyMs, lastPayMs;

    private final SettingGroup sg = settings.getDefaultGroup();
    private final SettingGroup sgHud = settings.createGroup("Screen");

    private final Setting<Game> game = sg.add(new EnumSetting.Builder<Game>().name("game")
        .description("CoinFlip: the player says heads or tails in chat. HigherNumber: two numbers 1-9, the higher one wins, a tie gives the money back.")
        .defaultValue(Game.CoinFlip).build());
    private final Setting<Keybind> payKey = sg.add(new KeybindSetting.Builder().name("pay-key")
        .description("Sends /pay for the first line in the owed list.").defaultValue(Keybind.none()).action(this::payNext).build());
    private final Setting<Double> multiplier = sg.add(new DoubleSetting.Builder().name("payout-multiplier")
        .description("What the winner gets back, 2.0 = double.").defaultValue(2.0).range(1.0, 3.0).sliderRange(1.5, 2.5).build());
    private final Setting<Integer> maxBetK = sg.add(new IntSetting.Builder().name("max-bet-k")
        .description("Bets above this (in thousands) are not played, they go on the list as 'refund'. 0 = no limit.").defaultValue(0).min(0).sliderMax(5000).build());
    private final Setting<Integer> waitSec = sg.add(new IntSetting.Builder().name("wait-for-choice-seconds")
        .description("How long a bet waits for heads/tails before it is put on the refund list.").defaultValue(60).range(10, 300).sliderRange(20, 120).build());
    private final Setting<Boolean> reply = sg.add(new BoolSetting.Builder().name("tell-player")
        .description("Also send the result to the player with /msg.").defaultValue(false).build());

    private final Setting<Integer> hx = sgHud.add(new IntSetting.Builder().name("x").defaultValue(6).range(0, 2000).sliderMax(600).build());
    private final Setting<Integer> hy = sgHud.add(new IntSetting.Builder().name("y").defaultValue(160).range(0, 2000).sliderMax(600).build());

    public GambleGames() {
        super(BlockOutlinesAddon.CATEGORY, "gamble-games", "Fair coin flip / higher-number games for when you host. You press the key to pay winners.");
    }

    @Override
    public void onActivate() {
        bets.clear();
    }

    private void say(String s, int color) {
        Line l = new Line();
        l.text = s;
        l.color = color;
        l.ms = System.currentTimeMillis();
        log.add(l);
        while (log.size() > 6) log.remove(0);
    }

    private void tell(String player, String msg) {
        if (reply.get() && mc.player != null) ChatUtils.sendPlayerMsg("/msg " + player + " " + msg);
    }

    @EventHandler
    private void onMessage(ReceiveMessageEvent event) {
        String text = event.getMessage().getString();
        if (text == null || text.length() > 300) return;
        long now = System.currentTimeMillis();

        Matcher m = INCOMING.matcher(text);
        if (m.find() && !m.group(1).equalsIgnoreCase("you")) {
            String who = m.group(1);
            long amt = Money.parse(m.group(2));
            String key = who.toLowerCase() + ":" + amt;
            if (amt > 0 && !(key.equals(lastKey) && now - lastKeyMs < 400)) {
                lastKey = key;
                lastKeyMs = now;
                newBet(who, amt);
            }
            return;
        }

        chatLines.add(text);
        chatTimes.add(new long[]{now});
        while (chatLines.size() > 60) {
            chatLines.remove(0);
            chatTimes.remove(0);
        }
        if (game.get() == Game.CoinFlip) for (Bet b : new ArrayList<>(bets)) if (!b.done && b.side < 0) pickSide(b);
        sweep();
    }

    private void newBet(String who, long amt) {
        long cap = maxBetK.get() * 1000L;
        if (cap > 0 && amt > cap) {
            addOwed(who, amt, true);
            say(who + " bet " + Money.pretty(amt) + " - too high, refund it", 0xFFFFC857);
            return;
        }
        Bet b = new Bet();
        b.player = who;
        b.amount = amt;
        b.born = System.currentTimeMillis();
        if (game.get() == Game.HigherNumber) {
            play(b);
            return;
        }
        bets.add(b);
        pickSide(b);
        if (!b.done && b.side < 0) {
            say(who + " paid " + Money.pretty(amt) + " - waiting for heads/tails", 0xFFB8B8C8);
            tell(who, "got your " + Money.pretty(amt) + ", say heads or tails");
        }
    }

    /** Look in the recent chat for a line from this player that says heads or tails. */
    private void pickSide(Bet b) {
        Pattern name = Pattern.compile("(?i)\\b" + Pattern.quote(b.player) + "\\b");
        for (int i = chatLines.size() - 1; i >= 0; i--) {
            if (b.born - chatTimes.get(i)[0] > 120_000) break;
            String line = chatLines.get(i);
            if (!name.matcher(line).find()) continue;
            Matcher s = SIDE.matcher(line);
            if (s.find()) {
                b.side = s.group(1).toLowerCase().startsWith("h") ? 0 : 1;
                play(b);
                return;
            }
        }
    }

    private void sweep() {
        long now = System.currentTimeMillis();
        for (Bet b : new ArrayList<>(bets)) {
            if (b.done) {
                bets.remove(b);
            } else if (now - b.born > waitSec.get() * 1000L) {
                b.done = true;
                bets.remove(b);
                addOwed(b.player, b.amount, true);
                say(b.player + " never chose - refund " + Money.pretty(b.amount), 0xFFFFC857);
            }
        }
    }

    private void play(Bet b) {
        b.done = true;
        bets.remove(b);
        long win = Math.round(b.amount * multiplier.get());
        if (game.get() == Game.CoinFlip) {
            int result = rng.nextInt(2);
            boolean won = result == b.side;
            String face = result == 0 ? "heads" : "tails";
            if (won) {
                addOwed(b.player, win, false);
                say(b.player + ": " + face + " - WON, you owe " + Money.pretty(win), 0xFFFF5C5C);
                tell(b.player, face + " - you win " + Money.pretty(win));
            } else {
                say(b.player + ": " + face + " - lost " + Money.pretty(b.amount), 0xFF3DDC84);
                tell(b.player, face + " - you lose");
            }
        } else {
            int p = rng.nextInt(9) + 1, d = rng.nextInt(9) + 1;
            String nums = "player " + p + " / dealer " + d;
            if (p > d) {
                addOwed(b.player, win, false);
                say(b.player + ": " + nums + " - WON, you owe " + Money.pretty(win), 0xFFFF5C5C);
                tell(b.player, nums + " - you win " + Money.pretty(win));
            } else if (p == d) {
                addOwed(b.player, b.amount, true);
                say(b.player + ": " + nums + " - tie, refund " + Money.pretty(b.amount), 0xFFFFC857);
                tell(b.player, nums + " - tie, money back");
            } else {
                say(b.player + ": " + nums + " - lost " + Money.pretty(b.amount), 0xFF3DDC84);
                tell(b.player, nums + " - you lose");
            }
        }
    }

    private void addOwed(String who, long amt, boolean refund) {
        Owed o = new Owed();
        o.player = who;
        o.amount = amt;
        o.refund = refund;
        owed.add(o);
    }

    private void payNext() {
        if (mc.player == null || owed.isEmpty()) return;
        long now = System.currentTimeMillis();
        if (now - lastPayMs < 1500) return;
        lastPayMs = now;
        Owed o = owed.remove(0);
        ChatUtils.sendPlayerMsg("/pay " + o.player + " " + o.amount);
        say("sent /pay " + o.player + " " + Money.pretty(o.amount), 0xFFB8B8C8);
    }

    @EventHandler
    private void onRender2D(Render2DEvent event) {
        if (mc.getWindow() == null || mc.options.hudHidden) return;
        sweep();
        DrawContext ctx = event.drawContext;
        int x = hx.get(), y = hy.get();
        int w = 260;
        int rows = 1 + owed.size() + (owed.isEmpty() ? 0 : 1) + log.size() + bets.size();
        ctx.fill(x, y, x + w, y + 6 + rows * 11, 0xB0151520);
        ctx.drawTextWithShadow(mc.textRenderer, "Gamble games - " + game.get(), x + 4, y + 4, 0xFFFFFFFF);
        int ty = y + 15;
        for (Bet b : bets) {
            ctx.drawTextWithShadow(mc.textRenderer, "waiting: " + b.player + " " + Money.pretty(b.amount), x + 4, ty, 0xFFB8B8C8);
            ty += 11;
        }
        if (!owed.isEmpty()) {
            ctx.drawTextWithShadow(mc.textRenderer, "You owe (pay-key sends the first):", x + 4, ty, 0xFFFFC857);
            ty += 11;
            for (Owed o : owed) {
                ctx.drawTextWithShadow(mc.textRenderer, (o.refund ? "refund " : "prize  ") + o.player + "  " + Money.pretty(o.amount), x + 4, ty, o.refund ? 0xFFFFC857 : 0xFFFF5C5C);
                ty += 11;
            }
        }
        for (Line l : log) {
            ctx.drawTextWithShadow(mc.textRenderer, l.text, x + 4, ty, l.color);
            ty += 11;
        }
    }
}
