// VENDORED VERBATIM from apks/pandapools 0.9.80 app/src/test/java/com/eurobuddha/pandapools/VirtualCurveTest.java by scripts/vendor-pandapools.sh — do not edit here; re-vendor.
package com.eurobuddha.pandapools;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.math.BigDecimal;
import java.util.Arrays;

/**
 * Constant-product quoting — the fund-critical math. Each quote MUST satisfy the on-chain covenant invariant
 * (nx-fx)*(ny-fy) >= x*y with pool-favourable rounding, must conserve each token exactly (out = reserve moved),
 * and must quantise to the token grain (Minima floors token amounts, so an off-grain output is rejected).
 */
public class VirtualCurveTest {

    private static final BigDecimal FEE = new BigDecimal("0.005");   // 5/1000

    private static Pool pool(String m, String t, int dp, String kmin) {
        Pool p = new Pool();
        p.reserveM = new BigDecimal(m);
        p.reserveT = new BigDecimal(t);
        p.tokDecimals = dp;
        p.kmin = kmin;
        p.tok = "0x7D39";
        p.address = "0xADDR";
        p.tokName = "USDT";
        return p;
    }

    // ---- MINIMA -> token ----

    @Test public void mToTConservesTheTokenExactly() {
        Pool p = pool("1000", "1000", 8, "1");
        VirtualCurve.Quote q = VirtualCurve.quoteMtoT(p, new BigDecimal("10"));
        assertTrue(q.ok);
        // ny + dy == y  (the token removed equals the reserve delta — no token created or destroyed)
        assertEquals(0, q.newY.add(q.outAmount).compareTo(p.reserveT));
        // nx == x + dx  (input augments the MINIMA reserve exactly)
        assertEquals(0, q.newX.compareTo(p.reserveM.add(q.inAmount)));
    }

    @Test public void mToTOutputIsOnTheTokenGrain() {
        Pool p = pool("1000", "1000", 8, "1");
        VirtualCurve.Quote q = VirtualCurve.quoteMtoT(p, new BigDecimal("10"));
        assertTrue("recreated token reserve is on-grain", q.newY.scale() <= 8);
        assertTrue("token out is on-grain", q.outAmount.stripTrailingZeros().scale() <= 8);
    }

    @Test public void mToTSatisfiesCovenantInvariant() {
        Pool p = pool("1000", "1000", 8, "1");
        BigDecimal dx = new BigDecimal("10");
        VirtualCurve.Quote q = VirtualCurve.quoteMtoT(p, dx);
        assertTrue(q.ok);
        BigDecimal fx = dx.multiply(FEE);                    // covenant floors this DOWN, so exact fx is stricter
        BigDecimal lhs = q.newX.subtract(fx).multiply(q.newY);   // (nx-fx)*(ny-fy), fy=0 for a buy
        BigDecimal rhs = p.reserveM.multiply(p.reserveT).max(new BigDecimal(p.kmin));
        assertTrue("(nx-fx)*ny >= max(x*y,KMIN)", lhs.compareTo(rhs) >= 0);
    }

    @Test public void mToTGrowsKSoFeeStaysInPool() {
        Pool p = pool("1000", "1000", 8, "1");
        VirtualCurve.Quote q = VirtualCurve.quoteMtoT(p, new BigDecimal("10"));
        assertTrue("K after >= K before (the fee is the LP reward)",
                q.newX.multiply(q.newY).compareTo(p.reserveM.multiply(p.reserveT)) >= 0);
    }

    // ---- token -> MINIMA ----

    @Test public void tToMConservesMinimaExactly() {
        Pool p = pool("1000", "1000", 8, "1");
        VirtualCurve.Quote q = VirtualCurve.quoteTtoM(p, new BigDecimal("10"));
        assertTrue(q.ok);
        // x - dm == nx  (MINIMA out equals the reserve delta)
        assertEquals(0, p.reserveM.subtract(q.outAmount).compareTo(q.newX));
        // ny == y + dyin  (clamped input augments the token reserve exactly)
        assertEquals(0, q.newY.compareTo(p.reserveT.add(q.inAmount)));
    }

