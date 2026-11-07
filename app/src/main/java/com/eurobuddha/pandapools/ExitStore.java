// VENDORED VERBATIM from apks/pandapools 0.9.86 app/src/main/java/com/eurobuddha/pandapools/ExitStore.java by scripts/vendor-pandapools.sh — do not edit here; re-vendor.
package com.eurobuddha.pandapools;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A durable record of every automatic withdrawal attempted, and how far it got.
 *
 * IT GRANTS NOTHING. No guard reads this file. Authority lives only in {@link ExitAuthority}, in memory, for the
 * duration of one signature — so a crash cannot leave a standing permission behind. What this store provides is
 * the other two things a fund-moving automatic action needs:
 *
 *   IDEMPOTENCY. A withdrawal marked {@code POSTING} is never retried automatically on relaunch. The one thing
 *   worse than a stranded pool is two closes against the same coins, both signing, each burning an owner leaf.
 *   So an interrupted exit becomes a card the user resolves, not a chain the app re-runs on its own.
 *
 *   A LIFETIME CAP. {@code signatures} only ever rises, per covenant address, and is checked before any new
 *   ticket is issued. Re-issuing after a failure is allowed a couple of times; it can never become an open
 *   unlock, however many times a restore is re-run.
 *
 * Terminal records are never deleted. They are the audit trail for a transaction the user did not press a button
 * for, which is exactly the history someone will want to reconstruct later.
 */
final class ExitStore {

    private static final String PREFS = "pandapools_exit";

    /** Where one pool's automatic withdrawal got to. */
    enum State {
        /** A ticket was issued and the close has not been posted yet. */
        ISSUED,
        /** A close is in flight. NEVER auto-retried — a person decides what happens next. */
        POSTING,
        /** The close posted; the funds are at $OADR and the forward hop may follow. */
        POSTED,
        /** Collection signing has started. Never automatically re-sign after interruption. */
        FORWARDING,
        /** Collection submitted; fresh confirmation and an empty address are still required. */
        FORWARDED,
        /** Reached the wallet. Done. */
        DONE,
        /** Deliberately not attempted (young reserves, no recorded floor, and so on). */
        SKIPPED,
        /** Attempted and refused or failed, with a reason. */
        FAILED,
        /** The node's reply was lost. Whether it posted is unknown; requires a person. */
        UNCERTAIN,
    }

    private ExitStore() {}

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String key(String address) {
        return address == null ? "" : address.toLowerCase(Locale.ROOT);
    }

    /** One stored row. */
    static final class Record {
        String address, opk, state, reason, closeTxpowid;
        int signatures;
        long updatedAt;

        State stateOf() {
            try { return State.valueOf(state); } catch (Exception unknown) { return State.FAILED; }
        }
    }

    static Record get(Context c, String address) {
        if (c == null || address == null) return null;
        String raw = prefs(c).getString(key(address), null);
        if (raw == null) return null;
        try {
            JSONObject o = new JSONObject(raw);
            Record r = new Record();
            r.address = o.optString("addr", address);
            r.opk = o.optString("opk", "");
            r.state = o.optString("state", "");   // missing stays missing: stateOf() reads it as FAILED, the hand-over blocks
            r.reason = o.optString("reason", "");
            r.closeTxpowid = o.optString("close_txpowid", "");
            r.signatures = o.optInt("signatures", 0);
            r.updatedAt = o.optLong("updated_at", 0);
            return r;
        } catch (Exception invalid) { return null; }
    }

    static List<Record> all(Context c) {
        List<Record> out = new ArrayList<>();
        if (c == null) return out;
        for (String k : prefs(c).getAll().keySet()) {
            Record r = null;
            try { r = get(c, k); } catch (RuntimeException unreadable) { /* listed below, never dropped */ }
            if (r == null) { r = new Record(); r.address = k; r.state = ""; }   // unreadable row: never "settled"
            out.add(r);
        }
        return out;
    }

