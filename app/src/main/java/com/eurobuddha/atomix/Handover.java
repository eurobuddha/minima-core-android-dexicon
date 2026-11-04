// VENDORED VERBATIM from apks/atomix 0.1.87 app/src/main/java/com/eurobuddha/atomix/Handover.java by scripts/vendor-atomix.sh — do not edit here; re-vendor.
package com.eurobuddha.atomix;

import android.content.SharedPreferences;

import com.eurobuddha.atomix.swap.OtcController;
import com.eurobuddha.atomix.swap.OtcDb;
import com.eurobuddha.atomix.swap.Order;
import com.eurobuddha.atomix.swap.PriceOracle;
import com.eurobuddha.atomix.swap.PublishGate;
import com.eurobuddha.atomix.swap.SwapDb;
import com.eurobuddha.atomix.swap.SwapVisibility;

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

    /** Check 1: every swap or OTC deal that is not finished, in EITHER currency. Same terminal rules the app already
     *  uses (SwapVisibility.isTerminal for swaps, OtcController.isTerminal for deals); deliberately no time window
     *  and no "dismissed" exemption — a hidden or long-expired leg can still hold a refund. Identifiers in full. */
    public static List<String> inFlight(List<SwapDb.Swap> swaps, List<OtcDb.Deal> deals) {
        List<String> out = new ArrayList<>();
        for (SwapDb.Swap s : swaps) {
            if (SwapVisibility.isTerminal(s)) continue;
            out.add("Swap in flight (" + market(TradingContext.forSwap(s.sellToken, s.buyToken)) + ", " + s.status
                    + "): " + s.hash);
        }
        for (OtcDb.Deal d : deals) {
            if (OtcController.isTerminal(d.status)) continue;
            out.add("OTC deal open (" + market(d.market()) + ", " + d.status + "): " + d.ref
                    + " — reject it or let it expire");
        }
        return out;
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
