package com.eurobuddha.dexicon.doors;

import static org.junit.Assert.*;

import com.eurobuddha.atomix.swap.SwapDb;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;

/** The door swap status card, derived from swap + events + clock (live 2026-10-26: "started" for two hours, no why). */
public class DoorStatusTest {
    static final TimeZone UTC = TimeZone.getTimeZone("UTC");
    static final String HASH = "0x" + "fb".repeat(32), LOCK_TX = "0x" + "a1".repeat(32), REFUND_TX = "0x" + "c3".repeat(32);
    static final String MAKER = "0x" + "77".repeat(64), ETH = "0x" + "e5".repeat(20);
    static final long T = 1793013743L;               // my ETH lock's timelock (unix s): respond-by T-3600, refund at T

    static SwapDb.Swap usdtIn(String status) {
        SwapDb.Swap s = new SwapDb.Swap();
        s.hash = HASH; s.role = "INITIATOR"; s.direction = "ERC20_TO_MINIMA"; s.status = status;
        s.sellAmount = "20.81"; s.sellToken = "USDT"; s.buyAmount = "20.8"; s.buyToken = "mxUSDT";
        s.myTimelock = T; s.myLegIsMinima = false;
        return s;
    }
    static List<SwapDb.Event> evs(String... kv) {   // given oldest first, returned newest first like SwapDb.getEvents
        List<SwapDb.Event> out = new ArrayList<>();
        for (int i = 0; i < kv.length; i += 2) { SwapDb.Event e = new SwapDb.Event(); e.event = kv[i]; e.note = kv[i + 1]; out.add(0, e); }
        return out;
    }
    static String all(DoorStatus.Card c) { return c.linesKey(); }

    @Test public void waitingShowsPlainStepsWithTimesAndTheDeadline() {
        List<SwapDb.Event> e = evs(SwapDb.EV_STARTED, LOCK_TX, SwapDb.EV_TAKE_SENT, MAKER);
        e.get(1).date = 1_793_000_000_000L; e.get(0).date = 1_793_000_033_000L;   // lock, then the TAKE (newest first)
        DoorStatus.Card c = DoorStatus.build(usdtIn(SwapDb.ST_STARTED), e, T - 7000, 0, null, UTC);
        String a = all(c);
        assertTrue(a, a.contains("DONE Your 20.81 USDT is locked on Ethereum"));
        assertTrue(a, a.contains("DONE The maker has been told"));
        assertTrue(a, a.contains("WAIT Waiting for the maker to lock your 20.8 MxUSD — it has until 10:22"));   // T-3600 = 10:22:23 UTC
        assertTrue(a, a.contains("INFO If the maker doesn't lock in time, your 20.81 USDT comes back by itself from 11:22 — app open or not."));
        assertFalse("ids live in the proof list, not the steps", a.contains(LOCK_TX) || a.contains(MAKER));
        assertEquals("07:33:20", c.lines.get(0).time);
        assertEquals("07:33:53", c.lines.get(1).time);
        assertEquals("never invent a time", "", c.lines.get(2).time);
        assertFalse(c.refundEnabled);
        assertEquals("REFUND NOW · opens in 1:56:40", c.refundLabel);
    }

    @Test public void makerNoticeIsShownLatestFirst() {
        DoorStatus.Card c = DoorStatus.build(usdtIn(SwapDb.ST_STARTED), evs(SwapDb.EV_STARTED, LOCK_TX, SwapDb.EV_TAKE_SENT, MAKER,
                SwapDb.EV_MAKER_NOTICE, "retrying|Maker wallet: too many coins", SwapDb.EV_MAKER_NOTICE, "declined|Maker declined: too many coins"), T - 7000, 0, null, UTC);
        String a = all(c);
        assertTrue(a, a.contains("NOTICE The maker declined: Maker declined: too many coins"));
        assertFalse(a, a.contains("retrying"));
        assertTrue(a, a.contains("INFO Your lock refunds automatically at 11:22, app open or not."));
        DoorStatus.Card r = DoorStatus.build(usdtIn(SwapDb.ST_STARTED), evs(SwapDb.EV_STARTED, LOCK_TX, SwapDb.EV_TAKE_SENT, MAKER,
                SwapDb.EV_MAKER_NOTICE, "retrying|Maker wallet: too many coins"), T - 7000, 0, null, UTC);
        assertTrue(all(r), all(r).contains("NOTICE The maker is retrying: Maker wallet: too many coins"));
    }

