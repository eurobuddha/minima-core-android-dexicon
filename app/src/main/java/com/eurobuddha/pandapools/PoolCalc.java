// VENDORED VERBATIM from apks/pandapools 0.10.12 app/src/main/java/com/eurobuddha/pandapools/PoolCalc.java by scripts/vendor-pandapools.sh — do not edit here; re-vendor.
package com.eurobuddha.pandapools;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The what-if pool calculator's maths. DISPLAY-ONLY — nothing here builds a transaction, so plain doubles are
 * fine (the fund-critical quoting stays in {@link VirtualCurve} on BigDecimal).
 *
 * A PandaPool is a full-range constant-product pool: the covenant enforces MINIMA × token ≥ K and nothing else,
 * so at any price P the reserves are fixed by K alone:  x = √(K / P),  y = √(K · P). The count ratio x / y is
 * always 1 / P, the pool is always half MINIMA / half token by value, and neither side ever reaches zero.
 *
 * Fees: every swap leaves 0.5 % of its input in the pool, which only ever raises K. The calculator values
 * the fees kept (rate × volume) at the CURRENT price, which gives the clean identity
 *   pool value with fees = fee-free value + fees kept   ⇔   √K' = √K + fees / (2 √P).
 * Volume that traded at other prices lands slightly differently; the dialog says so.
 *
 * Mirrored in the MiniDapp (calc.js) and minimaCore Desktop (renderer/poolcalc.js) — keep the three in step.
 */
public final class PoolCalc {

    private PoolCalc() {}

    public static final class Result {
        public double k;           // entry invariant x0 · y0 (== the KMIN floor at creation)
        public double kWithFees;   // K after the fees kept are folded in
        public double kGrowthPct;  // (kWithFees / k − 1) · 100 — the app's "fee growth" figure
        public double entryPrice;  // y0 / x0, token per MINIMA
        public double move;        // price / entryPrice
        public double minima;      // MINIMA in the pool at `price` (fees included)
        public double token;       // token in the pool at `price` (fees included)
        public double ratio;       // minima / token == 1 / price
        public double value;       // pool value in token units == 2 · token
        public double hold;        // value of simply holding x0 MINIMA + y0 token at `price`
        public double vsHoldPct;   // (value / hold − 1) · 100 — divergence loss, fees included
        public double feesKept;    // rate · volume, in token value
        public double breakEvenVolume;   // token-value volume whose fees exactly offset the divergence loss (NaN if fee is 0)
    }

    /**
     * @param x0      MINIMA added at creation (> 0)
     * @param y0      token added at creation (> 0)
     * @param price   the MINIMA price to evaluate, in token per MINIMA (> 0)
     * @param feePct  swap fee in percent (PandaPools: 0.5)
     * @param volume  total volume traded through the pool, in token value (both directions), ≥ 0
     */
    public static Result compute(double x0, double y0, double price, double feePct, double volume) {
        Result r = new Result();
        if (!(x0 > 0) || !(y0 > 0) || !(price > 0) || Double.isInfinite(x0) || Double.isInfinite(y0) || Double.isInfinite(price)) return null;
        if (!(feePct >= 0) || feePct >= 100) feePct = 0;
        if (!(volume >= 0)) volume = 0;
        r.k = x0 * y0;
        r.entryPrice = y0 / x0;
        r.move = price / r.entryPrice;
        r.feesKept = feePct / 100.0 * volume;
        double sqrtK2 = Math.sqrt(r.k) + r.feesKept / (2.0 * Math.sqrt(price));
        r.kWithFees = sqrtK2 * sqrtK2;
        r.kGrowthPct = (r.kWithFees / r.k - 1.0) * 100.0;
        r.minima = Math.sqrt(r.kWithFees / price);
        r.token = Math.sqrt(r.kWithFees * price);
        r.ratio = r.minima / r.token;
        r.value = 2.0 * r.token;
        r.hold = x0 * price + y0;
        r.vsHoldPct = (r.value / r.hold - 1.0) * 100.0;
        double loss = Math.max(0.0, r.hold - 2.0 * Math.sqrt(r.k * price));
        r.breakEvenVolume = feePct > 0 ? loss / (feePct / 100.0) : Double.NaN;
        return r;
    }

    /** Display formatter: whole numbers past 1,000, else enough decimals to keep a small value meaningful.
     *  Thousands-grouped, never scientific notation, never truncated to nothing. */
    public static String fmt(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return "—";
        double a = Math.abs(v);
        int dp = a >= 1000 ? 0 : a >= 1 ? 2 : a >= 0.01 ? 4 : 8;
        BigDecimal b = BigDecimal.valueOf(v).setScale(dp, RoundingMode.HALF_UP);
        if (dp > 0) b = b.stripTrailingZeros();   // 500.00 → 500, 52.50 → 52.5; never an exponent, never nothing
        String s = b.toPlainString();
        // group the integer part
        int dot = s.indexOf('.');
        String intPart = dot < 0 ? s : s.substring(0, dot), frac = dot < 0 ? "" : s.substring(dot);
        boolean neg = intPart.startsWith("-");
        if (neg) intPart = intPart.substring(1);
        StringBuilder g = new StringBuilder();
        for (int i = 0; i < intPart.length(); i++) {
            if (i > 0 && (intPart.length() - i) % 3 == 0) g.append(',');
            g.append(intPart.charAt(i));
        }
        return (neg ? "-" : "") + g + frac;
    }

    /** A price: plain decimal, no exponent, trailing zeros stripped (0.005 stays "0.005", 50 stays "50"). */
    public static String fmtPrice(double p) {
        if (Double.isNaN(p) || Double.isInfinite(p) || p <= 0) return "—";
        if (p >= 1000) return fmt(p);
        BigDecimal b = BigDecimal.valueOf(p).setScale(p >= 1 ? 4 : 12, RoundingMode.HALF_UP).stripTrailingZeros();
        return b.toPlainString();
    }

    /** "×4", "÷10", "×1" — the price move from entry, in the direction that reads naturally. */
    public static String fmtMove(double m) {
        if (Double.isNaN(m) || Double.isInfinite(m) || m <= 0) return "—";
        if (Math.abs(Math.log10(m)) < 1e-9) return "×1";
        return (m >= 1 ? "×" : "÷") + fmt(m >= 1 ? m : 1.0 / m);
    }
}
