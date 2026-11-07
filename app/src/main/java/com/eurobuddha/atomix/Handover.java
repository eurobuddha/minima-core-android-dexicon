// VENDORED VERBATIM from apks/atomix 0.2.9 app/src/main/java/com/eurobuddha/atomix/Handover.java by scripts/vendor-atomix.sh — do not edit here; re-vendor.
package com.eurobuddha.atomix;

import android.content.SharedPreferences;

import com.eurobuddha.atomix.swap.OtcController;
import com.eurobuddha.atomix.swap.OtcDb;
import com.eurobuddha.atomix.swap.Order;
import com.eurobuddha.atomix.swap.PriceOracle;
import com.eurobuddha.atomix.swap.PublishGate;
import com.eurobuddha.atomix.swap.SwapDb;
import com.eurobuddha.atomix.swap.SwapEngine;

import java.util.ArrayList;
import java.util.List;

/**
 * "Hand over to Dexicon" state. Dexicon (com.eurobuddha.dexicon) embeds AtomiX; both on one phone means two makers
 * on one wallet, so Dexicon refuses to run beside us and offers to uninstall AtomiX — but only after AtomiX itself
 * (HandoverActivity) confirms nothing can be lost.
 *
 * <p>While {@link #active()} AtomiX starts no new swap, responds to no new take, and publishes no market or OTC
 * offer. Settlement of swaps already in flight (claims, refunds, ETH withdraws) is never touched: the gate sits
 * beside {@link IdentityWatch#halted()} at the engine's NEW-liability choke points only.
 *
 * <p>Process-static like IdentityWatch so the Activity, the service and both engines agree instantly.
 */
public final class Handover {
    public static final String DEXICON = "com.eurobuddha.dexicon";
    public static final String P_QUIESCED = "handover_quiesced";         // withdraw pressed — new swaps stopped
    public static final String P_DONE = "handed_over_to_dexicon";        // every check passed, Dexicon told OK
    public static final String P_TS = "handed_over_to_dexicon_ts";
    public static final String P_TOMB_PENDING = "handover_tomb_pending"; // order tombstone not yet on the wire
    public static final String P_OTC_PENDING = "handover_otc_pending";   // OTC withdrawal not yet on the wire

    private static volatile boolean active;

    /** Serialises "is AtomiX handed over?" + "write a new initiator secret" (SwapEngine) against "re-check and mark
     *  done" (HandoverActivity), so a secret can never be written after the OK that lets Dexicon uninstall us. */
    public static final Object LOCK = new Object();

    private Handover() {}

    public static boolean active() { return active; }
    public static boolean done(SharedPreferences p) { return p.getBoolean(P_DONE, false); }

    public static void load(SharedPreferences p) {
        active = p.getBoolean(P_QUIESCED, false) || p.getBoolean(P_DONE, false);
    }

    /** "Take back": AtomiX may trade again. The market stays withdrawn — the user re-publishes deliberately. */
    public static void takeBack(SharedPreferences p) {
        p.edit().putBoolean(P_QUIESCED, false).putBoolean(P_DONE, false).remove(P_TS)
                .putBoolean(P_TOMB_PENDING, false).putBoolean(P_OTC_PENDING, false).commit();
        active = false;
    }

    static void setQuiesced(SharedPreferences.Editor e) { e.putBoolean(P_QUIESCED, true); active = true; }

    static void setDone(SharedPreferences p, long ts) {
        p.edit().putBoolean(P_QUIESCED, true).putBoolean(P_DONE, true).putLong(P_TS, ts).commit();
        active = true;
    }

