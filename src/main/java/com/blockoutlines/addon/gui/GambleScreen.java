package com.blockoutlines.addon.gui;

import com.blockoutlines.addon.modules.GambleTracker;
import com.blockoutlines.addon.util.Money;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** The window of the Gamble Tracker: overview, sessions, players and the list of payments. */
public class GambleScreen extends Screen {
    private static final int W = 520, H = 310;
    private static final int GOOD = 0xFF3DDC84, BAD = 0xFFFF5C5C, TEXT = 0xFFFFFFFF, MUTED = 0xFFB8B8C8, PANEL = 0xFF23232F, PANEL2 = 0xFF2C2C3A;
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("dd MMM HH:mm").withZone(ZoneId.systemDefault());

    private final GambleTracker tracker;
    private int tab;
    private boolean allTime;
    private int scroll;
    private long selectedSession = -1;
    private boolean confirmReset;

    private int left, top;
    private ButtonWidget sessionBtn, trackBtn, scopeBtn, resetBtn;

    public GambleScreen(GambleTracker tracker) {
        super(Text.literal("Gamble tracker"));
        this.tracker = tracker;
    }

    @Override
    protected void init() {
        left = (width - W) / 2;
        top = Math.max(6, (height - H) / 2);

        String[] names = {"Overview", "Sessions", "Players", "Payments"};
        for (int i = 0; i < names.length; i++) {
            final int t = i;
            addDrawableChild(ButtonWidget.builder(Text.literal(names[i]), b -> {
                tab = t;
                scroll = 0;
            }).dimensions(left + 8 + i * 84, top + 8, 80, 18).build());
        }
        scopeBtn = addDrawableChild(ButtonWidget.builder(Text.literal(""), b -> {
            allTime = !allTime;
            scroll = 0;
            refresh();
        }).dimensions(left + W - 150, top + 8, 142, 18).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("▲"), b -> scroll = Math.max(0, scroll - 3)).dimensions(left + W - 26, top + 38, 18, 18).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("▼"), b -> scroll += 3).dimensions(left + W - 26, top + 58, 18, 18).build());

        int by = top + H - 26;
        sessionBtn = addDrawableChild(ButtonWidget.builder(Text.literal(""), b -> {
            if (tracker.openSession() != null) tracker.endSession();
            else tracker.startSession();
            refresh();
        }).dimensions(left + 8, by, 110, 18).build());
        trackBtn = addDrawableChild(ButtonWidget.builder(Text.literal(""), b -> {
            tracker.setTracking(!tracker.isTracking());
            refresh();
        }).dimensions(left + 122, by, 110, 18).build());
        resetBtn = addDrawableChild(ButtonWidget.builder(Text.literal(""), b -> {
            if (confirmReset) {
                tracker.clearAll();
                confirmReset = false;
                selectedSession = -1;
            } else confirmReset = true;
            refresh();
        }).dimensions(left + 236, by, 130, 18).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Close"), b -> close()).dimensions(left + W - 78, by, 70, 18).build());
        refresh();
    }

    private void refresh() {
        scopeBtn.setMessage(Text.literal(allTime ? "Showing: all time" : "Showing: this session"));
        sessionBtn.setMessage(Text.literal(tracker.openSession() != null ? "End session" : "Start session"));
        trackBtn.setMessage(Text.literal(tracker.isTracking() ? "Tracking: ON" : "Tracking: OFF"));
        resetBtn.setMessage(Text.literal(confirmReset ? "Click again to erase" : "Erase all data"));
    }

    @Override
    public void close() {
        client.setScreen(null);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    private GambleTracker.Stats scopeStats() {
        if (allTime) return tracker.stats(0, 0);
        GambleTracker.Session s = tracker.openSession();
        if (s != null) return tracker.stats(s.start, 0);
        List<GambleTracker.Session> l = tracker.sessions();
        if (!l.isEmpty()) {
            GambleTracker.Session last = l.get(l.size() - 1);
            return tracker.stats(last.start, last.end);
        }
        return tracker.stats(Long.MAX_VALUE - 1, 0);
    }

    private long scopeFrom() {
        if (allTime) return 0;
        GambleTracker.Session s = tracker.openSession();
        if (s != null) return s.start;
        List<GambleTracker.Session> l = tracker.sessions();
        return l.isEmpty() ? Long.MAX_VALUE - 1 : l.get(l.size() - 1).start;
    }

    private static int netColor(long v) {
        return v > 0 ? GOOD : (v < 0 ? BAD : MUTED);
    }

    private static String duration(long ms) {
        long m = Math.max(0, ms / 60000);
        return (m / 60) + "h " + (m % 60) + "m";
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (super.mouseClicked(click, doubled)) return true;
        if (click.button() != 0) return false;
        double mx = click.x(), my = click.y();
        if (tab == 1) {
            List<GambleTracker.Session> l = sortedSessions();
            for (int i = 0; i < 12; i++) {
                int idx = i + scroll;
                if (idx >= l.size()) break;
                int y = top + 38 + i * 20;
                if (mx >= left + 8 && mx <= left + 218 && my >= y && my <= y + 18) {
                    selectedSession = l.get(idx).id;
                    return true;
                }
            }
            if (selectedSession >= 0 && mx >= left + 232 && mx <= left + 332 && my >= top + H - 56 && my <= top + H - 38) {
                tracker.deleteSession(selectedSession);
                selectedSession = -1;
                refresh();
                return true;
            }
        }
        return false;
    }

    private List<GambleTracker.Session> sortedSessions() {
        List<GambleTracker.Session> l = new ArrayList<>(tracker.sessions());
        l.sort(Comparator.comparingLong((GambleTracker.Session s) -> s.start).reversed());
        return l;
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        ctx.fill(left, top, left + W, top + H, 0xE0151520);
        ctx.fill(left, top, left + W, top + 34, 0xFF1D1D29);
        // widgets were drawn by super before the panel; draw them again on top
        for (var child : children()) {
            if (child instanceof net.minecraft.client.gui.Drawable d) d.render(ctx, mouseX, mouseY, delta);
        }
        switch (tab) {
            case 0 -> overview(ctx);
            case 1 -> sessionsTab(ctx);
            case 2 -> playersTab(ctx);
            default -> paymentsTab(ctx);
        }
    }

    private void overview(DrawContext ctx) {
        GambleTracker.Stats st = scopeStats();
        int x = left + 12, y = top + 44;

        ctx.fill(x, y, left + W - 12, y + 62, PANEL);
        ctx.drawTextWithShadow(textRenderer, "Net result", x + 10, y + 8, MUTED);
        ctx.getMatrices().pushMatrix();
        ctx.getMatrices().translate((float) (x + 10), (float) (y + 22));
        ctx.getMatrices().scale(2.4f, 2.4f);
        ctx.drawTextWithShadow(textRenderer, Money.signed(st.net()), 0, 0, netColor(st.net()));
        ctx.getMatrices().popMatrix();

        int cx = x + 230;
        ctx.drawTextWithShadow(textRenderer, "Received", cx, y + 8, MUTED);
        ctx.drawTextWithShadow(textRenderer, Money.pretty(st.in) + "  (" + st.inCount + ")", cx, y + 20, GOOD);
        ctx.drawTextWithShadow(textRenderer, "Paid", cx + 120, y + 8, MUTED);
        ctx.drawTextWithShadow(textRenderer, Money.pretty(st.out) + "  (" + st.outCount + ")", cx + 120, y + 20, BAD);
        ctx.drawTextWithShadow(textRenderer, "Longest in-a-row", cx, y + 36, MUTED);
        ctx.drawTextWithShadow(textRenderer, st.maxInRun + " in / " + st.maxOutRun + " out", cx, y + 47, TEXT);
        ctx.drawTextWithShadow(textRenderer, "Now", cx + 120, y + 36, MUTED);
        ctx.drawTextWithShadow(textRenderer, st.curRun == 0 ? "-" : st.curRun + (st.curRunIn ? " in" : " out") + " in a row", cx + 120, y + 47, st.curRun == 0 ? MUTED : (st.curRunIn ? GOOD : BAD));

        // bars: the last payments, up = received, down = paid
        int cy = y + 74;
        ctx.drawTextWithShadow(textRenderer, "Last payments (up = received, down = paid)", x, cy, MUTED);
        int top2 = cy + 12, ch = 130, mid = top2 + ch / 2;
        ctx.fill(x, top2, left + W - 12, top2 + ch, PANEL);
        ctx.fill(x, mid, left + W - 12, mid + 1, 0xFF4A4A62);
        List<GambleTracker.Pay> shown = new ArrayList<>();
        long from = scopeFrom();
        for (GambleTracker.Pay p : tracker.pays()) if (p.ms >= from) shown.add(p);
        int n = Math.min(40, shown.size());
        long max = 1;
        for (int i = shown.size() - n; i < shown.size(); i++) max = Math.max(max, shown.get(i).amount);
        int bw = 10;
        int bx = x + 8;
        for (int i = shown.size() - n; i < shown.size(); i++) {
            GambleTracker.Pay p = shown.get(i);
            int bh = (int) Math.max(2, (ch / 2 - 8) * (p.amount / (double) max));
            if (p.in) ctx.fill(bx, mid - bh, bx + bw - 2, mid, GOOD);
            else ctx.fill(bx, mid + 1, bx + bw - 2, mid + 1 + bh, BAD);
            bx += bw + 2;
        }
        if (n == 0) ctx.drawTextWithShadow(textRenderer, "No payments yet. They show up here when chat says someone paid you or you paid someone.", x + 8, mid - 4, MUTED);
    }

    private void sessionsTab(DrawContext ctx) {
        List<GambleTracker.Session> l = sortedSessions();
        ctx.drawTextWithShadow(textRenderer, "Sessions (" + l.size() + ")", left + 8, top + 28, MUTED);
        scroll = Math.min(scroll, Math.max(0, l.size() - 12));
        for (int i = 0; i < 12; i++) {
            int idx = i + scroll;
            if (idx >= l.size()) break;
            GambleTracker.Session s = l.get(idx);
            GambleTracker.Stats st = tracker.stats(s.start, s.end);
            int y = top + 38 + i * 20;
            ctx.fill(left + 8, y, left + 218, y + 18, s.id == selectedSession ? 0xFF4A4A62 : PANEL2);
            ctx.drawTextWithShadow(textRenderer, s.label + (s.end == 0 ? " (running)" : ""), left + 12, y + 5, TEXT);
            String net = Money.signed(st.net());
            ctx.drawTextWithShadow(textRenderer, net, left + 214 - textRenderer.getWidth(net), y + 5, netColor(st.net()));
        }
        GambleTracker.Session sel = null;
        for (GambleTracker.Session s : l) if (s.id == selectedSession) sel = s;
        int rx = left + 232;
        if (sel == null) {
            ctx.drawTextWithShadow(textRenderer, "Click a session to see its details.", rx, top + 44, MUTED);
            return;
        }
        GambleTracker.Stats st = tracker.stats(sel.start, sel.end);
        long endMs = sel.end == 0 ? System.currentTimeMillis() : sel.end;
        ctx.drawTextWithShadow(textRenderer, sel.label, rx, top + 38, TEXT);
        ctx.drawTextWithShadow(textRenderer, FMT.format(Instant.ofEpochMilli(sel.start)) + "   " + duration(endMs - sel.start), rx, top + 50, MUTED);
        ctx.drawTextWithShadow(textRenderer, "Net " + Money.signed(st.net()), rx, top + 66, netColor(st.net()));
        ctx.drawTextWithShadow(textRenderer, "In " + Money.pretty(st.in) + " (" + st.inCount + ")   Out " + Money.pretty(st.out) + " (" + st.outCount + ")", rx, top + 78, TEXT);
        ctx.drawTextWithShadow(textRenderer, "Longest: " + st.maxInRun + " in / " + st.maxOutRun + " out in a row", rx, top + 90, MUTED);
        ctx.drawTextWithShadow(textRenderer, "Players", rx, top + 108, MUTED);
        int row = 0;
        for (Map.Entry<String, long[]> e : topPlayers(st, 6)) {
            long net = e.getValue()[0] - e.getValue()[1];
            ctx.drawTextWithShadow(textRenderer, e.getKey(), rx, top + 120 + row * 12, TEXT);
            String s = Money.signed(net);
            ctx.drawTextWithShadow(textRenderer, s, left + W - 40 - textRenderer.getWidth(s), top + 120 + row * 12, netColor(net));
            row++;
        }
        ctx.fill(rx, top + H - 56, rx + 100, top + H - 38, 0xFF6A2C2C);
        ctx.drawCenteredTextWithShadow(textRenderer, "Delete session", rx + 50, top + H - 51, TEXT);
    }

    private static List<Map.Entry<String, long[]>> topPlayers(GambleTracker.Stats st, int limit) {
        List<Map.Entry<String, long[]>> l = new ArrayList<>(st.players.entrySet());
        l.sort((a, b) -> Long.compare(Math.abs(b.getValue()[0] - b.getValue()[1]), Math.abs(a.getValue()[0] - a.getValue()[1])));
        return l.size() > limit ? l.subList(0, limit) : l;
    }

    private void playersTab(DrawContext ctx) {
        GambleTracker.Stats st = scopeStats();
        List<Map.Entry<String, long[]>> l = topPlayers(st, 500);
        scroll = Math.min(scroll, Math.max(0, l.size() - 12));
        int x = left + 12, y = top + 38;
        ctx.drawTextWithShadow(textRenderer, "Player", x, y, MUTED);
        ctx.drawTextWithShadow(textRenderer, "Received", x + 170, y, MUTED);
        ctx.drawTextWithShadow(textRenderer, "Paid", x + 250, y, MUTED);
        ctx.drawTextWithShadow(textRenderer, "Net", x + 330, y, MUTED);
        ctx.drawTextWithShadow(textRenderer, "Times", x + 410, y, MUTED);
        for (int i = 0; i < 12; i++) {
            int idx = i + scroll;
            if (idx >= l.size()) break;
            Map.Entry<String, long[]> e = l.get(idx);
            long[] v = e.getValue();
            int ry = y + 12 + i * 20;
            ctx.fill(x - 4, ry, left + W - 34, ry + 18, i % 2 == 0 ? PANEL : PANEL2);
            ctx.drawTextWithShadow(textRenderer, e.getKey(), x, ry + 5, TEXT);
            ctx.drawTextWithShadow(textRenderer, Money.pretty(v[0]), x + 170, ry + 5, GOOD);
            ctx.drawTextWithShadow(textRenderer, Money.pretty(v[1]), x + 250, ry + 5, BAD);
            ctx.drawTextWithShadow(textRenderer, Money.signed(v[0] - v[1]), x + 330, ry + 5, netColor(v[0] - v[1]));
            ctx.drawTextWithShadow(textRenderer, String.valueOf(v[2]), x + 410, ry + 5, TEXT);
        }
        if (l.isEmpty()) ctx.drawTextWithShadow(textRenderer, "Nobody yet.", x, y + 14, MUTED);
    }

    private void paymentsTab(DrawContext ctx) {
        List<GambleTracker.Pay> all = new ArrayList<>();
        long from = scopeFrom();
        for (GambleTracker.Pay p : tracker.pays()) if (p.ms >= from) all.add(p);
        java.util.Collections.reverse(all);   // newest first
        scroll = Math.min(scroll, Math.max(0, all.size() - 12));
        int x = left + 12, y = top + 38;
        ctx.drawTextWithShadow(textRenderer, "When", x, y, MUTED);
        ctx.drawTextWithShadow(textRenderer, "Player", x + 110, y, MUTED);
        ctx.drawTextWithShadow(textRenderer, "Amount", x + 300, y, MUTED);
        for (int i = 0; i < 12; i++) {
            int idx = i + scroll;
            if (idx >= all.size()) break;
            GambleTracker.Pay p = all.get(idx);
            int ry = y + 12 + i * 20;
            ctx.fill(x - 4, ry, left + W - 34, ry + 18, i % 2 == 0 ? PANEL : PANEL2);
            ctx.drawTextWithShadow(textRenderer, FMT.format(Instant.ofEpochMilli(p.ms)), x, ry + 5, MUTED);
            ctx.drawTextWithShadow(textRenderer, (p.in ? "paid you: " : "you paid: ") + p.player, x + 110, ry + 5, TEXT);
            String a = (p.in ? "+" : "-") + Money.pretty(p.amount);
            ctx.drawTextWithShadow(textRenderer, a, x + 300, ry + 5, p.in ? GOOD : BAD);
        }
        if (all.isEmpty()) ctx.drawTextWithShadow(textRenderer, "No payments yet.", x, y + 14, MUTED);
    }
}
