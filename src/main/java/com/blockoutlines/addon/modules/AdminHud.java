package com.blockoutlines.addon.modules;

import com.blockoutlines.addon.BlockOutlinesAddon;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringListSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import com.blockoutlines.addon.util.HudHook;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.scoreboard.Team;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.world.GameMode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A small panel that lists the staff members who are online (found in the tab list), with a rank tag, how far away they
 * are when they are near you, and an alert when one joins or leaves.
 *
 * Staff are recognised by rank words in the name decoration of the tab list (prefix / suffix such as [Admin], Mod | ...)
 * and by a list of names you add yourself. Nothing is sent to the server.
 */
public class AdminHud extends Module {
    public enum Corner { TopLeft, TopRight, BottomLeft, BottomRight }

    private final SettingGroup sgFind = settings.getDefaultGroup();
    private final SettingGroup sgAlert = settings.createGroup("Alerts");
    private final SettingGroup sgPanel = settings.createGroup("Panel");

    // -------- who counts as staff
    private final Setting<List<String>> keywords = sgFind.add(new StringListSetting.Builder()
        .name("rank-words").description("A player counts as staff when one of these words is in the decoration of the name in the tab list (like the [Admin] in front of it).")
        .defaultValue(List.of("owner", "admin", "administrator", "mod", "moderator", "staff", "helper", "support", "manager", "dev", "developer", "builder"))
        .build());
    private final Setting<List<String>> extra = sgFind.add(new StringListSetting.Builder()
        .name("extra-names").description("Players that are always shown as staff (exact name). Comes filled with a list of known staff names; add or remove names as you like.")
        .defaultValue(new ArrayList<>(List.of("0Gsummer", "Fluffymaster07", "Frwost", "Itszdeath", "Munkerlich",
            "PastaGamer08", "Showered", "W1zoX_", "archivePedro", "bautiedgar", "drdonutt"))).build());
    private final Setting<List<String>> ignore = sgFind.add(new StringListSetting.Builder()
        .name("ignore-names").description("Players that are never shown, even with a rank word.")
        .defaultValue(new ArrayList<>()).build());
    private final Setting<Boolean> matchName = sgFind.add(new BoolSetting.Builder()
        .name("match-username").description("Also look for the rank words in the player's own name (more mistakes: a player called ModFan).")
        .defaultValue(false).build());
    private final Setting<Boolean> spectators = sgFind.add(new BoolSetting.Builder()
        .name("spectators-count").description("Also list players in spectator mode (on many servers only staff can do that).")
        .defaultValue(false).build());

    // -------- alerts
    private final Setting<Boolean> alertJoin = sgAlert.add(new BoolSetting.Builder()
        .name("alert-join").description("Tell me in chat when a staff member joins.").defaultValue(true).build());
    private final Setting<Boolean> alertLeave = sgAlert.add(new BoolSetting.Builder()
        .name("alert-leave").description("Tell me in chat when a staff member leaves.").defaultValue(false).build());
    private final Setting<Boolean> useIsland = sgAlert.add(new BoolSetting.Builder()
        .name("use-dynamic-island").description("Show the alerts on the Dynamic Island (turn that module on) instead of in chat.")
        .defaultValue(true).build());
    private final Setting<Boolean> sound = sgAlert.add(new BoolSetting.Builder()
        .name("sound").description("Play a short sound when a staff member joins.").defaultValue(true).build());

    // -------- panel
    private final Setting<Corner> corner = sgPanel.add(new EnumSetting.Builder<Corner>()
        .name("corner").description("Where the panel sits.").defaultValue(Corner.TopLeft).build());
    private final Setting<Integer> offX = sgPanel.add(new IntSetting.Builder()
        .name("offset-x").description("Distance from the side of the screen.").defaultValue(6).range(0, 600).sliderRange(0, 300).build());
    private final Setting<Integer> offY = sgPanel.add(new IntSetting.Builder()
        .name("offset-y").description("Distance from the top or bottom of the screen.").defaultValue(40).range(0, 600).sliderRange(0, 300).build());
    private final Setting<Double> scale = sgPanel.add(new DoubleSetting.Builder()
        .name("scale").description("Size of the panel.").defaultValue(1.0).range(0.5, 3.0).sliderRange(0.5, 2.0).build());
    private final Setting<Boolean> showEmpty = sgPanel.add(new BoolSetting.Builder()
        .name("show-when-empty").description("Keep the panel on screen (saying nobody is online) when there is no staff.")
        .defaultValue(true).build());
    private final Setting<Boolean> showDistance = sgPanel.add(new BoolSetting.Builder()
        .name("show-distance").description("Show how far away a staff member is when you can see them.").defaultValue(true).build());
    private final Setting<Integer> maxRows = sgPanel.add(new IntSetting.Builder()
        .name("max-rows").description("Most players listed.").defaultValue(10).range(1, 40).sliderRange(1, 20).build());
    private final Setting<Integer> background = sgPanel.add(new IntSetting.Builder()
        .name("background").description("How dark the panel background is (percent).").defaultValue(55).range(0, 100).sliderRange(0, 100).build());
    private final Setting<SettingColor> accent = sgPanel.add(new ColorSetting.Builder()
        .name("accent-color").description("Color of the rank tags and the side bar.")
        .defaultValue(new SettingColor(255, 80, 80, 255)).build());

