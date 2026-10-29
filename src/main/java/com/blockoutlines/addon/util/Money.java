package com.blockoutlines.addon.util;

public class Money {
    /** Parses things like "500", "1,000", "2.5k", "3m", "1b", "1t" into a long. Returns 0 if it can't be read. */
    public static long parse(String s) {
        if (s == null) return 0;
        s = s.trim().toLowerCase().replace(",", "").replace("$", "").replace(" ", "");
        if (s.isEmpty()) return 0;
        double mult = 1;
        char last = s.charAt(s.length() - 1);
        switch (last) {
            case 'k': mult = 1_000d; break;
            case 'm': mult = 1_000_000d; break;
            case 'b': mult = 1_000_000_000d; break;
            case 't': mult = 1_000_000_000_000d; break;
            default: break;
        }
        if (mult != 1) s = s.substring(0, s.length() - 1);
        try {
            return (long) (Double.parseDouble(s) * mult);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static String pretty(long v) {
        if (v >= 1_000_000_000_000L) return trim(v / 1_000_000_000_000.0) + "T";
        if (v >= 1_000_000_000L) return trim(v / 1_000_000_000.0) + "B";
        if (v >= 1_000_000L) return trim(v / 1_000_000.0) + "M";
        if (v >= 1_000L) return trim(v / 1_000.0) + "K";
        return String.valueOf(v);
    }

    private static String trim(double d) {
        if (d == Math.floor(d)) return String.valueOf((long) d);
        String s = String.format(java.util.Locale.ROOT, "%.2f", d);
        return s.replaceAll("0+$", "").replaceAll("\\.$", "");
    }
}
