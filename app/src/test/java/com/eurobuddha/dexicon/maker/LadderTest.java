package com.eurobuddha.dexicon.maker;

import static org.junit.Assert.*;

import org.json.JSONArray;
import org.junit.Before;
import org.junit.Test;

import java.math.BigDecimal;
import java.util.List;

/** The fast market maker's ladder maths and the MEXC feed's safety rules (ported from AtomiX PriceOracle). */
public class LadderTest {
    @Before public void reset() { MexcFeed.reset(); }

    private static Ladder.Config pegged(String step, String levels, String ask, String bid) {
        Ladder.Config c = new Ladder.Config(); c.enabled = true; c.peg = true; c.step = step; c.levels = levels; c.askSize = ask; c.bidSize = bid; return c;
    }

    @Test public void peggedLadderAsksUpBidsDownMakerFavourable() {
        List<Ladder.Rung> r = Ladder.desired(pegged("1", "2", "100", "50"), 0.003675, 1);
        assertEquals(4, r.size());
        assertEquals("A1 sell 100.00000000 @ 0.00371175", r.get(0).toString());   // 0.003675 × 1.01 exactly
        assertEquals("B1 buy 50.00000000 @ 0.00363825", r.get(1).toString());
        assertEquals("A2", r.get(2).key); assertEquals(new BigDecimal("0.00374850"), r.get(2).price);
        // 0.003675 × 1.005 = 0.003693375 → an ask rounds UP to 8 dp, a bid DOWN
        List<Ladder.Rung> h = Ladder.desired(pegged("0.5", "1", "1", "1"), 0.003675, 1);
        assertEquals(new BigDecimal("0.00369338"), h.get(0).price);
        assertEquals(new BigDecimal("0.00365662"), h.get(1).price);
    }

    @Test public void staleFeedWidensTheSpreadAndSkewShiftsTheMid() {
        assertEquals(new BigDecimal("0.00385875"), Ladder.desired(pegged("1", "1", "1", "0"), 0.003675, 5).get(0).price);   // 5 % instead of 1 %
        Ladder.Config c = pegged("1", "1", "1", "0"); c.skew = "-2";
        assertEquals(new BigDecimal("0.00363752"), Ladder.desired(c, 0.003675, 1).get(0).price);   // 0.003675 × 0.98 × 1.01, up
    }

    @Test public void oneSidedAndOffAndBidsPastTheMid() {
        assertTrue(Ladder.desired(pegged("1", "3", "0", "10"), 0.01, 1).stream().allMatch(x -> x.side.equals("buy")));
        Ladder.Config off = pegged("1", "3", "1", "1"); off.enabled = false;
        assertTrue(Ladder.desired(off, 0.01, 1).isEmpty());
        assertEquals(1, Ladder.desired(pegged("60", "2", "0", "1"), 0.01, 1).size());   // B2 at −120 % is dropped
        assertTrue(Ladder.desired(pegged("1", "1", "1", "1"), 0, 1).isEmpty());          // no price → nothing
        assertEquals(6, Ladder.desired(pegged("1", "99", "1", "0"), 1, 1).size());        // levels clamp to 6
    }

    @Test public void manualRowsAreUsedExactly() {
        Ladder.Config c = new Ladder.Config(); c.enabled = true;
        c.asks[0] = new String[]{"0.003675", "455.78231293"}; c.bids[1] = new String[]{"0.0035", "10"}; c.asks[2] = new String[]{"0.004", ""};
        List<Ladder.Rung> r = Ladder.desired(c, 0, 1);
        assertEquals(2, r.size());
        assertEquals("A1 sell 455.78231293 @ 0.003675", r.get(0).toString());
        assertEquals("B2", r.get(1).key);
        assertNull(Ladder.validate(c));
    }