    private static final class Row {
        String name, tag, extra;
        boolean spectator;
        double dist = -1;
    }

    private final List<Row> rows = new ArrayList<>();
    private final Set<String> known = new HashSet<>();
    private boolean first = true;
    private int timer;
    private List<Pattern> patterns = new ArrayList<>();
    private List<String> patternKey = null;

    public AdminHud() {
        super(BlockOutlinesAddon.COMBAT, "better-admin-hud",
            "A panel listing the staff members who are online, with an alert when one joins or leaves.");
    }

    @Override
    public void onActivate() {
        rows.clear();
        known.clear();
        first = true;
        timer = 0;
    }

    @Override
    public void onDeactivate() {
        rows.clear();
        known.clear();
    }

    // ------------------------------------------------------------------ finding staff

    private List<Pattern> patterns() {
        List<String> words = keywords.get();
        if (patternKey == null || !patternKey.equals(words)) {
            patternKey = new ArrayList<>(words);
            patterns = new ArrayList<>();
            for (String w : words) {
                String s = w.trim();
                if (s.isEmpty()) continue;
                // the word must stand alone: not inside a longer word (so "mod" does not match "modern")
                patterns.add(Pattern.compile("(?i)(?<![a-z])" + Pattern.quote(s) + "(?![a-z])"));
            }
        }
        return patterns;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null || mc.player == null || mc.getNetworkHandler() == null) {
            rows.clear();
            known.clear();
            first = true;
            return;
        }
        if (timer-- > 0) return;
        timer = 10; // twice a second is plenty

        List<Row> found = new ArrayList<>();
        Set<String> names = new HashSet<>();
        Set<String> ex = lower(extra.get()), ig = lower(ignore.get());
        List<Pattern> pats = patterns();

        for (PlayerListEntry e : mc.getNetworkHandler().getPlayerList()) {
            String name = e.getProfile().name();
            if (name == null || name.equals(mc.player.getName().getString())) continue;
            String lname = name.toLowerCase(Locale.ROOT);
            if (ig.contains(lname)) continue;

            String tag = null;
            if (ex.contains(lname)) tag = "STAFF";

            if (tag == null) {
                // the whole line as the tab list shows it, with team prefix / suffix
                Text shown = e.getDisplayName() != null ? e.getDisplayName() : Text.literal(name);
                String line = Team.decorateName(e.getScoreboardTeam(), shown).getString();
                if (!matchName.get()) line = line.replace(name, " ");
                for (Pattern p : pats) {
                    var m = p.matcher(line);
                    if (m.find()) { tag = m.group().toUpperCase(Locale.ROOT); break; }
                }
            }

            boolean spec = e.getGameMode() == GameMode.SPECTATOR;
            if (tag == null && spec && spectators.get()) tag = "SPEC";
            if (tag == null) continue;

            Row r = new Row();
            r.name = name;
            r.tag = tag;
            r.spectator = spec;
            for (PlayerEntity pl : mc.world.getPlayers()) {
                if (pl.getUuid().equals(e.getProfile().id())) { r.dist = Math.sqrt(pl.squaredDistanceTo(mc.player)); break; }
            }
            found.add(r);
            names.add(name);
        }

        // joins and leaves
        if (first) {
            first = false;
            if (!names.isEmpty() && alertJoin.get())
                alert("Staff online (" + names.size() + ")", String.join(", ", names), argb(accent.get(), 0) & 0xFFFFFF, names.size() + " staff online: " + String.join(", ", names));
        } else {
            for (String n : names) {
                if (!known.contains(n)) {
                    if (alertJoin.get()) alert("Staff joined", n, argb(accent.get(), 0) & 0xFFFFFF, "Staff joined: " + n);
                    if (sound.get()) ping();
                }
            }
            if (alertLeave.get()) {
                for (String n : known) if (!names.contains(n)) alert("Staff left", n, 0x7CFF8A, "Staff left: " + n);
            }
        }
        known.clear();
        known.addAll(names);

