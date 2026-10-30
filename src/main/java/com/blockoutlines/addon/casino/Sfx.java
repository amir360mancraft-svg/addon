package com.blockoutlines.addon.casino;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;

/** The little sounds of the casino, made from normal Minecraft sounds (so they follow the volume sliders). */
public final class Sfx {
    private Sfx() {}

    private static void play(SoundEvent e, float pitch, float volume) {
        try {
            MinecraftClient.getInstance().getSoundManager().play(PositionedSoundInstance.master(e, pitch, volume));
        } catch (Throwable ignored) {
        }
    }

    /** A chip or a button. */
    public static void tick() {
        play(SoundEvents.BLOCK_NOTE_BLOCK_HARP.value(), 2.0f, 0.35f);
    }

    /** A card lands on the table. */
    public static void card() {
        play(SoundEvents.BLOCK_NOTE_BLOCK_HARP.value(), 0.7f, 0.45f);
    }

    /** A card is turned over. */
    public static void flip() {
        play(SoundEvents.BLOCK_NOTE_BLOCK_HARP.value(), 1.5f, 0.3f);
    }

    /** Bet placed. */
    public static void bet() {
        play(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 0.7f, 0.5f);
    }

    /** Something good (a safe tile): the pitch goes up with the step. */
    public static void good(int step) {
        play(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, Math.min(2f, 0.8f + step * 0.06f), 0.6f);
    }

    public static void win(boolean big) {
        play(SoundEvents.BLOCK_NOTE_BLOCK_CHIME.value(), 1.0f, 0.8f);
        play(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 1.4f, 0.7f);
        if (big) play(SoundEvents.ENTITY_FIREWORK_ROCKET_LAUNCH, 1.0f, 0.5f);
    }

    public static void lose() {
        play(SoundEvents.BLOCK_NOTE_BLOCK_HARP.value(), 0.5f, 0.8f);
    }

    public static void boom() {
        play(SoundEvents.ENTITY_LIGHTNING_BOLT_IMPACT, 1.3f, 0.7f);
    }
}