    /** The withdraw half of the handover — the same quiesce doSwitchCurrency / maybePegWithdraw use (gen bump,
     *  auto_publish off, P_WITHDRAWN, OTC off), but kill-safe and host-free: the tombstone / OTC withdrawal are
     *  flagged PENDING here and posted by SwapService (the host that owns the node identity), which clears the flag
     *  only once the post is on the wire. A live market is judged exactly as doSwitchCurrency judges it. */
    public static void withdraw(SharedPreferences p) {
        boolean liveMarket = Order.fromConfigJson(p.getString("order_config", "")).hasLiquidity()
                || p.getBoolean("auto_publish", false);
        boolean otcLive = p.getBoolean("otc_enable", false) || p.getBoolean("otc_auto", false);
        PublishGate.bumpGen();   // any in-flight publish result is now stale — it must not re-arm the market
        SharedPreferences.Editor e = p.edit()
                .putBoolean("auto_publish", false)
                .putBoolean(PriceOracle.P_WITHDRAWN, true)
                .putBoolean("otc_enable", false)
                .putBoolean("otc_auto", false);
        if (liveMarket) e.putBoolean(P_TOMB_PENDING, true);
        if (otcLive) e.putBoolean(P_OTC_PENDING, true);
        setQuiesced(e);
        e.commit();
    }

    private static String market(TradingContext c) {
        return c == TradingContext.MINIMA ? "USDT⇄MINIMA" : c == TradingContext.MXUSDT ? "USDT⇄MxUSD" : "unattributed market";
    }

    /** Check 1: every swap or OTC deal that is not finished, in EITHER currency, plus an initiator start the engine
     *  has not yet written ({@code starting}, from SwapEngine.initiatorStarting). Deliberately no time window and no
     *  "dismissed" exemption — a hidden or long-expired leg can still hold a refund. Identifiers in full. */
    public static List<String> inFlight(SwapDb db, List<OtcDb.Deal> deals, boolean starting) {
        return inFlight(db, deals, starting, System.currentTimeMillis());
    }

    static List<String> inFlight(SwapDb db, List<OtcDb.Deal> deals, boolean starting, long nowMs) {
        List<String> out = new ArrayList<>();
        if (starting) out.add("A swap is starting — wait for it to be recorded");
        for (SwapDb.Swap s : db.allSwaps()) {
            if (finished(s, db) || recoverySaved(s, db, nowMs)) continue;
            out.add("Swap in flight (" + market(TradingContext.forSwap(s.sellToken, s.buyToken)) + ", " + s.status
                    + "): " + s.hash + why(s, db, nowMs));
        }
        for (OtcDb.Deal d : deals) {
            if (OtcController.isTerminal(d.status)) continue;
            out.add("OTC deal open (" + market(d.market()) + ", " + d.status + "): " + d.ref
                    + " — reject it or let it expire");
        }
        return out;
    }

    /** Why a row that may look dead still blocks. A STARTED row whose lock was never acknowledged may be a lock
     *  that never landed — or one that mined while its answer was lost. Neither chain read can rule the second out
     *  (the Minima lookup is depth-bounded; an empty ETH read can be RPC lag), so the row keeps its secret. */
    static String why(SwapDb.Swap s, SwapDb db, long nowMs) {
        if (stuck(s, db, nowMs)) return " — AtomiX has tried to refund my Minima lock well past its timelock, and two"
                + " chain reads at least six blocks apart found the lock spent (claimed by the counterparty, or already"
                + " refunded) or never present (never sent). Save this swap's recovery record below; once it is saved"
                + " and verified, this swap no longer blocks";
        if (SwapDb.ST_ERROR.equals(s.status)) return " — the trade failed, but my own lock has not been confirmed refunded yet";
        if (ownEthLock(s)) return " — if its Ethereum lock never landed, AtomiX clears this an hour after the lock's"
                + " timelock, once two independent Ethereum endpoints both show no contract and nothing of this wallet's is pending on"
                + " Ethereum";
        if (!SwapDb.ST_STARTED.equals(s.status) || db.hasEvent(s.hash, SwapDb.EV_STARTED)) return "";
        return " — its lock was never confirmed as sent. AtomiX cannot prove from the chain that it did not land, so"
                + " this record and its secret stay, and it blocks the handover. If the lock truly never landed, it will"
                + " not clear on its own";
    }

