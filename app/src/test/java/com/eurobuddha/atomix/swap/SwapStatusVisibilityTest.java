// VENDORED VERBATIM from apks/atomix 0.1.84 app/src/test/java/com/eurobuddha/atomix/swap/SwapStatusVisibilityTest.java by scripts/vendor-atomix.sh — do not edit here; re-vendor.
package com.eurobuddha.atomix.swap;

import android.os.Handler;
import com.eurobuddha.atomix.eth.*;
import com.eurobuddha.comms.NodeApi;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Live 2026-10-26: a maker declined a buy in silence and the taker saw "started" for two hours. */
public class SwapStatusVisibilityTest {
    private static final String HASH = "fbfdfda239b6ccbfeb88ebe37aef4334cb08a0527a066d65c5bc238a292d4b31";
    private static final String MAKER = "0xMAKERCOMMS", TAKER = "0xTAKERCOMMS";
    private final List<SwapDb.Event> events = new ArrayList<>();
    private final List<String[]> sent = new ArrayList<>();
    private SwapDb db; private SwapEngine.Notifier notifier; private SwapEngine engine; private EthRpc rpc;
    private SwapDb.Swap swap;

    @Before public void setup() throws Exception {
        Handler ui = mock(Handler.class);
        when(ui.post(any())).thenAnswer(inv -> { ((Runnable) inv.getArgument(0)).run(); return true; });
        db = mock(SwapDb.class);
        when(db.getEvents(anyString())).thenAnswer(inv -> new ArrayList<>(events));
        when(db.hasEvent(anyString(), anyString())).thenAnswer(inv -> events.stream().anyMatch(e -> e.event.equals(inv.getArgument(1))));
        doAnswer(inv -> { SwapDb.Event e = new SwapDb.Event(); e.event = inv.getArgument(1); e.note = inv.getArgument(4); events.add(0, e); return null; })
                .when(db).logEvent(anyString(), anyString(), anyString(), anyString(), anyString());
        swap = new SwapDb.Swap(); swap.hash = HASH; swap.role = "INITIATOR"; swap.direction = "ERC20_TO_MINIMA";
        swap.status = SwapDb.ST_STARTED; swap.myTimelock = 1793013743L; swap.myLegIsMinima = false;
        swap.sellAmount = "20.81"; swap.sellToken = "USDT";
        when(db.getSwap(anyString())).thenAnswer(inv -> swap);
        notifier = mock(SwapEngine.Notifier.class);
        engine = new SwapEngine(mock(NodeApi.class), mock(MinimaHtlc.class), db, mock(EthWallet.class), ui, notifier);
        rpc = mock(EthRpc.class);
        engine.setNetwork(rpc, EthNet.MAINNET);
        engine.setNoticeSink((to, h, st, why, m) -> sent.add(new String[]{to, h, st, why}));
    }
    @After public void done() { engine.shutdown(); }

    @Test public void makerSendsEachDeclineReasonToTheTakerOnce() {
        engine.addIncomingHashlock(HASH, TAKER);
        engine.recordDecline(HASH, "Maker wallet: TOO_MANY_COINS", false);
        engine.recordDecline(HASH, "Maker wallet: TOO_MANY_COINS", false);   // same reason: no second send
        assertEquals(1, sent.size());
        assertArrayEquals(new String[]{TAKER, HASH, SwapEngine.NOTICE_RETRYING, "Maker wallet: TOO_MANY_COINS"}, sent.get(0));
        engine.recordDecline(HASH, "b", true); engine.recordDecline(HASH, "c", true); engine.recordDecline(HASH, "d", true);
        assertEquals("bounded: each notice is a signed send", 3, sent.size());
        assertEquals(4, events.stream().filter(e -> SwapDb.EV_DECLINED.equals(e.event)).count());   // every distinct reason still recorded
    }

    @Test public void declineWithoutAKnownTakerIsStillRecorded() {
        engine.recordDecline(HASH, "why", true);
        assertTrue(sent.isEmpty());
        assertTrue(events.stream().anyMatch(e -> SwapDb.EV_DECLINED.equals(e.event) && "why".equals(e.note)));
    }

    @Test public void takerAcceptsANoticeOnlyFromTheMakerItNotified() {
        assertFalse("no handshake recorded yet", engine.onMakerNotice(HASH, MAKER, "declined", "x"));
        engine.noteTakeSent(HASH, MAKER);
        assertFalse("a stranger cannot speak for the maker", engine.onMakerNotice(HASH, "0xSTRANGER", "declined", "x"));
        assertTrue(engine.onMakerNotice(HASH, MAKER, "retrying", "Maker wallet: TOO_MANY_COINS"));
        assertFalse("duplicate", engine.onMakerNotice(HASH, MAKER, "retrying", "Maker wallet: TOO_MANY_COINS"));
        verify(notifier).notify(eq("Maker is retrying your swap"), contains("TOO_MANY_COINS"));
        swap.role = "RESPONDER";
        assertFalse("never for a swap I did not start", engine.onMakerNotice(HASH, MAKER, "declined", "y"));
    }

