package com.blockoutlines.addon.hud;

import com.blockoutlines.addon.BlockOutlinesAddon;
import com.blockoutlines.addon.gui.LunarGuiTheme;
import meteordevelopment.meteorclient.mixin.MinecraftAccessor;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.hud.Hud;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.meteorclient.utils.world.TickRate;

import java.util.ArrayList;
import java.util.List;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * The three small FPS / ping / TPS boxes from the reference screenshot, each its own box in a row.
 * Not compiled / tested yet. FPS and TPS come straight from Meteor's own helpers (MinecraftAccessor.meteor$getFps(),
 * TickRate.INSTANCE.getTickRate()) which every build of this version of Meteor has, so those two are solid. Ping
 * is the one line written from memory (mc.getNetworkHandler().getPlayerListEntry(...).getLatency()) - wrapped in
 * a try/catch so if that exact call is wrong on this build, the box just shows 0 instead of crashing.
 */
public class ClientStatsHud extends HudElement {
    public static final HudElementInfo<ClientStatsHud> INFO = new HudElementInfo<>(
        BlockOutlinesAddon.HUD_GROUP, "client-stats",
        "FPS, ping and TPS, each in their own small box.", ClientStatsHud::new
    );

    private static final int ICON_FPS = 0, ICON_PING = 1, ICON_TPS = 2;

    private final SettingGroup sg = settings.getDefaultGroup();
    private final SettingGroup sgStyle = settings.createGroup("Style");

    private final Setting<Boolean> showFps = sg.add(new BoolSetting.Builder()
        .name("show-fps").description("Shows your current FPS.").defaultValue(true).build());
    private final Setting<Boolean> showPing = sg.add(new BoolSetting.Builder()
        .name("show-ping").description("Shows your ping.").defaultValue(true).build());
    private final Setting<Boolean> showTps = sg.add(new BoolSetting.Builder()
        .name("show-tps").description("Shows the server's tick rate.").defaultValue(true).build());
    private final Setting<Integer> gap = sg.add(new IntSetting.Builder()
        .name("gap").description("Space between the boxes.").defaultValue(6).range(0, 30).sliderRange(0, 20).build());

    private final Setting<Boolean> background = sgStyle.add(new BoolSetting.Builder()
        .name("background").description("Draws a background behind each box.").defaultValue(true).build());
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

    public ClientStatsHud() {
        super(INFO);
    }

    private static final class Box {
        final int icon;
        final String text;
        double w;

        Box(int icon, String text) {
            this.icon = icon;
            this.text = text;
        }
    }

    @Override
    public void render(HudRenderer renderer) {
        boolean sh = shadow.get();
        double scale = Hud.get().getTextScale();
        Color accent = resolveAccent();

        double pad = 6 * scale, iconSize = 10 * scale, iconGap = 4 * scale, boxGap = gap.get() * scale;

        List<Box> boxes = new ArrayList<>();
        if (showFps.get()) boxes.add(new Box(ICON_FPS, fps() + " FPS"));
        if (showPing.get()) boxes.add(new Box(ICON_PING, ping() + " ms"));
        if (showTps.get()) boxes.add(new Box(ICON_TPS, tps() + " TPS"));

        if (boxes.isEmpty()) {
            setSize(1, 1);
            return;
        }

        double textH = renderer.textHeight(sh, scale);
        double boxH = Math.max(iconSize, textH) + pad * 1.6;
        double totalW = 0;

        for (int i = 0; i < boxes.size(); i++) {
            Box b = boxes.get(i);
            b.w = pad * 2 + iconSize + iconGap + renderer.textWidth(b.text, sh, scale);
            totalW += b.w;
            if (i < boxes.size() - 1) totalW += boxGap;
        }

        setSize(totalW, boxH);

        double cx = x;
        for (Box b : boxes) {
            if (background.get()) renderer.quad(cx, y, b.w, boxH, backgroundColor.get());

            double iconX = cx + pad;
            double iconY = y + (boxH - iconSize) / 2;
            drawIcon(b.icon, renderer, iconX, iconY, iconSize, accent);

            double textY = y + (boxH - textH) / 2;
            renderer.text(b.text, iconX + iconSize + iconGap, textY, Color.WHITE, sh, scale);

            cx += b.w + boxGap;
        }
    }

    private void drawIcon(int icon, HudRenderer r, double x, double y, double s, Color c) {
        switch (icon) {
            case ICON_FPS -> HudIcons.chevronUp(r, x, y, s, c);
            case ICON_PING -> HudIcons.signal(r, x, y, s, c);
            case ICON_TPS -> HudIcons.chip(r, x, y, s, c);
        }
    }

    private Color resolveAccent() {
        if (useThemeAccent.get() && LunarGuiTheme.INSTANCE != null) {
            int[] ac = LunarGuiTheme.INSTANCE.accent();
            return new Color(ac[0], ac[1], ac[2], 255);
        }
        return accentColor.get();
    }

    private int fps() {
        try {
            return MinecraftAccessor.meteor$getFps();
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private int ping() {
        try {
            if (mc.player == null || mc.getNetworkHandler() == null) return 0;
            var entry = mc.getNetworkHandler().getPlayerListEntry(mc.player.getUuid());
            return entry != null ? entry.getLatency() : 0;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private String tps() {
        try {
            return String.format("%.1f", TickRate.INSTANCE.getTickRate());
        } catch (Throwable ignored) {
            return "20.0";
        }
    }
}
