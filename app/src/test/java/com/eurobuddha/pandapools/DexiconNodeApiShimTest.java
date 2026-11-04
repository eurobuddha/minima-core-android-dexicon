package com.eurobuddha.pandapools;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import android.content.Context;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * DEXICON-OWNED: the pandapools.NodeApi shim keeps PandaPools' write-safety journal on top of Dexicon's NodeApi.
 * A fake Dexicon NodeApi runs the dispatch-time guard exactly where Dexicon's queue does, then answers as told.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class DexiconNodeApiShimTest {
    private Context ctx;
    private com.eurobuddha.dexicon.NodeApi dex;
    private final List<String> sent = new ArrayList<>();
    /** What the fake node does with the next admitted command: a reply, or an error message. */
    private JSONObject nextReply;
    private String nextError;

    @Before public void setUp() {
        ctx = RuntimeEnvironment.getApplication();
        ctx.getSharedPreferences("pandapools_write_safety", Context.MODE_PRIVATE).edit().clear().commit();
        NodeApi.commitWriteAcknowledgement(() -> true, () -> {});
        dex = mock(com.eurobuddha.dexicon.NodeApi.class);
        when(dex.context()).thenReturn(ctx);
        doAnswer(inv -> {
            String command = inv.getArgument(0);
            BooleanSupplier guard = inv.getArgument(1);
            com.eurobuddha.dexicon.NodeApi.Cb cb = inv.getArgument(3);
            if (!guard.getAsBoolean()) { cb.onError("Market changed; refresh and try again. No command was sent."); return null; }
            sent.add(command);
            if (nextError != null) cb.onError(nextError); else cb.onResult(nextReply);
            return null;
        }).when(dex).cmd(anyString(), any(BooleanSupplier.class), anyLong(), any(com.eurobuddha.dexicon.NodeApi.Cb.class));
    }

    @After public void tearDown() { NodeApi.commitWriteAcknowledgement(() -> true, () -> {}); }

    private String pending() { return ctx.getSharedPreferences("pandapools_write_safety", Context.MODE_PRIVATE).getString("pending", ""); }

    private static final class Box implements NodeApi.Cb {
        JSONObject result; String error; boolean throwOnResult;
        public void onResult(JSONObject json) { result = json; if (throwOnResult) throw new IllegalStateException("parser"); }
        public void onError(String message) { error = message; }
    }

    @Test public void aConfirmedWriteClearsItsOwnMarker() throws Exception {
        NodeApi n = new NodeApi(dex);
        nextReply = new JSONObject().put("status", true);
        Box b = new Box();
        n.cmd("txnpost id:x", b);
        assertNotNull(b.result);
        assertEquals("a complete reply clears the marker", "", pending());
        assertFalse(n.hasInterruptedWrite());
    }

    @Test public void aLostReplyPausesSigningUntilTheUserResolvesIt() throws Exception {
        NodeApi n = new NodeApi(dex);
        nextError = "The node has not confirmed the outcome of that write; it may still complete.";   // Dexicon's timeout
        Box b = new Box();
        n.cmd("txnsign id:x publickey:auto", b);
        assertEquals(NodeApi.ERR_WRITE_UNCERTAIN, b.error);
        String marker = pending();
        assertFalse("the marker stays", marker.isEmpty());
        assertTrue(n.hasInterruptedWrite());

        nextError = null; nextReply = new JSONObject().put("status", true);
        Box second = new Box();
        n.cmd("txnpost id:y", second);
        assertEquals("no second fund write while one is uncertain", NodeApi.ERR_WRITE_UNCERTAIN, second.error);
        assertEquals("and it never reached the node", 1, sent.size());

        Box read = new Box();
        n.cmd("balance", read);
        assertNotNull("reads still work", read.result);

        assertFalse("a stale review is refused", n.acknowledgeInterruptedWrite("not-the-marker"));
        assertTrue(n.acknowledgeInterruptedWrite(marker));
        assertFalse(n.hasInterruptedWrite());
    }

    @Test public void aParkedOrOversizeWriteIsUncertainNotFailed() throws Exception {
        NodeApi n = new NodeApi(dex);
        for (String reply : new String[]{com.eurobuddha.dexicon.NodeApi.ERR_NOT_ADMIN, com.eurobuddha.dexicon.NodeApi.ERR_TOO_LONG, "Incomplete node reply."}) {
            setUp();
            n = new NodeApi(dex);
            nextError = reply;
            Box b = new Box();
            n.cmd("send amount:1 address:0x00 tokenid:0x00", b);
            assertEquals(reply, NodeApi.ERR_WRITE_UNCERTAIN, b.error);
            assertFalse(reply, pending().isEmpty());
        }
    }

    @Test public void notEnabledAndClosedProveTheWriteNeverRan() throws Exception {
        for (String m : new String[]{NodeApi.ERR_NOT_ENABLED, "Node connection closed."}) {
            setUp();
            NodeApi n = new NodeApi(dex);
            nextError = m;
            Box b = new Box();
            n.cmd("txnpost id:x", b);
            assertEquals(m, b.error);
            assertEquals(m, "", pending());
        }
    }

    @Test public void aCallbackThatThrowsOnAPostedWriteIsUncertain() throws Exception {
        NodeApi n = new NodeApi(dex);
        nextReply = new JSONObject().put("status", true);
        Box b = new Box(); b.throwOnResult = true;
        n.cmd("txnpost id:x", b);
        assertEquals(NodeApi.ERR_WRITE_UNCERTAIN, b.error);
        assertFalse(pending().isEmpty());
    }

    @Test public void aSigningStateChangeWhileQueuedSendsNothing() throws Exception {
        NodeApi n = new NodeApi(dex);
        doAnswer(inv -> {
            BooleanSupplier guard = inv.getArgument(1);
            com.eurobuddha.dexicon.NodeApi.Cb cb = inv.getArgument(3);
            OwnPoolStore.signingStateChanged();   // a confirmation landed while the command waited in the queue
            if (!guard.getAsBoolean()) { cb.onError("Market changed; refresh and try again. No command was sent."); return null; }
            sent.add(inv.getArgument(0)); cb.onResult(new JSONObject().put("status", true)); return null;
        }).when(dex).cmd(anyString(), any(BooleanSupplier.class), anyLong(), any(com.eurobuddha.dexicon.NodeApi.Cb.class));
        Box b = new Box();
        n.cmd("txnsign id:x publickey:auto", b);
        assertTrue(b.error, b.error.startsWith("Pool signing state changed"));
        assertTrue(sent.isEmpty());
        assertEquals("", pending());
    }

    @Test public void dryrunAndReadsNeverTouchTheJournal() throws Exception {
        NodeApi n = new NodeApi(dex);
        nextError = "Minima Core didn't respond. Is it installed, running and enabled?";
        Box b = new Box();
        n.cmd("send amount:1 address:0x00 dryrun:true", b);
        n.cmd("coins address:0x00", new Box());
        assertEquals("", pending());
        assertEquals(nextError, b.error);
    }

    @Test public void longVerbsGetPandaPoolsTimeout() {
        assertEquals(180000, NodeApi.timeoutFor("backup file:x password:y confirm:y"));
        assertEquals(180000, NodeApi.timeoutFor("newaddress"));
        assertEquals(180000, NodeApi.timeoutFor(BlockOwnerRecovery.MODE_COMMAND));
        assertEquals(0, NodeApi.timeoutFor("balance"));
        verifyNoInteractions(dex);
    }
}
