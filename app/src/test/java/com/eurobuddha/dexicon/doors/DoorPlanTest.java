package com.eurobuddha.dexicon.doors;

import static org.junit.Assert.*;

import com.eurobuddha.atomix.TradingContext;
import com.eurobuddha.atomix.swap.Order;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

/** Both AtomiX markets quoted for the same input — the same synthetic books as mds/dexicon test/door.test.js. */
public class DoorPlanTest {

    static Order mk(String pk, double bid, double ask, double depth) {
        Order o = new Order();
        o.signerPk = pk; o.commsPublicId = "0xcid" + pk; o.minimaAvail = depth; o.usdtAvail = depth * bid;
        Order.Pair p = new Order.Pair(true, ask, bid, 0);
        p.bids.add(new Order.Level(bid, depth)); p.asks.add(new Order.Level(ask, depth));
        o.pairs.put("USDT", p);
        return o;
    }
    static Map<String, Order> book(Order... os) { Map<String, Order> m = new LinkedHashMap<>(); for (Order o : os) m.put(o.signerPk, o); return m; }
    final Map<String, Order> mxusd = book(mk("0xa1", 0.99, 1.01, 100), mk("0xa2", 0.98, 1.02, 100));
    final Map<String, Order> minima = book(mk("0xb1", 0.019, 0.021, 10000), mk("0xb2", 0.018, 0.022, 10000));

    @Test public void coinNamesNeverTheLegacyLabel() {
        assertEquals("MxUSD", DoorPlan.coin(TradingContext.MXUSDT));
        assertEquals("MINIMA", DoorPlan.coin(TradingContext.MINIMA));
    }

    @Test public void enterQuotesBothMarketsForTheSameUsdt() {
        DoorPlan.Route a = DoorPlan.route(mxusd, TradingContext.MXUSDT, false, "10", null);
        DoorPlan.Route b = DoorPlan.route(minima, TradingContext.MINIMA, false, "10", null);
        assertNotNull(a.plan); assertNotNull(b.plan);
        assertEquals(9.90099, a.plan.filledMinima, 1e-4);
        assertEquals(476.190476, b.plan.filledMinima, 1e-3);
        assertTrue("never over the USDT typed", a.plan.totalUsdt <= 10 + 1e-9 && b.plan.totalUsdt <= 10 + 1e-9);
        assertEquals(2, a.makers); assertEquals(2, b.makers);
        assertEquals(200, a.depth, 1e-9);                       // second ask within 2 % slippage
        assertEquals(10000, b.depth, 1e-9);                     // second ask 4.8 % away — beyond the cap
    }

    @Test public void exitQuotesEachRouteInItsOwnCoin() {
        assertEquals(4.95, DoorPlan.route(mxusd, TradingContext.MXUSDT, true, "5", null).plan.totalUsdt, 1e-9);
        assertEquals(0.095, DoorPlan.route(minima, TradingContext.MINIMA, true, "5", null).plan.totalUsdt, 1e-9);
    }

    @Test public void noAmountGivesBestPriceOnly() {
        DoorPlan.Route r = DoorPlan.route(minima, TradingContext.MINIMA, true, "", null);
        assertNull(r.plan); assertNull(r.err); assertEquals(0.019, r.best, 1e-12);
    }

    @Test public void emptyBookNamesTheCoin() {
        DoorPlan.Route r = DoorPlan.route(new LinkedHashMap<>(), TradingContext.MINIMA, false, "10", null);
        assertNull(r.plan); assertTrue(r.err, r.err.contains("selling MINIMA"));
    }

    @Test public void myOwnOrderIsNeverTaken() {
        DoorPlan.Route r = DoorPlan.route(mxusd, TradingContext.MXUSDT, true, "5", "0xcid0xa1");
        assertEquals(1, r.makers);
        assertEquals(0.98, r.best, 1e-12);
        for (DoorPlan.Leg l : r.plan.legs) assertNotEquals("0xa1", l.maker.signerPk);
    }

    @Test public void buyLegsRoundUpSellLegsRoundDown() {
        assertEquals("1.010002", DoorPlan.ceilUsdt("1.000001", 1.01));
        assertEquals("1.010001", DoorPlan.computeUsdt("1.000001", 1.01));
        assertEquals("0.333333", DoorPlan.legMinima(0.3333339));
        assertFalse(DoorPlan.positiveSwapAmount("1 amount:9"));
    }
}
