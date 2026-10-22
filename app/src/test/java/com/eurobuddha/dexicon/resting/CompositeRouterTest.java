package com.eurobuddha.dexicon.resting;
/* Ported from apks/pandadex app/src/test/java/com/eurobuddha/pandadex/CompositeRouterTest.java (PandaDEX 0.4.23). */


import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class CompositeRouterTest {

    private static final String PAYOUT =
            "0xBB11223344556677889900AABBCCDDEEFF00112233445566778899AABBCCDDEE";

    private static Order5 order(String coinid, boolean sell, String minima, String price) {
        try {
            BigDecimal m = new BigDecimal(minima);
            BigDecimal p = new BigDecimal(price);
            JSONObject c = new JSONObject();
            c.put("coinid", coinid);
            c.put("tokenid", sell ? Util.MINIMA_TOKENID : DexContract.MXUSD_ID);
            c.put(sell ? "amount" : "tokenamount", sell ? minima : m.multiply(p).toPlainString());
            c.put("amount", sell ? minima : m.multiply(p).toPlainString());
            c.put("created", 100);
            JSONObject st = new JSONObject();
            st.put("0", "0xAA");
            st.put("1", PAYOUT);
            st.put("2", sell ? m.multiply(p).toPlainString() : minima);
            st.put("3", sell ? DexContract.MXUSD_ID : Util.MINIMA_TOKENID);
            st.put("4", "0x" + coinid.substring(2));
            st.put("5", sell ? "1" : "0");
            st.put("7", "1");
            st.put("8", sell ? "1" : "0.0001");
            c.put("state", st);
            return Order5.from(c);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private static Pool pool(String m, String t) {
        Pool p = new Pool();
        p.reserveM = new BigDecimal(m);
        p.reserveT = new BigDecimal(t);
        p.tokDecimals = 8;
        p.kmin = "1";
        p.tok = DexContract.MXUSD_ID;
        p.address = "0x" + m.replace(".", "") + t.replace(".", "");
        p.oadr = PAYOUT;
        p.opk = "0xAA";
        p.coinidM = p.address + "01";
        p.coinidT = p.address + "02";
        return p;
    }

    @Test public void bookOnlyMatchesSweepIntent() {
        List<Order5> book = Arrays.asList(order("0xC1", true, "100", "0.005"));
        CompositeRouter.Plan p = CompositeRouter.plan(book, new ArrayList<>(), true,
                new BigDecimal("40"), new BigDecimal("0.006"), 200);
        assertFalse(p.isEmpty());
        assertEquals(1, p.orderTakes.size());
        assertEquals(0, new BigDecimal("40").compareTo(p.totalMinima));
        assertEquals(0, BigDecimal.ZERO.compareTo(p.poolMinima));
    }

    @Test public void poolOnlyClearsWhenBookEmpty() {
        CompositeRouter.Plan p = CompositeRouter.plan(new ArrayList<>(), Arrays.asList(pool("1000", "10")),
                true, new BigDecimal("10"), new BigDecimal("0.020"), 200);
        assertFalse(p.isEmpty());
        assertEquals(0, p.orderTakes.size());
        assertTrue(p.poolCount() > 0);
        assertTrue(p.totalMinima.compareTo(new BigDecimal("10")) >= 0);
    }

    @Test public void cheaperPoolBeatsWorseAsk() {
        List<Order5> book = Arrays.asList(order("0xC1", true, "100", "0.020"));
        CompositeRouter.Plan p = CompositeRouter.plan(book, Arrays.asList(pool("1000", "10")),
                true, new BigDecimal("10"), new BigDecimal("0.030"), 200);
        assertTrue("pool contributes", p.poolMinima.signum() > 0);
    }

    @Test public void buyInterleavesPoolAndAskAsMarginalPricesCross() {
        List<Order5> book = Arrays.asList(order("0xC1", true, "20", "0.0103"));
        Pool pool = pool("1000", "10");
        CompositeRouter.Plan p = CompositeRouter.plan(book, Arrays.asList(pool),
                true, new BigDecimal("80"), new BigDecimal("0.0200"), 200);
        assertTrue("pool contributes before the curve gets expensive", p.poolMinima.signum() > 0);
        assertTrue("ask contributes once it beats the pool margin", p.orderMinima.signum() > 0);
        assertTrue(p.sourceCoinIds.contains(pool.coinidM));
        assertTrue(p.sourceCoinIds.contains(pool.coinidT));
        assertTrue(p.sourceCoinIds.contains("0xC1"));
        assertTrue(p.totalMinima.compareTo(new BigDecimal("80")) >= 0);
        assertEquals(0, BigDecimal.ZERO.compareTo(p.unfilledMinima));
    }

    @Test public void sellInterleavesBidAndPoolAsMarginalPricesCross() {
        List<Order5> book = Arrays.asList(order("0xB1", false, "20", "0.0097"));
        Pool pool = pool("1000", "10");
        CompositeRouter.Plan p = CompositeRouter.plan(book, Arrays.asList(pool),
                false, new BigDecimal("80"), new BigDecimal("0.0010"), 200);
        assertTrue("pool contributes while its marginal bid is better", p.poolMinima.signum() > 0);
        assertTrue("book bid contributes once it beats the pool margin", p.orderMinima.signum() > 0);
        assertTrue(p.sourceCoinIds.contains(pool.coinidM));
        assertTrue(p.sourceCoinIds.contains(pool.coinidT));
        assertTrue(p.sourceCoinIds.contains("0xB1"));
        assertEquals(0, new BigDecimal("80").compareTo(p.totalMinima));
        assertEquals(0, BigDecimal.ZERO.compareTo(p.unfilledMinima));
    }

    @Test public void sellLimitRejectsWorsePoolAndRestsRemainder() {
        List<Order5> book = Arrays.asList(order("0xB1", false, "5", "0.004"));
        CompositeRouter.Plan p = CompositeRouter.plan(book, Arrays.asList(pool("1000", "5")),
                false, new BigDecimal("20"), new BigDecimal("0.006"), 200);
        assertTrue(p.isEmpty());
        assertEquals(0, new BigDecimal("20").compareTo(p.unfilledMinima));
    }

    @Test public void nearExpiryOrdersAreSkippedByCompositeRouter() {
        List<Order5> book = Arrays.asList(order("0xC1", true, "100", "0.005"));
        long nearExpiry = 100 + DexContract.EXPIRY_BLOCKS - SweepPlanner.EXPIRY_MARGIN + 1;
        CompositeRouter.Plan p = CompositeRouter.plan(book, new ArrayList<>(), true,
                new BigDecimal("10"), new BigDecimal("0.006"), nearExpiry);
        assertTrue(p.isEmpty());
        assertEquals(0, new BigDecimal("10").compareTo(p.unfilledMinima));
    }

    @Test public void compositeKeepsOnlyOnePartialAndPlacesItLast() {
        List<Order5> book = Arrays.asList(
                order("0xC1", true, "10", "0.005"),
                order("0xC2", true, "10", "0.006"),
                order("0xC3", true, "10", "0.007"));
        CompositeRouter.Plan p = CompositeRouter.plan(book, new ArrayList<>(), true,
                new BigDecimal("25"), new BigDecimal("0.010"), 200);
        assertEquals(3, p.orderTakes.size());
        assertFalse(p.orderTakes.get(0).partial);
        assertFalse(p.orderTakes.get(1).partial);
        assertTrue(p.orderTakes.get(2).partial);
        assertEquals(0, new BigDecimal("5").compareTo(p.orderTakes.get(2).minima));
    }

    @Test public void compositeCapsOrderBookTakesAtFive() {
        List<Order5> book = new ArrayList<>();
        for (int i = 0; i < 10; i++) book.add(order("0xD" + i, true, "1", "0.005"));
        CompositeRouter.Plan p = CompositeRouter.plan(book, new ArrayList<>(), true,
                new BigDecimal("10"), new BigDecimal("0.010"), 200);
        assertEquals(SweepPlanner.MAX_ORDERS, p.orderTakes.size());
        assertEquals(0, new BigDecimal("5").compareTo(p.totalMinima));
        assertEquals(0, new BigDecimal("5").compareTo(p.unfilledMinima));
    }

    @Test public void capacityBudgetIsEnforced() {
        List<Order5> book = new ArrayList<>();
        for (int i = 0; i < 5; i++) book.add(order("0xC" + i, true, "1", "0.010"));
        List<Pool> pools = new ArrayList<>();
        for (int i = 0; i < 6; i++) pools.add(pool("1000", "9." + i));
        CompositeRouter.Plan p = CompositeRouter.plan(book, pools, true,
                new BigDecimal("20"), new BigDecimal("0.020"), 200);
        assertTrue(p.capacityUnits() <= CompositeRouter.MAX_CAPACITY_UNITS);
    }
}
