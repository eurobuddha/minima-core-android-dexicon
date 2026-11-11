package com.eurobuddha.account;

import org.minima.objects.Address;
import org.minima.objects.base.MiniData;
import org.minima.objects.base.MiniString;
import org.minima.objects.keys.Signature;
import org.minima.objects.keys.TreeKey;
import org.minima.utils.BIP39;
import org.minima.utils.Crypto;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;

/**
 * The Trading Account's keys. Dexicon holds these itself and signs locally, so on a stock Minima Core node the node
 * never sees a private key and no other companion app (even one that escalates to ADMIN) can spend account funds.
 *
 * <p>Derivation mirrors minima-core {@code Wallet.createNewKey} on a DOMAIN-SEPARATED base seed:
 * <pre>
 *   phrase mode : base = hash( MiniString("dexicon-account-v1"), BIP39.convertStringToSeed(phrase) )
 *   node  mode  : base = the node's  seedrandom modifier:dexicon-account-v1  (one ADMIN call at setup)
 *   privSeed(i) = hash(base, MiniData(i)) ; key(i) = new TreeKey(privSeed(i), 128, 4)   // 268,435,456 leaves
 *   script(i)   = "RETURN SIGNEDBY(" + pubkey(i) + ")" ; address(i) = new Address(script(i))
 * </pre>
 * Restoring the same 24 words into a Minima node does NOT reproduce these keys (two independent signers on one
 * Winternitz tree would reuse a one-time leaf).
 *
 * <p><b>A leaf is never handed out twice — not even across a restore.</b> The leaf comes ONLY from
 * {@link KeyUses#reserveNextUse} (durably persisted before it is returned), and before every signature the counter is
 * raised to the TIME FLOOR: one leaf per {@link #LEAF_MILLIS} ms since {@link #EPOCH_MS} (stock Minima's
 * block-as-key-uses idea, on wall-clock time so a lagging or hostile node cannot pull it back; the caller refuses to
 * sign when the phone clock disagrees with the chain). A restore on any phone therefore starts beyond every leaf an
 * earlier install signed, unless that install out-signed the clock — with leaf shares (index mod 4 per app) a
 * signature can step up to 4 indices, so "out-signed" means more than one signature per 8 s on one key, sustained (the
 * restore margin is scaled to match). A key never signs past its capacity (the vendored {@code TreeKey.sign} wraps to leaf 0) — the in-app
 * replacement for fork commit 477db548347392692c443bbfa9a4db193c195aab.
 */
public final class AccountKeys {

    public static final String DOMAIN = "dexicon-account-v1";
    public static final int KEYS_PER_LEVEL = 128, LEVELS = 4;
    /** 2026-01-01T00:00:00Z. */
    public static final long EPOCH_MS = 1767225600000L;
    public static final long LEAF_MILLIS = 2000L;

    /** Public keys survive restarts here so the expensive TreeKey build runs once per key per install. */
    public interface PubCache {
        String get(int index);
        void put(int index, String pubkey);
    }

    private final MiniData base;
    private final KeyUses uses;
    private final PubCache cache;
    private final Map<Integer, String> pubs = new HashMap<>();
    private final Map<Integer, TreeKey> trees = new HashMap<>();   // TreeKeyNode caches built branches: warm signs are cheap
    private final Object signLock = new Object();
    /** A restored / node-derived account's minimum leaf on EVERY key (see {@link #restoreFloor}). */
    private volatile int restoreFloor;

