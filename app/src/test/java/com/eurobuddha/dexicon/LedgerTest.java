package com.eurobuddha.dexicon;

import static org.junit.Assert.*;

import com.eurobuddha.atomix.swap.SwapDb;
import com.eurobuddha.dexicon.doors.DoorForwards;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TimeZone;

/** The ledger lines ACTIVITY / HISTORY / ATOMIX paint: headline, steps (times only when recorded), proof (each id once, in full). */
public class LedgerTest {
    static final TimeZone UTC = TimeZone.getTimeZone("UTC");
    static final long TS = 1_793_000_000_000L;   // 2026-10-26 07:33:20 UTC
    static final String TX = "0x" + "0A".repeat(32), TXN = "0x" + "79".repeat(32), OUT1 = "0x" + "99".repeat(32), OUT2 = "0x" + "F4".repeat(32),
            IN1 = "0x" + "1E".repeat(32), TAKER = "0x" + "07".repeat(64), PAYTO = "0x" + "AA".repeat(32), ORDER = "0xc51c9e5026803350";

    static String steps(Ledger.Item it) { return it.stepsKey(); }
    static String proof(Ledger.Item it) { return it.proof.toString() + it.groups; }
    static int count(String hay, String needle) { int n = 0, i = 0; while ((i = hay.indexOf(needle, i)) >= 0) { n++; i += needle.length(); } return n; }

    static JSONObject slow(String stage) throws Exception {
        return new JSONObject().put("id", "slow_1").put("route", "POOL").put("role", "taker").put("side", "buy").put("amount", "2728.1472894674")
                .put("price", "0.003792827565").put("mxusd", "10.34739224").put("ts", TS).put("stage", stage);
    }
    static JSONObject fastTaker(String stage) throws Exception {
        return new JSONObject().put("id", "fast_1").put("route", "FAST").put("role", "taker").put("side", "sell").put("amount", "455.78")
                .put("price", "0.003675").put("mxusd", "1.6749915").put("orderId", ORDER).put("counterparty", PAYTO).put("ins", new JSONArray().put(IN1))
                .put("ts", TS).put("stage", stage);
    }

    // ------------------------------------------------------------------ ActivityLog rows, every stage

    @Test public void slowBuildingThenPostedThenBlockThenConfirmed() throws Exception {
        JSONObject a = slow(ActivityLog.BUILDING).put("reason", "Selecting wallet coins…");
        Ledger.Item it = Ledger.activity(a, 0, TS + 12_000, UTC);
        assertTrue(it.live);
        assertEquals("act_slow_1", it.id);
        assertEquals("Buy MINIMA", it.title);
        assertEquals("2728.1472894674 MINIMA @ 0.003792827565", it.amount);
        assertEquals("BUILDING", it.chip);
        assertEquals("26 Oct 07:33 · pool · paid 10.34739224 MxUSD · 12s elapsed", it.sub);
        assertEquals("[WAIT 07:33:20 Selecting wallet coins…]", steps(it));

        a.put("stage", ActivityLog.POSTED).put("reason", "").put("txpowid", TX).put("txnid", TXN).put("postBlock", 100L).put("tip", 100L)
                .put("postedMs", TS + 4_000).put("outs", new JSONArray().put(OUT1).put(OUT2)).put("ins", new JSONArray().put(IN1));
        it = Ledger.activity(a, 102, TS + 60_000, UTC);
        assertEquals("[OK 07:33:20 Built and signed on this node, OK 07:33:24 Posted to the network at block 100, "
                + "WAIT  In the node's mempool, waiting for a block (2 blocks since posting)]", steps(it));
        assertEquals("POSTED", it.chip);

        a.put("block", 101L);
        it = Ledger.activity(a, 102, TS, UTC);
        assertTrue(steps(it), steps(it).endsWith("OK  In block 101, WAIT  Confirming — 1 confirmation of 3]"));
        assertEquals("IN BLOCK", it.chip);

        a.put("settledMs", TS + 300_000);
        it = Ledger.activity(a, 104, TS, UTC);
        assertFalse(it.live);
        assertEquals("Bought MINIMA", it.title);
        assertEquals("↓", it.icon);
        assertEquals("CONFIRMED", it.chip);
        assertEquals(Ledger.Tone.OK, it.chipTone);
        assertTrue(steps(it), steps(it).endsWith("OK  In block 101, OK 07:38:20 Confirmed — 3 confirmations]"));
        assertEquals("26 Oct 07:33 · pool · paid 10.34739224 MxUSD", it.sub);
        assertEquals("[Transaction (txpow)=" + TX + ", Transaction id=" + TXN + "][Coins you received (2)[Received 1 of 2=" + OUT1
                + ", Received 2 of 2=" + OUT2 + "], Coins spent (1)[Spent 1 of 1=" + IN1 + "]]", proof(it));
    }