    /** Nothing of mine left to claim or refund. NOT SwapVisibility.isTerminal (a display rule): ERROR describes the
     *  trade outcome, not my funds — finalizeEthLost writes it when the counterparty refunded the ETH leg I was to
     *  receive, and leaves my own first leg to refund at its own timelock via the recorded lock coin
     *  (sweepExpiredMinima keeps ERROR rows for exactly that). So an ERROR row is finished only once a refund of my
     *  leg is CONFIRMED on chain (EV_EXPIRED, written by confirmPendingMinima / confirmEthRefunded from chain
     *  evidence only). An ETH-first-leg ERROR row needs the same proof: the engine never writes one, and its ETH
     *  loops skip ERROR rows, so nothing else could ever evidence that leg — it fails closed. */
    static boolean finished(SwapDb.Swap s, SwapDb db) {
        if (SwapDb.ST_COMPLETE.equals(s.status) || SwapDb.ST_REFUNDED.equals(s.status)) return true;
        // EV_NEVER_LOCKED: SwapEngine.finalizeIfNeverLocked's two-endpoint proof that my ETH first leg never existed.
        return SwapDb.ST_ERROR.equals(s.status) && s.hash != null
                && (db.haveCollectExpired(s.hash) || !s.myLegIsMinima && db.hasEvent(s.hash, SwapDb.EV_NEVER_LOCKED));
    }

    /** My own leg is an Ethereum lock not yet seen on chain or settled — SwapEngine.finalizeIfNeverLocked's scope. */
    private static boolean ownEthLock(SwapDb.Swap s) {
        return !s.myLegIsMinima && (SwapDb.ST_STARTED.equals(s.status) || SwapDb.ST_LOCKED.equals(s.status));
    }

    /** A further minimum on device-clock age (3x the longest, 2 h, timelock). Never sufficient on its own: the
     *  chain evidence below decides; this only keeps a fresh row from qualifying on an early or wrong clock. */
    static final long RECOVERY_AGE_MS = 6 * 60 * 60 * 1000L;

    /** Past my Minima timelock by this many blocks, a recorded refund pass counts (SwapEngine.REFUND_EVIDENCE_MARGIN_BLOCKS). */
    static final int REFUND_MARGIN_BLOCKS = 72;

    /** A Minima-leg row the engine can never clear from its own records — offered the recovery-record escape. ALL of:
     *  <ul><li>my leg is Minima and the row is not terminal (STARTED, LOCKED, CLAIMING or ERROR);</li>
     *  <li>the engine recorded a completed refund pass ({@link SwapDb#EV_REFUND_TRIED}) with the Minima chain at
     *      least {@link SwapEngine#refundEvidenceMargin} past myTimelock ({@link #REFUND_MARGIN_BLOCKS}; a RESPONDER
     *      waits {@link SwapEngine#TIMELOCK_BLOCKS}, past the taker's Ethereum leg) — read from the event, not the
     *      device clock;</li>
     *  <li>no Ethereum collect is owed: a CLAIMING row (any role) has its collect recorded
     *      ({@link SwapDb#haveCollect}); a RESPONDER row — whose secret may never have been harvested — has its
     *      collect recorded or an Ethereum read recorded the taker's contract refunded
     *      ({@link SwapDb#EV_ETH_LEG_REFUNDED});</li>
     *  <li>positive chain evidence about the lock, confirmed on two reads at least
     *      {@link SwapEngine#LOCK_GONE_CONFIRM_BLOCKS} blocks apart: the recorded lock coin read spent
     *      ({@link SwapDb#EV_LOCK_SPENT}), or — only when no coin is recorded — a depth-free read found no lock of
     *      mine ({@link SwapDb#EV_LOCK_ABSENT}). A missing record is never evidence, and an unspent, refundable lock
     *      never qualifies: it keeps blocking and the engine keeps refunding it;</li>
     *  <li>and, as an extra minimum only, the row is at least {@link #RECOVERY_AGE_MS} old.</li></ul>
     *  Not guessed: such a row stops blocking only once the user has saved and re-read its recovery record
     *  ({@link SwapDb#EV_RECOVERY_SAVED}). */
    public static boolean stuck(SwapDb.Swap s, SwapDb db, long nowMs) {
        if (s.hash == null || !s.myLegIsMinima || s.myTimelock <= 0 || s.created <= 0 || nowMs - s.created < RECOVERY_AGE_MS) return false;
        if (!(SwapDb.ST_STARTED.equals(s.status) || SwapDb.ST_LOCKED.equals(s.status)
                || SwapDb.ST_CLAIMING.equals(s.status) || SwapDb.ST_ERROR.equals(s.status))) return false;
        if (refundTriedAt(db, s.hash) < s.myTimelock + SwapEngine.refundEvidenceMargin(s)) return false;
        if (SwapDb.ST_CLAIMING.equals(s.status) && !db.haveCollect(s.hash)) return false;
        if ("RESPONDER".equals(s.role) && !db.haveCollect(s.hash) && !db.hasEvent(s.hash, SwapDb.EV_ETH_LEG_REFUNDED)) return false;
        return db.hasEvent(s.hash, db.rememberedLockCoin(s.hash) != null ? SwapDb.EV_LOCK_SPENT : SwapDb.EV_LOCK_ABSENT);
    }