    /**
     * Leaf shares (owner, 11 Nov 2026): AtomiX and PandaPools hold the SAME Trading Account side by side, and Dexicon the
     * same words on its own phones. Two apps with one seed keep separate counters, so each signs ONLY bottom leaves whose
     * index mod {@link #LEAF_SLOTS} is its own slot — no two apps can ever sign different data with one leaf. Safe because
     * only the bottom leaf of a TreeKey signs the transaction; every upper-level leaf signs its child tree's root, the
     * same bytes every time, so sharing those reveals nothing (TreeKey.sign caches that signature for the same reason).
     */
    public static final int LEAF_SLOTS = 4, SLOT_DEXICON = 0, SLOT_ATOMIX = 1, SLOT_PANDAPOOLS = 2;
    /** This app's leaf share, 0..3; -1 = none (only direct construction in unit tests — Account always sets one). */
    private volatile int leafSlot = -1;

    private AccountKeys(MiniData base, KeyUses uses, PubCache cache) {
        if (base == null || base.getLength() < 16) throw new IllegalArgumentException("account seed missing");
        this.base = base; this.uses = uses; this.cache = cache;
    }

    public static AccountKeys fromPhrase(String phrase, KeyUses uses, PubCache cache) {
        MiniData seed = BIP39.convertStringToSeed(BIP39.cleanSeedPhrase(phrase));
        return new AccountKeys(Crypto.getInstance().hashObjects(new MiniString(DOMAIN), seed), uses, cache);
    }

    /** Node mode: {@code base} is the 0x hex the node returned for {@code seedrandom modifier:dexicon-account-v1}. */
    public static AccountKeys fromBase(String baseHex, KeyUses uses, PubCache cache) {
        return new AccountKeys(new MiniData(baseHex), uses, cache);
    }

    /** A public fingerprint of THIS account's seed (16 hex chars of hash(hash(base))): every cache keyed by it can never
     *  hand one account's keys or addresses to another (a replaced or restored vault). Reveals nothing about the seed. */
    public String fingerprint() {
        return Crypto.getInstance().hashObjects(new MiniString("fingerprint"), Crypto.getInstance().hashObject(base)).to0xString().substring(2, 18);
    }

    /** Deterministic per-modifier secret, the in-app {@code seedrandom}: same formula as the node's, on our base. */
    public String seedRandom(String modifier) {
        MiniData mod = MiniData.getMiniDataVersion(new MiniString(modifier));
        MiniData hashmod = Crypto.getInstance().hashObjects(mod, new MiniData("0xDEADDEAD"));
        return Crypto.getInstance().hashObjects(hashmod, base).to0xString();
    }

    private TreeKey tree(int index) {
        if (index < 0) throw new IllegalArgumentException("key index must be >= 0");
        synchronized (trees) {
            TreeKey t = trees.get(index);
            if (t == null) {
                t = new TreeKey(Crypto.getInstance().hashObjects(base, new MiniData(BigInteger.valueOf(index))), KEYS_PER_LEVEL, LEVELS);
                trees.put(index, t);
            }
            return t;
        }
    }

    public String publicKey(int index) {
        synchronized (pubs) { String p = pubs.get(index); if (p != null) return p; }
        String p = cache == null ? null : cache.get(index);
        if (p == null || p.isEmpty()) {
            p = tree(index).getPublicKey().to0xString();
            if (cache != null) cache.put(index, p);
        }
        synchronized (pubs) { pubs.put(index, p); }
        return p;
    }

    public String script(int index) { return "RETURN SIGNEDBY(" + publicKey(index) + ")"; }
    public String address(int index) { return new Address(script(index)).getAddressData().to0xString(); }
    public String miniAddress(int index) { return Address.makeMinimaAddress(new MiniData(address(index))); }

