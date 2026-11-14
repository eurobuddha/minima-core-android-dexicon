package com.eurobuddha.dexicon;

import static org.junit.Assert.*;

import android.app.Application;
import android.content.pm.PackageInfo;

import com.eurobuddha.minimaapi.MinimaAPIListener;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.util.ArrayList;
import java.util.List;

/** Host-node mode (owner, 14 Nov 2026): inside Pandamonium every command goes straight to the host node's own wallet -
 *  wallet and txn verbs included - and nothing ever reaches a Trading Account; the sibling-app gate is off. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class HostModeTest {
    private Application app;
    private final List<String> sent = new ArrayList<>();
    private boolean closed;

    /** The host's in-process link: answers every command at once, registers as enabled + admin. */
    private final class FakeLink implements NodeLink {
        FakeLink(MinimaAPIListener registration) {
            try { registration.response(new JSONObject().put("status", true).put("enabled", true).put("admin", true)); }
            catch (Exception e) { throw new RuntimeException(e); }
        }
        public void Command(String command, MinimaAPIListener cb) {
            sent.add(command);
            try { cb.response(new JSONObject().put("status", true).put("enabled", true).put("admin", true).put("response", new JSONObject())); }
            catch (Exception e) { throw new RuntimeException(e); }
        }
        public void FileCommand(String action, String path, String newPath, android.net.Uri uri, MinimaAPIListener cb) { sent.add("FILE " + action); }
        public void onDestroy() { closed = true; }
    }

    @Before public void up() {
        app = RuntimeEnvironment.getApplication();
        NodeApi.resetQueueForTest();   // the queue is process-wide: an earlier test class may have left an operation open
        HostMode.install((ctx, registration) -> new FakeLink(registration), "Pandamonium");
    }

    @After public void down() { HostMode.reset(); }

    private static final class Box implements NodeApi.Cb {
        JSONObject result; String error;
        public void onResult(JSONObject j) { result = j; }
        public void onError(String m) { error = m; }
    }

    @Test public void everyCommandReachesTheHostNodeNeverAnAccount() {
        assertTrue(HostMode.on());
        NodeApi node = new NodeApi(app, enabled -> {});
        ShadowLooper.idleMainLooper();
        assertFalse("the host runs as admin", node.needsAdmin());
        String[] verbs = {"send address:0x00 amount:1", "txnsign id:x publickey:auto", "seedrandom modifier:dexicon-identity-v1",
                "coins relevant:true", "balance", "getaddress", "txnpost id:x"};
        for (String v : verbs) {
            Box b = new Box();
            node.cmd(v, b);
            ShadowLooper.idleMainLooper();
            assertNull(v + " must not fail: " + b.error, b.error);
            assertNotNull(v, b.result);
        }
        for (String v : verbs) assertTrue(v + " reached the host node", sent.contains(v));
        assertFalse("no Trading Account was ever created", com.eurobuddha.account.Account.exists(app));
        assertFalse(com.eurobuddha.account.Account.everCreated(app));
    }

    @Test public void siblingAppsNeverGateTradingInsideTheHost() throws Exception {
        PackageInfo pi = new PackageInfo(); pi.packageName = TradingGate.ATOMIX; pi.versionName = "0.2.41";
        pi.setLongVersionCode(241);
        Shadows.shadowOf(app.getPackageManager()).installPackage(pi);
        assertTrue("AtomiX really is installed", TradingGate.installed(app, TradingGate.ATOMIX));
        assertFalse("but it never conflicts in host mode", TradingGate.conflict(app));
        assertFalse("and trading is never blocked (MiniHub never read either)", TradingGate.blocked(app));
        HostMode.reset();
        assertTrue("standalone Dexicon still gates", TradingGate.blocked(app));
    }

    @Test public void aHostUserWhoNeverOpenedDexiconGetsNoBackgroundWork() {
        app.getSharedPreferences("dexicon_host", 0).edit().clear().commit();
        assertFalse("never opened, no old trading data: no maker, no relay traffic", HostMode.backgroundAllowed(app));
        HostMode.markOpened(app);
        assertTrue("opened once: background work as standalone", HostMode.backgroundAllowed(app));
        app.getSharedPreferences("dexicon_host", 0).edit().clear().commit();
        app.openOrCreateDatabase("atomix.db", 0, null).close();
        assertTrue("old built-in AtomiX data: live swaps keep being served", HostMode.backgroundAllowed(app));
        app.deleteDatabase("atomix.db");
        HostMode.reset();
        assertTrue("standalone always runs it", HostMode.backgroundAllowed(app));
        assertEquals("standalone", HostMode.text("standalone", "host"));
    }

    @Test public void standaloneNeverSeesHostMode() {
        HostMode.reset();
        assertFalse(HostMode.on());
    }
}