    @Test public void fastTakerSentCosignedDone() throws Exception {
        JSONObject a = fastTaker(ActivityLog.BUILDING);
        assertEquals("[WAIT 07:33:20 Building and signing your side…]", steps(Ledger.activity(a, 0, TS, UTC)));
        a.put("stage", ActivityLog.SENT).put("relays", 3).put("sentMs", TS + 2_000);
        Ledger.Item it = Ledger.activity(a, 0, TS + 9_000, UTC);
        assertEquals("[OK 07:33:20 Built and signed your side, WAIT 07:33:22 Take sent to 3 relays — waiting for the maker to co-sign (7s)]", steps(it));
        a.put("stage", ActivityLog.COSIGNED).put("cosignMs", 8_600L).put("txpowid", TX).put("outs", new JSONArray().put(OUT1));
        it = Ledger.activity(a, 0, TS, UTC);
        assertEquals("CO-SIGNED", it.chip);
        assertTrue(steps(it), steps(it).contains("OK 07:33:22 Take sent to 3 relays, OK 07:33:30 The maker co-signed in 8.6s, WAIT  In the node's mempool, waiting for a block]"));
        a.put("block", 50L);
        it = Ledger.activity(a, 53, TS, UTC);
        assertEquals("DONE", it.chip);
        assertEquals("Sold MINIMA", it.title);
        assertTrue(steps(it), steps(it).endsWith("OK  Done — 3 confirmations]"));
        assertTrue(proof(it), proof(it).contains("Order=" + ORDER + ", Maker's pay-to address=" + PAYTO));
        assertEquals("26 Oct 07:33 · fast book · received 1.6749915 MxUSD", it.sub);
    }

    @Test public void makerRowNamesTheTakerOnce() throws Exception {
        JSONObject a = new JSONObject().put("id", "maker_" + TX).put("route", "FAST").put("role", "maker").put("side", "sell").put("amount", "455.78")
                .put("price", "0.003675").put("mxusd", "1.6749915").put("orderId", ORDER).put("counterparty", TAKER).put("txpowid", TX)
                .put("outs", new JSONArray().put(OUT1)).put("ins", new JSONArray().put(IN1).put(OUT2)).put("ts", TS).put("cosignMs", 8_900L).put("stage", ActivityLog.COSIGNED);
        Ledger.Item it = Ledger.activity(a, 0, TS, UTC);
        assertEquals("Sell MINIMA · you are the maker", it.title);
        assertTrue(steps(it), steps(it).startsWith("[OK 07:33:20 A taker took your order, OK 07:33:28 Your node co-signed in 8.9s"));
        assertEquals(1, count(proof(it) + steps(it) + it.title + it.sub + it.amount, TAKER));
        assertTrue(proof(it).contains("Your order=" + ORDER + ", Taker ID=" + TAKER));
        a.put("block", 10L);
        assertEquals("Sold MINIMA · you were the maker", Ledger.activity(a, 20, TS, UTC).title);
    }

    @Test public void everyFailureStageIsAWarningStep() throws Exception {
        JSONObject f = fastTaker(ActivityLog.FAILED).put("reason", "no relay accepted the take: timeout");
        Ledger.Item it = Ledger.activity(f, 0, TS, UTC);
        assertFalse(it.live);
        assertEquals("!", it.icon);
        assertEquals(Ledger.Tone.BAD, it.chipTone);
        assertEquals("FAILED", it.chip);
        assertTrue(steps(it), steps(it).endsWith("WARN  Failed — no relay accepted the take: timeout]"));

        JSONObject r = fastTaker(ActivityLog.REFUSED).put("reason", "order filled").put("sentMs", TS + 1_000).put("relays", 1).put("cosignMs", 2_000L);
        assertTrue(steps(Ledger.activity(r, 0, TS, UTC)), steps(Ledger.activity(r, 0, TS, UTC)).endsWith("OK 07:33:21 Take sent to 1 relay, WARN 07:33:23 The maker refused — order filled]"));

        JSONObject d = slow(ActivityLog.DROPPED).put("reason", "an input was spent by another transaction").put("txpowid", TX).put("was", ActivityLog.POSTED);
        assertTrue(steps(Ledger.activity(d, 0, TS, UTC)), steps(Ledger.activity(d, 0, TS, UTC)).endsWith("WARN  Dropped — an input was spent by another transaction]"));

        JSONObject l = fastTaker(ActivityLog.LOST).put("reason", "no answer from the maker within 10 minutes");
        assertEquals("LOST", Ledger.activity(l, 0, TS, UTC).chip);
        assertTrue(steps(Ledger.activity(l, 0, TS, UTC)).endsWith("WARN  Lost — no answer from the maker within 10 minutes]"));
    }

