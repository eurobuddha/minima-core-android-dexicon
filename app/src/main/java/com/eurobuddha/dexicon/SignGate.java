package com.eurobuddha.dexicon;

/**
 * SERIAL SIGNING. Only one signing operation from this app may be in flight at a time.
 *
 * Minima signatures are stateful: each key is a tree of one-time (Winternitz) signatures and the node
 * picks the next leaf by reading, incrementing and writing a per-key {@code uses} counter. Two
 * transactions signing the same key at once both read the same value and both sign the SAME leaf over
 * DIFFERENT data — a reused one-time signature, which leaks that leaf's private key. This is not
 * theoretical: 7 of 64 default keys on a live node were confirmed re-used, witness-exact.
 *
 * Dexicon supplies that concurrency in two ways (ported from PandaDEX SignGate):
 *   • the maker engine posts several actions per cycle, and
 *   • MainActivity (taker build) and MakerService (maker co-sign) each construct their OWN {@code DexTxn}, so the only thing
 *     separating them is a snapshot {@code MainActivity.FOREGROUND} boolean read once at the top of a
 *     pipeline that then runs for minutes.
 *
 * Every path that signs must pass through here — the {@code txnsign} chains AND the bare {@code send}
 * commands that create orders, because {@code send} signs internally too.
 *
 * The node has since been fixed to synchronize its own signing, but this gate stays: the app also runs
 * against nodes we don't control, and serialising is correct anyway.
 *
 * Static, so the two engines in this process genuinely share one queue. Everything runs on the main
 * thread ({@link NodeApi} funnels every node callback back to it), so no locking is needed.
 */
public final class SignGate {

    private static final SerialQueue QUEUE = new SerialQueue();
    private SignGate() {}
    public interface Op { void run(Release release); }
    public static void submit(Op op) { QUEUE.submit(finish -> op.run(new Release(finish))); }
    public static final class Release {
        private final Runnable finish;
        private Release(Runnable finish) { this.finish = finish; }
        public void free() { finish.run(); }
    }
}
