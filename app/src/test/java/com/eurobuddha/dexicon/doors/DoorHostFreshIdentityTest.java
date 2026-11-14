package com.eurobuddha.dexicon.doors;

import static org.junit.Assert.*;

import android.app.Application;

import com.eurobuddha.atomix.swap.SwapDb;
import com.eurobuddha.dexicon.HostMode;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** Host mode's in-app reinstall: only inside a host, never while a swap still settles with the old keys. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class DoorHostFreshIdentityTest {
    @After public void down() { HostMode.reset(); }

    @Test public void refusedStandaloneAndWhileASwapIsUnsettled() {
        Application app = RuntimeEnvironment.getApplication();
        DoorHost host = DoorHost.get(app);
        assertNotNull("standalone: clear storage / reinstall instead", host.startFreshIdentity());

        HostMode.install((ctx, registration) -> { throw new IllegalStateException("no link needed"); }, "Pandamonium");
        SwapDb.Swap s = new SwapDb.Swap();
        s.hash = "cd".repeat(32); s.role = "RESPONDER"; s.sellToken = "MINIMA"; s.buyToken = "USDT";
        s.status = SwapDb.ST_STARTED; s.sellAmount = "10"; s.buyAmount = "0.05";
        host.swapDb().upsertSwap(s);
        String why = host.startFreshIdentity();
        assertNotNull(why);
        assertTrue(why, why.contains("still settling"));
        host.prefs().edit().putString("swap_pk", "0xAB").commit();
        assertEquals("nothing forgotten while refused", "0xAB", host.prefs().getString("swap_pk", ""));

        s.status = SwapDb.ST_COMPLETE; host.swapDb().upsertSwap(s);
        assertNull("every swap settled: allowed", host.startFreshIdentity());
    }
}