    // ------------------------------------------------------------------ door swaps, every state

    static final String HASH = "ad0471fa26cf64d37cfcafc98e2cf3710b3bef638297e4dcc259e99324934336", LOCK = "0x" + "18".repeat(32),
            CONTRACT = "0x" + "8e".repeat(32), MAKERKEY = "0x" + "17".repeat(32), MAKERID = "0x" + "6c".repeat(64), CLAIM = "0x" + "00".repeat(32),
            REFUND = "0x" + "84".repeat(32), ETH = "0xe532293d331ec712c053e13db58035babd7cce23", FWD_TO = "MxG0829Y2QW3FC679PEUQDG2YY4ASHHY1MUYACAMVF9VQS3EANZCP8P4Q7NGS52",
            FWD_TX = "0x" + "fa".repeat(32);
    static final long TL = 1793013743L;

    static SwapDb.Swap entry(String status) {
        SwapDb.Swap s = new SwapDb.Swap();
        s.hash = HASH; s.role = "INITIATOR"; s.direction = "ERC20_TO_MINIMA"; s.status = status;
        s.sellAmount = "20.81"; s.sellToken = "USDT"; s.buyAmount = "20.60396"; s.buyToken = "mxUSDT";
        s.myTimelock = TL; s.myLegIsMinima = false; s.contractId = CONTRACT; s.counterparty = MAKERKEY; s.created = TS; s.updated = TS + 600_000;
        return s;
    }
    static SwapDb.Event ev(String event, String token, String note, long date) { SwapDb.Event e = new SwapDb.Event(); e.event = event; e.token = token; e.note = note; e.date = date; return e; }
    /** given oldest first, returned newest first like SwapDb.getEvents */
    static List<SwapDb.Event> evs(SwapDb.Event... e) { List<SwapDb.Event> l = new ArrayList<>(Arrays.asList(e)); java.util.Collections.reverse(l); return l; }
    static List<SwapDb.Event> base() { return evs(ev(SwapDb.EV_STARTED, "ETH:0xdac1", LOCK, TS), ev(SwapDb.EV_TAKE_SENT, "", MAKERID, TS + 33_000)); }

    @Test public void liveSwapRollsItsStepsAndTicks() {
        Ledger.Item it = Ledger.swap(entry(SwapDb.ST_STARTED), base(), null, TL - 7000, 0, ETH, null, UTC);
        assertTrue(it.live); assertTrue(it.ticking); assertTrue(it.usd);
        assertEquals("swap_" + HASH, it.id);
        assertEquals("AtomiX entry", it.title);
        assertEquals("20.81 USDT → 20.60396 MxUSD", it.amount);
        assertEquals("LIVE", it.chip);
        assertEquals("26 Oct 07:33 · waiting for the maker", it.sub);
        assertEquals("[OK 07:33:20 Your 20.81 USDT is locked on Ethereum, OK 07:33:53 The maker has been told, "
                + "WAIT  Waiting for the maker to lock your 20.60396 MxUSD — it has until 10:22, "
                + "NOTE  If the maker doesn't lock in time, your 20.81 USDT comes back by itself from 11:22 — app open or not.]", steps(it));
        assertEquals("REFUND NOW · opens in 1:56:40", it.refundLabel);
        assertEquals("[Swap=" + HASH + ", Your USDT lock (Ethereum tx)=" + LOCK + ", Ethereum contract=" + CONTRACT + ", Maker key=" + MAKERKEY
                + ", Maker ID (who was told)=" + MAKERID + "][]", proof(it));
    }

