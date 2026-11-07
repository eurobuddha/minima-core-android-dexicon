package com.eurobuddha.dexicon;

import static org.junit.Assert.*;

import org.junit.Test;

import java.math.BigDecimal;

/** Owner 7 Nov: one click sweeps FAST and on-chain together; the split can never trade more than asked. */
public class SweepBothSplitTest {
    static BigDecimal d(String s) { return new BigDecimal(s); }
    static void eq(String a, BigDecimal b) { assertEquals(0, d(a).compareTo(b)); }

    @Test public void cheapOnChainThenFastThenOverflow() {
        // 3000 wanted: 500 on-chain is cheaper than FAST, FAST offers 1000, on-chain holds 4000 inside the limit
        BigDecimal[] q = MainActivity.split(d("3000"), d("500"), d("1000"), d("4000"));
        eq("2000", q[0]); eq("1000", q[1]);   // 500 cheap + 1500 overflow on-chain, FAST takes all it offers
    }

    @Test public void neverMoreThanAskedOrHeld() {
        for (String[] c : new String[][]{{"100", "0", "1000", "0"}, {"100", "500", "1000", "500"}, {"3000", "0", "1000", "200"}, {"10", "3", "4", "5"}}) {
            BigDecimal[] q = MainActivity.split(d(c[0]), d(c[1]), d(c[2]), d(c[3]));
            assertTrue(q[0].add(q[1]).compareTo(d(c[0])) <= 0);
            assertTrue(q[0].compareTo(d(c[3])) <= 0);
            assertTrue(q[1].compareTo(d(c[2])) <= 0);
            assertTrue(q[0].signum() >= 0 && q[1].signum() >= 0);
        }
        BigDecimal[] fastCovers = MainActivity.split(d("100"), d("0"), d("1000"), d("0"));
        eq("0", fastCovers[0]); eq("100", fastCovers[1]);   // nothing on-chain: no BOTH (decideBoth returns NONE)
    }
}
