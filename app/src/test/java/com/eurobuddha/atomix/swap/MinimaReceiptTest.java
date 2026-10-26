// VENDORED VERBATIM from apks/atomix 0.1.84 app/src/test/java/com/eurobuddha/atomix/swap/MinimaReceiptTest.java by scripts/vendor-atomix.sh — do not edit here; re-vendor.
package com.eurobuddha.atomix.swap;

import android.os.Handler;
import com.eurobuddha.comms.NodeApi;
import com.eurobuddha.atomix.eth.EthWallet;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import java.util.Collections;
import java.util.function.Consumer;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class MinimaReceiptTest {
    private static final String HASH = "0x" + "44".repeat(32), TX = "0x" + "55".repeat(32);
    private SwapDb db;
    private MinimaHtlc minima;
    private SwapEngine engine;
    private SwapDb.Swap swap;
    private SwapDb.Event receipt;
    private Consumer<Integer> confirmation;

    @Before public void setup() {
        SwapEngine.clearRetryMarkersForTest();
        db = mock(SwapDb.class); minima = mock(MinimaHtlc.class);
        engine = new SwapEngine(mock(NodeApi.class), minima, db, mock(EthWallet.class), mock(Handler.class), mock(SwapEngine.Notifier.class));
        swap = new SwapDb.Swap(); swap.hash = HASH; swap.status = SwapDb.ST_CLAIMING;
        when(db.allSwaps()).thenReturn(Collections.singletonList(swap));
        when(db.getSwap(HASH)).thenReturn(swap);
        receipt = new SwapDb.Event(); receipt.event = SwapDb.EV_MINIMA_CLAIM_SUBMITTED;
        receipt.note = TX; receipt.token = "0x00"; receipt.amount = "1";
        when(db.getEvents(HASH)).thenReturn(Collections.singletonList(receipt));
        doAnswer(i -> { confirmation = i.getArgument(1); return null; }).when(minima).confirmationDepth(eq(TX), any(), any());
    }

    @Test public void absentAndShallowReceiptsNeverFinalize() {
        engine.confirmPendingMinima();
        for (int depth : new int[]{-1, 0, 1}) confirmation.accept(depth);
        verify(db, never()).setSwapStatus(anyString(), anyString());
        verify(db, never()).logEvent(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test public void storedClaimReceiptFinalizesWithoutRediscoveringSpentCoin() {
        engine.confirmPendingMinima(); confirmation.accept(2);
        verify(db).setSwapStatus(HASH, SwapDb.ST_COMPLETE);
        verify(db).logEvent(HASH, SwapDb.EV_COLLECT, "minima", "1", TX);
        verify(minima, never()).claim(any(), anyString(), anyString(), any());
    }

    @Test public void storedRefundReceiptOnlyFinalizesAfterConfirmation() {
        receipt.event = SwapDb.EV_MINIMA_REFUND_SUBMITTED;
        engine.confirmPendingMinima(); confirmation.accept(3);
        verify(db).setSwapStatus(HASH, SwapDb.ST_REFUNDED);
        verify(db).logEvent(HASH, SwapDb.EV_EXPIRED, "minima", "1", TX);
    }

    @Test public void receiptChecksAreThrottledAndLostCallbacksCanRetry() {
        engine.confirmPendingMinima(); engine.confirmPendingMinima();
        verify(minima, times(1)).confirmationDepth(eq(TX), any(), any());
        SwapEngine.ageRetryMarkerForTest("receiptM:" + TX, 10_000);
        engine.confirmPendingMinima(); verify(minima, times(2)).confirmationDepth(eq(TX), any(), any());
    }

    @Test public void terminalStateIsRecheckedBeforeDelayedCallback() {
        engine.confirmPendingMinima(); swap.status = SwapDb.ST_COMPLETE; confirmation.accept(3);
        verify(db, never()).setSwapStatus(anyString(), anyString());
    }

    @Test public void malformedConfirmationEvidenceIsNotSuccess() throws Exception {
        JSONObject r = new JSONObject().put("found", true).put("confirmations", "2")
                .put("block", "100").put("tip", "102").put("blockid", TX);
        JSONObject reply = new JSONObject().put("status", true).put("response", r);
        assertEquals(2, MinimaHtlc.confirmedDepth(reply));
        for (Object depth : new Object[]{"1.5", "-1", "2147483648", JSONObject.NULL, "NaN"}) {
            r.put("confirmations", depth); assertEquals(-1, MinimaHtlc.confirmedDepth(reply));
        }
        r.put("confirmations", "9").put("found", false); assertEquals(-1, MinimaHtlc.confirmedDepth(reply));
        r.put("found", true); reply.put("status", false); assertEquals(-1, MinimaHtlc.confirmedDepth(reply));
    }

    @Test public void refundSubmissionDoesNotWritePermanentCompletionGuard() throws Exception {
        when(db.getEvents(HASH)).thenReturn(Collections.emptyList());
        JSONObject coin = new JSONObject().put("coinid", TX).put("tokenid", "0x00").put("amount", "1")
                .put("state", new JSONObject().put("0", "0xAB").put("3", "100").put("5", HASH));
        doAnswer(i -> { ((MinimaHtlc.PostCb)i.getArgument(1)).ok(TX); return null; }).when(minima).refund(any(), any());
        engine.checkExpiredMinima(coin, 200);
        verify(db).logEvent(HASH, SwapDb.EV_MINIMA_REFUND_SUBMITTED, "0x00", "1", TX);
        verify(db, never()).setSwapStatus(HASH, SwapDb.ST_REFUNDED);
        verify(db, never()).logEvent(eq(HASH), eq(SwapDb.EV_EXPIRED), any(), any(), any());
        SwapEngine.ageRetryMarkerForTest("refundM:" + HASH, 10_000);
        engine.checkExpiredMinima(coin, 200);
        verify(minima, times(2)).refund(any(), any());
    }

    @Test public void claimSubmissionDoesNotWritePermanentCompletionGuard() throws Exception {
        when(db.getSecret(HASH)).thenReturn("0xAB");
        swap.buyToken = "MINIMA";
        JSONObject coin = new JSONObject().put("coinid", TX).put("tokenid", "0x00").put("amount", "1")
                .put("state", new JSONObject().put("0", "0xAB").put("3", "1000").put("5", HASH));
        doAnswer(i -> { ((MinimaHtlc.PostCb)i.getArgument(3)).ok(TX); return null; }).when(minima).claim(any(), any(), any(), any());
        engine.checkCanSwapCoin(coin, 200);
        verify(db).logEvent(HASH, SwapDb.EV_MINIMA_CLAIM_SUBMITTED, "0x00", "1", TX);
        verify(db, never()).setSwapStatus(HASH, SwapDb.ST_COMPLETE);
        verify(db, never()).logEvent(eq(HASH), eq(SwapDb.EV_COLLECT), any(), any(), any());
    }

    private static JSONObject chainReply(String depth) throws Exception {
        return new JSONObject().put("status", true).put("response", new JSONObject().put("found", true)
                .put("blockid", TX).put("block", "100")
                .put("tip", new java.math.BigInteger(depth).add(java.math.BigInteger.valueOf(100)).toString())
                .put("confirmations", depth));
    }
    @Test public void completeChainEvidencePreservesExistingDepthPolicy() throws Exception {
        for (String depth : new String[]{"0", "1", "2", "3", "2147483647"})
            assertEquals(Integer.parseInt(depth), MinimaHtlc.confirmedDepth(chainReply(depth)));
        assertEquals(-1, MinimaHtlc.confirmedDepth(chainReply("2147483648")));
    }
    @Test public void partialTypedOrInconsistentChainEvidenceCannotFinalize() throws Exception {
        java.util.List<JSONObject> invalid = new java.util.ArrayList<>();
        invalid.add(null); invalid.add(new JSONObject());
        invalid.add(new JSONObject().put("status", true).put("response", new JSONObject().put("found", true).put("confirmations", "9")));
        for (String key : new String[]{"found", "blockid", "block", "tip", "confirmations"}) {
            JSONObject reply = chainReply("3"); reply.getJSONObject("response").remove(key); invalid.add(reply);
        }
        for (String key : new String[]{"block", "tip", "confirmations"})
            for (Object value : new Object[]{JSONObject.NULL, 3, true, "3.0", "-3", "1e3", "3;send", "9".repeat(31)}) {
                JSONObject reply = chainReply("3"); reply.getJSONObject("response").put(key, value); invalid.add(reply);
            }
        for (Object id : new Object[]{JSONObject.NULL, 1, "0x12", "0x" + "00".repeat(32), "0x" + "gg".repeat(32)}) {
            JSONObject reply = chainReply("3"); reply.getJSONObject("response").put("blockid", id); invalid.add(reply);
        }
        for (Object flag : new Object[]{false, "true", 1, JSONObject.NULL}) {
            invalid.add(chainReply("3").put("status", flag));
            JSONObject reply = chainReply("3"); reply.getJSONObject("response").put("found", flag); invalid.add(reply);
        }
        for (String[] values : new String[][]{{"0","3"},{"100","104"},{"104","100"}}) {
            JSONObject reply = chainReply("3"); reply.getJSONObject("response").put("block", values[0]).put("tip", values[1]); invalid.add(reply);
        }
        engine.confirmPendingMinima();
        for (JSONObject reply : invalid) {
            int depth = MinimaHtlc.confirmedDepth(reply); assertEquals(-1, depth); confirmation.accept(depth);
        }
        verify(db, never()).setSwapStatus(anyString(), anyString());
        verify(db, never()).logEvent(anyString(), anyString(), anyString(), anyString(), anyString());
    }
}
