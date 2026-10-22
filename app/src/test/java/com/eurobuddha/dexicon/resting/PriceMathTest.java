package com.eurobuddha.dexicon.resting;
/* Ported from apks/pandadex app/src/test/java/com/eurobuddha/pandadex/PriceMathTest.java (PandaDEX 0.4.23). */


import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.math.BigDecimal;
import java.util.Random;

/** Mirrors contract/phaseA.py: the app-side math must always construct covenant-acceptable
 *  amounts, and shaving any grain must flip the covenant mirror to reject. */
public class PriceMathTest {

    private static final BigDecimal GRAIN = new BigDecimal("0.00000001");

    private boolean acceptsHonest(BigDecimal locked, BigDecimal want, BigDecimal take, BigDecimal minRem) {
        BigDecimal rem = locked.subtract(take);
        BigDecimal pay = PriceMath.payFor(want, locked, take, PriceMath.MXUSD_DP);
        BigDecimal neww = PriceMath.newWantFor(want, locked, rem, PriceMath.MXUSD_DP);
        return PriceMath.covenantAccepts(locked, want, rem, minRem, pay, neww);
    }

    @Test public void honestPartialsAccepted() {
        assertTrue(acceptsHonest(new BigDecimal(100), new BigDecimal("0.575"), new BigDecimal(60), BigDecimal.ZERO));
        assertTrue(acceptsHonest(new BigDecimal(100), new BigDecimal("0.575"), new BigDecimal("99.9"), BigDecimal.ZERO));
        assertTrue(acceptsHonest(new BigDecimal(100), new BigDecimal("0.575"), new BigDecimal("0.01"), BigDecimal.ZERO));
        assertTrue(acceptsHonest(new BigDecimal("1000000"), new BigDecimal("0.00000001"), new BigDecimal("999999"), BigDecimal.ZERO));
    }

    @Test public void shavedPaymentRejected() {
        BigDecimal locked = new BigDecimal(100), want = new BigDecimal("0.575"), take = new BigDecimal(60);
        BigDecimal rem = locked.subtract(take);
        BigDecimal pay = PriceMath.payFor(want, locked, take, PriceMath.MXUSD_DP).subtract(GRAIN);
        BigDecimal neww = PriceMath.newWantFor(want, locked, rem, PriceMath.MXUSD_DP);
        assertFalse(PriceMath.covenantAccepts(locked, want, rem, BigDecimal.ZERO, pay, neww));
    }

    @Test public void shavedNewWantRejected() {
        BigDecimal locked = new BigDecimal(100), want = new BigDecimal("0.575"), take = new BigDecimal(60);
        BigDecimal rem = locked.subtract(take);
        BigDecimal pay = PriceMath.payFor(want, locked, take, PriceMath.MXUSD_DP);
        BigDecimal neww = PriceMath.newWantFor(want, locked, rem, PriceMath.MXUSD_DP).subtract(GRAIN);
        assertFalse(PriceMath.covenantAccepts(locked, want, rem, BigDecimal.ZERO, pay, neww));
    }

    @Test public void noProgressRejected() {
        BigDecimal locked = new BigDecimal(100), want = new BigDecimal("0.575");
        assertFalse(PriceMath.covenantAccepts(locked, want, locked, BigDecimal.ZERO, want, want));
    }

    @Test public void dustRemainderRejected() {
        // remainder 5 below a min-remainder of 10
        assertFalse(acceptsHonest(new BigDecimal(100), new BigDecimal("0.575"), new BigDecimal(95), new BigDecimal(10)));
        assertTrue(acceptsHonest(new BigDecimal(100), new BigDecimal("0.575"), new BigDecimal(90), new BigDecimal(10)));
    }

    @Test public void inflatedNewWantRejected() {
        BigDecimal locked = new BigDecimal(100), want = new BigDecimal("0.575"), take = new BigDecimal(60);
        BigDecimal rem = locked.subtract(take);
        BigDecimal pay = PriceMath.payFor(want, locked, take, PriceMath.MXUSD_DP);
        assertFalse(PriceMath.covenantAccepts(locked, want, rem, BigDecimal.ZERO, pay, want.add(GRAIN)));
    }

    @Test public void makerNeverUnderpaid() {
        // across the whole fill: pay + newWant >= want (maker's total receivable never shrinks)
        Random rnd = new Random(42);
        for (int i = 0; i < 500; i++) {
            BigDecimal locked = new BigDecimal(1 + rnd.nextInt(1_000_000)).movePointLeft(rnd.nextInt(4));
            BigDecimal want = new BigDecimal(1 + rnd.nextInt(1_000_000_000)).movePointLeft(rnd.nextInt(8));
            BigDecimal take = locked.multiply(new BigDecimal(1 + rnd.nextInt(99))).divide(new BigDecimal(100), PriceMath.MINIMA_DP, java.math.RoundingMode.DOWN);
            if (take.signum() == 0 || take.compareTo(locked) >= 0) continue;
            BigDecimal rem = locked.subtract(take);
            BigDecimal pay = PriceMath.payFor(want, locked, take, PriceMath.MXUSD_DP);
            BigDecimal neww = PriceMath.newWantFor(want, locked, rem, PriceMath.MXUSD_DP);
            assertTrue("accept " + i, PriceMath.covenantAccepts(locked, want, rem, BigDecimal.ZERO, pay, neww));
            assertTrue("no-underpay " + i, pay.add(neww).compareTo(want) >= 0);
        }
    }

    @Test public void priceDerivation() {
        assertEquals(0, new BigDecimal("0.005750000000")
                .compareTo(PriceMath.price(new BigDecimal("0.575"), new BigDecimal(100))));
    }
}
