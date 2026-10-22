package com.eurobuddha.dexicon.resting;
/* Ported from apks/pandadex app/src/test/java/com/eurobuddha/pandadex/SweepPlannerTest.java (PandaDEX 0.4.23). */


import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

public class SweepPlannerTest {

    /** A realistic 32-byte payout address — Order5.fillable() rejects malformed ones. */
    private static final String PAYOUT =
            "0xBB11223344556677889900AABBCCDDEEFF00112233445566778899AABBCCDDEE";

    private static Order5 sell(String coinid, String locked, String want, String minRem) {
        try {
            JSONObject c = new JSONObject();
            c.put("coinid", coinid);
            c.put("amount", locked);
            c.put("tokenid", "0x00");
            c.put("created", 100);
            JSONObject st = new JSONObject();
            st.put("0", "0xAA");
            st.put("1", PAYOUT);
            st.put("2", want);
            st.put("3", DexContract.MXUSD_ID);
            st.put("4", "0x" + coinid.substring(2));
            st.put("5", "1");
            st.put("6", "0");
            st.put("7", "1");
            st.put("8", minRem);
            c.put("state", st);
            return Order5.from(c);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    @Test public void bestPriceFirstFullThenPartial() {
        List<Order5> book = new ArrayList<>();
        book.add(sell("0xC1", "100", "0.60", "1"));   // 0.006
        book.add(sell("0xC2", "100", "0.50", "1"));   // 0.005  ← cheapest, taken first
        book.add(sell("0xC3", "100", "0.70", "1"));   // 0.007
        SweepPlanner.Plan p = SweepPlanner.plan(book, true, new BigDecimal("150"), null, 200);
        assertEquals(2, p.takes.size());
        assertEquals("0xC2", p.takes.get(0).order.coinid);
        assertFalse(p.takes.get(0).partial);
        assertEquals("0xC1", p.takes.get(1).order.coinid);
        assertTrue(p.takes.get(1).partial);
        assertEquals(0, new BigDecimal("50").compareTo(p.takes.get(1).minima));
        assertEquals(0, new BigDecimal("150").compareTo(p.totalMinima));
        // cost: 0.50 (full) + partial pay ceil(0.60*50/100)=0.30
        assertEquals(0, new BigDecimal("0.80").compareTo(p.totalMxusd));
    }

    @Test public void limitPriceExcludesWorseOrders() {
        List<Order5> book = new ArrayList<>();
        book.add(sell("0xC1", "100", "0.50", "1"));   // 0.005
        book.add(sell("0xC2", "100", "0.70", "1"));   // 0.007 — above limit
        SweepPlanner.Plan p = SweepPlanner.plan(book, true, new BigDecimal("200"),
                new BigDecimal("0.006"), 200);
        assertEquals(1, p.takes.size());
        assertEquals("0xC1", p.takes.get(0).order.coinid);
        assertEquals(0, new BigDecimal("100").compareTo(p.totalMinima));   // only what's within limit
    }

    @Test public void minRemainderShrinksThePartial() {
        List<Order5> book = new ArrayList<>();
        book.add(sell("0xC1", "100", "0.50", "20"));   // floor 20 MINIMA
        // wanting 95 would leave remainder 5 < 20 → take shrinks to 80 (remainder exactly 20)
        SweepPlanner.Plan p = SweepPlanner.plan(book, true, new BigDecimal("95"), null, 200);
        assertEquals(1, p.takes.size());
        assertTrue(p.takes.get(0).partial);
        assertEquals(0, new BigDecimal("80").compareTo(p.takes.get(0).minima));
    }

    @Test public void expiredOrdersSkipped() {
        List<Order5> book = new ArrayList<>();
        book.add(sell("0xC1", "100", "0.50", "1"));    // created 100; block 2000 → expired
        SweepPlanner.Plan p = SweepPlanner.plan(book, true, new BigDecimal("50"), null, 2000);
        assertTrue(p.isEmpty());
    }

    @Test public void onlyOnePartialEver() {
        List<Order5> book = new ArrayList<>();
        for (int i = 0; i < 8; i++) book.add(sell("0xC" + i, "10", "0.05", "1"));
        SweepPlanner.Plan p = SweepPlanner.plan(book, true, new BigDecimal("200"), null, 200);
        assertEquals(SweepPlanner.MAX_ORDERS, p.takes.size());
        int partials = 0;
        for (SweepPlanner.Take t : p.takes) if (t.partial) partials++;
        assertEquals(0, partials);   // all full (each order fully consumed before the cap)
        assertEquals(0, new BigDecimal("50").compareTo(p.totalMinima));
    }
}