    @Test public void timedOutSaysWhenTheAutomaticRefundOpens() {
        DoorStatus.Card c = DoorStatus.build(usdtIn(SwapDb.ST_STARTED), evs(SwapDb.EV_STARTED, LOCK_TX, SwapDb.EV_TIMEOUT, ""), T - 600, 0, null, UTC);
        assertTrue(all(c), all(c).contains("WAIT Waiting for the maker to lock your 20.8 MxUSD\n"));
        assertTrue(all(c), all(c).contains("WARN The maker didn't lock in time — your refund opens at 11:22 (in 10 min), automatically, app open or not."));
        assertFalse(c.refundEnabled);
        assertEquals("REFUND NOW · opens in 10:00", c.refundLabel);
    }

    @Test public void refundButtonEnablesAtTheTimelockAndShowsTheAnswer() {
        DoorStatus.Card c = DoorStatus.build(usdtIn(SwapDb.ST_STARTED), evs(SwapDb.EV_STARTED, LOCK_TX), T, 0, "Refunding now — you'll get a notification with the transaction id.", UTC);
        assertTrue(c.refundEnabled);
        assertEquals("REFUND NOW", c.refundLabel);
        assertTrue(all(c), all(c).contains("The refund window is open since 11:22"));
        assertTrue(all(c), all(c).contains("INFO Refunding now"));
    }

    @Test public void refundSentThenRefunded() {
        DoorStatus.Card sent = DoorStatus.build(usdtIn(SwapDb.ST_STARTED), evs(SwapDb.EV_STARTED, LOCK_TX, SwapDb.EV_REFUND_SENT, REFUND_TX), T + 60, 0, null, UTC);
        assertTrue(all(sent), all(sent).contains("DONE Refund sent"));
        DoorStatus.Card done = DoorStatus.build(usdtIn(SwapDb.ST_REFUNDED), evs(SwapDb.EV_STARTED, LOCK_TX, SwapDb.EV_REFUND_SENT, REFUND_TX), T + 600, 0, "stale answer", UTC);
        assertTrue(all(done), all(done).contains("DONE Refund sent"));
        assertTrue(all(done), all(done).contains("OK 20.81 USDT is back in this node’s Ethereum wallet"));
        assertNull("no button once refunded", done.refundLabel);
        assertFalse(all(done).contains("stale answer"));
    }

    @Test public void makerLockedHidesDeadlinesAndNotices() {
        DoorStatus.Card c = DoorStatus.build(usdtIn(SwapDb.ST_LOCKED), evs(SwapDb.EV_STARTED, LOCK_TX, SwapDb.EV_TAKE_SENT, MAKER, SwapDb.EV_MAKER_NOTICE, "retrying|busy"), T - 7000, 0, null, UTC);
        assertTrue(all(c).contains("DONE The maker locked 20.8 MxUSD"));
        assertFalse(all(c).contains("busy"));
        assertNull(c.refundLabel);
    }

    @Test public void minimaFirstLockUsesBlocks() {
        SwapDb.Swap s = usdtIn(SwapDb.ST_STARTED);
        s.direction = "MINIMA_TO_ERC20"; s.sellToken = "minima"; s.sellAmount = "100"; s.buyToken = "USDT"; s.myLegIsMinima = true; s.myTimelock = 1144;
        DoorStatus.Card unknown = DoorStatus.build(s, evs(SwapDb.EV_STARTED, LOCK_TX), T, 0, null, UTC);
        assertTrue(all(unknown), all(unknown).contains("DONE Your 100 MINIMA is locked on Minima"));
        assertTrue(all(unknown), all(unknown).contains("it has until block 1072"));
        assertFalse(all(unknown).contains("maker has been told"));   // a Minima-first lock is found on chain, not announced
        assertEquals("REFUND NOW · opens at block 1144", unknown.refundLabel);
        DoorStatus.Card late = DoorStatus.build(s, evs(SwapDb.EV_STARTED, LOCK_TX), 0, 1100, null, UTC);
        assertTrue(all(late), all(late).contains("refund opens at block 1144 (~00:36) (in 37 min)"));
        assertEquals("REFUND NOW · opens in ~36:40", late.refundLabel);
        s.status = SwapDb.ST_REFUNDED;
        assertTrue(all(DoorStatus.build(s, evs(), 0, 1200, null, UTC)).contains("100 MINIMA is back in this node’s Minima wallet"));
    }

    @Test public void notMineNoCard() {
        SwapDb.Swap s = usdtIn(SwapDb.ST_STARTED); s.role = "RESPONDER";
        assertTrue(DoorStatus.build(s, evs(), T, 0, null, UTC).lines.isEmpty());
    }
}
