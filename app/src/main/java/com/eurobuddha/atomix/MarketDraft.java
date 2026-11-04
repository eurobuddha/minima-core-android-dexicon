// VENDORED VERBATIM from apks/atomix 0.1.96 app/src/main/java/com/eurobuddha/atomix/MarketDraft.java by scripts/vendor-atomix.sh — do not edit here; re-vendor.
package com.eurobuddha.atomix;

import com.eurobuddha.atomix.swap.Order;
import java.math.BigDecimal;

/** Numeric checks before the market editor changes its rows or saved settings.
 * Reuses decimalInput's plain-decimal syntax, Order's finite-value boundary, and the
 * existing ladder formula/clamps. Blank fields and zero sizes retain their meaning. */
public final class MarketDraft {
    private MarketDraft() {}

    /** Invalid and underflowed nonzero inputs stay invalid; never silently turn them into an off side. */
    public static double number(String raw, boolean signed, double empty) {
        if (raw == null) return Double.NaN;
        String value = raw.trim();
        if (value.isEmpty()) return empty;
        if (!value.matches((signed ? "-?" : "") + "(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)")) return Double.NaN;
        try {
            BigDecimal exact = new BigDecimal(value);
            double parsed = exact.doubleValue();
            return Double.isFinite(parsed) && (parsed != 0 || exact.signum() == 0) ? parsed : Double.NaN;
        } catch (NumberFormatException error) { return Double.NaN; }
    }

    /** Fields in their existing editor order: skew, reprice, mid, step, levels, ask size, bid size, min. */
    public static boolean parameters(String... fields) {
        if (fields == null || fields.length != 8) return false;
        double[] value = new double[fields.length];
        for (int i = 0; i < fields.length; i++) {
            value[i] = number(fields[i], i == 0, i == 4 ? 1 : 0);
            if (!Double.isFinite(value[i])) return false;
            // Save serializes these preferences through MainActivity.trimSig (10 significant digits).
            if ((i == 1 || i == 3 || i == 5 || i == 6) && !Double.isFinite(editorValue(value[i]))) return false;
        }
        int levels = Math.max(1, Math.min(Order.MAX_LEVELS, (int) value[4]));
        return generation(value[2], value[3], value[5], value[6], levels);
    }

    /** Validate all generated prices/totals before the first setText can replace a hand-edited level. */
    public static boolean generation(double mid, double step, double askSize, double bidSize, int levels) {
        for (double value : new double[]{mid, step, askSize, bidSize})
            if (!Double.isFinite(value) || value < 0) return false;
        if (levels < 1 || levels > Order.MAX_LEVELS) return false;
        if (!Double.isFinite(editorValue(mid)) || !Double.isFinite(editorValue(askSize))
                || !Double.isFinite(editorValue(bidSize))) return false;
        if (mid == 0 || step == 0 || (askSize == 0 && bidSize == 0)) return true; // no auto-fill yet
        double asks = 0, bids = 0;
        for (int i = 1; i <= levels; i++) {
            if (askSize > 0) {
                double price = mid * (1 + i * step / 100.0);
                if (!positiveProduct(price, askSize) || !positiveProduct(editorValue(price), editorValue(askSize)) || !Double.isFinite(asks += editorValue(askSize))) return false;
            }
            if (bidSize > 0) {
                double price = mid * (1 - i * step / 100.0);
                if (!Double.isFinite(price)) return false;
                // Existing Order.sanitize drops bids at or below zero when the step crosses the midpoint.
                if (price > 0 && (!positiveProduct(price, bidSize) || !positiveProduct(editorValue(price), editorValue(bidSize)) || !Double.isFinite(bids += editorValue(bidSize)))) return false;
            }
        }
        return true;
    }

    /** Unused/partial rows remain allowed, but malformed values, totals and notionals cannot be saved. */
    public static boolean rows(String[][] rows) {
        if (rows == null) return false;
        double total = 0;
        for (String[] row : rows) {
            if (row == null || row.length != 2) return false;
            double price = number(row[0], false, 0), amount = number(row[1], false, 0);
            if (!Double.isFinite(price) || !Double.isFinite(amount)) return false;
            if (price > 0 && amount > 0 && (!positiveProduct(price, amount) || !Double.isFinite(total += amount))) return false;
        }
        return true;
    }

    // Exactly the existing trimSig rounding; tidyAmount only removes trailing decimal zeros.
    private static double editorValue(double value) {
        return Double.isFinite(value) ? new BigDecimal(value, new java.math.MathContext(10)).doubleValue() : Double.NaN;
    }

    private static boolean positiveProduct(double price, double amount) {
        double value = price * amount;
        return price > 0 && Double.isFinite(price) && value > 0 && Double.isFinite(value);
    }
}
