package com.eurobuddha.dexicon.doors;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import android.app.Application;

import com.eurobuddha.comms.SignGate;
import com.eurobuddha.dexicon.NodeApi;
import com.eurobuddha.pandapools.DexiconPools;
import com.eurobuddha.pandapools.OwnPoolStore;
import com.eurobuddha.pandapools.Pool;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.stubbing.Answer;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

/** 0.1.78 (review 3+4), the door's mirror of OwnerCoinExclusionTest: the vendored AtomiX engine reaches the node only
 *  through the Dexicon-owned comms.NodeApi shim, which hides ② pool owner-address coins from its wallet coin reads and
 *  runs PandaPools' owner-key guard before any node-picked spend (send / consolidate) — failing closed. The door forward's
 *  send also queues behind SignGate. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class DoorOwnerCoinTest {
    private static final String MINIMA = "0x00", OTHER = "0x" + "d8".repeat(32);
    private static final String OWNER = "0x" + "c1".repeat(32), WALLET = "0x" + "c2".repeat(32);
    private Application app;
    private NodeApi dex;
    private com.eurobuddha.comms.NodeApi shim;
    private final List<String> sends = new ArrayList<>();

    @Before public void up() throws Exception {
        app = RuntimeEnvironment.getApplication();
        Pool p = new Pool();
        p.address = "0x" + "a9".repeat(32); p.opk = "0x" + "b9".repeat(32); p.oadr = OWNER; p.tok = "0x" + "d9".repeat(32);
        p.kmin = "100"; p.covenantScript = "RETURN TRUE";
        assertTrue(OwnPoolStore.recordDurably(app, p));
        dex = mock(NodeApi.class);
        when(dex.context()).thenReturn(app);
        Answer<Object> node = inv -> {
            Object[] a = inv.getArguments();
            String c = (String) a[0]; NodeApi.Cb cb = (NodeApi.Cb) a[a.length - 1];
            if (c.startsWith("coins")) cb.onResult(new JSONObject().put("status", true).put("response", c.contains("tokenid:" + MINIMA)
                    ? new JSONArray().put(coin("0x" + "01".repeat(32), OWNER)).put(coin("0x" + "02".repeat(32), WALLET)) : new JSONArray()));
            else if (c.equals("keys")) cb.onResult(new JSONObject().put("status", true).put("response", new JSONObject().put("keys", new JSONArray())));   // the owner key is not held
            else if (c.startsWith("send") || c.startsWith("consolidate")) { sends.add(c); cb.onResult(new JSONObject().put("status", true).put("response", new JSONObject().put("txpowid", "0x" + "ee".repeat(32)))); }
            else cb.onError("unexpected " + c);
            return null;
        };
        doAnswer(node).when(dex).cmd(anyString(), any(NodeApi.Cb.class));
        doAnswer(node).when(dex).cmd(anyString(), any(), any(NodeApi.Cb.class));
        doAnswer(node).when(dex).cmd(anyString(), any(), anyLong(), any(NodeApi.Cb.class));
        shim = new com.eurobuddha.comms.NodeApi(dex);
    }

    private static JSONObject coin(String id, String address) throws Exception {
        return new JSONObject().put("coinid", id).put("address", address).put("tokenid", MINIMA).put("amount", "50").put("spent", false);
    }

    private static final class Got implements com.eurobuddha.comms.NodeApi.Cb {
        JSONObject json; String error;
        @Override public void onResult(JSONObject j) { json = j; }
        @Override public void onError(String m) { error = m; }
    }

    @Test public void theEnginesWalletCoinReadNeverShowsAnOwnerCoin() throws Exception {
        Got g = new Got();
        shim.cmd("coins relevant:true sendable:true tokenid:" + MINIMA + " coinage:1 checkmempool:true", g);   // MinimaHtlc.myFreeCoins
        assertNull(g.error);
        JSONArray rows = g.json.optJSONArray("response");
        assertEquals(1, rows.length());
        assertEquals(WALLET, rows.getJSONObject(0).getString("address"));
    }

    @Test public void aNodePickedSendIsRefusedWhileAnOwnerKeyCannotSign() {
        Got g = new Got();
        shim.cmd("send amount:5 mine:true address:0x" + "ab".repeat(32) + " tokenid:" + MINIMA, g);   // MinimaHtlc.lock
        assertNotNull(g.error);
        assertTrue(g.error, g.error.contains("owner"));
        assertTrue("nothing was sent", sends.isEmpty());
        Got c = new Got();
        shim.cmd("consolidate tokenid:" + MINIMA + " coinage:3 maxcoins:20 maxsigs:5", c);
        assertNotNull(c.error);
        assertTrue(sends.isEmpty());
    }

    @Test public void aSendOfATokenWithNoOwnerCoinGoesAhead() {
        Got g = new Got();
        shim.cmd("send amount:5 address:0x" + "ab".repeat(32) + " tokenid:" + OTHER, g);
        assertNull(g.error);
        assertEquals(1, sends.size());
    }

    @Test public void unreadableRecipesFailClosed() {
        when(dex.context()).thenReturn(null);
        Got r = new Got();
        shim.cmd("coins relevant:true sendable:true tokenid:" + MINIMA, r);
        assertEquals(DexiconPools.OWNERS_UNREADABLE, r.error);
        Got s = new Got();
        shim.cmd("send amount:5 address:0x" + "ab".repeat(32) + " tokenid:" + OTHER, s);
        assertEquals(DexiconPools.OWNERS_UNREADABLE, s.error);
        assertTrue(sends.isEmpty());
    }

    @Test public void theForwardSendWaitsForTheSignGateAndFreesIt() {
        final SignGate.Release[] held = new SignGate.Release[1];
        SignGate.submit(r -> held[0] = r);   // a fill co-sign in flight
        final String[] out = new String[2];
        DoorHost.signedSend(shim, "send amount:5 address:0x" + "ab".repeat(32) + " tokenid:" + OTHER, (err, tx, uncertain) -> { out[0] = err; out[1] = tx; });
        assertTrue("queued behind the in-flight signer", sends.isEmpty());
        held[0].free();
        assertEquals(1, sends.size());
        assertNull(out[0]);
        assertEquals("0x" + "ee".repeat(32), out[1]);
        final boolean[] next = {false};
        SignGate.submit(r -> { next[0] = true; r.free(); });
        assertTrue("the forward freed the gate", next[0]);
    }

    @Test public void aRefusedForwardSaysWhyAndFreesTheGate() {
        final String[] out = new String[1];
        DoorHost.signedSend(shim, "send amount:5 address:0x" + "ab".repeat(32) + " tokenid:" + MINIMA, (err, tx, uncertain) -> out[0] = err);
        assertNotNull(out[0]);
        assertTrue(sends.isEmpty());
        final boolean[] next = {false};
        SignGate.submit(r -> { next[0] = true; r.free(); });
        assertTrue(next[0]);
    }
}
