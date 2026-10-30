package com.blockoutlines.addon.modules;

import com.blockoutlines.addon.BlockOutlinesAddon;
import com.blockoutlines.addon.gui.CasinoScreen;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.KeybindSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.Keybind;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * A little casino for yourself: blackjack, baccarat, coin flip and mines, played with practice chips (not real
 * money). Every draw comes from SecureRandom and nothing is rigged. The chips are kept in .minecraft/better-render/casino.json.
 */
public class Casino extends Module {
    public static final long START = 100_000L;
    public static final long MAX_BET = 1_000_000_000_000_000L;

    private static final class Data {
        long balance = START;
        long rounds, wins, best;
    }

    public long balance = START, rounds, wins, best;
    private boolean loaded;

    private final SettingGroup sg = settings.getDefaultGroup();
    private final Setting<Keybind> openKey = sg.add(new KeybindSetting.Builder().name("open-key")
        .description("Opens the casino window.").defaultValue(Keybind.none()).action(this::open).build());

    private final Setting<Boolean> unlimited = sg.add(new BoolSetting.Builder().name("unlimited-chips")
        .description("You can bet any amount, even more than your chips (the chips can go below zero). Just for fun.").defaultValue(true).build());

    private final Setting<Integer> greenPockets = sg.add(new IntSetting.Builder().name("roulette-green-pockets")
        .description("How many green pockets the roulette has next to its 12 red and 12 black ones (more green = green wins more often).").defaultValue(3).range(1, 8).sliderRange(1, 6).build());
    private final Setting<Integer> greenPayout = sg.add(new IntSetting.Builder().name("roulette-green-payout")
        .description("What green pays (red and black always pay 2x).").defaultValue(4).range(2, 36).sliderRange(2, 14).build());

    public int greenPockets() {
        return greenPockets.get();
    }

    public int greenPayout() {
        return greenPayout.get();
    }

    public boolean unlimited() {
        return unlimited.get();
    }

    public Casino() {
        super(BlockOutlinesAddon.CATEGORY, "casino", "Blackjack, baccarat, coin flip and mines with animations. Practice chips, fair draws.");
    }

    @Override
    public void onActivate() {
        open();
    }

    public void open() {
        if (mc.player == null) return;
        ensureLoaded();
        mc.execute(() -> mc.setScreen(new CasinoScreen(this)));
    }

    private File file() {
        return new File(mc.runDirectory, "better-render/casino.json");
    }

    public void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        try {
            File f = file();
            if (f.exists()) {
                Data d = new Gson().fromJson(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8), Data.class);
                if (d != null) {
                    balance = d.balance;
                    rounds = d.rounds;
                    wins = d.wins;
                    best = d.best;
                }
            }
        } catch (Exception ignored) {
        }
    }

    public void save() {
        try {
            File f = file();
            f.getParentFile().mkdirs();
            Data d = new Data();
            d.balance = balance;
            d.rounds = rounds;
            d.wins = wins;
            d.best = best;
            Files.write(f.toPath(), new GsonBuilder().setPrettyPrinting().create().toJson(d).getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    public void resetChips() {
        balance = START;
        save();
    }
}
