package com.eurobuddha.dexicon.resting;
/* Ported from apks/pandadex app/src/test/java/com/eurobuddha/pandadex/PoolLiquidityRepositoryTest.java (PandaDEX 0.4.23). */


import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class PoolLiquidityRepositoryTest {

    private static Pool pool(String id) {
        Pool p = new Pool();
        p.address = id;
        p.tok = DexContract.MXUSD_ID;
        p.reserveM = BigDecimal.ONE;
        p.reserveT = BigDecimal.ONE;
        p.coinidM = id + "M";
        p.coinidT = id + "T";
        return p;
    }

    private static final class FakeScanner implements PoolLiquidityRepository.Scanner {
        final List<PoolBook.Listener> pending = new ArrayList<>();
        int scans = 0;

        @Override public void scan(PoolBook.Listener cb) {
            scans++;
            pending.add(cb);
        }

        void finish(List<Pool> pools) {
            pending.remove(0).onPools(pools);
        }
    }

    private static final class FakeScheduler implements PoolLiquidityRepository.Scheduler {
        final List<Runnable> queued = new ArrayList<>();
        long delay = -1;

        @Override public void after(long delayMs, Runnable r) {
            delay = delayMs;
            queued.add(r);
        }

        void runNext() {
            queued.remove(0).run();
        }
    }

    private static final class FakeClock implements PoolLiquidityRepository.Clock {
        long now;
        @Override public long now() { return now; }
    }

    @Test public void refreshIsSingleFlightWithOneQueuedFollowup() {
        FakeScanner scanner = new FakeScanner();
        PoolLiquidityRepository repo = new PoolLiquidityRepository(scanner);
        repo.refresh();
        repo.refresh();
        repo.refresh();
        assertEquals(1, scanner.scans);
        scanner.finish(Arrays.asList(pool("A")));
        assertEquals("one follow-up scan is queued", 2, scanner.scans);
    }

    @Test public void transientEmptyDoesNotReplaceLastGoodSnapshot() {
        FakeScanner scanner = new FakeScanner();
        PoolLiquidityRepository repo = new PoolLiquidityRepository(scanner);
        List<Pool> first = Arrays.asList(pool("A"));
        repo.refresh();
        scanner.finish(first);
        assertSame(first, repo.pools());

        repo.refresh();
        scanner.finish(new ArrayList<>());
        assertSame("first empty is suspect", first, repo.pools());

        repo.refresh();
        scanner.finish(new ArrayList<>());
        assertEquals("second empty is believed", 0, repo.pools().size());
    }

    @Test public void productionThrottleCoalescesPoolScans() {
        FakeScanner scanner = new FakeScanner();
        FakeScheduler scheduler = new FakeScheduler();
        FakeClock clock = new FakeClock();
        clock.now = 100;
        PoolLiquidityRepository repo = new PoolLiquidityRepository(scanner, 8_000, scheduler, clock);

        repo.refresh();
        scanner.finish(Arrays.asList(pool("A")));
        repo.refresh();
        repo.refresh();

        assertEquals("throttled refreshes do not scan immediately", 1, scanner.scans);
        assertEquals(1, scheduler.queued.size());
        assertTrue("a delayed refresh is scheduled", scheduler.delay > 0);

        clock.now += 8_000;
        scheduler.runNext();
        assertEquals("queued refresh runs once", 2, scanner.scans);
    }
}
