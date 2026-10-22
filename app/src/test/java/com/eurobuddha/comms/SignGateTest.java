// VENDORED VERBATIM from apks/atomix 0.1.82 app/src/test/java/com/eurobuddha/comms/SignGateTest.java by scripts/vendor-atomix.sh — do not edit here; re-vendor.
package com.eurobuddha.comms;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * SERIAL SIGNING invariant — only one op in flight at a time, and no interleaving can ever break it.
 *
 * Runs on the JVM (no Looper), so the lost-callback watchdog is absent in production here; {@link SignGate}
 * exposes {@code fireWatchdogForTest()} to simulate it firing, which is what makes the generation / stale-free
 * logic (CR-6) directly testable. Each {@code Rec} records the order ops actually START in and captures its
 * {@link SignGate.Release} so the test controls exactly when each op completes.
 */
public class SignGateTest {

    @Before public void setUp() { SignGate.resetForTest(); }

    /** An op that records the moment it starts and hands back its Release for the test to free on demand. */
    private static final class Rec implements SignGate.Op {
        final String name; final List<String> log; SignGate.Release rel;
        Rec(String name, List<String> log) { this.name = name; this.log = log; }
        @Override public void run(SignGate.Release r) { log.add(name); rel = r; }
    }

    @Test public void secondOpWaitsForTheFirstToFree() {
        List<String> log = new ArrayList<>();
        Rec a = new Rec("A", log), b = new Rec("B", log);
        SignGate.submit(a);
        SignGate.submit(b);
        assertEquals("only A may be in flight; B waits behind the gate", Arrays.asList("A"), log);
        a.rel.free();
        assertEquals("B starts only once A freed", Arrays.asList("A", "B"), log);
        b.rel.free();
    }

    @Test public void aThrowingOpDoesNotJamTheQueue() {
        // CR-5: an Op that throws synchronously (e.g. an empty command list) must not leave busy=true forever.
        List<String> log = new ArrayList<>();
        SignGate.Op boom = r -> { log.add("boom"); throw new RuntimeException("kaboom"); };
        Rec next = new Rec("N", log);
        SignGate.submit(boom);
        SignGate.submit(next);
        assertEquals("the throwing op ran, then the queue advanced to N", Arrays.asList("boom", "N"), log);
        next.rel.free();
    }

    @Test public void freeIsIdempotentAndAdvancesOnlyOnce() {
        List<String> log = new ArrayList<>();
        Rec a = new Rec("A", log), b = new Rec("B", log), c = new Rec("C", log);
        SignGate.submit(a);
        SignGate.submit(b);
        SignGate.submit(c);
        assertEquals(Arrays.asList("A"), log);
        a.rel.free();
        assertEquals(Arrays.asList("A", "B"), log);
        a.rel.free();   // double free — must be a no-op, NOT advance to C while B is in flight
        assertEquals("a second free() must not start C", Arrays.asList("A", "B"), log);
        b.rel.free();
        assertEquals(Arrays.asList("A", "B", "C"), log);
        c.rel.free();
    }

    @Test public void lateFreeAfterWatchdogTimeoutDoesNotStartTwoOps() {
        // CR-6 — the catastrophic case. A's callback is LOST; the watchdog fires and advances to B. When A's
        // callback finally arrives late, its free() must be a no-op — NOT cancel B's watchdog and start C
        // alongside B (two concurrent signing ops = one-time Winternitz key reuse).
        List<String> log = new ArrayList<>();
        Rec a = new Rec("A", log), b = new Rec("B", log), c = new Rec("C", log);
        SignGate.submit(a);
        SignGate.submit(b);
        SignGate.submit(c);
        assertEquals(Arrays.asList("A"), log);

        assertTrue("watchdog abandons A and dispatches B", SignGate.fireWatchdogForTest());
        assertEquals(Arrays.asList("A", "B"), log);

        a.rel.free();   // A's lost callback arrives at last
        assertEquals("A's stale free must not advance past the in-flight B", Arrays.asList("A", "B"), log);

        b.rel.free();   // B completes normally → C may start
        assertEquals(Arrays.asList("A", "B", "C"), log);
        c.rel.free();
    }
}
