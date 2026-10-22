package com.eurobuddha.dexicon.resting;
/* Vendored from apks/pandadex app/src/main/java/com/eurobuddha/pandadex/PriceMath.java (PandaDEX 0.4.23).
 * Keep as close to verbatim as possible; only the package, the NodeApi/SignGate imports and the minimal Dexicon
 * glue noted inline differ. Per the family naming rule the Minima dollar token is MxUSD in identifiers too
 * (PandaDEX MXUSD_ID/MXUSD_DP/*Mxusd are MXUSD_ID/MXUSD_DP/*Mxusd here). Re-copy rather than hand-edit when PandaDEX changes. */


import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * All order/fill arithmetic for the V5 partial-fill covenant. Mirrors the on-chain
 * cross-multiplication checks EXACTLY (proven in contract/phaseA.py 134/134 and on-chain in
 * phaseB.py) — every rounding here is in the MAKER's favor so a constructed txn can never
 * fail the covenant by a grain:
 *
 *   maker payment:  pay * locked >= want * (locked - rem)     (pay rounded UP to grain)
 *   remainder want: newWant * locked >= want * rem            (newWant rounded UP, capped at want)
 *
 * MINIMA amounts use 8dp here too (book grain), MxUSD is an 8-decimals token — on-chain
 * token amounts FLOOR to the token grain, so every outgoing token amount must already be
 * quantized (see [[minima-token-grain-gotcha]]).
 */
public final class PriceMath {

    private PriceMath() {}

    public static final MathContext MC = new MathContext(40, RoundingMode.DOWN);

    /** MxUSD token grain (8 decimals). */
    public static final int MXUSD_DP = 8;
    /** Book grain used for MINIMA legs (well inside the chain's native precision). */
    public static final int MINIMA_DP = 8;
    /** Display precision for prices. */
    public static final int PRICE_DP = 12;

    /** Minimum order size in MINIMA (dust guard, mirrors Limit's MIN_ORDER). */
    public static final BigDecimal MIN_ORDER_MINIMA = new BigDecimal("0.01");
    /** Maximum per-leg order size. MiniNumber.isValidMinimaValue rejects anything above 1e9,
     *  and the covenant's overflow headroom (products must stay under 2^64) assumes both legs
     *  respect this — so enforce it before the funds move rather than failing at the node. */
    public static final BigDecimal MAX_ORDER = new BigDecimal("1000000000");

    /** Quantize UP to n decimals (maker-favored: payments, remainder want). */
    public static BigDecimal up(BigDecimal v, int dp) {
        return v.setScale(dp, RoundingMode.CEILING);
    }

    /** Quantize DOWN to n decimals (taker-facing: proceeds, change). */
    public static BigDecimal down(BigDecimal v, int dp) {
        return v.setScale(dp, RoundingMode.FLOOR);
    }

    /** The maker payment for taking {@code take} out of {@code locked} on an order wanting
     *  {@code want} total — grain-ceiled so the covenant inequality always holds. */
    public static BigDecimal payFor(BigDecimal want, BigDecimal locked, BigDecimal take, int wantDp) {
        return up(want.multiply(take, MC).divide(locked, MC), wantDp);
    }

    /** The remainder coin's new want-amount (STATE(2)) after a partial leaves {@code rem}
     *  locked — grain-ceiled, capped at the original want (the covenant's upper bound). */
    public static BigDecimal newWantFor(BigDecimal want, BigDecimal locked, BigDecimal rem, int wantDp) {
        BigDecimal w = up(want.multiply(rem, MC).divide(locked, MC), wantDp);
        return w.min(want);
    }

    /** True iff the covenant would accept these partial-fill numbers (mirror of the script). */
    public static boolean covenantAccepts(BigDecimal locked, BigDecimal want, BigDecimal rem,
                                          BigDecimal minRem, BigDecimal pay, BigDecimal newWant) {
        if (rem.compareTo(locked) >= 0) return false;
        if (rem.compareTo(minRem) < 0) return false;
        if (newWant.multiply(locked, MC).compareTo(want.multiply(rem, MC)) < 0) return false;
        if (newWant.compareTo(want) > 0) return false;
        return pay.multiply(locked, MC).compareTo(want.multiply(locked.subtract(rem), MC)) >= 0;
    }

    /** Order price = want / locked (derived from the enforced amounts — port 6 is never
     *  trusted; Limit lesson). */
    public static BigDecimal price(BigDecimal want, BigDecimal locked) {
        if (locked.signum() == 0) return BigDecimal.ZERO;
        return want.divide(locked, PRICE_DP, RoundingMode.HALF_UP);
    }

    /** Same-side size weighting, as requested: sum(price * MINIMA size) / sum(size). */
    static BigDecimal weightedBookPrice(BigDecimal bid, BigDecimal bidSize,
                                        BigDecimal ask, BigDecimal askSize) {
        BigDecimal value = BigDecimal.ZERO, size = BigDecimal.ZERO;
        if (bid != null && bid.signum() > 0 && bidSize != null && bidSize.signum() > 0) {
            value = value.add(bid.multiply(bidSize)); size = size.add(bidSize);
        }
        if (ask != null && ask.signum() > 0 && askSize != null && askSize.signum() > 0) {
            value = value.add(ask.multiply(askSize)); size = size.add(askSize);
        }
        return size.signum() == 0 ? null : value.divide(size, PRICE_DP, RoundingMode.HALF_UP);
    }

    /** Tidy display for AMOUNTS: strip trailing zeros without exponent form. */
    public static String fmt(BigDecimal v) {
        return Util.tidyAmount(v.stripTrailingZeros().toPlainString());
    }

    /** Display an amount with exactly {@code dp} decimals, truncated down so it never overstates. */
    public static String fmtDown(BigDecimal v, int dp) {
        if (v == null) v = BigDecimal.ZERO;
        return v.setScale(dp, RoundingMode.DOWN).toPlainString();
    }

    /** Decimals shown for every PRICE in the UI. MINIMA trades around 0.05 MxUSD, so five
     *  decimals only resolves to the nearest 0.00001 — enough to make two genuinely different
     *  orders look identical and to misreport what you are about to trade at. Six gives a
     *  real tick at this price level. */
    public static final int DISPLAY_DP = 6;

    /**
     * Display a PRICE at a fixed width. Prices must NOT strip trailing zeros the way amounts
     * do: in a ladder "0.052" and "0.0520" read as different numbers, and ragged decimals stop
     * the columns lining up. Always {@link #DISPLAY_DP} places, rounded half-up (display only —
     * the on-chain price is always derived from the contract-enforced amounts).
     */
    public static String fmtPrice(BigDecimal v) {
        if (v == null) return "—";
        return v.setScale(DISPLAY_DP, RoundingMode.HALF_UP).toPlainString();
    }
}
