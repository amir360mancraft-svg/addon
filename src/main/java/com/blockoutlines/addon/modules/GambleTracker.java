package com.blockoutlines.addon.modules;

import com.blockoutlines.addon.BlockOutlinesAddon;
import com.blockoutlines.addon.gui.GambleScreen;
import com.blockoutlines.addon.util.Money;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.KeybindSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.orbit.EventHandler;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keeps the books of money that moves by chat ("X paid you $5K" / "You paid X $5K"): how much came in, how much went
 * out, the net result, streaks, who you play with, and a list of sessions. Open the window with the key, or look at the
 * small bar on the screen. Everything is saved in .minecraft/better-render/gamble.json.
 */
public class GambleTracker extends Module {
    /** One payment. in = money you received, otherwise money you paid. */
    public static final class Pay {
        public long ms;
        public String player;
        public long amount;
        public boolean in;
    }

    /** A stretch of play. end = 0 while it is still running. */
    public static final class Session {
        public long id;
        public String label;
        public long start, end;
    }

    private static final class Data {
        List<Pay> pays = new ArrayList<>();
        List<Session> sessions = new ArrayList<>();
        long nextId = 1;
    }

    /** The numbers of a set of payments. */
    public static final class Stats {
        public long in, out;
        public int inCount, outCount;
        public int maxInRun, maxOutRun;
        public int curRun;           // length of the run you are in now
        public boolean curRunIn;     // true = the run is payments received
        public final Map<String, long[]> players = new HashMap<>();   // name -> {in, out, count}

        public long net() {
            return in - out;
        }
    }

    private static final Pattern INCOMING = Pattern.compile("(?i)(\\w{1,16})\\s+paid\\s+you\\s+\\$?\\s*([0-9][0-9,.]*\\s*[kmbt]?)(?![a-z])");
    private static final Pattern OUTGOING = Pattern.compile("(?i)\\byou\\s+paid\\s+(\\w{1,16})\\s+\\$?\\s*([0-9][0-9,.]*\\s*[kmbt]?)(?![a-z])");
    private static final int CAP = 5000;

    private final SettingGroup sg = settings.getDefaultGroup();
    private final SettingGroup sgHud = settings.createGroup("Bar on screen");

    private final Setting<Keybind> openKey = sg.add(new KeybindSetting.Builder().name("open-key")
        .description("Opens the tracker window.").defaultValue(Keybind.none()).action(this::openWindow).build());
    private final Setting<Boolean> tracking = sg.add(new BoolSetting.Builder().name("tracking")
        .description("Write down the payments you see in chat.").defaultValue(true).build());
    private final Setting<Boolean> autoSession = sg.add(new BoolSetting.Builder().name("auto-session")
        .description("Starts a session by itself with the first payment.").defaultValue(true).build());
    private final Setting<Integer> warnStreak = sg.add(new IntSetting.Builder().name("streak-alert")
        .description("Shows an alert when this many payments in a row go the same way (0 = off).").defaultValue(3).range(0, 20).sliderRange(0, 10).build());

    private final Setting<Boolean> bar = sgHud.add(new BoolSetting.Builder().name("show-bar").defaultValue(true).build());
    private final Setting<Integer> barX = sgHud.add(new IntSetting.Builder().name("x").defaultValue(6).range(0, 3000).sliderRange(0, 800).build());
    private final Setting<Integer> barY = sgHud.add(new IntSetting.Builder().name("y").defaultValue(120).range(0, 3000).sliderRange(0, 600).build());
    private final Setting<Integer> barScale = sgHud.add(new IntSetting.Builder().name("scale").defaultValue(100).range(50, 250).sliderRange(70, 160).build());

    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private Data data = new Data();
    private boolean loaded;
    private String lastKey = "";
    private long lastKeyMs;
    private String toast = "";
    private boolean toastGood;
    private long toastUntil;

    public GambleTracker() {
        super(BlockOutlinesAddon.CATEGORY, "gamble-tracker", "Keeps the books of money paid by chat: in, out, net, streaks, players and sessions.");
    }

    // ------------------------------------------------------------------ data

    private File file() {
        return new File(mc.runDirectory, "better-render/gamble.json");
    }

    private void load() {
        if (loaded) return;
        loaded = true;
        try {
            File f = file();
            if (f.isFile()) {
                Data d = gson.fromJson(Files.readString(f.toPath(), StandardCharsets.UTF_8), Data.class);
                if (d != null) {
                    if (d.pays == null) d.pays = new ArrayList<>();
                    if (d.sessions == null) d.sessions = new ArrayList<>();
                    data = d;
                }
            }
        } catch (Exception ignored) {
        }
    }

