// VENDORED VERBATIM from apks/atomix 0.1.89 app/src/main/java/com/eurobuddha/atomix/Handover.java by scripts/vendor-atomix.sh — do not edit here; re-vendor.
package com.eurobuddha.atomix;

import android.content.SharedPreferences;

import com.eurobuddha.atomix.swap.OtcController;
import com.eurobuddha.atomix.swap.OtcDb;
import com.eurobuddha.atomix.swap.Order;
import com.eurobuddha.atomix.swap.PriceOracle;
import com.eurobuddha.atomix.swap.PublishGate;
import com.eurobuddha.atomix.swap.SwapDb;

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
        List<String> out = new ArrayList<>();
        if (starting) out.add("A swap is starting — wait for it to be recorded");
        for (SwapDb.Swap s : db.allSwaps()) {
            if (finished(s, db)) continue;
            out.add("Swap in flight (" + market(TradingContext.forSwap(s.sellToken, s.buyToken)) + ", " + s.status
                    + "): " + s.hash + why(s, db));
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
    static String why(SwapDb.Swap s, SwapDb db) {
        if (SwapDb.ST_ERROR.equals(s.status)) return " — the trade failed, but my own lock has not been confirmed refunded yet";
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
        return SwapDb.ST_ERROR.equals(s.status) && s.hash != null && db.haveCollectExpired(s.hash);
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
