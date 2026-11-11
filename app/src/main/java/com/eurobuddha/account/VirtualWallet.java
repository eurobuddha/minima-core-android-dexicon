package com.eurobuddha.account;

import org.json.JSONArray;
import org.json.JSONObject;
import org.minima.database.userprefs.txndb.TxnRow;
import org.minima.objects.Coin;
import org.minima.objects.CoinProof;
import org.minima.objects.ScriptProof;
import org.minima.objects.StateVariable;
import org.minima.objects.Token;
import org.minima.objects.Transaction;
import org.minima.objects.Witness;
import org.minima.objects.base.MiniData;
import org.minima.objects.base.MiniNumber;
import org.minima.objects.keys.Signature;
import org.minima.system.brains.TxPoWGenerator;
import org.minima.system.commands.Command;
import org.minima.system.commands.CommandRunner;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * The stock-Minima-Core answer to every node change Dexicon used to depend on: an in-app WALLET + TRANSACTION TABLE that
 * presents the Trading Account to Dexicon's engines exactly as a node would present its own wallet.
 *
 * <ul>
 *   <li><b>Wallet verbs</b> ({@code getaddress newaddress keys balance send consolidate seedrandom vault scripts}, and
 *       wallet-scoped {@code coins}) are answered from the account. The node never holds its keys.</li>
 *   <li><b>Every {@code txn*} verb</b> runs on a local table built from minima.jar's own {@link Transaction} /
 *       {@link Witness} / {@link TxnRow}, so every byte matches what a node would produce. A transaction reaches the node
 *       only once, fully signed, through {@code postfrom} — nothing unsigned ever sits in the node's shared, unprotected
 *       transaction table (replaces fork txn write-gate 15f5a37cb3e4b4aa2e3f3485700f6ecf1f86cdf2).</li>
 *   <li><b>Everything else</b> (chain reads, contract addresses, {@code newscript}, {@code cointrack}) returns
 *       {@code null} = pass through to the node unchanged.</li>
 * </ul>
 *
 * Thread model: {@link #handle} runs on ONE worker thread and blocks on {@link Node#call}; it never runs on the thread
 * that delivers node replies.
 */
public final class VirtualWallet {

    /** A blocking node call returning the node's full JSON reply. Throws on transport failure. */
    public interface Node { JSONObject call(String command) throws Exception; }

    /** How many account keys exist. Durable. */
    public interface Store {
        int keyCount();
        void setKeyCount(int n);
        /** Output coin ids of every transaction this account signed: a later account coin NOT among them arrived
         *  from outside (a deposit) — the wallet history's "Received" rows. */
        default void ownOutputs(java.util.Collection<String> coinIds) {}
        default boolean isOwnOutput(String coinId) { return false; }
        /** Our posts by their PRE-MINING txpowid (JSON object; see {@link #txpowOnchain}). */
        default String posted() { return "{}"; }
        /** Durable (synchronous) save; false = not written. */
        default boolean savePosted(String json) { return true; }
        /** How many account addresses (key count) the last clean MegaMMR archive pass covered on this node
         *  ({@link #archiveWanted}); 0 = never. */
        default int archivedKeys(String nodeId) { return Integer.MAX_VALUE; }
        default void setArchivedKeys(String nodeId, int keyCount) {}
    }

    /** Wall clock (tests inject one). */
    public interface Clock { long now(); }

    /** State port carrying the owner's tracked account address on every contract output we build, so the node keeps
     *  that coin relevant (TxPoWTreeNode.checkRelevant: a HEX state equal to a tracked address) — the replacement for
     *  "my node key sits in the lock's state". No contract in the family reads port 254. */
    public static final int OWNER_PORT = 254;
    /** The phone clock may disagree with the chain tip's time by at most this much before we refuse to sign: the
     *  one-time-leaf floor is wall-clock time (AccountKeys). */
    public static final long MAX_CLOCK_SKEW_MS = 30 * 60_000L;
    /** postfrom data limit. The node's REPLY echoes the data AND the whole TxPoW as JSON (~2.2–2.8× the hex), and a
     *  reply over ~131K chars kills the receiving process on stock Minima Core — so the posted hex stays ≤ 40K
     *  (≈ 3 signatures + a handful of coin proofs). Measured on 1.1.2.31: 46,456 hex → 54,418 chars of TxPoW JSON. */
    public static final int MAX_POST_HEX = 40_000;
    /** Inputs one send / consolidate may spend, so a signed transaction fits {@link #MAX_POST_HEX}. */
    public static final int MAX_SEND_INPUTS = 8, MAX_CONSOLIDATE_INPUTS = 6;
    /** A signature of a 128×4 TreeKey, hex chars (measured on a 1.1.2.31 Signature: 4 SignatureProofs). */
    static final int SIG_HEX_ESTIMATE = 12_000;
    /** At most this many coin rows in ONE node reply — stock Minima Core has no content:// hand-off and a reply of
     *  ~131K chars kills the receiving process (a token coin row is ~1.2K chars). */
    public static final int MAX_COIN_ROWS = 60;

    private static final Set<String> WALLET_VERBS = new HashSet<>(Arrays.asList(
            "getaddress", "newaddress", "keys", "balance", "send", "consolidate", "seedrandom", "vault",
            "sign", "sendpoll", "sendnosign", "multisig", "checkmode", "scripts"));

    private final Node node;
    private final AccountKeys keys;
    private final Store store;
    private final Clock clock;
    private final Set<String> contractAddresses;   // outputs that get the owner port (HTLC addresses …)
    private final Map<String, TxnRow> txns = new LinkedHashMap<>();
    private final Map<String, Cached> proofs = new HashMap<>();        // coinid -> byte-exact coin + MMR proof, short-lived
    private final Map<String, String> scripts = new HashMap<>();       // address -> script text
    private final Map<String, Long> spending = new HashMap<>();        // coinid -> when we posted a txn spending it
    private final SecureRandom rnd = new SecureRandom();
    private Set<String> nodeSimple;                                     // the NODE wallet's own simple addresses
    private long nodeSimpleAt;
    /** Re-checked immediately before the one write (postfrom): the engine's "market changed" contract. */
    private BooleanSupplier postGuard;

    private static final long PROOF_TTL_MS = 60_000L, SPENDING_TTL_MS = 15 * 60_000L, NODE_SIMPLE_TTL_MS = 10 * 60_000L;

    private static final class Cached { final CoinProof cp; final String data; final long at; Cached(CoinProof cp, String data, long at) { this.cp = cp; this.data = data; this.at = at; } }

    public VirtualWallet(Node node, AccountKeys keys, Store store, Set<String> contractAddresses) {
        this(node, keys, store, contractAddresses, System::currentTimeMillis);
    }

    public VirtualWallet(Node node, AccountKeys keys, Store store, Set<String> contractAddresses, Clock clock) {
        this.node = node; this.keys = keys; this.store = store; this.clock = clock;
        this.contractAddresses = new HashSet<>();
        if (contractAddresses != null) for (String a : contractAddresses) this.contractAddresses.add(norm(a));
        if (store.keyCount() < 1) store.setKeyCount(1);
        loadPosted();   // NodeApi routes txpow onchain: by the known ids from the first call on
    }

    /** Every txn* verb (the node's shared table must never hold our drafts), the wallet verbs, and coins. */
    public static boolean handles(String verb) { return verb.startsWith("txn") || WALLET_VERBS.contains(verb) || "coins".equals(verb); }

    // ------------------------------------------------------------------------------------------------------------ setup

    /** Register every account script with the node (trackall:true) unless already tracked. Re-run periodically: any
     *  companion can downgrade a script row with its own newscript (newscript REPLACES the row). */
    public synchronized void ensureTracked() throws Exception {
        for (int i = 0; i < store.keyCount(); i++) {
            track(i);
            String a = keys.address(i);
            if (!recovered.contains(norm(a)) && recoverExisting(a)) recovered.add(norm(a));
        }
    }
    /** Addresses whose existing coins were looked for on THIS node in this process (a node switch restarts the app). */
    private final Set<String> recovered = new HashSet<>();

    /** True when the node was NOT tracking this address yet (it was just registered). */
    private boolean track(int i) throws Exception {
        String addr = keys.address(i);
        scripts.put(norm(addr), keys.script(i));
        JSONObject s = node.call("scripts address:" + addr);
        JSONObject r = s.optJSONObject("response");
        if (s.optBoolean("status") && r != null && addr.equalsIgnoreCase(r.optString("address")) && r.optBoolean("track")) return false;
        JSONObject n = node.call("newscript trackall:true script:\"" + keys.script(i) + "\"");
        if (!n.optBoolean("status")) throw new IllegalStateException("could not register account address " + addr + ": " + err(n));
        return true;
    }

    /**
     * A node that has not tracked this address since its coins arrived does not know them: tracking only follows NEW coins
     * (10 Nov, on the Fold: the account moved to BlackBear read 0 while Minima Core showed its funds). Look for them in
     * the node's unpruned chain and import each one the node does not already hold, with its proof and tracking — the
     * same coinexport/coinimport the lock scan uses. Once per address per process; true only after a clean pass (a
     * failure is retried on the next ensureTracked). Bounded: an address holding more than MAX_COIN_ROWS coins (a dust
     * flood) is refused before the row read — the reply must never reach the IPC limit, nor stall the signing worker.
     * Older coins (beyond the node's window) come from the MegaMMR archive: {@link #importArchived}.
     */
    private boolean recoverExisting(String addr) {
        try {
            int n = coinCount(addr, null);   // `balance address:` counts every coin at the address in the unpruned chain
            if (n == 0) return true;
            if (n > MAX_COIN_ROWS) return true;   // refused, not retried: consolidating there needs them imported anyway
            Set<String> held = new HashSet<>();
            JSONObject mine = node.call("coins relevant:true address:" + addr);
            JSONArray mr = mine.optBoolean("status") ? mine.optJSONArray("response") : null;
            if (mr == null) return false;
            for (int k = 0; k < mr.length(); k++) held.add(norm(mr.getJSONObject(k).optString("coinid")));
            if (held.size() >= n) return true;   // the node already holds them all
            JSONObject r = node.call("coins address:" + addr);   // no relevant: = the unpruned chain
            JSONArray rows = r.optBoolean("status") ? r.optJSONArray("response") : null;
            if (rows == null) return false;
            boolean clean = true;
            for (int k = 0; k < rows.length(); k++) {
                JSONObject row = rows.optJSONObject(k);
                if (row == null || row.optBoolean("spent", false) || !norm(addr).equals(norm(row.optString("address")))) continue;
                if (held.contains(norm(row.optString("coinid")))) continue;
                JSONObject ex = node.call("coinexport coinid:" + row.optString("coinid"));
                JSONObject resp = ex.optJSONObject("response");
                if (!ex.optBoolean("status") || resp == null) { clean = false; continue; }
                if (!node.call("coinimport track:true data:" + resp.getString("data")).optBoolean("status")) clean = false;
            }
            return clean;
        } catch (Exception later) { return false; }
    }

    /**
     * The node's identity for the archive pass: its data folder ({@code status} → {@code data}), which differs per node
     * app (Minima Core, BlackBear …). Not {@code scripts}: that reply grows with every tracked script and came back
     * without a usable row on BlackBear (10 Nov, the pass silently never ran). Null = the archive pass already covered
     * every account address on this node. A coin the node saw arrive is tracked; only coins OLDER than its tracking (it
     * took the account over from another node, or after a restore) are missing. Re-run when the key count grows.
     * ponytail: a resync or reseed of the SAME node keeps its folder, and an archive that withholds coins is believed;
     * add a manual "search the archive again" if either is ever seen.
     */
    public synchronized String archiveWanted() throws Exception {
        JSONObject st = node.call("status");
        JSONObject r = st.optBoolean("status") ? st.optJSONObject("response") : null;
        String id = r == null ? "" : r.optString("data", "").trim();
        if (id.isEmpty()) throw new IllegalStateException("the node's status gave no data folder: " + err(st));
        return store.archivedKeys(id) >= store.keyCount() ? null : id;
    }

    /** Record a clean pass over the first {@code keyCount} account addresses on this node. */
    public synchronized void markArchived(String nodeId, int keyCount) { store.setArchivedKeys(nodeId, keyCount); }

    /**
     * Import coins this node is missing from part of the archive's reply ({@link ArchiveProofs#fetch}, downloaded OFF the
     * signing worker; the caller hands it over in small batches so signing interleaves). Each proof must be at an account
     * address and verified by THIS node: {@code coincheck} needs the proof's MMR on the node's own chain and refuses a coin
     * spent at any block from the proof up to the node's tip (MMR.checkProofTimeValid) — the archive's proofs are taken
     * 256 blocks below its tip (coinexport), so that is what keeps a spent coin out, not the proof's age. A spend still
     * only in a mempool is not seen; the tracked spend clears it when mined. True when every proof was handled.
     */
    public synchronized boolean importArchived(List<CoinProof> archive) throws Exception { return importArchived(archive, null); }

    /** As above; {@code log} (may be null) gets one line per coin: its id and what happened to it. */
    public synchronized boolean importArchived(List<CoinProof> archive, java.util.function.Consumer<String> log) throws Exception {
        boolean clean = true;
        for (CoinProof cp : archive) {
            String id = cp.getCoin().getCoinID().to0xString();
            if (indexOfAddress(cp.getCoin().getAddress().to0xString()) < 0) continue;
            String outcome;
            Boolean held = relevantNow(id);
            if (held == null) { clean = false; outcome = "lookup failed"; }
            else if (held) outcome = "already held";
            else {
                String data = MiniData.getMiniDataVersion(cp).to0xString();
                JSONObject chk = node.call("coincheck data:" + data);
                JSONObject r = chk.optJSONObject("response");
                if (!chk.optBoolean("status") || r == null || !r.optBoolean("valid")) { clean = false; outcome = "refused by coincheck: " + err(chk); }   // spent, or not synced yet
                else {
                    JSONObject im = node.call("coinimport track:true data:" + data);
                    if (!im.optBoolean("status")) { clean = false; outcome = "coinimport failed: " + err(im); }
                    else if (!Boolean.TRUE.equals(relevantNow(id))) { clean = false; outcome = "imported but not visible as relevant"; }
                    else outcome = "imported";
                }
            }
            if (log != null) log.accept(id + " " + outcome);
        }
        return clean;
    }

    /** Is the coin one this node tracks as unspent? null = the lookup failed. */
    private Boolean relevantNow(String coinid) throws Exception {
        JSONObject mine = node.call("coins relevant:true coinid:" + coinid);
        JSONArray held = mine.optBoolean("status") ? mine.optJSONArray("response") : null;
        return held == null ? null : held.length() > 0;
    }
    /** Proofs handed to {@link #importArchived} per worker task (each costs three node calls). */
    public static final int ARCHIVE_BATCH = 16;

    public List<String> accountAddresses() {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < store.keyCount(); i++) out.add(keys.address(i));
        return out;
    }

    private int indexOfAddress(String address) {
        String a = norm(address);
        for (int i = 0; i < store.keyCount(); i++) if (norm(keys.address(i)).equals(a)) return i;
        return -1;
    }

    private int indexOfKey(String pubkey) {
        String p = norm(pubkey);
        for (int i = 0; i < store.keyCount(); i++) if (norm(keys.publicKey(i)).equals(p)) return i;
        return -1;
    }

    // ------------------------------------------------------------------------------------------------------- dispatch

    /** Answer {@code command} locally, or return null to pass it to the node unchanged. {@code guard} is re-checked right
     *  before any post. */
    /** Engine guards can have SIDE EFFECTS (PandaPools' Write.admit records a pending-write marker and refuses a second
     *  call), so a guard is evaluated EXACTLY ONCE — as the node's own queue does: immediately before the post for a
     *  command that writes (txnpost / send / consolidate), at the start for everything else. */
    public synchronized JSONObject handle(String command, BooleanSupplier guard) {
        String verb = command.trim().split("\\s+", 2)[0].toLowerCase();
        boolean writes = verb.equals("txnpost") || verb.equals("send") || verb.equals("consolidate");
        if (guard != null && !writes) {
            boolean go; try { go = guard.getAsBoolean(); } catch (RuntimeException e) { go = false; }
            if (!go) throw new ToCaller("Market changed; refresh and try again. No command was sent.");
        }
        postGuard = writes ? guard : null;
        try { return handle(command); } finally { postGuard = null; }
    }

    /** An answer that is NOT an ordinary node reply: an engine guard's refusal, or a post whose outcome is unknown. It
     *  reaches the caller's onError (as the node's own queue does), so an engine keeps its pending-write marker and its
     *  coin locks — a "status:false" would tell it nothing was broadcast and invite a second spend. */
    public static final class ToCaller extends RuntimeException { ToCaller(String m) { super(m); } }

    /** The engine's guard, evaluated exactly once, BEFORE any key leaf is spent on a write. */
    private void admit() {
        if (postGuard == null) return;
        BooleanSupplier g = postGuard;
        postGuard = null;
        boolean go; try { go = g.getAsBoolean(); } catch (RuntimeException e) { go = false; }
        if (!go) throw new ToCaller("Market changed; refresh and try again. Nothing was signed or posted.");
    }

    public synchronized JSONObject handle(String command) {
        String verb = command.trim().split("\\s+", 2)[0].toLowerCase();
        if ("txpow".equals(verb)) { try { return txpowOnchain(command); } catch (Exception e) { return null; } }
        if (!handles(verb)) return null;
        try {
            if ("coins".equals(verb)) return coins(command);
            Command c = CommandRunner.getRunner().getCommand(command);
            P p = new P(c);
            switch (verb) {
                case "getaddress":  return ok(verb, addressRow(0, true));
                case "scripts":     return scriptsCmd(p);
                case "newaddress":  return newAddress();
                case "keys":        return keysCmd(p);
                case "balance":     return balance(p);
                case "send":        return send(p);
                case "consolidate": return consolidate(p);
                case "seedrandom":  return ok(verb, new JSONObject().put("modifier", p.s("modifier", "")).put("seedrandom", keys.seedRandom(p.s("modifier", ""))));
                case "vault":       return fail(verb, "The Trading Account seed never leaves this app.");
                case "checkmode":   return ok(verb, new JSONObject().put("mode", "WRITE").put("writemode", true));
                case "txncreate":   return txnCreate(p);
                case "txninput":    return txnInput(p);
                case "txnoutput":   return txnOutput(p);
                case "txnstate":    return txnState(p);
                case "txnsign":     return txnSign(p);
                case "txnbasics":   case "txnmmr": return txnBasics(p, verb);
                case "txnpost":     return txnPost(p);
                case "txndelete":   return txnDelete(p);
                case "txnexport":   return txnExport(p);
                case "txnimport":   return txnImport(p);
                case "txnview":     return txnView(p);
                case "txnlist":     return txnList(p);
                case "txncheck":    return txnCheck(p);
                case "txnclear":    return txnClear(p);
                default:            return fail(verb, verb + " is not available on the Trading Account.");
            }
        } catch (ToCaller e) {
            throw e;
        } catch (Exception e) {
            return fail(verb, e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    // ------------------------------------------------------------------------------------------------------- wallet

    private JSONObject addressRow(int i, boolean dflt) throws Exception {
        String addr = keys.address(i);
        return new JSONObject().put("script", keys.script(i)).put("address", addr).put("miniaddress", keys.miniAddress(i))
                .put("simple", true).put("default", dflt).put("publickey", keys.publicKey(i)).put("track", true);
    }

    /** The node's script table as the engines read it to decide "mine": the ACCOUNT's scripts are the simple (signable)
     *  wallet; the node's own simple scripts are dropped (Dexicon cannot sign them — FillTxn / MakerEngine must never
     *  treat them as ours); contract scripts pass through. {@code address:} of a non-account address = the node's. */
    private JSONObject scriptsCmd(P p) throws Exception {
        String address = p.s("address", "");
        if (!address.isEmpty()) {
            int k = indexOfAddress(address);
            return k < 0 ? null : ok("scripts", addressRow(k, k == 0));
        }
        JSONObject r = node.call("scripts");
        if (!r.optBoolean("status")) return r;
        JSONArray rows = r.optJSONArray("response"), out = new JSONArray();
        for (int i = 0; i < store.keyCount(); i++) out.put(addressRow(i, i == 0));
        if (rows != null) for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            if (row.optBoolean("simple") || indexOfAddress(row.optString("address")) >= 0) continue;
            out.put(row);
        }
        return ok("scripts", out);
    }

    private JSONObject newAddress() throws Exception {
        int i = store.keyCount();
        track(i);                          // registered BEFORE anyone can be given the address
        store.setKeyCount(i + 1);
        return ok("newaddress", addressRow(i, false).put("total", i + 1));
    }

    private JSONObject keysCmd(P p) throws Exception {
        String action = p.s("action", "list");
        if ("genkey".equals(action)) return genkey(p);
        if (!"list".equals(action)) return fail("keys", "keys action:" + action + " is not available on the Trading Account.");
        String only = p.s("publickey", "");
        // the stored counter is raised only with a clock the chain vouches for (as signing requires): a phone clock set
        // ahead must never burn leaves on a mere read
        long now = -1;
        try { now = checkedClock(); } catch (Exception unchecked) { }
        JSONArray arr = new JSONArray();
        for (int i = 0; i < store.keyCount(); i++) {
            if (!only.isEmpty() && !norm(only).equals(norm(keys.publicKey(i)))) continue;
            arr.put(new JSONObject().put("size", AccountKeys.KEYS_PER_LEVEL).put("depth", AccountKeys.LEVELS)
                    .put("uses", now > 0 ? keys.reportUses(i, now) : keys.storedUses(i)).put("maxuses", keys.maxUses())
                    .put("modifier", new MiniData(java.math.BigInteger.valueOf(i)).to0xString()).put("publickey", keys.publicKey(i)));
        }
        return ok("keys", new JSONObject().put("keys", arr).put("total", arr.length()).put("maxuses", keys.maxUses()));
    }

    /** The node's {@code keys action:genkey phrase:} — a throw-away key from a PUBLIC phrase, stored nowhere. PandaPools'
     *  owner recovery uses it as its key-mode probe (BlockOwnerRecovery.MODE_COMMAND), so it must give the node's exact
     *  answer: seed = BIP39(phrase), key = TreeKey(hash(seed, 0)) in this account's 128x4 shape (= the node in block
     *  mode). Phrase required (no random keys), and the private key is never returned. */
    private JSONObject genkey(P p) throws Exception {
        String phrase = p.s("phrase", "");
        if (phrase.isEmpty()) return fail("keys", "keys action:genkey needs phrase: on the Trading Account.");
        MiniData seed = org.minima.utils.BIP39.convertStringToSeed(phrase);
        MiniData priv = org.minima.utils.Crypto.getInstance().hashObjects(seed, new MiniData(java.math.BigInteger.ZERO));
        String pub = new org.minima.objects.keys.TreeKey(priv, AccountKeys.KEYS_PER_LEVEL, AccountKeys.LEVELS).getPublicKey().to0xString();
        String script = "RETURN SIGNEDBY(" + pub + ")";
        org.minima.objects.Address a = new org.minima.objects.Address(script);
        return ok("keys", new JSONObject().put("phrase", phrase).put("modifier", 0).put("publickey", pub).put("script", script)
                .put("address", a.getAddressData().to0xString()).put("miniaddress", org.minima.objects.Address.makeMinimaAddress(a.getAddressData())));
    }

    /** Coin rows (node JSON) at every account address, with optional filters passed to the node. A count preflight
     *  (balance) refuses a read whose reply could exceed the IPC limit — a dust flood can never kill the app. */
    private List<JSONObject> accountCoinRows(String tokenid, boolean checkMempool, String coinage, String extra) throws Exception {
        List<JSONObject> out = new ArrayList<>();
        for (String a : accountAddresses()) {
            int n = coinCount(a, tokenid);
            if (n == 0) continue;
            if (n > MAX_COIN_ROWS)
                throw new IllegalStateException("The Trading Account holds " + n + " coins at one address — too many to read safely in one reply. Consolidate first (the wallet screen's CONSOLIDATE).");
            StringBuilder q = new StringBuilder("coins relevant:true address:").append(a);
            if (tokenid != null && !tokenid.isEmpty()) q.append(" tokenid:").append(tokenid);
            if (checkMempool) q.append(" checkmempool:true");
            if (coinage != null && !coinage.isEmpty()) q.append(" coinage:").append(coinage);
            if (extra != null && !extra.isEmpty()) q.append(' ').append(extra);
            JSONObject r = node.call(q.toString());
            if (!r.optBoolean("status")) throw new IllegalStateException("coins: " + err(r));
            JSONArray rows = r.optJSONArray("response");
            if (rows != null) for (int k = 0; k < rows.length(); k++) out.add(rows.getJSONObject(k));
        }
        return out;
    }

    /** Number of relevant coins at {@code address} (optionally one token): one small balance row per token. */
    private int coinCount(String address, String tokenid) throws Exception {
        JSONObject r = node.call("balance address:" + address + (tokenid == null || tokenid.isEmpty() ? "" : " tokenid:" + tokenid));
        if (!r.optBoolean("status")) throw new IllegalStateException("balance: " + err(r));
        JSONArray rows = r.optJSONArray("response");
        int n = 0;
        if (rows != null) for (int i = 0; i < rows.length(); i++) n += Integer.parseInt(rows.getJSONObject(i).optString("coins", "0"));
        return n;
    }

    private JSONObject coins(String command) throws Exception {
        Command c = CommandRunner.getRunner().getCommand(command);
        P p = new P(c);
        String address = p.s("address", "");
        boolean relevant = p.has("relevant") || (!p.has("coinid") && !p.has("address") && !p.has("state") && !p.has("tokenid"));
        boolean sendable = p.b("sendable", false);
        if (p.has("coinid")) return coinById(p, relevant, sendable);
        if (!address.isEmpty() && indexOfAddress(address) < 0) return null;      // a contract / shared address (Account notices locks)
        if (!relevant && address.isEmpty()) return null;                          // chain search (state / tokenid)
        if (sendable || !address.isEmpty()) {                                     // MY spendable coins
            String extra = rebuild(p, "relevant", "sendable", "address", "tokenid", "checkmempool", "coinage");
            List<JSONObject> rows = new ArrayList<>();
            for (JSONObject row : accountCoinRows(p.s("tokenid", ""), p.b("checkmempool", false), p.s("coinage", ""), extra)) {
                if (!address.isEmpty() && !norm(address).equals(norm(row.optString("address")))) continue;
                if (sendable && isSpending(row.optString("coinid"))) continue;
                rows.add(row);
            }
            return ok("coins", new JSONArray(rows));
        }
        // relevant:true wallet-wide = MY relevant coins: the account's own + those at the contract addresses (my locks carry
        // the owner port, locks to me are imported with tracking). Never the node's unbounded relevant set: it holds the
        // user's whole node wallet, and a fragmented one would kill the app (the 0.1.57 parcel kill, by another road).
        String extra = rebuild(p, "relevant", "sendable", "address", "tokenid", "checkmempool", "coinage");
        String tok = p.s("tokenid", ""), age = p.s("coinage", "");
        boolean mem = p.b("checkmempool", false);
        // The account's coins only: no caller needs the shared HTLC address here (locks are found by the hash / key scans),
        // and counting that PUBLIC address would let anyone block every wallet read with 61 dust outputs.
        return ok("coins", new JSONArray(accountCoinRows(tok, mem, age, extra)));
    }

    private final Set<String> imported = new HashSet<>();

    /** A counterparty's lock that names one of our keys is not relevant on our node (the node's key table never holds
     *  account keys). Track it the moment a scan first sees it — not at claim time, when it may be past the window. */
    public synchronized void noticeLocksToUs(JSONObject reply) {
        JSONArray rows = reply == null ? null : reply.optJSONArray("response");
        if (rows == null) return;
        for (int k = 0; k < rows.length(); k++) {
            JSONObject row = rows.optJSONObject(k);
            if (row == null) continue;
            String id = norm(row.optString("coinid"));
            if (id.isEmpty() || imported.contains(id)) continue;
            JSONArray st = row.optJSONArray("state");
            boolean ours = false;
            if (st != null) for (int i = 0; i < st.length() && !ours; i++) {
                JSONObject v = st.optJSONObject(i);
                String d = v == null ? "" : v.optString("data", "");
                ours = indexOfKey(d) >= 0 || indexOfAddress(d) >= 0;
            }
            if (!ours || row.optBoolean("spent", false)) continue;   // a spent lock imported is a phantom balance on the node
            // Tracked at first sight, so a late claim can still prove it after the node's ~1024-block tree (14 h) prunes
            // it — no scan ever sees a lock older than that, so tracking cannot wait (review of 0.3.27). Known cost: once
            // spent, a lock imported this way stays in the NODE's balance as unspent (the spend of a coin the node does not
            // otherwise watch is never marked; cointrack cannot remove it; a resync clears it) — display only, never the
            // account's funds. Locks from AtomiX 0.2.32 on carry the receiver's address at port 253 (an address the node
            // tracks), so the node holds those itself and marks their spend — they are skipped here; the import remains
            // for locks made by older apps.
            try {
                if (Boolean.TRUE.equals(relevantNow(row.optString("coinid")))) { imported.add(id); continue; }
                if (!unspentNow(row.optString("coinid"))) continue;
                JSONObject ex = node.call("coinexport coinid:" + row.optString("coinid"));
                JSONObject resp = ex.optJSONObject("response");
                if (!ex.optBoolean("status") || resp == null) continue;                 // retried on the next scan
                JSONObject im = node.call("coinimport track:true data:" + resp.getString("data"));
                if (im.optBoolean("status")) imported.add(id);
            } catch (Exception later) { /* retried on the next scan */ }
        }
    }

    /** One coin. {@code sendable:true} means "spendable by ME": an account address (the node's own sendable flag means
     *  its simple wallet, which we cannot sign). {@code relevant:true} falls back to the bare lookup — a counterparty's
     *  lock addressed to our key is not relevant on our node until we track it. */
    private JSONObject coinById(P p, boolean relevant, boolean sendable) throws Exception {
        String id = p.s("coinid", "");
        String mem = p.b("checkmempool", false) ? " checkmempool:true" : "";
        JSONObject r = node.call("coins coinid:" + id + (relevant ? " relevant:true" : "") + mem);
        if (relevant && r.optBoolean("status") && r.optJSONArray("response") != null && r.getJSONArray("response").length() == 0)
            r = node.call("coins coinid:" + id + mem);
        if (!r.optBoolean("status")) return r;
        if (!sendable) return r;
        JSONArray rows = r.optJSONArray("response"), kept = new JSONArray();
        if (rows != null) for (int k = 0; k < rows.length(); k++) {
            JSONObject row = rows.getJSONObject(k);
            if (indexOfAddress(row.optString("address")) >= 0 && !isSpending(row.optString("coinid"))) kept.put(row);
        }
        return ok("coins", kept);
    }

    private Set<String> nodeSimpleAddresses() throws Exception {
        if (nodeSimple != null && clock.now() - nodeSimpleAt < NODE_SIMPLE_TTL_MS) return nodeSimple;
        Set<String> out = new HashSet<>();
        JSONObject r = node.call("scripts");
        JSONArray rows = r.optJSONArray("response");
        if (rows != null) for (int k = 0; k < rows.length(); k++) {
            JSONObject s = rows.getJSONObject(k);
            if (s.optBoolean("simple") && indexOfAddress(s.optString("address")) < 0) out.add(norm(s.optString("address")));
        }
        nodeSimple = out; nodeSimpleAt = clock.now();
        return out;
    }

    private JSONObject balance(P p) throws Exception {
        String only = p.s("tokenid", ""), onlyAddr = p.s("address", "");
        if (!onlyAddr.isEmpty() && indexOfAddress(onlyAddr) < 0) return ok("balance", new JSONArray());
        long tip = Long.parseLong(blockInfo().optString("block", "0"));
        int conf = Integer.parseInt(p.s("confirmations", "3"));
        List<JSONObject> rows = accountCoinRows(only, false, "", "");
        Set<String> notInMempool = new HashSet<>();   // checkmempool:true drops coins a pending txn already spends
        for (JSONObject r : accountCoinRows(only, true, "", "")) notInMempool.add(norm(r.optString("coinid")));
        Map<String, BigBal> per = new LinkedHashMap<>();
        per.put("0x00", new BigBal());
        for (JSONObject row : rows) {
            if (!onlyAddr.isEmpty() && !norm(onlyAddr).equals(norm(row.optString("address")))) continue;
            String tid = row.optString("tokenid", "0x00");
            BigBal b = per.computeIfAbsent(tid, x -> new BigBal());
            if (b.token == null) b.token = row.optJSONObject("token");
            java.math.BigDecimal amt = new java.math.BigDecimal(row.has("tokenamount") && !"0x00".equals(tid) ? row.getString("tokenamount") : row.getString("amount"));
            long created = Long.parseLong(row.optString("created", "0"));
            b.coins++;
            if (tip - created >= conf) {
                b.confirmed = b.confirmed.add(amt);
                String id = norm(row.optString("coinid"));
                if (notInMempool.contains(id) && !isSpending(id)) b.sendable = b.sendable.add(amt);
            } else b.unconfirmed = b.unconfirmed.add(amt);
        }
        JSONArray out = new JSONArray();
        for (Map.Entry<String, BigBal> e : per.entrySet()) {
            if (!only.isEmpty() && !only.equalsIgnoreCase(e.getKey())) continue;
            BigBal b = e.getValue();
            boolean minima = "0x00".equals(e.getKey());
            if (!minima && b.coins == 0) continue;
            JSONObject t = new JSONObject();
            t.put("token", minima ? "Minima" : (b.token == null ? JSONObject.NULL : b.token.opt("name")));
            t.put("tokenid", e.getKey());
            t.put("confirmed", b.confirmed.toPlainString()).put("unconfirmed", b.unconfirmed.toPlainString())
                    .put("sendable", b.sendable.toPlainString()).put("coins", String.valueOf(b.coins))
                    .put("total", minima ? "1000000000" : (b.token == null ? "0" : b.token.optString("total", "0")));
            out.put(t);
        }
        return ok("balance", out);
    }

    private static final class BigBal {
        java.math.BigDecimal confirmed = java.math.BigDecimal.ZERO, unconfirmed = java.math.BigDecimal.ZERO, sendable = java.math.BigDecimal.ZERO;
        int coins; JSONObject token;
    }

    // --------------------------------------------------------------------------------------------- send / consolidate

    private boolean isSpending(String coinid) {
        String id = norm(coinid);
        Long at = spending.get(id);
        if (at == null) return false;
        if (clock.now() - at > SPENDING_TTL_MS) { spending.remove(id); return false; }   // checkmempool covers mining txns
        return true;
    }

    /** Spendable account coin ROWS of {@code tokenid}: old enough, not in a pending spend. Nothing exported yet. */
    private List<JSONObject> spendableRows(String tokenid, int coinage, String fromAddress) throws Exception {
        List<JSONObject> out = new ArrayList<>();
        for (JSONObject row : accountCoinRows(tokenid, true, String.valueOf(coinage), "")) {
            if (fromAddress != null && !fromAddress.isEmpty() && !norm(fromAddress).equals(norm(row.optString("address")))) continue;
            if (isSpending(row.optString("coinid"))) continue;
            out.add(row);
        }
        return out;
    }

    /** node {@code send.selectCoins}: the smallest single coin that covers the amount, else accumulate largest-first.
     *  On coin ROWS ("amount" is the raw minima amount for every token); only the picked coins get exported. */
    static List<JSONObject> select(List<JSONObject> coins, MiniNumber need, int maxInputs) {
        JSONObject single = null; MiniNumber singleAmt = null;
        for (JSONObject c : coins) {
            MiniNumber a = new MiniNumber(c.optString("amount", "0"));
            if (a.isMoreEqual(need) && (single == null || a.isLess(singleAmt))) { single = c; singleAmt = a; }
        }
        List<JSONObject> out = new ArrayList<>();
        if (single != null) { out.add(single); return out; }
        List<JSONObject> sorted = new ArrayList<>(coins);
        sorted.sort((x, y) -> new MiniNumber(y.optString("amount", "0")).compareTo(new MiniNumber(x.optString("amount", "0"))));
        MiniNumber sum = MiniNumber.ZERO;
        for (JSONObject c : sorted) {
            if (out.size() >= maxInputs) break;
            out.add(c); sum = sum.add(new MiniNumber(c.optString("amount", "0")));
            if (sum.isMoreEqual(need)) return out;
        }
        return null;
    }

    private JSONObject send(P p) throws Exception {
        String tokenid = p.s("tokenid", "0x00");
        String to = toHexAddress(p.s("address", ""));
        if (to.isEmpty()) return fail("send", "send needs address:");
        if (p.has("multi")) return fail("send", "send multi: is not available on the Trading Account.");
        if (!MiniNumber.ZERO.isEqual(new MiniNumber(p.s("burn", "0")))) return fail("send", "send burn: is not available on the Trading Account.");
        int split = Integer.parseInt(p.s("split", "1"));
        if (split < 1 || split > 20) return fail("send", "split must be 1..20");
        boolean storestate = p.b("storestate", true);
        int coinage = Integer.parseInt(p.s("coinage", "3"));
        if (coinage < 3) return fail("send", "Coinage MUST be >= 3");
        MiniNumber amount = new MiniNumber(p.s("amount", "0"));
        if (!amount.isMore(MiniNumber.ZERO)) return fail("send", "amount must be > 0");

        List<JSONObject> rows = spendableRows(tokenid, coinage, p.s("fromaddress", ""));
        if (rows.isEmpty()) return fail("send", "No Coins of tokenid:" + tokenid + " available!");
        Token token = null;
        if (!"0x00".equalsIgnoreCase(tokenid)) {
            token = proof(rows.get(0).getString("coinid")).getCoin().getToken();
            if (token == null) return fail("send", "No token descriptor for " + tokenid);
            if (!"RETURN TRUE".equalsIgnoreCase(token.getTokenScript().toString().trim()))
                return fail("send", "This token has its own script — the Trading Account only sends plain tokens.");
        }
        MiniNumber want = token == null ? amount : token.getScaledMinimaAmount(amount);
        List<JSONObject> pickedRows = select(rows, want, MAX_SEND_INPUTS);
        if (pickedRows == null) return fail("send", "Insufficient funds in " + MAX_SEND_INPUTS + " coins — consolidate first (the wallet screen's CONSOLIDATE).");
        Set<String> signers = new HashSet<>();
        for (JSONObject r : pickedRows) signers.add(norm(r.optString("address")));
        if (pickedRows.size() * 3_000 + signers.size() * SIG_HEX_ESTIMATE + 4_000 > MAX_POST_HEX)
            return fail("send", "Those coins are spread over too many addresses for one transaction — consolidate first (the wallet screen's CONSOLIDATE).");

        Transaction t = new Transaction();
        List<CoinProof> picked = new ArrayList<>();
        MiniNumber in = MiniNumber.ZERO;
        for (JSONObject row : pickedRows) { CoinProof cp = proof(row.getString("coinid")); picked.add(cp); t.addInput(cp.getCoin()); in = in.add(cp.getCoin().getAmount()); }
        if (in.isLess(want)) return fail("send", "Insufficient funds");
        MiniNumber each = want.div(new MiniNumber(split)), done = MiniNumber.ZERO;
        if (!each.isMore(MiniNumber.ZERO)) return fail("send", "Cannot have ZERO output");
        for (int i = 0; i < split; i++) { t.addOutput(out(to, each, tokenid, token, storestate)); done = done.add(each); }
        MiniNumber rem = want.sub(done);
        if (rem.isMore(MiniNumber.ZERO)) t.addOutput(out(to, rem, tokenid, token, storestate));
        MiniNumber change = in.sub(want);
        String chg = p.s("fromaddress", "").isEmpty() ? keys.address(0) : toHexAddress(p.s("fromaddress", ""));
        if (change.isMore(MiniNumber.ZERO)) t.addOutput(out(chg, change, tokenid, token, false));
        Object st = p.raw("state");
        if (st instanceof org.minima.utils.json.JSONObject) {
            org.minima.utils.json.JSONObject sj = (org.minima.utils.json.JSONObject) st;
            for (Object k : sj.keySet()) t.addStateVariable(new StateVariable(Integer.parseInt(String.valueOf(k)), String.valueOf(sj.get(k))));
        }
        TxnRow row = new TxnRow(freshId(), t, new Witness());
        for (CoinProof c : picked) row.getWitness().addCoinProof(c);
        ownerPort(row);
        TxPoWGenerator.precomputeTransactionCoinID(t);
        t.calculateTransactionID();
        if (p.b("dryrun", false)) return ok("send", new JSONObject().put("dryrun", true).put("txn", new JSONObject(row.toJSON().toString())));
        attachScripts(row);
        admit();
        JSONObject signed = signInputs(row, null);
        if (signed.has("error")) return fail("send", signed.getString("error"));
        JSONObject posted = post(row);
        if (!posted.optBoolean("status")) return posted;
        return ok("send", posted.opt("response"));
    }

    private static Coin out(String address, MiniNumber amt, String tokenid, Token token, boolean storestate) {
        Coin c = new Coin(Coin.COINID_OUTPUT, new MiniData(address), amt, Token.TOKENID_MINIMA, storestate);
        if (token != null) { c.resetTokenID(new MiniData(tokenid)); c.setToken(token); }
        return c;
    }

    private JSONObject consolidate(P p) throws Exception {
        String tokenid = p.s("tokenid", "");
        if (tokenid.isEmpty()) return fail("consolidate", "consolidate needs tokenid:");
        int coinage = Math.max(3, Integer.parseInt(p.s("coinage", "3")));
        int maxcoins = Math.max(3, Math.min(MAX_CONSOLIDATE_INPUTS, Integer.parseInt(p.s("maxcoins", String.valueOf(MAX_CONSOLIDATE_INPUTS)))));   // stays under the postfrom size cap
        List<JSONObject> all = spendableRows(tokenid, coinage, "");
        // ONE address per consolidation = one signature, so 6 inputs always fit the post size cap; the address with
        // the most coins first.
        Map<String, List<JSONObject>> byAddr = new LinkedHashMap<>();
        for (JSONObject r : all) byAddr.computeIfAbsent(norm(r.optString("address")), x -> new ArrayList<>()).add(r);
        List<JSONObject> rows = new ArrayList<>();
        for (List<JSONObject> l : byAddr.values()) if (l.size() > rows.size()) rows = l;
        if (rows.size() < 2) return fail("consolidate", "Nothing to consolidate: no address holds 2 or more coins of this token.");
        rows.sort((a, b) -> new MiniNumber(a.optString("amount", "0")).compareTo(new MiniNumber(b.optString("amount", "0"))));   // smallest first: sweep dust
        Transaction t = new Transaction();
        List<CoinProof> picked = new ArrayList<>();
        MiniNumber sum = MiniNumber.ZERO;
        Token token = null;
        for (JSONObject row : rows.subList(0, Math.min(maxcoins, rows.size()))) {
            CoinProof c = proof(row.getString("coinid"));
            picked.add(c); t.addInput(c.getCoin()); sum = sum.add(c.getCoin().getAmount());
            if (token == null) token = c.getCoin().getToken();
        }
        if (picked.size() < 3) t.addOutput(out(keys.address(0), sum, tokenid, token, false));   // 2 coins -> 1: the count drops
        else {
            MiniNumber half = sum.div(MiniNumber.TWO);
            t.addOutput(out(keys.address(0), half, tokenid, token, false));
            t.addOutput(out(keys.address(0), sum.sub(half), tokenid, token, false));
        }
        TxnRow row = new TxnRow(freshId(), t, new Witness());
        for (CoinProof c : picked) row.getWitness().addCoinProof(c);
        TxPoWGenerator.precomputeTransactionCoinID(t);
        t.calculateTransactionID();
        if (p.b("dryrun", false)) return ok("consolidate", new JSONObject().put("dryrun", true).put("coins", picked.size()).put("txn", new JSONObject(row.toJSON().toString())));
        attachScripts(row);
        admit();
        JSONObject signed = signInputs(row, null);
        if (signed.has("error")) return fail("consolidate", signed.getString("error"));
        JSONObject posted = post(row);
        if (!posted.optBoolean("status")) return posted;
        return ok("consolidate", posted.opt("response"));
    }

    // ------------------------------------------------------------------------------------------------- txn emulation

    private TxnRow row(P p) {
        TxnRow r = txns.get(p.s("id", ""));
        if (r == null) throw new IllegalArgumentException("Transaction not found : " + p.s("id", ""));
        return r;
    }

    private JSONObject rowJson(String verb, TxnRow r) throws Exception { return ok(verb, new JSONObject(r.toJSON().toString())); }

    private JSONObject txnCreate(P p) throws Exception {
        String id = p.s("id", "");
        if (id.isEmpty()) return fail("txncreate", "txncreate needs id:");
        TxnRow r = new TxnRow(id, new Transaction(), new Witness());
        txns.put(id, r);
        return rowJson("txncreate", r);
    }

    private JSONObject txnInput(P p) throws Exception {
        TxnRow r = row(p);
        CoinProof cp;
        if (p.has("coinid")) cp = proof(p.s("coinid", ""));
        else if (p.has("coindata")) { Coin c = Coin.convertMiniDataVersion(new MiniData(p.s("coindata", ""))); if (c == null) throw new IllegalArgumentException("ERROR importing coin data"); cp = proof(c.getCoinID().to0xString()); }
        else return fail("txninput", "txninput needs coinid: (floating inputs are not available on the Trading Account)");
        Transaction t = r.getTransaction();
        t.addInput(cp.getCoin());
        TxPoWGenerator.precomputeTransactionCoinID(t);
        t.calculateTransactionID();
        if (p.b("scriptmmr", false)) { addProof(r.getWitness(), cp); addScript(r.getWitness(), cp.getCoin().getAddress().to0xString()); }
        return rowJson("txninput", r);
    }

    /** Building a spend (txninput) never tracks the coin it spends: that import is exactly the one that becomes a phantom
     *  once the spend lands, and the coin is already tracked from first sight if it is a lock to us (noticeLocksToUs). */

    /** True only when the node's chain lists {@code coinid} and it is unspent right now. */
    private boolean unspentNow(String coinid) throws Exception {
        JSONObject r = node.call("coins coinid:" + coinid + " checkmempool:true");   // a pending spend counts as spent
        JSONArray rows = r.optBoolean("status") ? r.optJSONArray("response") : null;
        if (rows == null || rows.length() == 0) return false;
        JSONObject c = rows.optJSONObject(0);
        return c != null && !c.optBoolean("spent", false);
    }

    private JSONObject txnOutput(P p) throws Exception {
        TxnRow r = row(p);
        String tokenid = p.s("tokenid", "0x00");
        MiniNumber amount = new MiniNumber(p.s("amount", "0"));
        Token token = null;
        if (!"0x00".equalsIgnoreCase(tokenid)) {
            token = tokenFor(r, tokenid);
            if (token == null) return fail("txnoutput", "Token not found : " + tokenid);
            amount = token.getScaledMinimaAmount(amount);
        }
        Transaction t = r.getTransaction();
        t.addOutput(out(toHexAddress(p.s("address", "")), amount, tokenid, token, p.b("storestate", true)));
        TxPoWGenerator.precomputeTransactionCoinID(t);
        t.calculateTransactionID();
        return rowJson("txnoutput", r);
    }

    /** The token descriptor, byte-exact: from a coin already in this transaction, else the node's own token export —
     *  never rebuilt from coins JSON (a JSON token name re-serialises differently and consensus drops the txn). */
    private Token tokenFor(TxnRow r, String tokenid) throws Exception {
        for (Coin c : r.getTransaction().getAllInputs()) if (c.getToken() != null && c.getTokenID().to0xString().equalsIgnoreCase(tokenid)) return c.getToken();
        for (Coin c : r.getTransaction().getAllOutputs()) if (c.getToken() != null && c.getTokenID().to0xString().equalsIgnoreCase(tokenid)) return c.getToken();
        JSONObject ex = node.call("tokens action:export tokenid:" + tokenid);
        JSONObject resp = ex.optJSONObject("response");
        if (ex.optBoolean("status") && resp != null && !resp.optString("data", "").isEmpty()) {
            Token tk = Token.convertMiniDataVersion(new MiniData(resp.getString("data")));
            if (tk != null && tk.getTokenID().to0xString().equalsIgnoreCase(tokenid)) return tk;
        }
        return null;
    }

    private JSONObject txnState(P p) throws Exception {
        TxnRow r = row(p);
        Transaction t = r.getTransaction();
        t.addStateVariable(new StateVariable(Integer.parseInt(p.s("port", "")), p.s("value", "")));
        t.calculateTransactionID();
        return rowJson("txnstate", r);
    }

    private JSONObject txnSign(P p) throws Exception {
        TxnRow r = row(p);
        String pk = p.s("publickey", "");
        if ("custom".equals(pk)) return fail("txnsign", "publickey:custom is not available on the Trading Account.");
        ownerPort(r);
        JSONObject resp = signInputs(r, "auto".equals(pk) ? null : pk);
        if (resp.has("error")) return fail("txnsign", resp.getString("error"));
        resp.put("txnpost", false);
        return ok("txnsign", resp);
    }

    /** Sign with account keys: {@code onlyKey == null} = every input at an account address ("auto"). Refuses before any
     *  leaf is spent when the phone clock disagrees with the chain or the post would be too large. */
    private JSONObject signInputs(TxnRow r, String onlyKey) throws Exception {
        Transaction t = r.getTransaction();
        Witness w = r.getWitness();
        TxPoWGenerator.precomputeTransactionCoinID(t);
        t.calculateTransactionID();
        JSONArray found = new JSONArray(), nonsimple = new JSONArray(), notfound = new JSONArray();
        List<Integer> toSign = new ArrayList<>();
        if (onlyKey != null) {
            int k = indexOfKey(onlyKey);
            if (k < 0) return new JSONObject().put("error", "Public Key not found : " + onlyKey);
            toSign.add(k);
        } else {
            for (Coin c : t.getAllInputs()) {
                String a = c.getAddress().to0xString();
                int k = indexOfAddress(a);
                if (k < 0) { if (scriptFor(a) == null) notfound.put(a); else nonsimple.put(a); continue; }
                if (!toSign.contains(k)) toSign.add(k);
            }
        }
        List<Integer> needed = new ArrayList<>();
        for (int k : toSign) if (!w.isSignedBy(keys.publicKey(k))) needed.add(k);
        if (!needed.isEmpty()) {
            long now = checkedClock();
            int estimate = hex(r).length() + needed.size() * SIG_HEX_ESTIMATE + proofEstimate(r);
            if (estimate > MAX_POST_HEX)
                return new JSONObject().put("error", "Transaction too large for the node link (~" + estimate + " chars) — use fewer coins. Nothing was signed.");
            for (int k : needed) {
                Signature s = keys.sign(t.getTransactionID(), k, now);
                w.addSignature(s);
                found.put(keys.publicKey(k));
            }
        }
        if (!toSign.isEmpty()) {   // our key is in this transaction: its outputs are never "received from outside"
            List<String> outs = new ArrayList<>();
            for (Coin c : t.getAllOutputs()) outs.add(norm(c.getCoinID().to0xString()));
            store.ownOutputs(outs);
        }
        JSONObject resp = new JSONObject().put("keys", found);
        if (nonsimple.length() > 0) resp.put("nonsimple", nonsimple);
        if (notfound.length() > 0) resp.put("notfound", notfound);
        return resp;
    }

    /** Hex chars of the coin + script proofs still to be attached before posting. */
    private int proofEstimate(TxnRow r) {
        int have = r.getWitness().getAllCoinProofs().size(), inputs = r.getTransaction().getAllInputs().size();
        return Math.max(0, inputs - have) * 3_000;
    }

    /** Wall clock, after checking it against the chain tip's time. */
    private long checkedClock() throws Exception {
        JSONObject b = blockInfo();
        long tipTime = Long.parseLong(b.optString("timemilli", "0")), now = clock.now();
        if (tipTime <= 0 || Math.abs(now - tipTime) > MAX_CLOCK_SKEW_MS)
            throw new IllegalStateException("This phone's clock and the Minima chain disagree by more than 30 minutes (or the node is not synced). Fix the clock / wait for the node, then retry. Nothing was signed.");
        return now;
    }

    /** Contract outputs that keep state get {@link #OWNER_PORT} = account address 0, BEFORE any signature (it changes the
     *  txn id), when the transaction spends account coins (we are the owner). A lock that would miss it after a
     *  counterparty already signed is refused: our node would forget it after the cascade. */
    private void ownerPort(TxnRow r) {
        Transaction t = r.getTransaction();
        boolean contractOut = false, ours = false;
        for (Coin c : t.getAllOutputs()) if (c.storeState() && contractAddresses.contains(norm(c.getAddress().to0xString()))) contractOut = true;
        for (Coin c : t.getAllInputs()) if (indexOfAddress(c.getAddress().to0xString()) >= 0) ours = true;
        if (!contractOut || !ours || t.getStateValue(OWNER_PORT) != null) return;
        if (!r.getWitness().getAllSignatures().isEmpty())
            throw new IllegalStateException("A swap lock here would not carry the Trading Account's owner mark and was already signed by someone else — refusing to sign it.");
        t.addStateVariable(new StateVariable(OWNER_PORT, keys.address(0)));
        t.calculateTransactionID();
    }

    private JSONObject txnBasics(P p, String verb) throws Exception {
        TxnRow r = row(p);
        attachScripts(r);
        return rowJson(verb, r);
    }

    /** Coin proofs (byte-exact, from coinexport) + script proofs for every input that lacks them. */
    private void attachScripts(TxnRow r) throws Exception {
        Witness w = r.getWitness();
        Set<String> have = new HashSet<>();
        for (CoinProof cp : w.getAllCoinProofs()) have.add(norm(cp.getCoin().getCoinID().to0xString()));
        for (Coin c : r.getTransaction().getAllInputs()) {
            String id = norm(c.getCoinID().to0xString());
            if (!have.contains(id)) { addProof(w, proof(c.getCoinID().to0xString())); have.add(id); }
            addScript(w, c.getAddress().to0xString());
        }
        TxPoWGenerator.precomputeTransactionCoinID(r.getTransaction());
        r.getTransaction().calculateTransactionID();
    }

    /** Fresh proofs for every input just before posting (witness data — the txn id is unchanged): a proof older than
     *  the node's MMR history is silently invalid. A coin our node cannot export keeps the proof it came with. */
    private void refreshProofs(TxnRow r) {
        Witness w = r.getWitness();
        Map<String, CoinProof> current = new LinkedHashMap<>();
        for (CoinProof cp : w.getAllCoinProofs()) current.put(norm(cp.getCoin().getCoinID().to0xString()), cp);
        List<CoinProof> fresh = new ArrayList<>();
        for (Coin c : r.getTransaction().getAllInputs()) {
            String id = norm(c.getCoinID().to0xString());
            CoinProof cp = null;
            try { proofs.remove(id); cp = proof(c.getCoinID().to0xString()); } catch (Exception keepOld) { cp = current.get(id); }
            if (cp != null) fresh.add(cp);
        }
        if (fresh.size() != r.getTransaction().getAllInputs().size()) return;   // keep the witness as built
        w.clearCoinProofs();
        for (CoinProof cp : fresh) w.addCoinProof(cp);
    }

    private static void addProof(Witness w, CoinProof cp) {
        String id = cp.getCoin().getCoinID().to0xString();
        for (CoinProof x : w.getAllCoinProofs()) if (x.getCoin().getCoinID().to0xString().equalsIgnoreCase(id)) return;
        w.addCoinProof(cp);
    }

    private void addScript(Witness w, String address) throws Exception {
        if (w.getScript(new MiniData(address)) != null) return;
        String s = scriptFor(address);
        if (s == null) throw new IllegalStateException("SERIOUS ERROR script missing for address : " + address);
        w.addScript(new ScriptProof(s));
    }

    private String scriptFor(String address) throws Exception {
        String a = norm(address);
        String s = scripts.get(a);
        if (s != null) return s;
        int k = indexOfAddress(address);
        if (k >= 0) { s = keys.script(k); scripts.put(a, s); return s; }
        JSONObject r = node.call("scripts address:" + address);
        JSONObject resp = r.optJSONObject("response");
        if (!r.optBoolean("status") || resp == null || resp.optString("script", "").isEmpty()) return null;
        s = resp.getString("script");
        scripts.put(a, s);
        return s;
    }

    private JSONObject txnPost(P p) throws Exception {
        TxnRow r = row(p);
        if (!MiniNumber.ZERO.isEqual(new MiniNumber(p.s("burn", "0")))) return fail("txnpost", "txnpost burn: is not available on the Trading Account.");
        attachScripts(r);
        JSONObject res = post(r);
        if (res.optBoolean("status") && p.b("txndelete", false)) txns.remove(r.getID());
        if (!res.optBoolean("status")) return res;
        return ok("txnpost", res.opt("response"));
    }

    /** The ONE write: a fully-signed transaction to the node through postfrom, under a fresh unguessable id.
     *  ALWAYS mine:false, whatever the caller asked: stock Minima Core runs IPC commands on its UI thread, and mine:true
     *  mines there synchronously for up to 120 s (ANR risk; a timed-out mine also leaves the inputs flagged "in the
     *  mempool" until the node restarts). The reply's txpowid is then the pre-mining id; the engines reconcile from
     *  the chain (proven on mainnet 9 Nov: AtomiX lock + claim, pool swap/create/close, DEX take). */
    private JSONObject post(TxnRow r) throws Exception {
        admit();
        refreshProofs(r);
        TxnRow out = new TxnRow(freshId(), r.getTransaction(), r.getWitness());
        String hex = hex(out);
        if (hex.length() > MAX_POST_HEX) return fail("txnpost", "Transaction too large for the node link (" + hex.length() + " chars) — split it. Nothing was posted.");
        JSONObject res;
        try { res = node.call("postfrom data:" + hex + " mine:false"); }
        catch (Exception noAnswer) {
            // The node may have posted it: lock the inputs as in flight and report UNCERTAIN, never "not accepted".
            rememberPost(r.getTransaction().getTransactionID().to0xString(), r.getTransaction());   // provable by its txn id
            long now = clock.now();
            for (Coin c : r.getTransaction().getAllInputs()) if (indexOfAddress(c.getAddress().to0xString()) >= 0) spending.put(norm(c.getCoinID().to0xString()), now);
            StringBuilder made = new StringBuilder(), spends = new StringBuilder();
            List<Coin> outs = new ArrayList<>(r.getTransaction().getAllOutputs());
            outs.sort((x, y) -> Boolean.compare(indexOfAddress(y.getAddress().to0xString()) >= 0, indexOfAddress(x.getAddress().to0xString()) >= 0));   // ours first: we can always see them
            for (Coin c : outs) made.append(made.length() == 0 ? "" : ",").append(c.getCoinID().to0xString());
            for (Coin c : r.getTransaction().getAllInputs()) spends.append(spends.length() == 0 ? "" : ",").append(c.getCoinID().to0xString());
            throw new ToCaller("UNCERTAIN: the node has not confirmed that write; it may still complete (" + noAnswer.getMessage()
                    + "). Outcome is unknown — check your coins before trying again. Transaction " + r.getTransaction().getTransactionID().to0xString()
                    + " creates coins " + made + " and spends coins " + spends + ".");
        }
        Object resp = res.opt("response");
        if (!res.optBoolean("status") || !(resp instanceof JSONObject) || ((JSONObject) resp).optString("txpowid", "").isEmpty())
            return fail("txnpost", "The node did not accept the transaction" + (res.has("error") ? ": " + res.optString("error") : "") + ". Nothing was spent.");
        String posted = postedTxnId((JSONObject) resp);
        String ours = r.getTransaction().getTransactionID().to0xString();
        if (posted != null && !posted.equalsIgnoreCase(ours))
            return fail("txnpost", "The node posted a different transaction (" + posted + ") than ours (" + ours + ") — another app interfered. Check your coins.");
        long now = clock.now();
        for (Coin c : r.getTransaction().getAllInputs()) if (indexOfAddress(c.getAddress().to0xString()) >= 0) spending.put(norm(c.getCoinID().to0xString()), now);
        rememberPost(((JSONObject) resp).optString("txpowid"), r.getTransaction());
        return ok("txnpost", resp);
    }

    // ---------------------------------------------------------------------------------- txpow onchain: (mine:false)

    /** Posts are mine:false (stock mines mine:true on its UI thread), so the txpowid an engine records is the PRE-mining
     *  id: mining changes it and {@code txpow onchain:<that id>} never finds it (AtomiX left a landed claim at CLAIMING).
     *  Each post is remembered by that id (or, when the node never answered, by its transaction id) with its OUTPUTS
     *  (coin id, address, amount, token). Proof = an output on chain with exactly those fields — a coin id alone is not
     *  enough (it derives from the FIRST INPUT + index: an HTLC refund of the same input makes the same id elsewhere).
     *  "found" only at depth >= {@link #PROVEN_DEPTH}; while an output is visible it is re-read every call (a reorg moves
     *  or removes it); once proven the answer is final. The block id: that block's txpow, fetched once (marked first, so
     *  an oversized block can cost one failed fetch, never a loop), or {@code txpow inblock:} once past the cascade.
     *  Nothing else of unknown size is fetched. */
    static final int PROVEN_DEPTH = 3, CASCADE_DEPTH = 1024, OPEN_CAP = 200, FINAL_CAP = 3000, BLOCK_MARKS = 500, MAX_TRIES = 6, MAX_DEATHS = 3;
    private static final String BLOCKS = "_blocks";
    /** Ids this account can answer (open + final), for NodeApi's routing on the caller's thread. */
    private static final Set<String> KNOWN = java.util.Collections.synchronizedSet(new HashSet<>());

    private JSONObject loadPosted() {
        JSONObject all;
        try { all = new JSONObject(store.posted()); } catch (Exception bad) { all = new JSONObject(); }
        refreshKnown(all);
        return all;
    }

    private static void refreshKnown(JSONObject all) {
        synchronized (KNOWN) { KNOWN.clear(); for (java.util.Iterator<String> it = all.keys(); it.hasNext(); ) { String k = it.next(); if (!BLOCKS.equals(k)) KNOWN.add(k); } }
    }

    private boolean savePosted(JSONObject all) {
        int open = 0, fin = 0;
        for (java.util.Iterator<String> it = all.keys(); it.hasNext(); ) {
            String k = it.next(); if (BLOCKS.equals(k)) continue;
            JSONObject r = all.optJSONObject(k); if (r != null && r.optBoolean("final")) fin++; else open++;
        }
        for (java.util.Iterator<String> it = all.keys(); it.hasNext() && (open > OPEN_CAP || fin > FINAL_CAP); ) {   // oldest first, per kind
            String k = it.next(); if (BLOCKS.equals(k)) continue;
            JSONObject r = all.optJSONObject(k);
            boolean f = r != null && r.optBoolean("final");
            if (f && fin > FINAL_CAP) { it.remove(); fin--; } else if (!f && open > OPEN_CAP) { it.remove(); open--; }
        }
        JSONObject blocks = all.optJSONObject(BLOCKS);
        if (blocks != null) for (java.util.Iterator<String> it = blocks.keys(); it.hasNext() && blocks.length() > BLOCK_MARKS; ) { it.next(); it.remove(); }
        boolean ok = store.savePosted(all.toString());
        refreshKnown(all);
        return ok;
    }

    private void rememberPost(String id, Transaction t) {
        try {
            if (id == null || !id.matches("(?i)0x[0-9a-f]{64}")) return;
            JSONObject all = loadPosted();
            JSONArray outs = new JSONArray(), ins = new JSONArray();
            for (Coin c : t.getAllOutputs())
                outs.put(new JSONObject().put("coinid", c.getCoinID().to0xString()).put("address", c.getAddress().to0xString())
                        .put("amount", c.getAmount().toString()).put("tokenid", c.getTokenID().to0xString()));
            for (Coin c : t.getAllInputs())   // in full: a coin id alone is not an identity (first input + index)
                ins.put(new JSONObject().put("coinid", c.getCoinID().to0xString()).put("address", c.getAddress().to0xString())
                        .put("amount", c.getAmount().toString()).put("tokenid", c.getTokenID().to0xString()));
            all.put(idKey(id), new JSONObject().put("txnid", t.getTransactionID().to0xString()).put("outs", outs).put("ins", ins));
            savePosted(all);
        } catch (Exception ignored) { }
    }

    /** One spelling for a stored id: 0x + upper-case hex. */
    static String idKey(String id) { return "0x" + id.substring(2).toUpperCase(); }

    /** {@code txpow … onchain:<id>} for an id this account posted — the only txpow form routed to it. */
    public static boolean answersTxpow(String command) {
        String c = command.trim();
        if (!c.toLowerCase(java.util.Locale.ROOT).startsWith("txpow ")) return false;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?i)onchain:(0x[0-9a-f]{64})").matcher(c);
        return m.find() && KNOWN.contains(idKey(m.group(1)));
    }

    /** Record that {@code rec}'s output was seen at block {@code b}: true when it changed (saved only then). */
    private static boolean see(JSONObject rec, long b, long tip) throws Exception {
        long old = rec.optLong("seenBlock", 0), oldDepth = rec.optLong("seenDepth", 0), depth = Math.max(0, tip - b);
        if (b == old && !(oldDepth < PROVEN_DEPTH && depth >= PROVEN_DEPTH)) return false;
        rec.put("seenBlock", b).put("seenDepth", depth);
        return true;
    }

    /** The account scan's coin rows: an output of an open post seen here (all fields matching) is recorded with its
     *  depth, so the proof survives the coin being spent onward. Saved only when something changed. */
    public synchronized void noteCoins(JSONArray rows) {
        try {
            JSONObject all = loadPosted();
            long tip = -1;
            boolean changed = false;
            for (java.util.Iterator<String> it = all.keys(); it.hasNext(); ) {
                String k = it.next(); if (BLOCKS.equals(k)) continue;
                JSONObject rec = all.optJSONObject(k);
                if (rec == null || rec.optBoolean("final")) continue;
                long b = 0;
                for (int i = 0; i < rows.length() && b <= 0; i++) b = matchOut(rec, rows.getJSONObject(i));
                if (b <= 0) continue;
                if (tip < 0) tip = node.call("block").getJSONObject("response").optLong("block", 0);
                changed |= see(rec, b, tip);
            }
            if (changed) savePosted(all);
        } catch (Exception ignored) { }
    }

    /** {@code coin} (node JSON) is one of {@code rec}'s outputs, exactly: its created block, else 0. */
    private static long matchOut(JSONObject rec, JSONObject coin) {
        JSONArray outs = rec.optJSONArray("outs");
        for (int i = 0; outs != null && i < outs.length(); i++) {
            JSONObject o = outs.optJSONObject(i);   // records saved as plain ids (0.3.11) carry no proof: skipped
            if (o == null || !o.optString("coinid").equalsIgnoreCase(coin.optString("coinid"))) continue;
            return sameCoin(o, coin) ? coin.optLong("created", 0) : 0;
        }
        return 0;
    }

    /** Same coin id AND address, token and raw amount (exact, no rounding). */
    private static boolean sameCoin(JSONObject a, JSONObject b) {
        try {
            return a.optString("coinid").equalsIgnoreCase(b.optString("coinid"))
                    && a.optString("address").equalsIgnoreCase(b.optString("address"))
                    && a.optString("tokenid").equalsIgnoreCase(b.optString("tokenid"))
                    && new MiniNumber(a.optString("amount", "0")).isEqual(new MiniNumber(b.optString("amount", "-1")));
        } catch (RuntimeException bad) { return false; }
    }

    /** The block of a PROVEN later post of ours that spends one of {@code rec}'s outputs (so {@code rec} landed at or
     *  before it), else 0. Covers an output spent onward before any scan saw it 3 deep. */
    private static long provenSpender(JSONObject all, JSONObject rec, long tip) {
        JSONArray outs = rec.optJSONArray("outs");
        for (java.util.Iterator<String> it = all.keys(); it.hasNext(); ) {
            String k = it.next(); if (BLOCKS.equals(k)) continue;
            JSONObject b = all.optJSONObject(k);
            JSONArray ins = b == null ? null : b.optJSONArray("ins");
            if (ins == null || b == rec) continue;
            for (int i = 0; outs != null && i < outs.length(); i++) {
                JSONObject o = outs.optJSONObject(i);
                if (o == null) continue;
                for (int j = 0; j < ins.length(); j++) {
                    JSONObject in = ins.optJSONObject(j);   // 0.3.14 stored bare ids: no proof
                    if (in == null || !sameCoin(o, in)) continue;
                    long at = b.optBoolean("final") ? b.optLong("block", 0) : b.optLong("seenBlock", 0);
                    if (at > 0 && tip - at >= PROVEN_DEPTH && (b.optBoolean("final") || b.optLong("seenDepth", 0) >= PROVEN_DEPTH)) return at;
                }
            }
        }
        return 0;
    }

    private JSONObject txpowOnchain(String command) throws Exception {
        P p = new P(CommandRunner.getRunner().getCommand(command));
        String pre = p.s("onchain", "");
        if (!pre.matches("(?i)0x[0-9a-f]{64}")) return null;
        pre = idKey(pre);
        JSONObject all = loadPosted();
        JSONObject rec = all.optJSONObject(pre);
        if (rec == null || BLOCKS.equals(pre)) return null;            // not one of ours: the node answers as ever
        long tip = node.call("block").getJSONObject("response").optLong("block", 0);
        JSONObject notFound = new JSONObject().put("command", "txpow").put("status", true).put("response", new JSONObject().put("found", false));
        if (rec.optBoolean("final")) return proof(rec.optLong("block"), rec.optString("blockid"), tip);
        boolean dirty = false;
        long b = 0;
        JSONArray outs = rec.optJSONArray("outs");
        for (int i = 0; b <= 0 && outs != null && i < outs.length(); i++) {
            JSONObject o = outs.optJSONObject(i);
            if (o == null) continue;
            Object resp = node.call("coins coinid:" + o.optString("coinid")).opt("response");
            if (resp instanceof JSONArray && ((JSONArray) resp).length() > 0) b = matchOut(rec, ((JSONArray) resp).getJSONObject(0));
        }
        if (b > 0) dirty = see(rec, b, tip);                               // visible: re-read every call (a reorg moves it)
        else if (rec.optLong("seenBlock", 0) > 0 && rec.optLong("seenDepth", 0) >= PROVEN_DEPTH) b = rec.optLong("seenBlock");   // proven, then spent onward
        else {
            long spender = provenSpender(all, rec, tip);                   // spent onward by our own proven post
            // only "at or before" the spender is proven (a shallow sighting may have been on a fork): report the
            // spender's block — fewer confirmations, never more
            if (spender > 0) b = spender;
        }
        if (b <= 0 || tip - b < PROVEN_DEPTH) { if (dirty) savePosted(all); return notFound; }
        String blockid = blockIdAt(all, b, tip);
        if (blockid == null) { if (dirty) savePosted(all); return notFound; }
        all.put(pre, new JSONObject().put("final", true).put("block", b).put("blockid", blockid)
                .put("ins", rec.optJSONArray("ins") == null ? new JSONArray() : rec.optJSONArray("ins")));   // still proves what it spent
        savePosted(all);
        return proof(b, blockid, tip);
    }

    /** Block {@code b}'s id: cached per block; past the cascade from {@code txpow inblock:} (small rows); otherwise its
     *  block txpow (one small object, header.block verified), with a mark committed BEFORE the call. A mark still set
     *  on entry means that call never came back (the process died): counted, and the block given up after
     *  {@link #MAX_DEATHS}; ordinary failures retry with backoff, at most {@link #MAX_TRIES}. Never a crash loop. */
    private String blockIdAt(JSONObject all, long b, long tip) throws Exception {
        JSONObject blocks = all.optJSONObject(BLOCKS);
        if (blocks == null) { blocks = new JSONObject(); all.put(BLOCKS, blocks); }
        JSONObject m = blocks.optJSONObject(String.valueOf(b));
        if (m == null) { m = new JSONObject(); blocks.put(String.valueOf(b), m); }
        String id = m.optString("id", "");
        if (id.matches("(?i)0x[0-9a-f]{64}")) return id;
        if (tip - b > CASCADE_DEPTH) {
            JSONObject r = node.call("txpow inblock:" + b).optJSONObject("response");
            JSONArray txns = r == null ? null : r.optJSONArray("txns");
            id = txns != null && txns.length() > 0 ? txns.getJSONObject(0).optString("blockid", "") : "";
            if (id.matches("(?i)0x[0-9a-f]{64}")) { m.put("id", id); return id; }
        }
        if (m.optBoolean("inflight")) { m.put("inflight", false).put("deaths", m.optInt("deaths") + 1); savePosted(all); }
        if (m.optInt("deaths") >= MAX_DEATHS || m.optInt("tries") >= MAX_TRIES || tip < m.optLong("next", 0)) return null;
        m.put("inflight", true).put("tries", m.optInt("tries") + 1);
        if (!savePosted(all)) return null;                                 // the mark must be durable before the call
        id = null;
        try {
            JSONObject r = node.call("txpow block:" + b).optJSONObject("response");
            JSONObject h = r == null ? null : r.optJSONObject("header");
            if (h != null && String.valueOf(b).equals(h.optString("block"))) id = r.optString("txpowid", "");
        } catch (Exception failed) { id = null; }
        m.put("inflight", false).put("next", tip + Math.min(1L << m.optInt("tries"), 64L));
        if (id != null && id.matches("(?i)0x[0-9a-f]{64}")) m.put("id", id); else id = null;
        savePosted(all);
        return id;
    }

    private static JSONObject proof(long b, String blockid, long tip) throws Exception {
        if (tip < b) return new JSONObject().put("command", "txpow").put("status", true).put("response", new JSONObject().put("found", false));
        return new JSONObject().put("command", "txpow").put("status", true).put("response", new JSONObject()
                .put("found", true).put("block", String.valueOf(b)).put("blockid", blockid)
                .put("tip", String.valueOf(tip)).put("confirmations", String.valueOf(tip - b)));
    }

    private static String postedTxnId(JSONObject txpow) {
        JSONObject body = txpow.optJSONObject("body");
        JSONObject txn = body == null ? null : body.optJSONObject("txn");
        String id = txn == null ? "" : txn.optString("transactionid", "");
        return id.isEmpty() || "null".equals(id) ? null : id;
    }

    private JSONObject txnDelete(P p) throws Exception {
        String id = p.s("id", "");
        boolean found = txns.remove(id) != null;
        return ok("txndelete", "Deleted : " + found);
    }

    private JSONObject txnClear(P p) throws Exception {
        TxnRow r = row(p);
        if (p.b("signatures", true)) r.getWitness().clearSignatures();
        if (p.b("mmr", true)) r.getWitness().clearCoinProofs();
        if (p.b("scripts", true)) r.getWitness().clearScriptProofs();
        return rowJson("txnclear", r);
    }

    private JSONObject txnExport(P p) throws Exception {
        TxnRow r = row(p);
        JSONObject resp = new JSONObject();
        if (p.b("showtxn", false)) resp.put("txn", new JSONObject(r.toJSON().toString()));
        resp.put("data", hex(r));
        return ok("txnexport", resp);
    }

    /** A counterparty's transaction. Its proofs are NOT cached: they are the counterparty's word, and our own later
     *  spends must use proofs our node exported. */
    private JSONObject txnImport(P p) throws Exception {
        TxnRow r = TxnRow.convertMiniDataVersion(new MiniData(p.s("data", "")));
        if (r == null) return fail("txnimport", "Invalid transaction data");
        if (p.has("id")) r.setID(p.s("id", ""));
        txns.put(r.getID(), r);
        return rowJson("txnimport", r);
    }

    private JSONObject txnView(P p) throws Exception {
        TxnRow r = TxnRow.convertMiniDataVersion(new MiniData(p.s("data", "")));
        if (r == null) return fail("txnview", "Invalid transaction data");
        if (p.has("id")) r.setID(p.s("id", ""));
        return rowJson("txnview", r);
    }

    private JSONObject txnList(P p) throws Exception {
        if (p.has("id")) return rowJson("txnlist", row(p));
        JSONArray all = new JSONArray();
        for (TxnRow r : txns.values()) all.put(new JSONObject(r.toJSON().toString()));
        return ok("txnlist", all);
    }

    /** The node checks the transaction (scripts, signatures, proofs) without ever holding OUR copy: it gets an
     *  exported snapshot under a fresh id, checks it, and drops it. Another app swapping that snapshot can only make
     *  the CHECK lie — we never sign or post the node's copy. */
    private JSONObject txnCheck(P p) throws Exception {
        TxnRow r = row(p);
        String data = hex(r);
        if (data.length() > MAX_POST_HEX) return fail("txncheck", "Transaction too large to check over the node link (" + data.length() + " chars).");
        String tmp = freshId();
        JSONObject imp = node.call("txnimport id:" + tmp + " data:" + data);
        if (!imp.optBoolean("status")) return imp;
        try { return node.call("txncheck id:" + tmp); }
        finally { try { node.call("txndelete id:" + tmp); } catch (Exception ignored) { } }
    }

    // ---------------------------------------------------------------------------------------------------- helpers

    /** Byte-exact coin + MMR proof from our node, reused for at most {@link #PROOF_TTL_MS}. */
    private CoinProof proof(String coinid) throws Exception {
        String id = norm(coinid);
        Cached c = proofs.get(id);
        if (c != null && clock.now() - c.at < PROOF_TTL_MS) return c.cp;
        JSONObject r = node.call("coinexport coinid:" + coinid);
        JSONObject resp = r.optJSONObject("response");
        if (!r.optBoolean("status") || resp == null) throw new IllegalArgumentException("CoinID not found : " + coinid);
        String data = resp.getString("data");
        CoinProof cp = CoinProof.convertMiniDataVersion(new MiniData(data));
        if (cp == null || !norm(cp.getCoin().getCoinID().to0xString()).equals(id)) throw new IllegalStateException("coinexport returned a different coin for " + coinid);
        proofs.put(id, new Cached(cp, data, clock.now()));
        return cp;
    }

    private JSONObject blockInfo() throws Exception {
        JSONObject r = node.call("block");
        JSONObject resp = r.optJSONObject("response");
        if (resp == null) throw new IllegalStateException("block: " + err(r));
        return resp;
    }

    private String freshId() {
        byte[] b = new byte[16];
        rnd.nextBytes(b);
        StringBuilder s = new StringBuilder("dx");
        for (byte x : b) s.append(String.format("%02x", x));
        return s.toString();
    }

    static String hex(TxnRow r) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(bo);
        r.writeDataStream(d);
        d.flush();
        return new MiniData(bo.toByteArray()).to0xString();
    }

    private static String toHexAddress(String a) {
        if (a == null) return "";
        a = a.trim();
        if (a.toUpperCase().startsWith("MX")) return org.minima.objects.Address.convertMinimaAddress(a).to0xString();
        return a;
    }

    /** A JSON array of ids plus {@code add}, oldest dropped past 2000 (enough for any live wallet's history window). */
    public static String boundedAdd(String json, java.util.Collection<String> add) {
        java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>();
        try { org.json.JSONArray a = new org.json.JSONArray(json); for (int i = 0; i < a.length(); i++) set.add(a.getString(i)); } catch (Exception fresh) { }
        for (String id : add) if (id != null && !id.isEmpty()) { set.remove(id); set.add(id); }
        java.util.List<String> l = new java.util.ArrayList<>(set);
        return new org.json.JSONArray(l.subList(Math.max(0, l.size() - 2000), l.size())).toString();
    }

    /** True when {@code coinId} is an output of a transaction this account signed. */
    public boolean isOwnOutput(String coinId) { return store.isOwnOutput(norm(coinId)); }

    public static String norm(String s) {
        if (s == null) return "";
        s = s.trim().toUpperCase();
        if (s.startsWith("MX")) { try { s = org.minima.objects.Address.convertMinimaAddress(s).to0xString().toUpperCase(); } catch (Exception ignored) { } }
        return s.startsWith("0X") ? s.substring(2) : s;
    }

    private static String err(JSONObject r) {
        String e = r.optString("error", "");
        if (e.isEmpty()) e = r.optString("message", "");
        if (e.isEmpty()) e = String.valueOf(r.opt("response"));
        return e;
    }

    private static JSONObject ok(String verb, Object response) throws Exception {
        return new JSONObject().put("command", verb).put("status", true).put("pending", false).put("response", response == null ? JSONObject.NULL : response);
    }

    private static JSONObject fail(String verb, String error) {
        try { return new JSONObject().put("command", verb).put("status", false).put("pending", false).put("error", error); }
        catch (Exception e) { return new JSONObject(); }
    }

    /** Rebuild the params not in {@code skip} as "k:v" pairs to forward to the node. */
    private static String rebuild(P p, String... skip) {
        Set<String> s = new HashSet<>(Arrays.asList(skip));
        StringBuilder b = new StringBuilder();
        for (Object k : p.params.keySet()) {
            String key = String.valueOf(k);
            if (s.contains(key)) continue;
            Object v = p.params.get(k);
            String val = String.valueOf(v);
            if (val.contains(" ") || val.contains(";")) continue;   // never forward anything that could split a command
            if (b.length() > 0) b.append(' ');
            b.append(key).append(':').append(val);
        }
        return b.toString();
    }

    /** Parameters exactly as the node's own parser reads them. */
    private static final class P {
        final org.minima.utils.json.JSONObject params;
        P(Command c) {
            if (c.getClass().getSimpleName().equals("missingcmd")) throw new IllegalArgumentException("Invalid command");
            params = c.getParams();
        }
        boolean has(String k) { return params.containsKey(k); }
        Object raw(String k) { return params.get(k); }
        String s(String k, String d) { Object v = params.get(k); return v == null ? d : String.valueOf(v); }
        boolean b(String k, boolean d) { Object v = params.get(k); return v == null ? d : "true".equalsIgnoreCase(String.valueOf(v)); }
    }
}