    public void save() {
        try {
            File f = file();
            File dir = f.getParentFile();
            if (dir != null) dir.mkdirs();
            Files.writeString(f.toPath(), gson.toJson(data), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onActivate() {
        load();
    }

    public List<Pay> pays() {
        load();
        return data.pays;
    }

    public List<Session> sessions() {
        load();
        return data.sessions;
    }

    public Session openSession() {
        for (Session s : sessions()) if (s.end == 0) return s;
        return null;
    }

    public Session startSession() {
        endSession();
        Session s = new Session();
        s.id = data.nextId++;
        s.label = "Session " + s.id;
        s.start = System.currentTimeMillis();
        data.sessions.add(s);
        save();
        return s;
    }

    public void endSession() {
        Session s = openSession();
        if (s != null) {
            s.end = System.currentTimeMillis();
            save();
        }
    }

    public void deleteSession(long id) {
        data.sessions.removeIf(s -> s.id == id);
        save();
    }

    public void clearAll() {
        data = new Data();
        save();
    }

    public boolean isTracking() {
        return tracking.get();
    }

    public void setTracking(boolean on) {
        tracking.set(on);
    }

    /** Numbers for the payments between two moments (end = 0 means "until now"). */
    public Stats stats(long from, long to) {
        Stats st = new Stats();
        long end = to <= 0 ? Long.MAX_VALUE : to;
        int run = 0;
        boolean runIn = false;
        for (Pay p : pays()) {
            if (p.ms < from || p.ms > end) continue;
            if (p.in) {
                st.in += p.amount;
                st.inCount++;
            } else {
                st.out += p.amount;
                st.outCount++;
            }
            long[] pl = st.players.computeIfAbsent(p.player, k -> new long[3]);
            pl[p.in ? 0 : 1] += p.amount;
            pl[2]++;
            if (run > 0 && runIn == p.in) run++;
            else {
                run = 1;
                runIn = p.in;
            }
            if (p.in) st.maxInRun = Math.max(st.maxInRun, run);
            else st.maxOutRun = Math.max(st.maxOutRun, run);
        }
        st.curRun = run;
        st.curRunIn = runIn;
        return st;
    }

    // ------------------------------------------------------------------ chat

    private void record(boolean in, String player, long amount) {
        if (amount <= 0) return;
        long now = System.currentTimeMillis();
        String key = (in ? "i" : "o") + player.toLowerCase() + amount;
        if (key.equals(lastKey) && now - lastKeyMs < 400) return;   // the same message twice
        lastKey = key;
        lastKeyMs = now;

        load();
        Pay p = new Pay();
        p.ms = now;
        p.player = player;
        p.amount = amount;
        p.in = in;
        data.pays.add(p);
        while (data.pays.size() > CAP) data.pays.remove(0);
        if (autoSession.get() && openSession() == null) startSession();
        save();

        int warn = warnStreak.get();
        if (warn > 0) {
            Stats st = stats(openSession() != null ? openSession().start : 0, 0);
            if (st.curRun >= warn) {
                toast = st.curRun + (st.curRunIn ? " payments IN in a row" : " payments OUT in a row");
                toastGood = st.curRunIn;
                toastUntil = now + 5000;
            }
        }
    }

    @EventHandler
    private void onMessage(ReceiveMessageEvent event) {
        if (!tracking.get()) return;
        String text = event.getMessage().getString();
        if (text == null || text.length() > 300) return;
        Matcher m = OUTGOING.matcher(text);
        if (m.find()) {
            record(false, m.group(1), Money.parse(m.group(2)));
            return;
        }
        m = INCOMING.matcher(text);
        if (m.find()) {
            String who = m.group(1);
            if (who.equalsIgnoreCase("you")) return;
            record(true, who, Money.parse(m.group(2)));
        }
    }

    private void openWindow() {
        if (mc.player == null) return;
        mc.execute(() -> mc.setScreen(new GambleScreen(this)));
    }

    // ------------------------------------------------------------------ the bar on screen

    @EventHandler
    private void onRender2D(Render2DEvent event) {
        if (!bar.get() || mc.getWindow() == null || mc.options.hudHidden) return;
        Session s = openSession();
        Stats st = stats(s != null ? s.start : System.currentTimeMillis(), 0);
        long now = System.currentTimeMillis();

        float sc = barScale.get() / 100f;
        int x = barX.get(), y = barY.get();
        int w = 128, h = 44;
        var ctx = event.drawContext;
        ctx.getMatrices().pushMatrix();
        ctx.getMatrices().translate((float) x, (float) y);
        ctx.getMatrices().scale(sc, sc);

        int accent = st.net() > 0 ? 0xFF3DDC84 : (st.net() < 0 ? 0xFFFF5C5C : 0xFFAAAAAA);
        ctx.fill(0, 0, w, h, 0xB0101018);
        ctx.fill(0, 0, 3, h, accent);
        ctx.drawTextWithShadow(mc.textRenderer, tracking.get() ? "Gamble tracker" : "Gamble tracker (paused)", 8, 4, 0xFFB8B8C8);
        ctx.drawTextWithShadow(mc.textRenderer, Money.signed(st.net()), 8, 15, accent);
        ctx.drawTextWithShadow(mc.textRenderer, "in " + Money.pretty(st.in) + "  out " + Money.pretty(st.out), 8, 27, 0xFFDDDDEE);

        if (now < toastUntil) {
            int tw = mc.textRenderer.getWidth(toast) + 12;
            ctx.fill(0, h + 3, tw, h + 17, 0xC0101018);
            ctx.fill(0, h + 3, 3, h + 17, toastGood ? 0xFF3DDC84 : 0xFFFF5C5C);
            ctx.drawTextWithShadow(mc.textRenderer, toast, 8, h + 6, toastGood ? 0xFF3DDC84 : 0xFFFF5C5C);
        }
        ctx.getMatrices().popMatrix();
    }
}