    @Test public void makerNoticeIsAWarningStep() {
        List<SwapDb.Event> e = base(); e.add(0, ev(SwapDb.EV_MAKER_NOTICE, "", "declined|too many coins", TS + 60_000));
        Ledger.Item it = Ledger.swap(entry(SwapDb.ST_STARTED), e, null, TL - 7000, 0, ETH, null, UTC);
        assertTrue(steps(it), steps(it).contains("WARN 07:34:20 The maker declined: too many coins"));
        assertTrue(steps(it), steps(it).contains("NOTE  Your lock refunds automatically at 11:22, app open or not."));
    }

    @Test public void lockedIsClaiming() {
        Ledger.Item it = Ledger.swap(entry(SwapDb.ST_LOCKED), base(), null, TL - 7000, 0, ETH, null, UTC);
        assertTrue(it.live); assertFalse(it.ticking);
        assertNull(it.refundLabel);
        assertEquals("26 Oct 07:33 · claiming", it.sub);
        assertTrue(steps(it), steps(it).endsWith("OK  The maker locked 20.60396 MxUSD, WAIT  Claiming your 20.60396 MxUSD now]"));
    }

    @Test public void completeFoldsWithClaimAndEveryIdOnce() {
        List<SwapDb.Event> e = base(); e.add(0, ev(SwapDb.EV_MINIMA_CLAIM_SUBMITTED, "0x7D39", CLAIM, TS + 300_000));
        Ledger.Item it = Ledger.swap(entry(SwapDb.ST_COMPLETE), e, null, TL, 0, ETH, null, UTC);
        assertFalse(it.live);
        assertEquals("DONE", it.chip); assertEquals("✓", it.icon);
        assertEquals("26 Oct 07:33 · in your wallet", it.sub);
        assertTrue(steps(it), steps(it).endsWith("OK 07:38:20 Claimed into your wallet, OK 07:43:20 Done — 20.60396 MxUSD is in your wallet (less the 0.0001 MxUSD notify output)]"));
        String p = proof(it);
        assertTrue(p, p.contains("Your Minima claim (txpow)=" + CLAIM));
        for (String id : new String[]{HASH, LOCK, CONTRACT, MAKERKEY, MAKERID, CLAIM}) assertEquals(id, 1, count(p, id));

        Ledger.Item h = Ledger.asHistory(it, entry(SwapDb.ST_COMPLETE), UTC);
        assertEquals("Swapped", h.title); assertEquals("⇄", h.icon); assertEquals("", h.chip);
        assertEquals("AtomiX entry · in your wallet", h.sub);
        assertEquals("07:33", h.time);
        assertEquals("You paid", h.facts.get(0)[0]); assertEquals("20.81 USDT (Ethereum)", h.facts.get(0)[1]);
        assertEquals("20.60396 MxUSD, less the 0.0001 notify output", h.facts.get(1)[1]);
        assertEquals("26 Oct 2026 07:43:20", h.facts.get(2)[1]);
    }

    @Test public void forwardStatesStayLiveUntilResolved() throws Exception {
        JSONObject f = new JSONObject().put("to", FWD_TO).put("status", DoorForwards.PENDING).put("err", "");
        Ledger.Item it = Ledger.swap(entry(SwapDb.ST_COMPLETE), base(), f, TL, 0, ETH, null, UTC);
        assertTrue(it.live); assertEquals("FORWARDING", it.chip); assertFalse(it.retryForward);
        assertTrue(steps(it).endsWith("WAIT  Forwarding MxUSD to the address you named…]"));
        assertTrue(proof(it).contains("Forward to=" + FWD_TO));
        assertFalse("the address is in the proof, not the step", steps(it).contains(FWD_TO));

        f.put("status", DoorForwards.FAILED).put("err", "insufficient funds");
        it = Ledger.swap(entry(SwapDb.ST_COMPLETE), base(), f, TL, 0, ETH, null, UTC);
        assertTrue(it.live); assertTrue(it.retryForward); assertEquals("FORWARD FAILED", it.chip); assertEquals(Ledger.Tone.WARN, it.chipTone);
        assertTrue(steps(it), steps(it).endsWith("WARN  The forward failed: insufficient funds — tap Retry forward.]"));

        f.put("status", DoorForwards.UNCERTAIN).put("err", "timeout");
        it = Ledger.swap(entry(SwapDb.ST_COMPLETE), base(), f, TL, 0, ETH, null, UTC);
        assertTrue(it.live); assertTrue(it.retryForward); assertEquals("FORWARD UNCONFIRMED", it.chip);
        assertTrue(steps(it).contains("The forward is unconfirmed: the node lost the reply to the send (timeout)"));

        f.put("status", DoorForwards.SENT).put("tx", FWD_TX).put("amount", "20.60386");
        it = Ledger.swap(entry(SwapDb.ST_COMPLETE), base(), f, TL, 0, ETH, null, UTC);
        assertFalse(it.live); assertEquals("DONE", it.chip); assertEquals("26 Oct 07:33 · forwarded", it.sub);
        assertTrue(steps(it).endsWith("OK  Forwarded 20.60386 MxUSD to the address you named]"));
        assertTrue(proof(it).contains("Forward to=" + FWD_TO + ", Forward tx=" + FWD_TX));
    }

