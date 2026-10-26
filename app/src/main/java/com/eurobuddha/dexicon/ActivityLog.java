package com.eurobuddha.dexicon;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The ACTIVITY tab's receipts: one row per trade, newest first, persisted (DexDb table `activity`), live stage.
 *
 * Ported from PandaPools ActivityLog / ActivityTimeline (apks/pandapools 0.9.67): the receipt store (newest first,
 * capped at 60), CONFIRM_BLOCKS = 3, the confirmationText wording, the "never estimate depth from time" rule and
 * the pending-poll gate. What differs, on purpose (family facts): PandaPools proves inclusion with
 * `txpow onchain:<txpowid>`; here the txpowid txnpost returns is the PRE-MINING id and may never match the mined
 * block, so inclusion is proven by an OUTPUT coin exactly like MakerService.settleNext — `coins coinid:<output>`
 * → its `created` block — and confirmations = tip − created (the node's own `txpow onchain` arithmetic).
 *
 * Stored stages (the event that last happened): BUILDING → POSTED (slow path) or SENT → COSIGNED (fast path);
 * terminal FAILED / REFUSED / DROPPED / LOST. Inclusion is not a stored stage: `block` > 0 means "in block",
 * and depth ≥ CONFIRM_BLOCKS means confirmed / done — so the count moves with the tip and nothing else.
 */
public final class ActivityLog {
    private ActivityLog() {}

    public static final String BUILDING = "BUILDING", POSTED = "POSTED", SENT = "SENT", COSIGNED = "COSIGNED",
            FAILED = "FAILED", REFUSED = "REFUSED", DROPPED = "DROPPED", LOST = "LOST";
    /** Node-reported confirmation depth required for a confirmed receipt (PandaPools CONFIRM_BLOCKS). */
    public static final int CONFIRM_BLOCKS = 3;
    /** A posted transaction with no output on chain after this many blocks is reported dropped. A later output
     *  sighting still revives it (the tracker keeps looking for a while). */
    public static final int DROP_BLOCKS = 60;
    /** A take with no maker answer by now is lost (the sealed take expires at +300 s; a reply may lag the inbox poll). */
    public static final long LOST_MS = 10 * 60_000L;
    /** A dropped row is still probed this long after it started (same 2 h window as the fast settler's "lost"). */
    public static final long REVIVE_MS = 2 * 3600_000L;
    private static final int MAX = 60;

    public static String newId(String prefix) { return prefix + System.currentTimeMillis() + "_" + Long.toString((long) (Math.random() * 1e9), 36); }

    // ---------------------------------------------------------------- the stage machine (pure)

    public static boolean failedTerminal(JSONObject a) {
        String s = a.optString("stage");
        return FAILED.equals(s) || REFUSED.equals(s) || DROPPED.equals(s) || LOST.equals(s);
    }
    public static long tip(JSONObject a, long chainBlock) { return Math.max(chainBlock, a.optLong("tip", 0)); }
    /** Confirmations once an output is on chain (tip − created), else -1. */
    public static long depth(JSONObject a, long chainBlock) {
        long b = a.optLong("block", 0);
        return b > 0 ? Math.max(0, tip(a, chainBlock) - b) : -1;
    }
    public static boolean confirmed(JSONObject a, long chainBlock) { return !failedTerminal(a) && depth(a, chainBlock) >= CONFIRM_BLOCKS; }
    /** Nothing more can change: terminal failure, or buried CONFIRM_BLOCKS deep. */
    public static boolean settled(JSONObject a, long chainBlock) { return failedTerminal(a) || confirmed(a, chainBlock); }
    static boolean fast(JSONObject a) { return "FAST".equals(a.optString("route")); }

    /** PandaPools ActivityLog.confirmationText. */
    static String confirmationText(long depth) { return depth + (depth == 1 ? " confirmation" : " confirmations"); }

    /** Short chip text. */
    public static String chip(JSONObject a, long chainBlock) {
        String s = a.optString("stage");
        if (failedTerminal(a)) return s;
        long d = depth(a, chainBlock);
        if (d >= CONFIRM_BLOCKS) return fast(a) ? "DONE" : "CONFIRMED";
        if (d >= 0) return fast(a) ? "SETTLING" : "IN BLOCK";
        if (COSIGNED.equals(s)) return "CO-SIGNED";
        return s;
    }

    /** The live status line (also used on the TRADE stage line). */
    public static String statusText(JSONObject a, long chainBlock, long nowMs) {
        String s = a.optString("stage"), why = MainActivity.here(a.optString("reason", ""));
        switch (s) {
            case FAILED: return "Failed — " + why;
            case REFUSED: return "Refused by the maker — " + why;
            case DROPPED: return "Dropped — " + why;
            case LOST: return "Lost — " + why;
            default:
        }
        long d = depth(a, chainBlock), block = a.optLong("block", 0);
        if (d >= CONFIRM_BLOCKS) return (fast(a) ? "Done" : "Confirmed") + " · block " + block + " · " + confirmationText(d);
        if (d >= 0) return (fast(a) ? "Settling · in block " : "In block ") + block + " · " + confirmationText(d);
        switch (s) {
            case BUILDING: return why.isEmpty() ? "Building and signing…" : why;
            case SENT: return "Take sent to " + a.optInt("relays") + " relay" + (a.optInt("relays") == 1 ? "" : "s")
                    + " · waiting for the maker to co-sign (" + Math.max(0, (nowMs - a.optLong("sentMs", nowMs)) / 1000) + "s)";
            case COSIGNED:
                String who = "maker".equals(a.optString("role"))
                        ? "Co-signed for " + a.optString("counterparty") + " in " + secs(a.optLong("cosignMs"))
                        : "Maker co-signed in " + secs(a.optLong("cosignMs"));
                return who + " · " + waiting(a, chainBlock);
            case POSTED: return "Posted · " + waiting(a, chainBlock);
            default: return s;
        }
    }
    static String waiting(JSONObject a, long chainBlock) {
        long post = a.optLong("postBlock", 0), t = tip(a, chainBlock);
        long gone = post > 0 && t > post ? t - post : 0;
        return "in the node's mempool, waiting for a block" + (gone > 0 ? " (" + gone + (gone == 1 ? " block" : " blocks") + " since posting)" : "");
    }
    static String secs(long ms) { return String.format(Locale.US, "%.1fs", ms / 1000.0); }

    /**
     * Apply one tracker observation. {@code inputGone}: an input of this transaction was found spent/absent BEFORE the
     * output lookup ran; {@code outCreated}: the probed output coin's created block (0 = not found). Order matters:
     * inputs are checked first, so "input gone and output still absent" cannot be our own transaction landing between
     * the two lookups. Returns true when the row changed.
     */
    public static boolean observe(JSONObject a, long tip, boolean inputGone, long outCreated, long nowMs) {
        try {
            boolean changed = false;
            if (tip > a.optLong("tip", 0)) { a.put("tip", tip); changed = true; }
            String s = a.optString("stage");
            if (BUILDING.equals(s) && nowMs - a.optLong("ts", nowMs) > LOST_MS) {
                a.put("stage", LOST).put("reason", "the app closed before the outcome was recorded — check ASSETS and HISTORY"); return true;
            }
            if (SENT.equals(s) && nowMs - a.optLong("sentMs", nowMs) > LOST_MS) {
                a.put("stage", LOST).put("reason", "no answer from the maker within " + LOST_MS / 60_000 + " minutes"); return true;
            }
            boolean onChainCandidate = POSTED.equals(s) || COSIGNED.equals(s) || DROPPED.equals(s);
            if ((POSTED.equals(s) || COSIGNED.equals(s)) && a.optLong("postBlock", 0) == 0 && tip > 0) { a.put("postBlock", tip); changed = true; }   // fast rows: first tip seen
            if (!onChainCandidate || a.optLong("block", 0) > 0) return changed;
            if (outCreated > 0) {
                a.put("block", outCreated);
                if (DROPPED.equals(s)) a.put("stage", a.optString("was", POSTED)).put("reason", "");   // late inclusion revives it
                return true;
            }
            if (DROPPED.equals(s)) return changed;
            String why = null;
            if (inputGone) why = "an input was spent by another transaction";
            else if (a.optLong("postBlock", 0) > 0 && tip - a.optLong("postBlock") > DROP_BLOCKS) why = "not mined after " + DROP_BLOCKS + " blocks";
            if (why != null) { a.put("was", s).put("stage", DROPPED).put("reason", why); return true; }
            return changed;
        } catch (Exception e) { return false; }
    }

    /** Output/input coin ids and the immutable transaction id from a txnpost reply (response = the TxPoW JSON).
     *  Outputs paying {@code mine} come first so the tracker probes a coin this wallet tracks. */
    public static JSONObject fromPostReply(JSONObject reply, String mine) {
        JSONObject out = new JSONObject();
        try {
            JSONObject r = reply == null ? null : reply.optJSONObject("response");
            if (r != null && r.optJSONObject("txpow") != null) r = r.optJSONObject("txpow");
            JSONObject body = r == null ? null : r.optJSONObject("body");
            JSONObject txn = body == null ? null : body.optJSONObject("txn");
            JSONArray outs = new JSONArray(), ins = new JSONArray();
            List<String> later = new ArrayList<>();
            if (txn != null) {
                JSONArray o = txn.optJSONArray("outputs"), i = txn.optJSONArray("inputs");
                for (int k = 0; o != null && k < o.length(); k++) {
                    JSONObject c = o.optJSONObject(k); if (c == null || c.optString("coinid").isEmpty()) continue;
                    if (mine != null && !mine.isEmpty() && mine.equalsIgnoreCase(c.optString("address"))) outs.put(c.optString("coinid"));
                    else later.add(c.optString("coinid"));
                }
                for (String id : later) outs.put(id);
                for (int k = 0; i != null && k < i.length(); k++) { JSONObject c = i.optJSONObject(k); if (c != null && !c.optString("coinid").isEmpty()) ins.put(c.optString("coinid")); }
                out.put("txnid", txn.optString("transactionid", ""));
            }
            out.put("outs", outs).put("ins", ins);
        } catch (Exception ignored) {}
        return out;
    }

    // ---------------------------------------------------------------- persistence (DexDb `activity`)

    public interface Edit { void apply(JSONObject a) throws Exception; }

    /** Insert a new row (newest first; the store keeps the newest MAX). */
    public static synchronized void add(DexDb db, JSONObject a) { db.saveActivity(a); db.trimActivity(MAX); }

    /** Read-modify-write one row. Static-synchronized: the Activity and the service share one process (two DexDb
     *  helpers on one file), and a stale write must not undo the other's stage change. */
    public static synchronized JSONObject update(DexDb db, String id, Edit e) {
        if (id == null || id.isEmpty()) return null;
        JSONObject a = db.activity(id);
        if (a == null) return null;
        try { e.apply(a); } catch (Exception ex) { return null; }
        db.saveActivity(a);
        return a;
    }
    public static synchronized List<JSONObject> list(DexDb db) { return db.activities(MAX); }

    /** True while a row can still change: not settled, or dropped recently enough that a late block may revive it. */
    public static boolean watch(JSONObject a, long chainBlock, long nowMs) {
        return !settled(a, chainBlock) || (DROPPED.equals(a.optString("stage")) && nowMs - a.optLong("ts") < REVIVE_MS);
    }
    /** Drives the service's per-tick tracker pass (PandaPools hasPending). */
    public static boolean hasPending(List<JSONObject> rows, long chainBlock, long nowMs) {
        for (JSONObject a : rows) if (watch(a, chainBlock, nowMs)) return true;
        return false;
    }

    // ---------------------------------------------------------------- the tracker (MakerService, every tick)

    private static boolean tracking;

    /**
     * One serial pass over the rows that can still change: read the tip, then per row the inputs (spent / gone?)
     * and THEN the first output (`coins coinid:` → created). Rows already in a block only take the new tip.
     * Mirrors MakerService.settleNext (output-coin settlement) — never a time- or height-based guess of inclusion.
     */
    public static void track(NodeApi node, DexDb db, Runnable changed) {
        if (tracking || node == null) return;
        List<JSONObject> rows = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (JSONObject a : list(db)) if (watch(a, a.optLong("tip"), now)) rows.add(a);
        if (rows.isEmpty()) return;
        tracking = true;
        node.cmd("block", new NodeApi.Cb() {
            public void onResult(JSONObject j) {
                JSONObject r = j.optJSONObject("response");
                long tip = 0; try { tip = Long.parseLong(r == null ? "0" : r.optString("block", "0")); } catch (Exception ignored) {}
                if (tip <= 0) { tracking = false; return; }
                next(node, db, rows, 0, tip, new boolean[1], changed);
            }
            public void onError(String m) { tracking = false; }
        });
    }
    private static void next(NodeApi node, DexDb db, List<JSONObject> rows, int i, long tip, boolean[] any, Runnable changed) {
        if (i >= rows.size()) { tracking = false; if (any[0] && changed != null) changed.run(); return; }
        JSONObject a = rows.get(i);
        String id = a.optString("id");
        JSONArray outs = a.optJSONArray("outs"), ins = a.optJSONArray("ins");
        boolean probe = a.optLong("block", 0) == 0 && outs != null && outs.length() > 0
                && (POSTED.equals(a.optString("stage")) || COSIGNED.equals(a.optString("stage")) || DROPPED.equals(a.optString("stage")));
        Runnable after = () -> next(node, db, rows, i + 1, tip, any, changed);
        if (!probe) { apply(db, id, tip, false, 0, any); after.run(); return; }
        inputsGone(node, ins, 0, gone -> node.cmd("coins coinid:" + outs.optString(0), new NodeApi.Cb() {
            public void onResult(JSONObject j) {
                List<JSONObject> c = NodeApi.rows(j);
                long created = 0;
                if (j.optBoolean("status", false) && !c.isEmpty() && !c.get(0).optString("coinid", "").isEmpty())
                    try { created = Long.parseLong(c.get(0).optString("created", "0")); } catch (Exception ignored) {}
                apply(db, id, tip, gone, created, any); after.run();
            }
            public void onError(String m) { after.run(); }
        }));
    }
    private static void apply(DexDb db, String id, long tip, boolean gone, long created, boolean[] any) {
        final boolean[] ch = {false};
        update(db, id, a -> ch[0] = observe(a, tip, gone, created, System.currentTimeMillis()));
        if (ch[0]) any[0] = true;
    }
    private interface Gone { void done(boolean gone); }
    /** True when any input is no longer an unspent coin on chain. A lookup error counts as "not known gone". */
    private static void inputsGone(NodeApi node, JSONArray ins, int k, Gone cb) {
        if (ins == null || k >= ins.length()) { cb.done(false); return; }
        node.cmd("coins coinid:" + ins.optString(k), new NodeApi.Cb() {
            public void onResult(JSONObject j) {
                if (!j.optBoolean("status", false)) { inputsGone(node, ins, k + 1, cb); return; }
                List<JSONObject> c = NodeApi.rows(j);
                if (c.isEmpty() || c.get(0).optString("coinid", "").isEmpty() || c.get(0).optBoolean("spent", false)) cb.done(true);
                else inputsGone(node, ins, k + 1, cb);
            }
            public void onError(String m) { inputsGone(node, ins, k + 1, cb); }
        });
    }
}
