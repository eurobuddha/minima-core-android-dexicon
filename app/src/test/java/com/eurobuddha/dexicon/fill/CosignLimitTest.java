package com.eurobuddha.dexicon.fill;

import static org.junit.Assert.*;

import org.junit.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Review 23 Nov 2026 (MINOR): co-signs burn one-time leaves - sign only the keys a take spends, and limit attempts. */
public class CosignLimitTest {
    static String addr(String key) { return new org.minima.objects.Address("RETURN SIGNEDBY(" + key + ")").getAddressData().to0xString(); }

    @Test public void onlyTheKeysWhoseCoinsTheTakeSpendsSign() {
        String a = "0x" + "A1".repeat(32), b = "0x" + "B2".repeat(32), c = "0x" + "C3".repeat(32);
        List<Coin> ins = Arrays.asList(new Coin("0x01", addr(b).toLowerCase(), "0x00", BigDecimal.ONE, false, false, 1),
                new Coin("0x02", "0x" + "99".repeat(32), "0x00", BigDecimal.ONE, false, false, 1));
        assertEquals(Collections.singletonList(b), FillTxn.keysSpent(Arrays.asList(a, b, c), ins));
        assertTrue(FillTxn.keysSpent(Arrays.asList(a, c), ins).isEmpty());
    }

    @Test public void attemptsAreLimitedPerTakerAndPerOrder() {
        CosignLimit l = new CosignLimit();
        long t = 1_000_000L;
        for (int i = 0; i < CosignLimit.PER_TAKER; i++) assertTrue(l.admit("taker1", "order" + i, t));
        assertFalse("per taker", l.admit("taker1", "orderX", t));
        for (int i = 0; i < CosignLimit.PER_ORDER; i++) assertTrue(l.admit("t" + i, "hot", t));
        assertFalse("per order", l.admit("someone", "hot", t));
        assertTrue("the window slides", l.admit("taker1", "orderX", t + CosignLimit.WINDOW_MS));
    }
}