    @Test public void editOnlyWhenDriftReachesTheThresholdOrAfterAFill() {
        Ladder.Rung want = Ladder.desired(pegged("1", "1", "100", "0"), 1, 1).get(0);   // 1.01
        assertFalse(Ladder.needsEdit(want, new BigDecimal("1.0105"), new BigDecimal("100"), true, "1"));
        assertTrue(Ladder.needsEdit(want, new BigDecimal("1.03"), new BigDecimal("100"), true, "1"));
        assertTrue(Ladder.needsEdit(want, new BigDecimal("1.01"), new BigDecimal("40"), true, "1"));     // part-filled → top up
        assertTrue(Ladder.needsEdit(want, new BigDecimal("1.0105"), new BigDecimal("100"), false, "1"));  // manual: exact
        assertFalse(Ladder.needsEdit(want, new BigDecimal("1.0100"), new BigDecimal("100.0"), false, "1"));
    }

    @Test public void validateRejectsJunk() {
        Ladder.Config c = pegged("0", "1", "1", "1");
        assertNotNull(Ladder.validate(c));
        c.step = "1"; c.skew = "abc"; assertNotNull(Ladder.validate(c));
        c.skew = "-3"; assertNull(Ladder.validate(c));
        Ladder.Config m = new Ladder.Config(); m.enabled = true; assertNotNull(Ladder.validate(m));   // nothing to quote
        m.enabled = false; assertNull(Ladder.validate(m));
    }

    @Test public void configRoundTrips() {
        Ladder.Config c = pegged("0.75", "4", "120", "60"); c.skew = "-1.5"; c.asks[5] = new String[]{"9", "1"};
        Ladder.Config back = Ladder.Config.fromJson(c.toJson().toString());
        assertEquals(c.toJson().toString(), back.toJson().toString());
    }

    @Test public void feedTakesDepthPriceAndRejectsThinWideOrJumpyReads() throws Exception {
        JSONArray side = new JSONArray("[[\"0.0036\",\"1000\"],[\"0.0035\",\"10000\"],[\"0.0034\",\"30000\"]]");   // 3.6, 38.6, then 140.6 USDT
        assertEquals(0.0034, MexcFeed.effectiveLevel(side), 0);
        assertEquals(0, MexcFeed.effectiveLevel(new JSONArray("[[\"0.0036\",\"10\"]]")), 0);   // too thin
        assertNotNull(MexcFeed.accept(0.0036, 0.0035, 1));    // crossed
        assertNotNull(MexcFeed.accept(0.003, 0.004, 1));      // 25 % spread
        long now = System.currentTimeMillis();
        assertNull(MexcFeed.accept(0.00366, 0.00369, now));
        assertEquals(0.003675, MexcFeed.mid(), 1e-12);
        assertTrue(MexcFeed.fresh());
        assertNotNull(MexcFeed.accept(0.0099, 0.0100, now)); // +170 % in one read: held as suspect
        assertEquals(0.003675, MexcFeed.mid(), 1e-12);
        assertNotNull(MexcFeed.accept(0.0099, 0.0101, now + 30_000));   // agrees, but has not held 60 s yet
        assertEquals(0.003675, MexcFeed.mid(), 1e-12);
        assertNull(MexcFeed.accept(0.0099, 0.0101, now + 60_000));      // held a minute: accepted
        assertEquals(0.01, MexcFeed.mid(), 1e-12);
        // review 30 Oct: a 5 % push is no longer taken on one read either
        assertNotNull(MexcFeed.accept(0.01045, 0.01055, now + 61_000));
        assertEquals(0.01, MexcFeed.mid(), 1e-12);
        assertNull(MexcFeed.accept(0.01009, 0.01011, now + 62_000));    // within 3 %: taken at once
        assertEquals(0.0101, MexcFeed.mid(), 1e-12);
    }

    @Test public void wideningRampsFromOneToTen() {
        assertEquals(1.0, MexcFeed.wideningFactor(MexcFeed.FRESH_MS), 0);
        assertEquals(10.0, MexcFeed.wideningFactor(MexcFeed.STALE_WIDEN_FULL_MS), 1e-9);
        assertEquals(5.5, MexcFeed.wideningFactor((MexcFeed.FRESH_MS + MexcFeed.STALE_WIDEN_FULL_MS) / 2), 1e-9);
        assertTrue(MexcFeed.unusable());   // never fetched → a pegged ladder must withdraw
    }
}
