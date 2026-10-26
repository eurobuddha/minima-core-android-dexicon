// VENDORED VERBATIM from apks/atomix 0.1.84 app/src/main/java/com/eurobuddha/comms/SignGate.java by scripts/vendor-atomix.sh — do not edit here; re-vendor.
package com.eurobuddha.comms;

import android.os.Handler;
import android.os.Looper;

import java.util.ArrayDeque;

/**
 * SERIAL SIGNING. Only one signing operation from this app may be in flight at a time.
 *
 * Minima signatures are stateful: each key is a tree of one-time (Winternitz) signatures and the node
 * picks the next leaf by reading, incrementing and writing a per-key {@code uses} counter. Two
 * operations signing the same key at once both read the same value and both sign the SAME leaf over
 * DIFFERENT data — a reused one-time signature, which leaks that leaf's private key. Not theoretical:
 * 7 of 64 default keys on a live node were confirmed re-used, witness-exact.
 *
 * AtomiX is unusually exposed to this:
 *   • MainActivity and SwapService each construct their OWN SwapEngine and MinimaHtlc, and most of the
 *     de-duplication guards are instance fields — only CP_LOCKING is static;
 *   • the settlement loop retries claims on a timer with no persistent dedup, so a failing claim
 *     re-signs indefinitely, at double rate whenever both engines are alive;
 *   • its swap identity is deliberately PINNED to one key (so the maker's published key stays
 *     constant), which concentrates every claim, refund and change output onto that single key.
 *
 * Everything that signs must pass through here — the {@code txnsign} sequences in MinimaHtlc AND the
 * bare {@code send} commands in {@link CommsTransport}, because {@code send} signs internally too and
 * is the highest-frequency signer in the app (one per order publish, OTC publish and tombstone).
 *
 * The node has since been fixed to synchronize its own signing, but this gate stays: the app also runs
 * against nodes we don't control, and serialising is correct regardless.
 *
 * Static, so the two engines in this process share one queue. Everything is expected to run on the main
 * thread ({@link NodeApi} funnels every node callback back to it); {@link #submit} reposts to the main
 * thread if ever called off it (MA-23), so the queue is never mutated concurrently.
 *
 * Each dispatch carries a monotonic GENERATION (CR-6). A {@link Release} and the lost-callback watchdog
 * are both tied to the generation they were created for, so a callback that arrives AFTER its op was
 * already timed out cannot cancel a later op's watchdog or advance the queue past it — which would have
 * let two signing ops run at once, the exact one-time-key-reuse failure this class prevents.
 */
public final class SignGate {

    private static final ArrayDeque<Op> QUEUE = new ArrayDeque<>();
    private static boolean busy = false;
    private static Runnable watchdog = null;
    /** Bumped on every dispatch; identifies the in-flight op so a stale Release/watchdog is a no-op (CR-6). */
    private static long generation = 0;

    /** Longer than NodeApi's write timeout, so this only fires for a genuinely lost callback — never
     *  for an operation that is merely slow. Proof-of-work on a phone is not quick. */
    private static final long MAX_HOLD_MS = 200_000;

    private SignGate() {}

    /** Lazily resolved so the queue itself works without a Looper — the serialisation logic is plain
     *  Java and is unit-tested on the JVM. Only the lost-callback watchdog needs Android; without a
     *  Looper it is simply absent, which is correct for a test. */
    private static Handler main;
    private static boolean mainResolved = false;
    private static Handler main() {
        if (!mainResolved) {
            mainResolved = true;
            // Off-device, android.jar's stub THROWS rather than returning null, so catch broadly.
            try { Looper l = Looper.getMainLooper(); if (l != null) main = new Handler(l); }
            catch (Throwable noAndroidRuntime) { main = null; }
        }
        return main;
    }

    /** Queue a signing operation. It must call {@link Release#free()} exactly once, however it ends. */
    public static void submit(final Op op) {
        // The queue + busy flag are unsynchronised, safe only because every caller is on the main thread.
        // If one ever isn't, repost rather than corrupt the deque (MA-23). On the JVM (no Looper) main()
        // is null, so this is skipped and the plain serialisation logic stays directly unit-testable.
        Handler h = main();
        if (h != null && Looper.myLooper() != Looper.getMainLooper()) { h.post(() -> submit(op)); return; }
        QUEUE.add(op);
        if (!busy) next();
    }

    public interface Op { void run(Release release); }

    /** Idempotent — a sequence with several exit paths can safely call this from all of them. Only the
     *  Release for the CURRENT in-flight op may advance the queue (CR-6). */
    public static final class Release {
        private final long gen;
        private boolean done = false;
        Release(long gen) { this.gen = gen; }
        public void free() {
            if (done) return;                 // idempotent per instance
            done = true;
            if (gen != generation) return;    // our op was already timed out and the queue moved on — do NOT advance
            cancelWatchdog();
            advance();
        }
    }

    private static void next() {
        if (busy) return;                     // never dispatch a second op while one is in flight
        final Op op = QUEUE.poll();
        if (op == null) return;
        busy = true;
        final long gen = ++generation;        // this dispatch's identity
        Handler h = main();
        if (h != null) {
            watchdog = () -> {                // lost-callback recovery for THIS op only
                if (gen != generation) return;   // a real free already advanced us; a stale watchdog is a no-op
                watchdog = null;
                advance();
            };
            h.postDelayed(watchdog, MAX_HOLD_MS);
        }
        try {
            op.run(new Release(gen));
        } catch (Throwable t) {
            // The Op threw synchronously, before its async callback could ever free — advance so the queue
            // never jams (CR-5). The NORMAL path is async (op.run returns immediately and Release.free()
            // advances later), so only this exceptional path releases here. Guard on gen so we don't advance
            // twice if the op already freed before throwing.
            if (gen == generation) { cancelWatchdog(); advance(); }
        }
    }

    private static void advance() { busy = false; next(); }

    private static void cancelWatchdog() {
        if (watchdog != null) { Handler h = main(); if (h != null) h.removeCallbacks(watchdog); watchdog = null; }
    }

    // ---- test seams (package-private): the lost-callback watchdog is Android-only, so expose a way to
    //      simulate it firing and to reset static state between tests. ----

    /** Simulate the lost-callback watchdog firing for the current in-flight op (advances the queue exactly
     *  as the watchdog would). Returns true if it advanced. TEST ONLY. */
    static boolean fireWatchdogForTest() {
        if (!busy) return false;
        watchdog = null;
        advance();
        return true;
    }

    /** Clear all static state so each test starts clean. TEST ONLY (public so cross-package tests — e.g. the
     *  swap package's HTLC signing-path tests — can reset the shared static gate). Never called by app code. */
    public static void resetForTest() {
        QUEUE.clear(); busy = false; watchdog = null; generation = 0;
    }
}