    @Test public void refundedNamesTheRefundTxAndWhereItWent() {
        List<SwapDb.Event> e = base(); e.add(0, ev(SwapDb.EV_REFUND_SENT, "ETH", REFUND, TS + 13_500_000));
        Ledger.Item it = Ledger.swap(entry(SwapDb.ST_REFUNDED), e, null, TL + 600, 0, ETH, null, UTC);
        assertFalse(it.live);
        assertEquals("REFUNDED", it.chip); assertEquals("↺", it.icon); assertEquals(Ledger.Tone.BAD, it.chipTone);
        assertEquals("26 Oct 07:33 · maker didn't lock · USDT back 11:18", it.sub);
        assertTrue(steps(it), steps(it).endsWith("OK 11:18:20 Refund sent, OK  20.81 USDT is back in this node’s Ethereum wallet]"));
        assertTrue(proof(it), proof(it).contains("Refund (Ethereum tx)=" + REFUND + ", Refunded to=" + ETH));
        Ledger.Item h = Ledger.asHistory(it, entry(SwapDb.ST_REFUNDED), UTC);
        assertEquals("Refunded", h.title); assertEquals("20.81 USDT", h.amount); assertEquals(Ledger.Tone.DIM, h.tone);
        assertEquals("REFUNDED", h.chip);
        assertEquals("your 20.81 USDT came back", h.facts.get(0)[1]);
    }

    @Test public void errorIsAWarning() {
        Ledger.Item it = Ledger.swap(entry(SwapDb.ST_ERROR), base(), null, TL, 0, ETH, null, UTC);
        assertFalse(it.live); assertEquals("FAILED", it.chip); assertEquals("!", it.icon);
        assertTrue(steps(it).endsWith("WARN 07:43:20 Failed — see the recorded transactions; the counterparty took the leg you paid or refunded theirs.]"));
    }

    // ------------------------------------------------------------------ fills

    @Test public void poolFillWithItsActivityRowShowsSpentCoins() throws Exception {
        JSONObject f = new JSONObject().put("role", "taker").put("orderId", "").put("side", "buy").put("price", "0.003792827565").put("filled", "2728.1472894674")
                .put("gave", "10.34739224").put("got", "2728.1472894674").put("txpowid", TX).put("coins", new JSONArray().put(OUT1)).put("counterparty", "POOL")
                .put("ts", TS / 1000).put("status", "done").put("block", "2382347");
        JSONObject act = slow(ActivityLog.POSTED).put("txpowid", TX).put("txnid", TXN).put("ins", new JSONArray().put(IN1));
        Ledger.Item it = Ledger.fill(f, act, UTC);
        assertEquals("Bought", it.title); assertEquals("2728.1472894674 MINIMA", it.amount);
        assertEquals("@ 0.003792827565 · pool", it.sub); assertEquals("07:33", it.time); assertEquals("", it.chip);
        assertEquals("You paid=10.34739224 MxUSD|Route=Pool · you took the price|Settled=block 2382347|When=26 Oct 2026 07:33:20|", facts(it));
        assertEquals("[Transaction (txpow)=" + TX + ", Transaction id=" + TXN + "][Coins you received (1)[Received 1 of 1=" + OUT1 + "], Coins spent (1)[Spent 1 of 1=" + IN1 + "]]", proof(it));
        assertEquals("without its ACTIVITY row the fill still stands", "[Transaction (txpow)=" + TX + "][Coins you received (1)[Received 1 of 1=" + OUT1 + "]]",
                proof(Ledger.fill(f, null, UTC)));
    }

