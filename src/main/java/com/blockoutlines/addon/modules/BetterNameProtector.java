package com.blockoutlines.addon.modules;

import com.blockoutlines.addon.BlockOutlinesAddon;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringListSetting;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.text.TextCodecs;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Name protector with extras: replaces your real name everywhere on your screen, can give other players nicknames,
 * and can put a small rank icon (pink "media" video-camera character or a green star) in front of your name in the tab list and chat.
 * Everything happens only on your own screen.
 */
public class BetterNameProtector extends Module {
    public enum Icon { None, Media, Star }

    private final SettingGroup sgName = settings.getDefaultGroup();
    private final SettingGroup sgIcon = settings.createGroup("Icon");
    private final SettingGroup sgNick = settings.createGroup("Nicknames");

    private final Setting<String> name = sgName.add(new StringSetting.Builder()
        .name("name").description("What your name is shown as.").defaultValue("Protected").build());
    private final Setting<Boolean> protect = sgName.add(new BoolSetting.Builder()
        .name("replace-name").description("Replace your real name in chat, tab list, name tags, scoreboard and menus.").defaultValue(true).build());

    private final Setting<Icon> icon = sgIcon.add(new EnumSetting.Builder<Icon>()
        .name("icon").description("The little icon in front of your name. Media = pink video camera, Star = green star (both are normal game-font characters).").defaultValue(Icon.Media).build());
    private final Setting<Boolean> inTab = sgIcon.add(new BoolSetting.Builder()
        .name("in-tab-list").description("Show the icon in the player list.").defaultValue(true).visible(() -> icon.get() != Icon.None).build());
    private final Setting<Boolean> inChat = sgIcon.add(new BoolSetting.Builder()
        .name("in-chat").description("Show the icon before your name in chat messages.").defaultValue(true).visible(() -> icon.get() != Icon.None).build());

    private final Setting<List<String>> nicknames = sgNick.add(new StringListSetting.Builder()
        .name("nicknames").description("One per line: RealName=Nickname. The real name is shown as the nickname on your screen.")
        .defaultValue(new ArrayList<>()).build());

    private Text mediaIcon, starIcon;

    public BetterNameProtector() {
        super(BlockOutlinesAddon.COSMETICS, "better-name-protector", "Name protector with a rank icon (media camera / star) and nicknames for other players.");
    }

    private String realName() {
        return mc.getSession() == null ? null : mc.getSession().getUsername();
    }

    // ------------------------------------------------------------------ used by the mixins

    /** Replaces your real name (and nicknamed players) in a piece of text. */
    public String replace(String text) {
        if (text == null || text.length() < 3) return text;
        String out = text;
        String real = realName();
        if (protect.get() && real != null && !real.isEmpty() && out.contains(real)) out = out.replace(real, name.get());
        for (String line : nicknames.get()) {
            int eq = line.indexOf('=');
            if (eq <= 0 || eq >= line.length() - 1) continue;
            String from = line.substring(0, eq).trim(), to = line.substring(eq + 1).trim();
            if (!from.isEmpty() && out.contains(from)) out = out.replace(from, to);
        }
        return out;
    }

    private Text iconText() {
        Icon i = icon.get();
        if (i == Icon.None) return null;
        try {
            if (i == Icon.Media && mediaIcon == null) mediaIcon = build("\uD83D\uDCF9", "#FF00AA"); // U+1F4F9 video camera, a normal game-font character
            if (i == Icon.Star && starIcon == null) starIcon = build("\u2605", "#33EE77"); // U+2605 star, also a normal game-font character
        } catch (Throwable t) {
            return null;
        }
        return i == Icon.Media ? mediaIcon : starIcon;
    }

    /** A colored character of the normal game font (the game draws the dark shadow itself); made from JSON so it does not depend on the style API of this version. */
    private Text build(String glyph, String color) {
        JsonObject o = new JsonObject();
        o.addProperty("text", glyph + " ");
        o.addProperty("color", color);
        return TextCodecs.CODEC.parse(JsonOps.INSTANCE, o).result().orElse(null);
    }

    /** Tab list: icon in front of your own entry. */
    public Text decorateTab(Text original) {
        if (!inTab.get()) return original;
        Text ic = iconText();
        if (ic == null) return original;
        return Text.empty().append(ic).append(original);
    }

    // ------------------------------------------------------------------ chat

    @EventHandler
    private void onMessage(ReceiveMessageEvent event) {
        if (!inChat.get()) return;
        Text ic = iconText();
        String real = realName();
        if (ic == null || real == null || real.isEmpty()) return;
        Text msg = event.getMessage();
        if (!msg.getString().contains(real)) return;

        MutableText out = Text.empty();
        boolean[] done = {false};
        msg.visit((Style style, String str) -> {
            int i = done[0] ? -1 : str.indexOf(real);
            if (i < 0) {
                out.append(Text.literal(str).setStyle(style));
            } else {
                if (i > 0) out.append(Text.literal(str.substring(0, i)).setStyle(style));
                out.append(ic);
                out.append(Text.literal(str.substring(i)).setStyle(style));
                done[0] = true;
            }
            return Optional.empty();
        }, Style.EMPTY);
        if (done[0]) event.setMessage(out);
    }
}
