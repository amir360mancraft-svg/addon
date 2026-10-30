package com.blockoutlines.addon.util;

import java.util.Locale;

/** Amounts of money the way the server writes them: 1,500 / 2.5K / 3M / 1.2B / 4T. */
public final class Money {
    private Money() {
    }

    /** Parses an amount. Returns -1 when the text is not a number. */
    public static long parse(String text) {
        if (text == null) return -1;
        String s = text.replaceAll("[,\\s$]", "");
        if (s.isEmpty()) return -1;
        char last = Character.toLowerCase(s.charAt(s.length() - 1));
        double mul = 1;
        switch (last) {
            case 'k' -> mul = 1e3;
            case 'm' -> mul = 1e6;
            case 'b' -> mul = 1e9;
            case 't' -> mul = 1e12;
            default -> {
            }
        }
        if (mul > 1) s = s.substring(0, s.length() - 1);
        try {
            double v = Double.parseDouble(s);
            if (Double.isNaN(v) || Double.isInfinite(v) || v < 0) return -1;
            return Math.round(v * mul);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String trim(double v) {
        String s = String.format(Locale.ROOT, "%.2f", v);
        if (s.contains(".")) s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
        return s;
    }

    /** 1500 -> 1.5K, 2000000 -> 2M ... */
    public static String pretty(long v) {
        long a = Math.abs(v);
        String sign = v < 0 ? "-" : "";
        if (a >= 1_000_000_000_000L) return sign + trim(a / 1e12) + "T";
        if (a >= 1_000_000_000L) return sign + trim(a / 1e9) + "B";
        if (a >= 1_000_000L) return sign + trim(a / 1e6) + "M";
        if (a >= 1_000L) return sign + trim(a / 1e3) + "K";
        return sign + a;
    }

    /** Like pretty, but with a + in front of gains. */
    public static String signed(long v) {
        return (v > 0 ? "+" : "") + pretty(v);
    }
}
