package com.eurobuddha.dexicon.maker;

import static org.junit.Assert.*;

import com.eurobuddha.dexicon.resting.DexContract;
import com.eurobuddha.dexicon.resting.Pool;

import org.junit.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Owner 2026-10-29: when PandaPools moves past my own ladder, my node trades the pool back to my best rung. */
public class AutoCrossTest {
    static Pool pool(String m, String t) {
        Pool p = new Pool();
        p.reserveM = new BigDecimal(m); p.reserveT = new BigDecimal(t); p.tokDecimals = 8; p.kmin = "1";
        p.tok = DexContract.MXUSD_ID; p.address = "0xA1"; p.oadr = "0xB2"; p.opk = "0xAA";
        p.coinidM = "0xC1"; p.coinidT = "0xC2";
        return p;
    }
    static MyOrder rung(String side, String price, String status) {
        MyOrder o = new MyOrder(); o.orderId = "0x" + side + price; o.side = side; o.price = price; o.amount = "1200"; o.remaining = "1200"; o.status = status;
        o.coins.add("coin" + price);
        return o;
    }
    static final List<MyOrder> LADDER = Arrays.asList(
            rung("sell", "0.0035", "live"), rung("sell", "0.00355", "live"), rung("sell", "0.0036", "live"), rung("sell", "0.00345", "cancelled"),
            rung("buy", "0.0034", "live"), rung("buy", "0.00335", "live"), rung("buy", "0.0033", "live"));
    static final double MEXC = 0.00347;

    @Test public void poolAboveMyAsksSellsDownToMyBestAsk() {
        AutoCross.Decision d = AutoCross.decide(Arrays.asList(pool("1000000", "3600")), LADDER, MEXC);   // spot 0.0036
        assertNotNull(d);
        assertTrue(d.sell);
        assertEquals(0, d.limit.compareTo(new BigDecimal("0.0035")));
        assertEquals(0, d.want.compareTo(new BigDecimal("2400")));   // only the rungs the pool moved past (0.0035, 0.00355), not the cancelled one
        assertTrue(d.plan.totalMinima.compareTo(new BigDecimal("2400")) <= 0 && d.plan.totalMinima.compareTo(new BigDecimal("2399")) >= 0);
        assertTrue(d.plan.effectivePrice.compareTo(d.limit) >= 0);
        assertTrue(d.plan.effectivePrice.doubleValue() > MEXC);
        // 29 Oct: the cross failed when the wallet scan could not reach split coins; it must spend the crossed rungs' own coins
        assertEquals(Arrays.asList("coin0.0035", "coin0.00355"), d.coins);
    }

    /** 29 Oct: crosses 2 and 3 sold 9000 MINIMA more than the ladder held, counting asks whose coins the first had spent. */
    @Test public void unbackedRungsAreNotCrossed() {
        List<MyOrder> ladder = new java.util.ArrayList<>(LADDER);
        ladder.set(0, rung("sell", "0.0035", "live")); ladder.get(0).coins.clear();
        AutoCross.Decision d = AutoCross.decide(Arrays.asList(pool("1000000", "3600")), ladder, MEXC);
        assertNotNull(d);
        assertEquals(0, d.want.compareTo(new BigDecimal("1200")));   // only the backed 0.00355 rung
        assertEquals(Arrays.asList("coin0.00355"), d.coins);
    }

    @Test public void poolBelowMyBidsBuysUpToMyBestBid() {
        AutoCross.Decision d = AutoCross.decide(Arrays.asList(pool("1000000", "3300")), LADDER, MEXC);   // spot 0.0033
        assertNotNull(d);
        assertFalse(d.sell);
        assertEquals(0, d.limit.compareTo(new BigDecimal("0.0034")));
        // review 30 Oct: the crossed bids' coins hold 0.0034×1200 + 0.00335×1200 = 8.10 MxUSD; at up to 0.0034 each that buys 2382.35…
        assertEquals(0, d.want.compareTo(new BigDecimal("2382.35294117")));
        assertTrue(d.plan.totalMxusd.compareTo(new BigDecimal("8.10")) <= 0);
        assertTrue(d.plan.effectivePrice.compareTo(d.limit) <= 0);
        assertTrue(d.plan.effectivePrice.doubleValue() < MEXC);
    }

    @Test public void poolInsideMySpreadDoesNothing() {
        assertNull(AutoCross.decide(Arrays.asList(pool("1000000", "3470")), LADDER, MEXC));
    }