        rows.clear();
        rows.addAll(found);
        rows.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
    }

    private void alert(String title, String sub, int rgb, String chat) {
        if (useIsland.get() && DynamicIsland.push(title, sub, rgb)) return;
        info(chat);
    }

    private static Set<String> lower(List<String> in) {
        Set<String> out = new HashSet<>();
        for (String s : in) out.add(s.trim().toLowerCase(Locale.ROOT));
        return out;
    }

    private void ping() {
        try {
            mc.world.playSoundClient(mc.player.getX(), mc.player.getY(), mc.player.getZ(),
                SoundEvent.of(Identifier.ofVanilla("block.note_block.pling")), SoundCategory.PLAYERS, 0.8f, 1.4f, false);
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------ panel

    @EventHandler
    private void onRender2D(Render2DEvent event) {
        if (mc.player == null || mc.world == null) return;
        if (rows.isEmpty() && !showEmpty.get() && !HudHook.editing()) return;

        DrawContext ctx = event.drawContext;
        var tr = mc.textRenderer;
        int shown = Math.min(rows.size(), maxRows.get());

        String title = rows.isEmpty() ? "Staff: nobody online" : "Staff online (" + rows.size() + ")";
        int accentC = argb(accent.get(), 255);
        int pad = 4, lineH = 11;

        // widths
        int w = tr.getWidth(title);
        List<String> left = new ArrayList<>(), tags = new ArrayList<>(), right = new ArrayList<>();
        for (int i = 0; i < shown; i++) {
            Row r = rows.get(i);
            left.add(r.name);
            tags.add(r.tag);
            String extraText = r.spectator ? "spectating" : "";
            if (showDistance.get() && r.dist >= 0) extraText += (extraText.isEmpty() ? "" : " ") + (int) Math.round(r.dist) + "m";
            right.add(extraText);
        }
        int tagW = 0, nameW = 0, rightW = 0;
        for (int i = 0; i < shown; i++) {
            tagW = Math.max(tagW, tr.getWidth(tags.get(i)));
            nameW = Math.max(nameW, tr.getWidth(left.get(i)));
            rightW = Math.max(rightW, tr.getWidth(right.get(i)));
        }
        int rowW = tagW + 6 + nameW + (rightW > 0 ? 8 + rightW : 0);
        w = Math.max(w, rowW) + pad * 2 + 3;
        int h = pad * 2 + lineH + (shown > 0 ? 3 + shown * lineH : 0) + (rows.size() > shown ? lineH : 0);

        float s = scale.get().floatValue();
        int sw = mc.getWindow().getScaledWidth(), sh = mc.getWindow().getScaledHeight();
        float pw = w * s, ph = h * s;
        float x = (corner.get() == Corner.TopLeft || corner.get() == Corner.BottomLeft) ? offX.get() : sw - offX.get() - pw;
        float y = (corner.get() == Corner.TopLeft || corner.get() == Corner.TopRight) ? offY.get() : sh - offY.get() - ph;

        HudHook.mark("Admin HUD", Math.round(x), Math.round(y), Math.round(pw), Math.round(ph));
        handleDrag(x, y, pw, ph, sw, sh);

        ctx.getMatrices().pushMatrix();
        ctx.getMatrices().translate(x, y);
        ctx.getMatrices().scale(s, s);

        // background with softly cut corners
        int bg = ((int) (255 * background.get() / 100.0) << 24);
        ctx.fill(1, 0, w - 1, h, bg);
        ctx.fill(0, 1, w, h - 1, bg);
        ctx.fill(0, 1, 2, h - 1, accentC);

        int tx = pad + 3, ty = pad;
        ctx.drawText(tr, title, tx, ty, rows.isEmpty() ? 0xFF7CFF8A : 0xFFFFFFFF, true);
        ty += lineH + 3;
        for (int i = 0; i < shown; i++) {
            ctx.drawText(tr, tags.get(i), tx, ty, accentC, true);
            ctx.drawText(tr, left.get(i), tx + tagW + 6, ty, 0xFFFFFFFF, true);
            if (!right.get(i).isEmpty()) ctx.drawText(tr, right.get(i), tx + tagW + 6 + nameW + 8, ty, 0xFFAAAAAA, true);
            ty += lineH;
        }
        if (rows.size() > shown) ctx.drawText(tr, "+" + (rows.size() - shown) + " more", tx, ty, 0xFFAAAAAA, true);

        ctx.getMatrices().popMatrix();
    }

    private boolean dragging;
    private double grabX, grabY;

    /** Drag the panel (hub or chat open): moves it by changing the offsets from its corner. */
    private void handleDrag(float x, float y, float pw, float ph, int sw, int sh) {
        if (!HudHook.editing()) {
            dragging = false;
            return;
        }
        double mx = mc.mouse.getX() * sw / (double) mc.getWindow().getWidth();
        double my = mc.mouse.getY() * sh / (double) mc.getWindow().getHeight();
        boolean down = org.lwjgl.glfw.GLFW.glfwGetMouseButton(mc.getWindow().getHandle(), org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT) == org.lwjgl.glfw.GLFW.GLFW_PRESS;
        if (!down) {
            dragging = false;
            return;
        }
        if (!dragging) {
            if (mx < x || mx > x + pw || my < y || my > y + ph) return;
            dragging = true;
            grabX = mx - x;
            grabY = my - y;
        }
        double nx = Math.max(0, Math.min(sw - pw, mx - grabX));
        double ny = Math.max(0, Math.min(sh - ph, my - grabY));
        boolean left = corner.get() == Corner.TopLeft || corner.get() == Corner.BottomLeft;
        boolean top = corner.get() == Corner.TopLeft || corner.get() == Corner.TopRight;
        offX.set((int) Math.round(left ? nx : sw - pw - nx));
        offY.set((int) Math.round(top ? ny : sh - ph - ny));
    }

    private static int argb(SettingColor c, int alpha) {
        return (alpha << 24) | (c.r << 16) | (c.g << 8) | c.b;
    }
}