    /** Unknown/corrupt records are not permission to repeat a signature. */
    static boolean unattempted(Context c, String address) {
        if (c == null) return true; // pure eligibility tests only; beginClose requires a real context
        try {
            String raw = prefs(c).getString(key(address), null);
            if (raw == null) return true;
            Record r = get(c, address);
            return r != null && r.signatures == 0 && r.stateOf() == State.SKIPPED;
        } catch (RuntimeException unavailable) { return false; }
    }

    static synchronized boolean beginClose(Context c, Pool p) {
        return c != null && p != null && unattempted(c, p.address)
                && set(c, p.address, p.opk, State.POSTING, "", "", 1);
    }

    static synchronized boolean beginForward(Context c, Pool p) {
        Record r = p == null ? null : get(c, p.address);
        return r != null && r.signatures == 1 && r.stateOf() == State.POSTED
                && p.opk.equalsIgnoreCase(r.opk)
                && set(c, p.address, p.opk, State.FORWARDING, "Collection started; do not retry signing.", "", 1);
    }

    /** A recorded recovery routes collection through the bounded path, even after a failure. */
    static Pool recoveryPool(Context c, String payout) {
        for (Pool p : OwnPoolStore.all(c)) if (payout.equalsIgnoreCase(p.oadr)) {
            Record r = get(c, p.address);
            if (r != null && r.signatures > 0) return p;
        }
        return null;
    }

    /** Review 7 Nov (R2-2): the recovery whose collection is still OUTSTANDING (POSTED) — the only one that may route a
     *  new collection through the bounded recovery path. Any other state (failed, interrupted, uncertain, forwarded,
     *  done) never returns to POSTED, so routing on {@link #recoveryPool} refused every later collection from this
     *  payout address forever — including the manual Collect the interrupted-exit card tells the user to press. */
    static Pool outstandingRecovery(Context c, String payout) {
        for (Pool p : OwnPoolStore.all(c)) if (payout.equalsIgnoreCase(p.oadr)) {   // migrated pools share a payout address
            Record r = get(c, p.address);
            if (r != null && r.signatures > 0 && r.stateOf() == State.POSTED) return p;
        }
        return null;
    }

    /** Signatures already spent on this address's automatic withdrawals, across every attempt ever. */
    static int signaturesUsed(Context c, String address) {
        Record r = get(c, address);
        return r == null ? 0 : r.signatures;
    }

    /** Whether another exit signature may be issued for this address at all. */
    static boolean withinLifetimeCap(Context c, String address) {
        return signaturesUsed(c, address) < ExitTicket.MAX_LIFETIME_SIGNATURES;
    }

    /**
     * Move a pool's exit to a new state, optionally charging signatures. {@code commit()} throughout: a state
     * that did not reach disk must be treated as a refusal by the caller, because the whole point is that a
     * relaunch can tell what was already attempted.
     */
    static synchronized boolean set(Context c, String address, String opk, State state,
                                    String reason, String closeTxpowid, int chargeSignatures) {
        if (c == null || address == null || address.isEmpty()) return false;
        try {
            Record prev = get(c, address);
            JSONObject o = new JSONObject();
            o.put("addr", address);
            o.put("opk", opk == null ? (prev == null ? "" : prev.opk) : opk);
            o.put("state", state.name());
            o.put("reason", reason == null ? "" : reason);
            String tx = closeTxpowid != null && !closeTxpowid.isEmpty() ? closeTxpowid
                    : (prev == null ? "" : prev.closeTxpowid);
            o.put("close_txpowid", tx);
            int before = prev == null ? 0 : prev.signatures;
            o.put("signatures", before + Math.max(0, chargeSignatures));   // monotone: never decreases
            o.put("updated_at", System.currentTimeMillis());
            return prefs(c).edit().putString(key(address), o.toString()).commit();
        } catch (Exception invalid) { return false; }
    }

    /** Rows left mid-flight by a crash or a kill. These need a person, never an automatic retry. */
    static List<Record> interrupted(Context c) {
        List<Record> out = new ArrayList<>();
        for (Record r : all(c)) {
            State s = r.stateOf();
            if (s == State.POSTING || s == State.FORWARDING || s == State.UNCERTAIN) out.add(r);
        }
        return out;
    }
}