    /** The highest Minima block a recorded refund pass saw, or -1. */
    public static long refundTriedAt(SwapDb db, String hash) {
        long best = -1;
        List<SwapDb.Event> events = db.getEvents(hash);
        if (events != null) for (SwapDb.Event e : events) {
            if (!SwapDb.EV_REFUND_TRIED.equals(e.event) || e.note == null || !e.note.matches("minima block \\d{1,18}")) continue;
            best = Math.max(best, Long.parseLong(e.note.substring("minima block ".length())));
        }
        return best;
    }

    static boolean recoverySaved(SwapDb.Swap s, SwapDb db, long nowMs) {
        return stuck(s, db, nowMs) && db.hasEvent(s.hash, SwapDb.EV_RECOVERY_SAVED);
    }

    /** The stuck rows still waiting for a saved recovery record, for HandoverActivity's save buttons. */
    public static List<SwapDb.Swap> needRecovery(SwapDb db, long nowMs) {
        List<SwapDb.Swap> out = new ArrayList<>();
        for (SwapDb.Swap s : db.allSwaps()) if (!finished(s, db) && stuck(s, db, nowMs) && !recoverySaved(s, db, nowMs)) out.add(s);
        return out;
    }

    /** Everything needed to recover this swap by hand, every identifier in full. The Minima HTLC's claim branch has
     *  no deadline and its refund branch opens at my timelock; the ETH contract (contractId = sha256(hash)) holds the
     *  preimage once withdrawn. */
    public static String recoveryRecord(SwapDb.Swap s, SwapDb db) throws org.json.JSONException {
        org.json.JSONObject coin = db.rememberedLockCoin(s.hash);
        String secret = db.getSecret(s.hash);
        String owner = coin == null ? null : port(coin, 0), claimer = coin == null ? null : port(coin, 4);
        // A responder's Minima lock pays the taker's Minima key, which is what its row's counterparty holds.
        if (claimer == null && "RESPONDER".equals(s.role) && s.counterparty != null && s.counterparty.matches("0x[0-9a-fA-F]{64,}"))
            claimer = s.counterparty;
        return new org.json.JSONObject()
                .put("atomix_recovery_record", 1)
                .put("warning", SECRET_WARNING)
                .put("hash", s.hash)
                .put("secret", secret == null ? org.json.JSONObject.NULL : secret)
                .put("contractId", s.contractId != null && !s.contractId.isEmpty() ? s.contractId
                        : com.eurobuddha.atomix.eth.EthHtlc.contractId(s.hash))
                .put("lockCoinId", coin == null ? org.json.JSONObject.NULL : coin.optString("coinid"))
                .put("lockCoinAmount", coin == null ? org.json.JSONObject.NULL : coin.optString("amount"))
                .put("lockCoinTokenId", coin == null ? org.json.JSONObject.NULL : coin.optString("tokenid"))
                .put("lockOwnerKey", owner == null ? org.json.JSONObject.NULL : owner)
                .put("counterpartyKey", claimer == null ? org.json.JSONObject.NULL : claimer)
                .put("myTimelock", s.myTimelock)
                .put("myTimelockUnit", s.myLegIsMinima ? "minima block" : "unix seconds")
                .put("market", market(TradingContext.forSwap(s.sellToken, s.buyToken)))
                .put("role", s.role).put("direction", s.direction).put("status", s.status)
                .put("sellAmount", s.sellAmount).put("sellToken", tokenLabel(s.sellToken))
                .put("buyAmount", s.buyAmount).put("buyToken", tokenLabel(s.buyToken))
                .put("counterparty", s.counterparty)
                .put("htlcAddress", com.eurobuddha.atomix.swap.MinimaHtlc.HTLC_ADDRESS)
                .put("htlcAddressV13", com.eurobuddha.atomix.swap.MinimaHtlc.HTLC_ADDRESS_V13)
                .put("created", s.created)
                .put("howToRecover", "Find the HTLC coin at htlcAddress (or htlcAddressV13) whose state[5] is hash (lockCoinId if known)."
                        + " Unspent and past myTimelock: refund it with your node's owner key (lockOwnerKey). Spent: it was claimed"
                        + " (or refunded) and nothing is left. If you are owed the Ethereum leg, the contract with"
                        + " contractId can be withdrawn with secret; once withdrawn, the preimage is readable from it."
                        + ("RESPONDER".equals(s.role) ? " As the responder you are owed the Ethereum contract contractId:"
                        + " if secret is null, the counterparty's claim of your Minima lock revealed it in a coin sent to"
                        + " the notify address " + com.eurobuddha.atomix.swap.MinimaHtlc.NOTIFY + " - that coin's state[100] is"
                        + " the secret and its state[101] is hash (check that SHA-256 of state[100] equals hash). Withdraw"
                        + " contractId with it before that contract's timelock." : ""))
                .toString(2);
    }