    @Test public void lessThanOneMinimaDoesNothing() {
        assertNull(AutoCross.decide(Arrays.asList(pool("1000", "3.52")), LADDER, MEXC));
    }

    @Test public void neverTradesTheWrongWayAgainstMexc() {
        assertNull(AutoCross.decide(Arrays.asList(pool("1000000", "3600")), LADDER, 0.0036));   // selling below MEXC is no profit
        assertNull(AutoCross.decide(Arrays.asList(pool("1000000", "3600")), LADDER, 0));        // no MEXC price at all
    }

    @Test public void noPoolsOrNoRungsDoesNothing() {
        assertNull(AutoCross.decide(new ArrayList<>(), LADDER, MEXC));
        assertNull(AutoCross.decide(Arrays.asList(pool("1000000", "3600")), new ArrayList<>(), MEXC));
    }
    @Test public void postedOrUnknownCrossDoesNotReleaseOnTimeOrFailureLabel() throws Exception {
        org.json.JSONObject r=new org.json.JSONObject().put("stage","POSTED").put("mayHavePosted",true).put("tip",2399000);
        assertFalse(AutoCross.safeToRelease(r));
        assertFalse(AutoCross.safeToRelease(r.put("stage","DROPPED")));
        assertFalse(AutoCross.safeToRelease(r.put("stage","FAILED")));
        assertFalse(AutoCross.safeToRelease(null));
        assertTrue(AutoCross.safeToRelease(r.put("stage","POSTED").put("block",2398803)));
    }
    @Test public void knownFailureBeforePostingMayReleaseButUnknownOldReceiptMayNot() throws Exception {
        org.json.JSONObject r=new org.json.JSONObject().put("stage","FAILED");
        assertFalse(AutoCross.safeToRelease(r));
        assertTrue(AutoCross.safeToRelease(r.put("mayHavePosted",false)));
    }
    @Test public void endedBeforePostingIsReleasedWhateverTheTerminalStage() throws Exception {
        for (String st : new String[]{"FAILED","LOST","REFUSED","DROPPED"}) {
            assertTrue(st, AutoCross.safeToRelease(new org.json.JSONObject().put("stage",st).put("mayHavePosted",false)));
            assertFalse(st, AutoCross.safeToRelease(new org.json.JSONObject().put("stage",st).put("mayHavePosted",true)));
        }
        assertFalse("still building is never released here", AutoCross.safeToRelease(new org.json.JSONObject().put("stage","BUILDING").put("mayHavePosted",false)));
    }
    @Test public void onlyAutoCrossReceiptsAreAdopted() throws Exception {
        assertTrue(AutoCross.ownReceipt(new org.json.JSONObject().put("auto",true)));
        assertTrue("pre-upgrade receipt", AutoCross.ownReceipt(new org.json.JSONObject().put("reason","Auto-cross: Signing transaction")));
        assertFalse("a manual pool trade", AutoCross.ownReceipt(new org.json.JSONObject().put("route","POOL").put("reason","Selecting wallet coins…")));
    }
    private static final String A = "0x" + "a1".repeat(32);
    private static AutoCross.FundingCommand node(String state) {
        return (q, reply) -> {
            boolean mem = q.contains("checkmempool:true");
            try {
                if (state.equals("unknown")) { reply.accept(null); return; }
                boolean live = state.equals("unspent") || (state.equals("mempool") && !mem);
                org.json.JSONArray rows = new org.json.JSONArray();
                if (live) rows.put(new org.json.JSONObject().put("coinid", A).put("spent", false));
                reply.accept(new org.json.JSONObject().put("status", true).put("response", rows));
            } catch (Exception e) { reply.accept(null); }
        };
    }
    @Test public void coinStatesAndVerdicts() throws Exception {
        for (String st : new String[]{"unspent","mempool","spent","unknown"}) {
            String[] got = {null}; AutoCross.coinState(node(st), A, v -> got[0] = v); assertEquals(st, got[0]);
            AutoCross.Review r = new AutoCross.Review(new org.json.JSONObject()); r.coins.add(new String[]{A, st}); AutoCross.verdict(r);
            assertEquals(st, st.equals("unspent") || st.equals("spent"), r.safe);
            assertEquals(st, st.equals("unspent"), r.unspent);
        }
    }
}
