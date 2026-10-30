package com.blockoutlines.addon.casino;

import net.minecraft.client.gui.DrawContext;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Playing cards: the deck, one card on the table and how a card is drawn (with deal and flip animations). */
public final class Cards {
    private Cards() {}

    public static final int W = 56, H = 78;
    private static final String[] RANK = {"A", "2", "3", "4", "5", "6", "7", "8", "9", "10", "J", "Q", "K"};
    /** Where the pips of the number cards go: x as -1 / 0 / 1 (left, middle, right) and y from 0 (top) to 1 (bottom). */
    private static final float[][][] PIPS = {
        {},
        {{0, .5f}},
        {{0, .2f}, {0, .8f}},
        {{0, .2f}, {0, .5f}, {0, .8f}},
        {{-1, .2f}, {1, .2f}, {-1, .8f}, {1, .8f}},
        {{-1, .2f}, {1, .2f}, {0, .5f}, {-1, .8f}, {1, .8f}},
        {{-1, .2f}, {1, .2f}, {-1, .5f}, {1, .5f}, {-1, .8f}, {1, .8f}},
        {{-1, .2f}, {1, .2f}, {0, .35f}, {-1, .5f}, {1, .5f}, {-1, .8f}, {1, .8f}},
        {{-1, .2f}, {1, .2f}, {0, .35f}, {-1, .5f}, {1, .5f}, {0, .65f}, {-1, .8f}, {1, .8f}},
        {{-1, .2f}, {1, .2f}, {-1, .4f}, {1, .4f}, {0, .5f}, {-1, .6f}, {1, .6f}, {-1, .8f}, {1, .8f}},
        {{-1, .2f}, {1, .2f}, {0, .3f}, {-1, .4f}, {1, .4f}, {-1, .6f}, {1, .6f}, {0, .7f}, {-1, .8f}, {1, .8f}},
    };

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
        UI.round(c, left + 1, top + 3, w, H, 0x66000000);
        if (flip < 0.5f) {
            UI.round(c, left, top, w, H, 0xFFE8E8F0);
            if (w > 8) {
                UI.round(c, left + 2, top + 2, w - 4, H - 4, 0xFF2B3FA8);
                if (w > 14) {
                    UI.rect(c, left + 5, top + 5, w - 10, H - 10, 0xFF3A55D6);
                    UI.rect(c, left + 7, top + 7, w - 14, H - 14, 0xFF2B3FA8);
                    UI.diamond(c, cx, top + H / 2f, Math.max(2, (int) (10 * s)), 0xFF8FA5FF);
                    UI.diamond(c, cx, top + H / 2f, Math.max(1, (int) (5 * s)), 0xFF2B3FA8);
                }
            }
        } else {
            UI.round(c, left, top, w, H, 0xFFCFCFDA);
            UI.round(c, left + 1, top + 1, w - 2, H - 2, 0xFFF8F8FC);
            if (s > 0.85f) face(c, id, cx, top, s);
        }
    }

    private static void face(DrawContext c, int id, float cx, int top, float s) {
        int col = red(id) ? 0xFFD62F3A : 0xFF1B1B26;
        int r = rank(id), suit = id / 13;
        String label = RANK[r];
        float lx = cx - 22, rx = cx + 22;
        // the two corners (the lower one has the suit turned over)
        UI.plain(c, lx, top + 8, 1.0f, label, col);
        UI.suit(c, suit, lx, top + 19, 1, false, col, false);
        UI.plain(c, rx, top + H - 8, 1.0f, label, col);
        UI.suit(c, suit, rx, top + H - 19, 1, false, col, true);
        // the middle
        if (r == 0) {
            UI.suit(c, suit, cx, top + H / 2f, 3, true, col, false);
        } else if (r >= 10) {
            UI.rect(c, Math.round(cx - 16), top + 12, 32, H - 24, 0xFFFFF3C4);
            UI.outline(c, Math.round(cx - 16), top + 12, 32, H - 24, 0xFFC9A227);
            UI.plain(c, cx, top + H / 2f, 2.6f, label, col);
            UI.suit(c, suit, cx - 9, top + 21, 1, false, col, false);
            UI.suit(c, suit, cx + 9, top + H - 21, 1, false, col, true);
        } else {
            for (float[] p : PIPS[r + 1]) {
                UI.suit(c, suit, cx + p[0] * 12f, top + 8 + p[1] * (H - 16), 1, true, col, p[1] > 0.5f);
            }
        }
    }

    /** A pile that looks like a deck. */
    public static void pile(DrawContext c, float x, float y) {
        for (int i = 4; i >= 1; i--) UI.round(c, Math.round(x) + i, Math.round(y) + i, W, H, i == 4 ? 0x66000000 : 0xFF1E2C78);
        drawAt(c, 0, x, y, 0f);
    }
}