    /** Shown in the record and on the screen that saves it: the file is a bearer credential for the swap. */
    public static final String SECRET_WARNING = "This file contains the swap's secret. The secret alone cannot move the"
            + " Minima lock: a claim also needs the counterparty's signature. The real risk is the counterparty getting"
            + " it: they could claim your Minima lock with it and still refund their own Ethereum lock, taking both legs"
            + " of the swap. Keep it private: do not share, upload or sync it anywhere you do not control.";

    /** Swap rows persist the coin label current when written (older rows carry the legacy one); the token is MxUSD. */
    static String tokenLabel(String t) {
        return TradingContext.forCoinLabel(t) == TradingContext.MXUSDT ? "MxUSD" : t;
    }

    private static String port(org.json.JSONObject coin, int port) {
        org.json.JSONArray st = coin.optJSONArray("state");
        if (st != null) for (int i = 0; i < st.length(); i++) {
            org.json.JSONObject p = st.optJSONObject(i);
            if (p != null && p.optInt("port", -1) == port) {
                String d = p.optString("data", "");
                return d.isEmpty() ? null : d;
            }
        }
        return null;
    }

    /** Verify a re-picked recovery file against what was written: same size, same SHA-256, and it is this swap's
     *  record. Null on success, else what failed. */
    public static String verifyRecoveryFile(byte[] readBack, long size, String sha256, String hash) {
        if (readBack == null) return "the file could not be read back";
        if (readBack.length != size) return "the file is " + readBack.length + " bytes, not the " + size + " written";
        if (!sha256(readBack).equals(sha256)) return "the file's SHA-256 differs from what was written";
        try {
            org.json.JSONObject j = new org.json.JSONObject(new String(readBack, java.nio.charset.StandardCharsets.UTF_8));
            if (!hash.equals(j.optString("hash"))) return "the file is the recovery record of another swap";
        } catch (org.json.JSONException e) { return "the file is not a recovery record"; }
        return null;
    }

    public static String sha256(byte[] b) {
        try {
            StringBuilder sb = new StringBuilder();
            for (byte x : java.security.MessageDigest.getInstance("SHA-256").digest(b)) sb.append(String.format(java.util.Locale.ROOT, "%02x", x));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    /** Check 2: the maker market and OTC offer are withdrawn, and the withdrawals reached the wire. */
    public static List<String> marketLive(SharedPreferences p) {
        List<String> out = new ArrayList<>();
        if (p.getBoolean("auto_publish", false)) out.add("Your market is still being kept live");
        if (p.getBoolean("otc_enable", false) || p.getBoolean("otc_auto", false)) out.add("Your OTC offer is still enabled");
        if (p.getBoolean(P_TOMB_PENDING, false)) out.add("Market withdrawal not yet posted — waiting for your node");
        if (p.getBoolean(P_OTC_PENDING, false)) out.add("OTC withdrawal not yet posted — waiting for your node");
        return out;
    }
}