    @Test public void makerAndFastTakerFills() throws Exception {
        JSONObject m = new JSONObject().put("role", "maker").put("orderId", ORDER).put("side", "sell").put("price", "0.003675").put("filled", "455.78")
                .put("received", "1.6749915").put("paid", "455.78").put("txpowid", TX).put("coins", new JSONArray().put(OUT1)).put("counterparty", TAKER)
                .put("ts", TS / 1000).put("status", "settling");
        Ledger.Item it = Ledger.fill(m, new JSONObject().put("cosignMs", 8_600L), UTC);
        assertEquals("Sold", it.title); assertEquals("@ 0.003675 · you were the maker", it.sub);
        assertEquals("SETTLING", it.chip); assertEquals(Ledger.Tone.LIVE, it.chipTone);
        assertEquals("You got=1.6749915 MxUSD|Route=Fast book · a taker took your order|Co-signed=in 8.6s|Settled=waiting for a block|When=26 Oct 2026 07:33:20|", facts(it));
        assertTrue(proof(it).contains("Your order=" + ORDER + ", Taker ID=" + TAKER));

        JSONObject t = new JSONObject().put("role", "taker").put("orderId", ORDER).put("side", "buy").put("price", "0.003675").put("filled", "100")
                .put("gave", "0.3675").put("got", "100").put("txpowid", TX).put("counterparty", PAYTO).put("ts", TS / 1000).put("status", "lost");
        it = Ledger.fill(t, null, UTC);
        assertEquals("@ 0.003675 · fast book", it.sub);
        assertEquals("LOST", it.chip); assertEquals(Ledger.Tone.BAD, it.chipTone);
        assertTrue(facts(it), facts(it).contains("You paid=0.3675 MxUSD|Route=Fast book · you took a maker's order|Settled=not in a block after 2h — coins were not spent"));
        assertTrue(proof(it).contains("Order=" + ORDER + ", Maker's pay-to address=" + PAYTO));
    }
    static String facts(Ledger.Item it) { StringBuilder b = new StringBuilder(); for (String[] f : it.facts) b.append(f[0]).append('=').append(f[1]).append('|'); return b.toString(); }

    // ------------------------------------------------------------------ days, arrangement, identity

    @Test public void dayLabelsAndArrangement() {
        assertEquals("Today · 26 Oct", Ledger.dayLabel(TS, TS + 3_600_000, UTC));
        assertEquals("24 Oct", Ledger.dayLabel(TS - 2 * 86_400_000L, TS, UTC));
        assertEquals("26 Oct 2025", Ledger.dayLabel(TS - 365 * 86_400_000L, TS, UTC));
        Ledger.Item live = new Ledger.Item(), today = new Ledger.Item(), old = new Ledger.Item();
        live.id = "l"; live.live = true; live.sortMs = TS - 5_000;
        today.id = "t"; today.sortMs = TS;
        old.id = "o"; old.sortMs = TS - 2 * 86_400_000L;
        List<Ledger.Item> a = Ledger.arrange(Arrays.asList(old, today, live), true, TS, UTC);
        StringBuilder b = new StringBuilder(); for (Ledger.Item i : a) b.append(i.header ? "[" + i.title + "]" : i.id).append(' ');
        assertEquals("[Now] l [Today · 26 Oct] t [24 Oct] o ", b.toString());
        a = Ledger.arrange(Arrays.asList(old, today, live), false, TS, UTC);
        b.setLength(0); for (Ledger.Item i : a) b.append(i.header ? "[" + i.title + "]" : i.id).append(' ');
        assertEquals("[Today · 26 Oct] t l [24 Oct] o ", b.toString());
    }

    @Test public void publicIdSplitsIntoBoxAndSignKeys() {
        String box = "ab".repeat(32), sign = "CD".repeat(32);
        String[] k = Ledger.keysOf("0x" + box + sign);
        assertEquals("0x" + box, k[0]);
        assertEquals("0x" + sign, k[1]);
        assertEquals(66, k[0].length()); assertEquals(66, k[1].length());
        assertNull(Ledger.keysOf("0x" + box));
        assertNull(Ledger.keysOf(null));
    }
}