    @Test public void tToMClampsInputToTokenGrain() {
        Pool p = pool("1000", "1000", 8, "1");
        // an off-grain input (12 dp) must be clamped DOWN to 8 dp before it is used
        VirtualCurve.Quote q = VirtualCurve.quoteTtoM(p, new BigDecimal("10.123456789999"));
        assertTrue(q.ok);
        assertTrue("input clamped to grain", q.inAmount.scale() <= 8);
        assertEquals(new BigDecimal("10.12345678"), q.inAmount);
    }

    @Test public void tToMSatisfiesCovenantInvariant() {
        Pool p = pool("1000", "1000", 8, "1");
        VirtualCurve.Quote q = VirtualCurve.quoteTtoM(p, new BigDecimal("10"));
        assertTrue(q.ok);
        BigDecimal fy = q.inAmount.multiply(FEE);             // covenant floors DOWN → exact is stricter
        BigDecimal lhs = q.newX.multiply(q.newY.subtract(fy));   // nx*(ny-fy), fx=0 for a sell
        BigDecimal rhs = p.reserveM.multiply(p.reserveT).max(new BigDecimal(p.kmin));
        assertTrue("nx*(ny-fy) >= max(x*y,KMIN)", lhs.compareTo(rhs) >= 0);
    }

    // ---- guards ----

    @Test public void unfundedOrZeroTradeIsNotOk() {
        assertFalse(VirtualCurve.quoteMtoT(new Pool(), new BigDecimal("10")).ok);   // unfunded
        Pool p = pool("1000", "1000", 8, "1");
        assertFalse(VirtualCurve.quoteMtoT(p, BigDecimal.ZERO).ok);
        assertFalse(VirtualCurve.quoteMtoT(p, new BigDecimal("-5")).ok);
        assertFalse(VirtualCurve.quoteMtoT(p, null).ok);
    }

    @Test public void dustTradeThatCannotMoveAGrainIsNotOk() {
        // deep pool + a 0-dp token: a tiny MINIMA input can't shift a whole token unit → no quote
        Pool p = pool("1000000000", "1000000000", 0, "1");
        assertFalse(VirtualCurve.quoteMtoT(p, new BigDecimal("0.000000000001")).ok);
    }

    // ---- independent oracles (not construction-consistency) ----

    @Test public void mToTMatchesConstantProductOracle() {
        Pool p = pool("1000", "1000", 8, "1");
        BigDecimal dx = new BigDecimal("10");
        VirtualCurve.Quote q = VirtualCurve.quoteMtoT(p, dx);
        assertTrue(q.ok);
        // (1) EXACT: recompute ny = rhs/(nx-fx) rounded UP to grain, independently of the impl.
        BigDecimal nx = p.reserveM.add(dx);
        BigDecimal fx = dx.multiply(FEE);
        BigDecimal rhs = p.reserveM.multiply(p.reserveT).max(new BigDecimal(p.kmin));
        BigDecimal nyOracle = rhs.divide(nx.subtract(fx), 8, java.math.RoundingMode.UP);
        assertEquals("ny matches rhs/(nx-fx) UP-to-grain", 0, q.newY.compareTo(nyOracle));
        assertEquals("dy = y - ny", 0, q.outAmount.compareTo(p.reserveT.subtract(nyOracle)));
        // (2) INDEPENDENT DERIVATION: textbook Uniswap-with-0.5%-fee dy = y·0.995·dx/(x+0.995·dx), within a grain.
        BigDecimal eff = dx.multiply(new BigDecimal("0.995"));
        BigDecimal textbook = p.reserveT.multiply(eff)
                .divide(p.reserveM.add(eff), 40, java.math.RoundingMode.DOWN);
        assertTrue("out within a few grains of the textbook formula",
                q.outAmount.subtract(textbook).abs().compareTo(new BigDecimal("0.00000010")) < 0);
        // (3) bounds + price impact
        assertTrue("0 < out < y", q.outAmount.signum() > 0 && q.outAmount.compareTo(p.reserveT) < 0);
        assertTrue("buying the token moves the effective price below spot",
                q.effPrice.compareTo(p.spotPrice()) < 0);
    }

