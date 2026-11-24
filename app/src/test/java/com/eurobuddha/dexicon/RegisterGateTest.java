package com.eurobuddha.dexicon;

import static org.junit.Assert.*;

import org.junit.Test;

/** Review 23 Nov 2026 (MINOR): "__register__" destroyed the SDK with replies still pending (a write in flight lost its
 *  outcome). It now waits until no request is pending, or 60 s. */
public class RegisterGateTest {
    @Test public void aReRegistrationWaitsForEveryPendingReply() {
        NodeTransportService.RegisterGate g = new NodeTransportService.RegisterGate();
        int[] ran = {0};
        g.register(() -> ran[0]++);
        assertEquals("nothing pending: at once", 1, ran[0]);
        int a = g.begin(), b = g.begin();
        g.register(() -> ran[0]++);
        assertEquals("a reply still pending: waits", 1, ran[0]);
        g.end(a);
        assertEquals(1, ran[0]);
        g.end(b);
        assertEquals("the last reply in: runs", 2, ran[0]);
        g.end(b);
        assertEquals(0, g.pending());
    }

    @Test public void aReplyThatNeverComesDoesNotHoldItForEver_andALateOldReplyDoesNotCountAgainstTheNewRun() {
        NodeTransportService.RegisterGate g = new NodeTransportService.RegisterGate();
        int[] ran = {0};
        int old = g.begin();                     // a request whose reply never comes
        g.register(() -> ran[0]++);
        assertEquals(0, ran[0]);
        g.force();                               // the 60 s are over
        assertEquals("it runs anyway", 1, ran[0]);
        int fresh = g.begin();                   // a request on the NEW registration
        g.register(() -> ran[0]++);
        g.end(old);                              // the old request's reply arrives late
        assertEquals("an old run's reply does not release the new run's wait", 1, ran[0]);
        g.end(fresh);
        assertEquals(2, ran[0]);
        g.force();                               // nothing waits: nothing happens
        assertEquals(2, ran[0]);
    }
}
