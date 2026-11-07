// VENDORED VERBATIM from apks/pandapools 0.9.89 app/src/main/java/com/eurobuddha/pandapools/VirtualCurve.java by scripts/vendor-pandapools.sh — do not edit here; re-vendor.
package com.eurobuddha.pandapools;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;

/**
 * Constant-product quoting: given a pool (or later, an aggregated set of pools) and a trade size, compute
 * the output honoring (nx-fx)(ny-fy) >= MAX(x*y, KMIN) with the 0.5% fee. Rounding favours the pool so a
 * quote that clears here will also clear on-chain.
 *
 * M1 = single best pool. The multi-pool water-filling split (N equal pools == one deep pool: Δi ∝ reserves)
 * lands in the routing milestone; the aggregate helpers below are the seam for it.
 */
public final class VirtualCurve {

    private static final MathContext MC = new MathContext(40, RoundingMode.DOWN);
    private static final BigDecimal FEE_NUM = new BigDecimal("5");   // 5/1000 = 0.5% — MUST match PoolCovenant *5/1000
    private static final BigDecimal FEE_DEN = new BigDecimal("1000");
    /** MINIMA grain for a recreated MINIMA reserve — well under MINIMA's 44-dp precision, so no on-chain floor. */
    private static final int MINIMA_DP = 11;

    public static class Quote {
        public BigDecimal inAmount;     // what the taker puts in
        public BigDecimal outAmount;    // what the taker gets
        public BigDecimal newX, newY;   // recreated reserves
        public BigDecimal spotBefore, spotAfter;  // token-per-MINIMA
        public BigDecimal effPrice;     // realised price = out/in (direction-aware)
        public boolean ok;
    }

    private VirtualCurve() {}

    /**
     * MINIMA -> token: taker puts in dx MINIMA, gets dy token. The recreated token reserve ny is rounded
     * UP to the token's ON-CHAIN grain (10^-decimals) so the value the covenant reads back after Minima
     * floors it still clears the invariant (nx-fx)*ny >= MAX(x*y, KMIN). dy = y - ny is then on-grain too.
     */
    public static Quote quoteMtoT(Pool p, BigDecimal dx) {
        Quote q = new Quote();
        if (!p.funded() || dx == null || dx.signum() <= 0) return q;
        int dp = p.tokDecimals;
        BigDecimal x = p.reserveM, y = p.reserveT, kmin = Util.decOr(p.kmin, BigDecimal.ZERO);
        BigDecimal nx = x.add(dx);
        BigDecimal fx = dx.multiply(FEE_NUM).divide(FEE_DEN, MC);
        BigDecimal rhs = x.multiply(y).max(kmin);
        BigDecimal ny = rhs.divide(nx.subtract(fx), dp, RoundingMode.UP);   // token-grain, pool-favourable
        BigDecimal dy = y.subtract(ny);
        if (dy.signum() <= 0) return q;   // pool too shallow / trade too small to move a token grain
        q.inAmount = dx; q.outAmount = dy; q.newX = nx; q.newY = ny;
        q.spotBefore = p.spotPrice(); q.spotAfter = ny.divide(nx, MC);
        q.effPrice = dy.divide(dx, MC);   // token per MINIMA realised
        q.ok = true;
        return q;
    }

    /**
     * token -> MINIMA: taker puts in dyin token, gets dm MINIMA. The input is first clamped DOWN to the
     * token grain (so it is an achievable coin amount and the recreated token reserve ny = y + dyin is
     * on-grain); the recreated MINIMA reserve nx is rounded UP, pool-favourable, so nx*(ny-fy) >= rhs.
     */
    public static Quote quoteTtoM(Pool p, BigDecimal dyinRaw) {
        Quote q = new Quote();
        if (!p.funded() || dyinRaw == null || dyinRaw.signum() <= 0) return q;
        int dp = p.tokDecimals;
        BigDecimal dyin = dyinRaw.setScale(dp, RoundingMode.DOWN);   // clamp token input to the on-chain grain
        if (dyin.signum() <= 0) return q;
        BigDecimal x = p.reserveM, y = p.reserveT, kmin = Util.decOr(p.kmin, BigDecimal.ZERO);
        BigDecimal ny = y.add(dyin);
        BigDecimal fy = dyin.multiply(FEE_NUM).divide(FEE_DEN, MC);
        BigDecimal rhs = x.multiply(y).max(kmin);
        BigDecimal nx = rhs.divide(ny.subtract(fy), MINIMA_DP, RoundingMode.UP);   // MINIMA-grain, pool-favourable
        BigDecimal dm = x.subtract(nx);
        if (dm.signum() <= 0) return q;
        q.inAmount = dyin; q.outAmount = dm; q.newX = nx; q.newY = ny;
        q.spotBefore = p.spotPrice(); q.spotAfter = ny.divide(nx, MC);
        q.effPrice = dyin.divide(dm, MC);
        q.ok = true;
        return q;
    }

    /** Aggregate spot price across pools (reserve-weighted mean token-per-MINIMA) — the virtual-curve mid. */
    public static BigDecimal aggregatePrice(List<Pool> pools) {
        BigDecimal sumX = BigDecimal.ZERO, sumY = BigDecimal.ZERO;
        for (Pool p : pools) if (p.funded()) { sumX = sumX.add(p.reserveM); sumY = sumY.add(p.reserveT); }
        return sumX.signum() == 0 ? BigDecimal.ZERO : sumY.divide(sumX, MC);
    }

    /** Total MINIMA-side depth across all pools (aggregate pool size). */
    public static BigDecimal totalMinima(List<Pool> pools) {
        BigDecimal s = BigDecimal.ZERO;
        for (Pool p : pools) if (p.funded()) s = s.add(p.reserveM);
        return s;
    }
}
