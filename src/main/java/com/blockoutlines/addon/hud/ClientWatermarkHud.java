package com.blockoutlines.addon.hud;

import com.blockoutlines.addon.BlockOutlinesAddon;
import com.blockoutlines.addon.gui.LunarGuiTheme;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.hud.Hud;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * The brand bar from the reference screenshot: your client's name, your own name, the server you're on and the
 * clock, one after another in a single bar. Everything is a toggle, so you can turn any of the four off and the
 * bar just gets shorter.
 * Not compiled / tested yet - the drawing itself only uses HudRenderer.text/quad/line/triangle (the same calls
 * every other Meteor HUD element uses), the riskiest part is just the two or three vanilla calls below
 * (server address, player name) which are written from memory, not verified against this build's exact API.
 */
public class ClientWatermarkHud extends HudElement {
    public static final HudElementInfo<ClientWatermarkHud> INFO = new HudElementInfo<>(
        BlockOutlinesAddon.HUD_GROUP, "client-watermark",
        "Your client's name, your name, the server and the time, in one bar.", ClientWatermarkHud::new
    );

    private static final int ICON_BRAND = 0, ICON_PERSON = 1, ICON_SERVER = 2, ICON_CLOCK = 3;

    private final SettingGroup sg = settings.getDefaultGroup();
    private final SettingGroup sgStyle = settings.createGroup("Style");

    private final Setting<Boolean> showClientName = sg.add(new BoolSetting.Builder()
        .name("show-client-name").description("Shows your client's name.").defaultValue(true).build());
    private final Setting<String> clientName = sg.add(new StringSetting.Builder()
        .name("client-name").description("The name shown.").defaultValue("BETTER RENDER")
        .visible(showClientName::get).build());
    private final Setting<Boolean> showUsername = sg.add(new BoolSetting.Builder()
        .name("show-username").description("Shows your in-game name.").defaultValue(true).build());
    private final Setting<Boolean> showServer = sg.add(new BoolSetting.Builder()
        .name("show-server").description("Shows the server address (or Singleplayer).").defaultValue(true).build());
    private final Setting<Boolean> showTime = sg.add(new BoolSetting.Builder()
        .name("show-time").description("Shows the current real-world time.").defaultValue(true).build());
    private final Setting<Boolean> use24Hour = sg.add(new BoolSetting.Builder()
        .name("24-hour").description("Uses a 24 hour clock instead of AM/PM.").defaultValue(true)
        .visible(showTime::get).build());

    private final Setting<Boolean> background = sgStyle.add(new BoolSetting.Builder()
        .name("background").description("Draws a background behind the bar.").defaultValue(true).build());
    private final Setting<SettingColor> backgroundColor = sgStyle.add(new ColorSetting.Builder()
        .name("background-color").description("Color of the background.")
        .defaultValue(new SettingColor(18, 18, 24, 190)).visible(background::get).build());
    private final Setting<Boolean> useThemeAccent = sgStyle.add(new BoolSetting.Builder()
        .name("use-theme-accent").description("Colors the icons with the Lunar theme's accent color instead of a fixed color.")
        .defaultValue(true).build());
    private final Setting<SettingColor> accentColor = sgStyle.add(new ColorSetting.Builder()
        .name("accent-color").description("Icon color used when not following the theme.")
        .defaultValue(new SettingColor(64, 140, 255)).visible(() -> !useThemeAccent.get()).build());
    private final Setting<Boolean> shadow = sgStyle.add(new BoolSetting.Builder()
        .name("text-shadow").description("Draws a shadow behind the text.").defaultValue(true).build());

    public ClientWatermarkHud() {
        super(INFO);
    }

    private static final class Seg {
        final int icon;
        final String text;
        double w;

        Seg(int icon, String text) {
            this.icon = icon;
            this.text = text;
        }
    }

    @Override
    public void render(HudRenderer renderer) {
        boolean sh = shadow.get();
        double scale = Hud.get().getTextScale();
        Color accent = resolveAccent();

        double pad = 6 * scale, iconSize = 11 * scale, iconGap = 5 * scale, segGap = 12 * scale;

        List<Seg> segs = new ArrayList<>();
        if (showClientName.get()) segs.add(new Seg(ICON_BRAND, clientName.get()));
        if (showUsername.get()) segs.add(new Seg(ICON_PERSON, username()));
        if (showServer.get()) segs.add(new Seg(ICON_SERVER, serverAddress()));
        if (showTime.get()) segs.add(new Seg(ICON_CLOCK, timeString()));

        if (segs.isEmpty()) {
            setSize(1, 1);
            return;
        }

        double textH = renderer.textHeight(sh, scale);
        double totalW = pad * 2;
        for (int i = 0; i < segs.size(); i++) {
            Seg s = segs.get(i);
            s.w = iconSize + iconGap + renderer.textWidth(s.text, sh, scale);
            totalW += s.w;
            if (i < segs.size() - 1) totalW += segGap;
        }

        double height = Math.max(iconSize, textH) + pad * 1.6;
        setSize(totalW, height);

        if (background.get()) renderer.quad(x, y, getWidth(), getHeight(), backgroundColor.get());

        double cx = x + pad;
        double iy = y + (getHeight() - iconSize) / 2;
        double ty = y + (getHeight() - textH) / 2;

        for (int i = 0; i < segs.size(); i++) {
            Seg s = segs.get(i);
            drawIcon(s.icon, renderer, cx, iy, iconSize, accent);
            renderer.text(s.text, cx + iconSize + iconGap, ty, Color.WHITE, sh, scale);
            cx += s.w;

            if (i < segs.size() - 1) {
                renderer.line(cx + segGap / 2, y + pad * 0.5, cx + segGap / 2, y + getHeight() - pad * 0.5, new Color(255, 255, 255, 55));
                cx += segGap;
            }
        }
    }

    private void drawIcon(int icon, HudRenderer r, double x, double y, double s, Color c) {
        switch (icon) {
            case ICON_BRAND -> HudIcons.brand(r, x, y, s, c);
            case ICON_PERSON -> HudIcons.person(r, x, y, s, c);
            case ICON_SERVER -> HudIcons.server(r, x, y, s, c, Color.WHITE);
            case ICON_CLOCK -> HudIcons.clock(r, x, y, s, c);
        }
    }

    private Color resolveAccent() {
        if (useThemeAccent.get() && LunarGuiTheme.INSTANCE != null) {
            int[] ac = LunarGuiTheme.INSTANCE.accent();
            return new Color(ac[0], ac[1], ac[2], 255);
        }
        return accentColor.get();
    }

    private String username() {
        return mc.player != null ? mc.player.getName().getString() : "You";
    }

    private String serverAddress() {
        try {
            if (mc.isIntegratedServerRunning()) return "Singleplayer";
            var entry = mc.getCurrentServerEntry();
            return entry != null ? entry.address : "-";
        } catch (Throwable ignored) {
            return "-";
        }
    }

    private String timeString() {
        LocalTime t = LocalTime.now();
        if (use24Hour.get()) return String.format("%02d:%02d:%02d", t.getHour(), t.getMinute(), t.getSecond());
        return t.format(DateTimeFormatter.ofPattern("h:mm:ss a"));
    }
}