    @Test public void largerTradeGetsStrictlyWorsePricePerUnit() {
        Pool p = pool("1000000", "1000000", 8, "1");
        BigDecimal small = VirtualCurve.quoteMtoT(p, new BigDecimal("100")).effPrice;
        BigDecimal big = VirtualCurve.quoteMtoT(p, new BigDecimal("100000")).effPrice;
        assertTrue("bigger buy = worse token-per-MINIMA (slippage)", big.compareTo(small) < 0);
    }

    @Test public void kminFloorBindsWhenProductBelowIt() {
        // Simulate the forged-dust edge: the pool's product (100*100 = 10,000) has dipped just below its KMIN
        // floor (10,500). rhs = max(x*y, KMIN) must then select KMIN, and the recreated reserve must honour it —
        // this is the dust-pairing-drain defense. KMIN sits just above x*y so the trade still clears (dy > 0).
        Pool p = pool("100", "100", 8, "10500");
        BigDecimal dx = new BigDecimal("50");
        VirtualCurve.Quote q = VirtualCurve.quoteMtoT(p, dx);
        assertTrue("trade clears", q.ok);
        BigDecimal nx = p.reserveM.add(dx), fx = dx.multiply(FEE);
        BigDecimal rhsIfProduct = p.reserveM.multiply(p.reserveT);         // 10,000
        BigDecimal rhsIfKmin = new BigDecimal(p.kmin);                     // 10,500
        assertTrue("KMIN really is the larger floor here", rhsIfKmin.compareTo(rhsIfProduct) > 0);
        // ny must be built off KMIN, not the (smaller) current product → independently reconstruct it
        BigDecimal nyOracle = rhsIfKmin.divide(nx.subtract(fx), 8, java.math.RoundingMode.UP);
        assertEquals("ny built off the KMIN floor", 0, q.newY.compareTo(nyOracle));
        assertTrue("(nx-fx)*ny >= KMIN", nx.subtract(fx).multiply(q.newY).compareTo(rhsIfKmin) >= 0);
    }

    @Test public void fractionalGrainFeeStillClearsOnChain() {
        // a dx whose *0.995 doesn't terminate cleanly at the token grain — exercises the pool-favourable margin
        Pool p = pool("777.123456789", "1234.5", 6, "1");
        BigDecimal dx = new BigDecimal("13.333333");
        VirtualCurve.Quote q = VirtualCurve.quoteMtoT(p, dx);
        assertTrue(q.ok);
        assertTrue("token out on the dp=6 grain", q.outAmount.stripTrailingZeros().scale() <= 6);
        // even with the covenant flooring fx DOWN, the exact-fx invariant here is a valid lower bound
        BigDecimal lhs = p.reserveM.add(dx).subtract(dx.multiply(FEE)).multiply(q.newY);
        assertTrue(lhs.compareTo(p.reserveM.multiply(p.reserveT)) >= 0);
    }

    @Test public void tToMDustGuardAndGrain() {
        // sell side: a deep pool + a 0-dp token, tiny token input clamps to 0 → no quote
        Pool zeroDp = pool("1000000000", "1000000000", 0, "1");
        assertFalse(VirtualCurve.quoteTtoM(zeroDp, new BigDecimal("0.4")).ok);   // clamps DOWN to 0
        Pool p = pool("1000", "1000", 2, "1");
        VirtualCurve.Quote q = VirtualCurve.quoteTtoM(p, new BigDecimal("5.019"));
        assertTrue(q.ok);
        assertEquals("input clamped to dp=2 grain", new BigDecimal("5.01"), q.inAmount);
    }

    @Test public void aggregateHelpers() {
        Pool a = pool("100", "200", 8, "1");
        Pool b = pool("300", "300", 8, "1");
        // reserve-weighted mean price = sumY/sumX = 500/400 = 1.25
        assertEquals(0, VirtualCurve.aggregatePrice(Arrays.asList(a, b)).compareTo(new BigDecimal("1.25")));
        assertEquals(0, VirtualCurve.totalMinima(Arrays.asList(a, b)).compareTo(new BigDecimal("400")));
    }
}
