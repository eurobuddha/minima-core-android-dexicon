package com.eurobuddha.dexicon.maker;

import static org.junit.Assert.*;

import android.app.Application;
import android.content.pm.PackageInfo;

import com.eurobuddha.dexicon.DexDb;
import com.eurobuddha.dexicon.TradingGate;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

/** E (0.1.75): the gate is re-checked at the last moment — per take in the maker — and the Activity follows a change the
 *  service broadcasts, without waiting for the MiniHub read (TradingGateTest). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class GateLivenessTest {

    /** Records replies instead of sending them. */
    public static class Spy extends MakerService {
        final List<JSONObject> replies = new ArrayList<>();
        @Override void reply(String to, JSONObject obj) { replies.add(obj); }
    }

    @Test public void aTakeArrivingAfterTheGateShutIsRefusedPerTake() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        TradingGate.record(app, new org.json.JSONArray());
        Spy s = Robolectric.buildService(Spy.class).get();
        s.db = new DexDb(s);
        MyOrder o = new MyOrder(); o.orderId = "0x00000000000000e1"; o.side = "sell"; o.price = "0.0035"; o.amount = "100"; o.remaining = "100";
        o.address = "0x" + "c3".repeat(32); o.seq = 5; o.expiry = MakerEngine.now() + 3600; o.coins.add("0x" + "08".repeat(32));
        s.db.saveOrder(o);
        PackageInfo pi = new PackageInfo(); pi.packageName = TradingGate.ATOMIX;
        Shadows.shadowOf(app.getPackageManager()).installPackage(pi);   // shut between the batch check and this take
        final boolean[] next = {false};
        s.handleTake(new JSONObject().put("_from", "0x" + "44".repeat(32)).put("orderId", o.orderId).put("txn", "0xabcd"), () -> next[0] = true);
        assertTrue(next[0]);
        assertEquals(1, s.replies.size());
        assertEquals(MakerService.PAUSED, s.replies.get(0).optString("reason"));
        Shadows.shadowOf(app.getPackageManager()).removePackage(TradingGate.ATOMIX);
    }
}
