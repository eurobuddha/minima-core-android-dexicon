package com.eurobuddha.pandapools;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import android.app.Application;

import com.eurobuddha.dexicon.CustodyFake;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.stubbing.Answer;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

/** 0.1.83 (owner decision): coins at a pool owner address block a node-picked spend only when signing could harm that
 *  pool — its owner key is not safe to sign with, or the pool is kept elsewhere. Dust anyone sends to the public $OADR no
 *  longer blocks comms posts (e.g. a taker-protecting tombstone). Nothing is queued by the guard (0.1.85): an
 *  evidence-less PendingCollect entry would block that address's real collections. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class OwnerCoinGuardTest {
    private static final String MINIMA = "0x00";
    private static final String POOL = "0x" + "a9".repeat(32), OPK = "0x" + "b9".repeat(32), OWNER = "0x" + "c1".repeat(32);
    private Application app;
    private final List<String> sends = new ArrayList<>();
    private boolean keyHeld, keysFailSilently;
    private com.eurobuddha.comms.NodeApi shim;

    @Before public void up() throws Exception {
        app = RuntimeEnvironment.getApplication();
        CustodyFake.rows = new String[0][];
        CustodyFake.install(app, CustodyFake.Install.NONE, 0);
        Pool p = new Pool();
        p.address = POOL; p.opk = OPK; p.oadr = OWNER; p.tok = "0x" + "d9".repeat(32); p.kmin = "100"; p.covenantScript = "RETURN TRUE";
        p.newlyCreatedWithCurrentOwnerState = true;   // signing state confirmed
        assertTrue(OwnPoolStore.recordDurably(app, p));
        com.eurobuddha.dexicon.NodeApi dex = mock(com.eurobuddha.dexicon.NodeApi.class);
        when(dex.context()).thenReturn(app);
        Answer<Object> node = inv -> {
            Object[] a = inv.getArguments();
            String c = (String) a[0]; com.eurobuddha.dexicon.NodeApi.Cb cb = (com.eurobuddha.dexicon.NodeApi.Cb) a[a.length - 1];
            if (c.startsWith("coins")) cb.onResult(new JSONObject().put("status", true).put("response", c.contains("address:" + OWNER.toLowerCase())
                    ? new JSONArray().put(new JSONObject().put("coinid", "0x" + "01".repeat(32)).put("address", OWNER).put("tokenid", MINIMA).put("amount", "0.000001"))
                    : new JSONArray()));
            else if (c.equals("keys") && keysFailSilently) cb.onError(null);
            else if (c.equals("keys")) cb.onResult(new JSONObject().put("status", true).put("response", new JSONObject().put("keys", keyHeld
                    ? new JSONArray().put(new JSONObject().put("publickey", OPK).put("uses", 5)) : new JSONArray())));
            else if (c.startsWith("send")) { sends.add(c); cb.onResult(new JSONObject().put("status", true)); }
            else cb.onError("unexpected " + c);
            return null;
        };
        doAnswer(node).when(dex).cmd(anyString(), any(com.eurobuddha.dexicon.NodeApi.Cb.class));
        doAnswer(node).when(dex).cmd(anyString(), any(), any(com.eurobuddha.dexicon.NodeApi.Cb.class));
        doAnswer(node).when(dex).cmd(anyString(), any(), anyLong(), any(com.eurobuddha.dexicon.NodeApi.Cb.class));
        shim = new com.eurobuddha.comms.NodeApi(dex);
    }

    /** A comms tombstone post: a node-picked MINIMA send. Returns its error (null = sent). */
    private String tombstone() {
        final String[] err = new String[1];
        shim.cmd("send amount:0.0001 address:0x" + "ab".repeat(32) + " tokenid:" + MINIMA + " state:{\"99\":\"0x01\"}", new com.eurobuddha.comms.NodeApi.Cb() {
            public void onResult(JSONObject j) {}
            public void onError(String m) { err[0] = m; }
        });
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        return err[0];
    }

    @Test public void dustAtADexiconKeptPoolWithASafeKeyDoesNotBlockAndQueuesNothing() {
        keyHeld = true;
        assertNull(PendingCollect.get(app, OWNER));
        assertNull(tombstone());
        assertEquals("the tombstone was posted", 1, sends.size());
        // never queued from the guard: an evidence-less PendingCollect entry parses as legacy forever and would block
        // that address's real collections (its later refresh/close evidence could never clear it)
        assertNull("nothing queued by the guard", PendingCollect.get(app, OWNER.toLowerCase()));
    }

    @Test public void anOwnerKeyNotSafeToSignWithRefuses() {
        keyHeld = false;   // the key is not on this node
        String why = tombstone();
        assertNotNull(why);
        assertTrue(why, why.startsWith(DexiconPools.REFUSED));
        assertTrue("names the owner address in full", why.contains(OWNER.toLowerCase()));
        assertTrue(why, why.contains("not safe to sign"));
        assertFalse("never promises a collection the guard does not queue", why.contains("queued"));
        assertTrue(sends.isEmpty());
        assertNull(PendingCollect.get(app, OWNER.toLowerCase()));
    }

    /** PandaPools' key check passes a failed `keys` read's message through; a null one must not read as "safe". */
    @Test public void aKeysReadFailingWithNoMessageRefuses() {
        keyHeld = true; keysFailSilently = true;
        String why = tombstone();
        assertNotNull("a silent keys failure never lets an owner-address send through", why);
        assertTrue(why, why.startsWith(DexiconPools.REFUSED));
        assertTrue(sends.isEmpty());
    }

    @Test public void aPoolKeptElsewhereRefusesEvenWithASafeKey() {
        keyHeld = true;
        CustodyFake.rows = new String[][]{{POOL.toLowerCase(), DexiconPools.KEPT_HERE}};
        CustodyFake.install(app, CustodyFake.Install.NEW, 982);
        String why = tombstone();
        assertNotNull(why);
        assertTrue(why, why.startsWith(DexiconPools.REFUSED));
        assertTrue(why, why.contains("standalone PandaPools still keeps"));
        assertTrue(why.contains(OWNER.toLowerCase()) && why.contains(POOL.toLowerCase()));
        assertTrue(sends.isEmpty());
        CustodyFake.install(app, CustodyFake.Install.NONE, 0);
    }

    @Test public void anUnreadableCustodyRefuses() {
        keyHeld = true;
        CustodyFake.install(app, CustodyFake.Install.OLD, 980);
        String why = tombstone();
        assertNotNull(why);
        assertTrue(why, why.contains("could not say which pools it keeps"));
        assertTrue(sends.isEmpty());
        CustodyFake.install(app, CustodyFake.Install.NONE, 0);
    }

    /** A stale custody read only asks for a retry — it never sends the user into PandaPools for a pool Dexicon may keep. */
    @Test public void aStaleCustodyRefusalOnlyAsksForARetry() {
        String why = DexiconPools.keptElsewhereRefusal(true, null, OWNER, POOL);
        assertTrue(why, why.startsWith(DexiconPools.REFUSED));
        assertTrue("full addresses", why.contains(OWNER.toLowerCase()) && why.contains(POOL.toLowerCase()));
        assertTrue(why, why.contains("could not confirm") && why.contains("Try again in a moment."));
        assertFalse("no instruction to collect in PandaPools", why.contains("Collect them in PandaPools"));
        String kept = DexiconPools.keptElsewhereRefusal(false, null, OWNER, POOL);
        assertTrue(kept, kept.contains("could not say which pools it keeps") && kept.contains("Collect them in PandaPools"));
    }
}
