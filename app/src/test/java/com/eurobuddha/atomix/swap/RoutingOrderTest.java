// VENDORED VERBATIM from apks/atomix 0.1.82 app/src/test/java/com/eurobuddha/atomix/swap/RoutingOrderTest.java by scripts/vendor-atomix.sh — do not edit here; re-vendor.
package com.eurobuddha.atomix.swap;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Equal-price routing = largest-cap-first (fewest swaps). When several makers quote the SAME best price, the router
 * fills the DEEPEST maker first so a fillable amount uses one swap (or the fewest sweep legs) instead of splitting
 * across a newer, smaller maker. Price is never sacrificed for size — a strictly better price always ranks first.
 */
public class RoutingOrderTest {

    private Order maker(double usdtAvail, double minimaAvail) {
        Order o = new Order();
        o.usdtAvail = usdtAvail;
        o.minimaAvail = minimaAvail;
        return o;
    }
    private Order.Level lvl(double price, double amount) { return new Order.Level(price, amount); }

    // ---- levelCap ----
    @Test public void levelCapSellClampsByUsdtBalance() {
        Order o = maker(10.0, 0);
        assertEquals("cap = usdt/price when balance binds", 10.0, SwapOrderBook.levelCap(o, lvl(1.0, 30), true), 1e-9);
        assertEquals("cap = level amount when smaller", 5.0, SwapOrderBook.levelCap(o, lvl(1.0, 5), true), 1e-9);
    }
    @Test public void levelCapBuyClampsByMinimaBalance() {
        Order o = maker(0, 20.0);
        assertEquals(20.0, SwapOrderBook.levelCap(o, lvl(1.0, 30), false), 1e-9);
        assertEquals(8.0, SwapOrderBook.levelCap(o, lvl(1.0, 8), false), 1e-9);
    }
    @Test public void levelCapZeroForNonPositivePriceOrNull() {
        assertEquals(0.0, SwapOrderBook.levelCap(maker(10, 10), lvl(0, 30), true), 1e-9);
        assertEquals(0.0, SwapOrderBook.levelCap(null, lvl(1, 1), true), 1e-9);
        assertEquals(0.0, SwapOrderBook.levelCap(maker(10, 10), null, true), 1e-9);
    }

    // ---- equal price → deeper maker first ----
    @Test public void equalPricePrefersDeeperMakerSell() {
        Order small = maker(4.95, 0);  Order.Level ls = lvl(0.99, 5);    // cap 5
        Order big   = maker(29.7, 0);  Order.Level lb = lvl(0.99, 30);   // cap 30
        assertTrue("deeper maker ranks first", SwapOrderBook.compareForFill(big, lb, small, ls, true) < 0);
        assertTrue("and symmetrically", SwapOrderBook.compareForFill(small, ls, big, lb, true) > 0);
    }
    @Test public void equalPricePrefersDeeperMakerBuy() {
        Order small = maker(0, 5);   Order.Level ls = lvl(1.01, 5);
        Order big   = maker(0, 30);  Order.Level lb = lvl(1.01, 30);
        assertTrue(SwapOrderBook.compareForFill(big, lb, small, ls, false) < 0);
    }

    // ---- price priority: a strictly better price beats a bigger cap ----
    @Test public void betterPriceOutranksLargerCapSell() {
        Order hiSmall = maker(0.5, 0);  Order.Level lh = lvl(1.00, 0.5);   // higher bid, tiny
        Order loBig   = maker(100, 0);  Order.Level ll = lvl(0.99, 100);   // lower bid, huge
        assertTrue("higher bid first even with a tiny cap", SwapOrderBook.compareForFill(hiSmall, lh, loBig, ll, true) < 0);
    }
    @Test public void betterPriceOutranksLargerCapBuy() {
        Order loSmall = maker(0, 0.5);  Order.Level ll = lvl(1.00, 0.5);   // lower ask, tiny
        Order hiBig   = maker(0, 100);  Order.Level lh = lvl(1.01, 100);   // higher ask, huge
        assertTrue("lower ask first even with a tiny cap", SwapOrderBook.compareForFill(loSmall, ll, hiBig, lh, false) < 0);
    }

    // ---- full sort: best price, then deepest-at-equal-price, regardless of insertion order ----
    @Test public void sortRanksBestPriceThenDeepestFirstSell() {
        List<Object[]> rows = new ArrayList<>();
        rows.add(new Object[]{maker(4.95, 0), lvl(0.99, 5)});    // 0.99 cap5  — inserted BEFORE the deeper 0.99
        rows.add(new Object[]{maker(29.7, 0), lvl(0.99, 30)});   // 0.99 cap30
        rows.add(new Object[]{maker(1.0, 0),  lvl(1.00, 1)});    // 1.00 cap1  — best price
        rows.sort((a, b) -> SwapOrderBook.compareForFill((Order) a[0], (Order.Level) a[1], (Order) b[0], (Order.Level) b[1], true));

        assertEquals("best (highest) bid first", 1.00, ((Order.Level) rows.get(0)[1]).price, 1e-9);
        assertEquals("then the DEEPEST 0.99", 0.99, ((Order.Level) rows.get(1)[1]).price, 1e-9);
        assertEquals("cap 30 before cap 5 (not insertion order)", 30.0, ((Order.Level) rows.get(1)[1]).amount, 1e-9);
        assertEquals("shallow 0.99 last", 5.0, ((Order.Level) rows.get(2)[1]).amount, 1e-9);
    }

    @Test public void nonFiniteMakerBalanceClampedToZero() throws Exception {
        // A self-signed maker could advertise a non-finite balance; NaN/Infinity would poison levelCap (they sort
        // as the LARGEST cap, so largest-cap-first would route the griefer first and crash sweep planning).
        // fromJson must clamp it. 1e999 is valid JSON that parses to Infinity.
        Order bad = Order.fromJson(new org.json.JSONObject("{\"mpk\":\"0x\",\"ts\":1,\"bal\":{\"m\":1e999,\"u\":1e999}}"));
        assertEquals("infinite minimaAvail clamped", 0.0, bad.minimaAvail, 0.0);
        assertEquals("infinite usdtAvail clamped", 0.0, bad.usdtAvail, 0.0);
        assertEquals("→ no routing cap", 0.0, SwapOrderBook.levelCap(bad, lvl(1.0, 30), false), 0.0);

        Order neg = Order.fromJson(new org.json.JSONObject("{\"mpk\":\"0x\",\"ts\":1,\"bal\":{\"m\":-5,\"u\":-5}}"));
        assertEquals("negative clamped", 0.0, neg.minimaAvail, 0.0);

        Order ok = Order.fromJson(new org.json.JSONObject("{\"mpk\":\"0x\",\"ts\":1,\"bal\":{\"m\":30,\"u\":29.7}}"));
        assertEquals("a normal balance survives", 30.0, ok.minimaAvail, 1e-9);
        assertEquals(29.7, ok.usdtAvail, 1e-9);
    }

    @Test public void identicalPricesTieByCapDespiteFloatNoise() {
        // 0.99 vs 0.9900000001 quantize to the same 8dp key → tie broken by cap, not the ULP difference
        assertEquals(SwapOrderBook.pxKey(0.99), SwapOrderBook.pxKey(0.9900000001));
        Order small = maker(0, 5);   Order.Level ls = lvl(0.99, 5);
        Order big   = maker(0, 30);  Order.Level lb = lvl(0.9900000001, 30);
        assertTrue("near-identical price → deeper maker first", SwapOrderBook.compareForFill(big, lb, small, ls, false) < 0);
    }
}
