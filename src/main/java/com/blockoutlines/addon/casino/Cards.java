package com.blockoutlines.addon.casino;

import net.minecraft.client.gui.DrawContext;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Playing cards: the deck, one card on the table and how a card is drawn (with deal and flip animations). */
public final class Cards {
    private Cards() {}

    public static final int W = 44, H = 62;
    private static final String[] RANK = {"A", "2", "3", "4", "5", "6", "7", "8", "9", "10", "J", "Q", "K"};
    private static final String[] SUIT = {"♠", "♥", "♦", "♣"};

    public static int rank(int id) {
        return id % 13;
    }

    public static boolean red(int id) {
        int s = id / 13;
        return s == 1 || s == 2;
    }

    /** A card on the table. Where it flies from, when, and when it turns around. */
    public static final class Card {
        public final int id;
        public long dealAt;
        public long flipAt = -1;      // -1 = face down for now
        public boolean faceUp;
        public float fromX, fromY;

        public Card(int id, long dealAt, boolean faceUp, float fromX, float fromY) {
            this.id = id;
            this.dealAt = dealAt;
            this.faceUp = faceUp;
            this.fromX = fromX;
            this.fromY = fromY;
            if (faceUp) this.flipAt = dealAt + 220;
        }

        public void reveal(long now) {
            if (!faceUp) {
                faceUp = true;
                flipAt = now;
            }
        }

        /** 0 = back, 1 = face (the turn takes 260 ms). */
        public float flip(long now) {
            if (!faceUp || flipAt < 0) return 0;
            return Math.max(0, Math.min(1, (now - flipAt) / 260f));
        }
    }

    /** Several decks, shuffled with SecureRandom (Fisher-Yates through Collections.shuffle). */
    public static final class Shoe {
        private final SecureRandom rng = new SecureRandom();
        private final List<Integer> cards = new ArrayList<>();
        private final int decks;

        public Shoe(int decks) {
            this.decks = decks;
            refill();
        }

        public void refill() {
            cards.clear();
            for (int d = 0; d < decks; d++) for (int i = 0; i < 52; i++) cards.add(i);
            Collections.shuffle(cards, rng);
        }

        public int draw() {
            if (cards.size() < 12) refill();
            return cards.remove(cards.size() - 1);
        }
    }

    /** Draws the card with its top-left corner at its place, flying in from where the deck is. */
    public static void draw(DrawContext c, Card k, float tx, float ty, long now) {
        if (now < k.dealAt) return;
        float t = UI.easeOut((now - k.dealAt) / 340f);
        float x = k.fromX + (tx - k.fromX) * t;
        float y = k.fromY + (ty - k.fromY) * t;
        drawAt(c, k.id, x, y, k.flip(now));
    }

    public static void drawAt(DrawContext c, int id, float x, float y, float flip) {
        float s = Math.abs(1 - 2 * flip);
        float cx = x + W / 2f;
        int w = Math.max(2, Math.round(W * s));
        int left = Math.round(cx - w / 2f), top = Math.round(y);
        UI.rect(c, left + 1, top + 2, w, H, 0x66000000);
        if (flip < 0.5f) {
            UI.round(c, left, top, w, H, 0xFF2B3FA8);
            if (w > 12) {
                UI.rect(c, left + 4, top + 4, w - 8, H - 8, 0xFF3A55D6);
                UI.rect(c, left + 6, top + 6, w - 12, H - 12, 0xFF2B3FA8);
                UI.diamond(c, cx, top + H / 2f, Math.max(2, (int) (8 * s)), 0xFF8FA5FF);
            }
        } else {
            UI.round(c, left, top, w, H, 0xFFF4F4F8);
            if (s > 0.45f) {
                int col = red(id) ? 0xFFD62F3A : 0xFF1B1B26;
                String r = RANK[rank(id)];
                UI.plain(c, cx - W / 2f * s + 9 * s + 2, top + 9, 1.5f * s, r, col);
                UI.suit(c, id / 13, cx - W / 2f * s + 9 * s + 2, top + 21, 0.9f * s, col);
                UI.suit(c, id / 13, cx + 2, top + H / 2f + 8, 2.6f * s, col);
            }
        }
    }

    /** A pile that looks like a deck. */
    public static void pile(DrawContext c, float x, float y) {
        for (int i = 3; i >= 0; i--) {
            UI.round(c, Math.round(x) + i, Math.round(y) + i, W, H, i == 0 ? 0xFF2B3FA8 : 0xFF1E2C78);
        }
        UI.rect(c, Math.round(x) + 6, Math.round(y) + 6, W - 12, H - 12, 0xFF3A55D6);
    }
}
