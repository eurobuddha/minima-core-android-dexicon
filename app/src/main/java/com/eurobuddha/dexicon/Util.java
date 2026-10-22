package com.eurobuddha.dexicon;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Small helpers (PandaDEX Util subset). NO identifier-shortening helper exists here on purpose: addresses,
 *  coin ids, txpowids and public ids are always shown in full (tap-to-copy copies the full value). */
public final class Util {
    private Util() {}

    /** Parse a possibly-empty amount string to BigDecimal, defaulting to zero (tolerates a locale comma). */
    public static BigDecimal dec(String s) {
        try {
            if (s == null || s.length() > 100 || s.trim().isEmpty()) return BigDecimal.ZERO;
            String t = s.trim();
            t = (t.indexOf('.') >= 0) ? t.replace(",", "") : t.replace(',', '.');
            BigDecimal v = new BigDecimal(t);
            return v.precision() <= 64 ? v : BigDecimal.ZERO;
        } catch (Exception e) { return BigDecimal.ZERO; }
    }

    /** Tidy display for AMOUNTS: strip trailing zeros, never exponent form. */
    public static String fmt(BigDecimal v) {
        if (v == null) return "0";
        String s = v.stripTrailingZeros().toPlainString();
        return s.isEmpty() ? "0" : s;
    }

    /** Prices at a fixed width (a ladder must line up); display only. */
    public static String fmtPrice(BigDecimal v) {
        if (v == null) return "—";
        BigDecimal s = v.stripTrailingZeros();   // never rounds: pads to 6 dp for alignment, shows every digit it has
        return (s.scale() < 6 ? s.setScale(6) : s).toPlainString();
    }

    /** Display an amount with exactly {@code dp} decimals, truncated down so it never overstates. */
    public static String fmtDown(BigDecimal v, int dp) {
        return (v == null ? BigDecimal.ZERO : v).setScale(dp, RoundingMode.DOWN).toPlainString();
    }

    /** "12s ago" / "9 minutes ago" / "3 hours ago". */
    public static String ago(long ms) {
        long s = Math.max(0, ms) / 1000;
        if (s < 60) return s + "s ago";
        long m = s / 60;
        if (m < 60) return m + (m == 1 ? " minute ago" : " minutes ago");
        long h = m / 60;
        if (h < 24) return h + (h == 1 ? " hour ago" : " hours ago");
        long d = h / 24;
        return d + (d == 1 ? " day ago" : " days ago");
    }
}