    @Test public void reasonsAreCleanedAndCapped() {
        assertEquals("a b", SwapEngine.cleanReason("a\nb"));
        assertEquals(240, SwapEngine.cleanReason("x".repeat(500)).length());
    }

    @Test public void deadlinesFollowTheLockedLeg() {
        SwapEngine.Deadlines d = SwapEngine.deadlines(swap);
        assertFalse(d.blocks);
        assertEquals(1793013743L - SwapEngine.CP_SECS_CHECK, d.respondBy);
        assertEquals(1793013743L, d.refundAt);
        swap.myLegIsMinima = true; swap.myTimelock = 2386600;
        d = SwapEngine.deadlines(swap);
        assertTrue(d.blocks);
        assertEquals(2386600 - SwapEngine.CP_BLOCKS_CHECK, d.respondBy);
        swap.role = "RESPONDER";
        assertNull(SwapEngine.deadlines(swap));
    }

    @Test public void refundNowExplainsWhenTheContractWillAcceptIt() throws Exception {
        when(rpc.latestBlockTimestamp()).thenReturn(1793012291L);   // 24 minutes before the timelock
        String msg = engine.refundNowBlocking(HASH);
        assertTrue(msg, msg.contains("only accepts a refund after"));
        assertTrue(msg, msg.contains("25 min"));
        swap.status = SwapDb.ST_REFUNDED;
        assertTrue(engine.refundNowBlocking(HASH).contains("already refunded"));
    }

    @Test public void timeoutIsAnnouncedOnceWithTheRefundTime() throws Exception {
        when(db.allSwaps()).thenReturn(java.util.Collections.singletonList(swap));
        when(rpc.latestBlockTimestamp()).thenReturn(1793013743L - SwapEngine.CP_SECS_CHECK + 5);
        engine.noteTimeouts(0);
        engine.noteTimeouts(0);
        verify(notifier, times(1)).notify(eq("Maker did not respond in time"), contains(HASH));
    }

    private static SwapDb.Event ev(String name, String note) { SwapDb.Event e = new SwapDb.Event(); e.event = name; e.note = note; return e; }

    @Test public void cardTellsTheWholeStoryAtEachStage() {
        long tl = swap.myTimelock, respond = tl - SwapEngine.CP_SECS_CHECK;
        SwapEngine.StatusView v = SwapEngine.statusView(swap, new ArrayList<>(), respond - 600, 0);
        assertTrue(v.deadline, v.deadline.startsWith("Maker must lock by") && v.deadline.contains("in 10 min"));
        assertEquals("Refund at " + SwapEngine.clock(tl), v.refundLabel);
        assertFalse(v.refundOpen);

        List<SwapDb.Event> evs = new ArrayList<>();
        evs.add(ev(SwapDb.EV_MAKER_NOTICE, "retrying|Maker wallet: TOO_MANY_COINS"));
        v = SwapEngine.statusView(swap, evs, tl - 60 * 24, 0);
        assertEquals("Maker wallet: TOO_MANY_COINS", v.notice);
        assertFalse(v.noticeFinal);
        assertTrue(v.deadline, v.deadline.startsWith("Maker did not lock in time") && v.deadline.contains("in 24 min") && v.deadline.contains("app open or not"));

        v = SwapEngine.statusView(swap, evs, tl + 1, 0);
        assertTrue(v.refundOpen);
        assertEquals("Refund now", v.refundLabel);

        String tx = "0x" + "ab".repeat(32);
        evs.add(0, ev(SwapDb.EV_REFUND_SENT, tx));
        v = SwapEngine.statusView(swap, evs, tl + 200, 0);
        assertEquals("the full tx hash, never shortened", tx, v.refundTx);
        assertTrue(v.deadline.startsWith("Refund sent"));

        swap.status = SwapDb.ST_REFUNDED;
        v = SwapEngine.statusView(swap, evs, tl + 900, 0);
        assertEquals("", v.refundLabel);
        assertEquals("", v.deadline);
    }

    @Test public void aFinalDeclineIsMarkedAndMinimaLocksCountBlocks() {
        List<SwapDb.Event> evs = new ArrayList<>();
        evs.add(ev(SwapDb.EV_MAKER_NOTICE, "declined|Maker declined: price"));
        swap.myLegIsMinima = true; swap.myTimelock = 2386600;
        SwapEngine.StatusView v = SwapEngine.statusView(swap, evs, 0, 2386600 - SwapEngine.CP_BLOCKS_CHECK - 6);
        assertTrue(v.noticeFinal);
        assertTrue(v.deadline, v.deadline.contains("block " + (2386600 - SwapEngine.CP_BLOCKS_CHECK)) && v.deadline.contains("6 blocks, about 5 min"));
        assertEquals("Refund at block 2386600", v.refundLabel);
    }
}
