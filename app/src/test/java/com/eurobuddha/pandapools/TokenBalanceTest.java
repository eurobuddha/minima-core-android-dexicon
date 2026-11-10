// VENDORED VERBATIM from apks/pandapools 0.9.97 app/src/test/java/com/eurobuddha/pandapools/TokenBalanceTest.java by scripts/vendor-pandapools.sh — do not edit here; re-vendor.
package com.eurobuddha.pandapools;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The Wallet card's four figures — sendable, confirmed, unconfirmed and the derived locked — ported from
 * AtomiX's {@code minimaBreakdown()}, whose whole point is that "the displayed numbers are never a black
 * box". These tests exist mostly to keep a zero from ever being hidden again.
 */
public class TokenBalanceTest {

    private static TokenBalance of(String confirmed, String sendable, String unconfirmed, int coins) {
        JSONObject o = new JSONObject();
        try {
            o.put("tokenid", "0x00");
            o.put("confirmed", confirmed);
            o.put("sendable", sendable);
            o.put("unconfirmed", unconfirmed);
            o.put("coins", coins);
        } catch (Exception e) { throw new RuntimeException(e); }
        return TokenBalance.from(o);
    }

    // ---- locked ----

    @Test public void lockedIsConfirmedMinusSendable() {
        // the real shape on a node whose funds are all in a pool: nothing sendable, everything locked
        assertEquals("175901.49982642299", of("175901.49982642299", "0", "0", 3).locked());
        assertEquals("100", of("150", "50", "0", 2).locked());
    }

    @Test public void lockedNeverGoesNegative() {
        // sendable can exceed confirmed transiently; a negative "locked" would be nonsense
        assertEquals("0", of("50", "150", "0", 1).locked());
    }

    @Test public void lockedIsFullPrecisionNotRounded() {
        // the Wallet card must agree with the My LP card about the same coins, so no 6dp rounding
        TokenBalance b = of("175901.49982642299", "0", "0", 3);
        assertEquals("175901.49982642299", b.locked());
        assertTrue(b.breakdown(1, 1).contains("locked ≈ 175901.49982642299"));
    }

    @Test public void unparseableLockedReadsAsUnknownNotZero() {
        // AtomiX swallows the exception and shows "—". Showing 0 would assert something we don't know.
        assertEquals("—", of("—", "0", "0", 0).locked());
        assertEquals("—", of("", "", "", 0).locked());
    }

    // ---- the breakdown line ----

    @Test public void everyFigureIsShownEvenAtZero() {
        // THE regression test: the old card hid locked and pending when zero, so a node with everything in
        // a pool showed a bare "0" and nothing explaining where the money had gone.
        String s = of("0", "0", "0", 0).breakdown(1, 1);
        assertTrue(s.contains("confirmed 0"));
        assertTrue(s.contains("locked ≈ 0"));
        assertTrue(s.contains("unconfirmed 0"));
        assertTrue(s.contains("0 coins"));
    }

    @Test public void breakdownReadsInAtomixOrder() {
        String s = of("150", "50", "7", 4).breakdown(0, 0);
        assertEquals("confirmed 150  ·  locked ≈ 100  ·  unconfirmed 7  ·  4 coins"
                + "  ·  updated never  ·  tap for coins", s);
    }

    @Test public void lockedCarriesTheApproximatelySign() {
        // it is a subtraction, not a figure the node reported — the "≈" says so
        assertTrue(of("150", "50", "0", 1).breakdown(0, 0).contains("locked ≈ "));
    }

    // ---- freshness stamp ----

    @Test public void freshnessStampReadsNeverBeforeTheFirstReply() {
        assertTrue(of("1", "1", "0", 1).breakdown(0, 5_000L).contains("updated never"));
    }

    @Test public void freshnessStampCountsSecondsSinceTheLastReply() {
        assertTrue(of("1", "1", "0", 1).breakdown(1_000L, 5_000L).contains("updated 4s ago"));
    }

    @Test public void aClockJumpingBackwardsReadsAsZeroNotNegative() {
        assertTrue(of("1", "1", "0", 1).breakdown(9_000L, 5_000L).contains("updated 0s ago"));
    }

    // ---- trailing zeros ----

    @Test public void trailingZerosAreStrippedButValueIsUntouched() {
        TokenBalance b = of("150.500000", "50.250000", "0.000000", 2);
        String s = b.breakdown(0, 0);
        assertTrue(s.contains("confirmed 150.5"));
        assertTrue(s.contains("locked ≈ 100.25"));
        assertTrue(s.contains("unconfirmed 0"));
    }
}
