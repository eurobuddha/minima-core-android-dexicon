package com.eurobuddha.dexicon.maker;

import static org.junit.Assert.*;

import android.app.Application;
import android.content.pm.PackageInfo;

import com.eurobuddha.dexicon.DexDb;
import com.eurobuddha.dexicon.TradingGate;
import com.eurobuddha.dexicon.identity.DexIdentity;
import com.eurobuddha.dexicon.relay.HttpTransport;
import com.eurobuddha.dexicon.relay.RelayClient;
import com.goterl.lazysodium.LazySodiumJava;
import com.goterl.lazysodium.SodiumJava;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

import java.util.Collections;

/** The gate shut while the maker runs: takes are refused with a reason the taker moves past, and once an app was FOUND
 *  my live orders are withdrawn (not left up for 23 h). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class MakerServicePausedTest {

    private static DexIdentity id(String b) {
        StringBuilder s = new StringBuilder("0x"); for (int i = 0; i < 32; i++) s.append(b);
        return DexIdentity.fromSeed(new LazySodiumJava(new SodiumJava()), s.toString());
    }

    /** A service attached to the app but not started (no foreground, no node): the paused pass alone. */
    private MakerService svc() {
        MakerService s = Robolectric.buildService(MakerService.class).get();
        s.db = new DexDb(s); s.relay = new RelayClient(new HttpTransport()); s.me = id("11");
        s.engine = new MakerEngine(s, null, s.db, s.relay, s.me);
        MyOrder o = new MyOrder(); o.orderId = "0x00000000000000a1"; o.side = "sell"; o.price = "0.0035"; o.amount = "100"; o.remaining = "100";
        o.address = "0x0CDCD61692F186EB0BBCFA289F438043F586FF7B3F6864193358E29166E8454A"; o.seq = 5; o.expiry = MakerEngine.now() + 3600;
        s.db.saveOrder(o);
        return s;
    }

    private static JSONObject take() throws Exception { return new JSONObject().put("k", "take").put("orderId", "0x00000000000000a1").put("_from", id("44").publicId()); }

    private static void settle(MakerService s, String want) throws Exception {
        for (int i = 0; i < 100 && !want.equals(s.db.myOrder("0x00000000000000a1").status); i++) {
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            Thread.sleep(50);
        }
    }

    @Test public void theRefusalIsOneATakerMovesPast() {
        assertTrue(MakerService.PAUSED.matches(MakerService.MOVE_ON));
        assertEquals(MakerService.PAUSED, MakerService.refused("0x00000000000000a1", MakerService.PAUSED).optString("reason"));
        assertFalse("the old reason stopped the taker's run", "maker paused".matches(MakerService.MOVE_ON));
    }

    @Test public void aFoundAppWithdrawsMyLiveOrders() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        TradingGate.record(app, new JSONArray());
        PackageInfo pi = new PackageInfo(); pi.packageName = TradingGate.ATOMIX;
        Shadows.shadowOf(app.getPackageManager()).installPackage(pi);
        MakerService s = svc();
        s.pausedPass(Collections.singletonList(take()));
        settle(s, "cancelled");
        assertEquals("withdrawn: a cancel, not a trade", "cancelled", s.db.myOrder("0x00000000000000a1").status);
    }

    @Test public void anUnreadMiniHubRefusesTakesButWithdrawsNothing() throws Exception {
        assertTrue(TradingGate.blocked(RuntimeEnvironment.getApplication()));
        MakerService s = svc();
        s.pausedPass(Collections.singletonList(take()));
        settle(s, "cancelled");
        assertEquals("live", s.db.myOrder("0x00000000000000a1").status);
    }

    @Test public void theEngineOffersNothingWhileGated() {
        MakerService s = svc();
        final String[] err = {null};
        s.engine.create("sell", "0.0035", "10", (e, o) -> err[0] = e);
        assertEquals(MakerEngine.GATED, err[0]);
        s.engine.edit("0x00000000000000a1", "0.004", "10", (e, o) -> err[0] = e + "!");
        assertEquals(MakerEngine.GATED + "!", err[0]);
    }
}