    public int usesOf(int index) { return uses.currentUses(index); }
    /** The counter as the node would show it: the leaf the NEXT signature will use at the earliest (stored counter,
     *  wall-clock floor, restore floor). A restored phone's store starts at 0, but it never signs below the floors —
     *  reporting the raw 0 would read as a counter regression to anything that recorded the old phone's count. */
    public int reportedUses(int index, long nowMillis) { return Math.max(uses.currentUses(index), Math.max(timeFloor(nowMillis), restoreFloor)); }
    /** Without a trusted clock: the stored counter and the restore floor only (writes nothing). */
    public int storedUses(int index) { return Math.max(uses.currentUses(index), restoreFloor); }
    /** {@link #reportedUses}, and the stored counter raised to it (raise-only, burns no leaf): a count once shown never
     *  goes down — even if the phone clock later steps back — so nothing that recorded it reads a regression. */
    public int reportUses(int index, long nowMillis) {
        synchronized (signLock) {
            int r = reportedUses(index, nowMillis);
            if (uses.currentUses(index) < r) uses.recordExternalUses(index, r);
            return r;
        }
    }
    public int maxUses() { return (int) Math.pow(KEYS_PER_LEVEL, LEVELS); }

    /** Leaf floor stored once when an account is RESTORED (or derived from the node seed): the time floor now, plus two
     *  phones' worth of allowed clock skew (2 × 30 min), plus a margin for an earlier install that signed faster than
     *  the clock — so a restore on a slow phone never lands on a leaf a fast one already signed. */
    public static int restoreFloor(long nowMillis) {
        long skewLeaves = 2 * (30 * 60_000L) / LEAF_MILLIS;
        // the out-signing margin scales with the leaf shares: each signature can now step up to LEAF_SLOTS indices
        return (int) Math.min(Integer.MAX_VALUE, (long) timeFloor(nowMillis) + skewLeaves + 10_000L * LEAF_SLOTS);
    }

    public void setRestoreFloor(int floor) { restoreFloor = Math.max(0, floor); }

    public void setLeafSlot(int slot) {
        if (slot < 0 || slot >= LEAF_SLOTS) throw new IllegalArgumentException("leaf slot must be 0.." + (LEAF_SLOTS - 1));
        leafSlot = slot;
    }
    public int leafSlot() { return leafSlot; }

    /** The smallest index >= {@code n} in {@code slot}'s share. */
    static int alignToSlot(int n, int slot) { return n + Math.floorMod(slot - n, LEAF_SLOTS); }

    /** The lowest leaf a signature at wall-clock {@code nowMillis} may use. */
    public static int timeFloor(long nowMillis) {
        long f = (nowMillis - EPOCH_MS) / LEAF_MILLIS;
        return (int) Math.max(0, Math.min(Integer.MAX_VALUE, f));
    }

    /** Sign a transaction id with key {@code index} at wall-clock {@code nowMillis}. One signer at a time; the counter
     *  is raised to the time floor and the leaf reserved durably BEFORE the signature exists. */
    public Signature sign(MiniData txnId, int index, long nowMillis) {
        synchronized (signLock) {
            TreeKey key = tree(index);
            if (!key.getPublicKey().to0xString().equalsIgnoreCase(publicKey(index)))
                throw new IllegalStateException("account key " + index + " does not match its cached public key — refusing to sign");
            int floor = Math.max(timeFloor(nowMillis), restoreFloor);
            if (uses.currentUses(index) < floor) uses.recordExternalUses(index, floor);
            int slot = leafSlot;
            if (slot >= 0) {   // raise (never lower: MAX rule) to the next leaf in this app's share
                int cur = uses.currentUses(index), aligned = alignToSlot(cur, slot);
                if (aligned != cur) uses.recordExternalUses(index, aligned);
            }
            int leaf = uses.reserveNextUse(index);
            if (leaf < floor) throw new IllegalStateException("account key " + index + " leaf store did not advance — refusing to sign");
            if (slot >= 0 && Math.floorMod(leaf, LEAF_SLOTS) != slot)
                throw new IllegalStateException("account key " + index + " leaf " + leaf + " is outside this app's share " + slot + " — refusing to sign");
            if (leaf >= key.getMaxUses())
                throw new IllegalStateException("account key " + index + " exhausted: " + leaf + " / " + key.getMaxUses()
                        + " one-time signatures used — refusing to sign (would reuse a leaf)");
            key.setUses(leaf);
            return key.sign(txnId);
        }
    }
}
