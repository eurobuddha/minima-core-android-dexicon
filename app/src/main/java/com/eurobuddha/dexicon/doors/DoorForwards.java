package com.eurobuddha.dexicon.doors;

import android.content.SharedPreferences;

import com.eurobuddha.atomix.swap.SwapDb;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Door glue, a port of mds/dexicon lib/door.js: naming (the Minima dollar token is MxUSD everywhere Dexicon speaks,
 * whatever label the engine persisted), each swap's two legs' transaction ids, plain-language status, and the FORWARD
 * queue — "when this swap completes, move what arrived on by one send": the coin (MxUSD / MINIMA) to a Minima address
 * the user named (SWAP lane) or USDT to an Ethereum address (EXIT / SWAP). Records live in SharedPreferences
 * ("dexicon_doors", key fwd_&lt;hash&gt;) so they survive restarts; {@link DoorHost} carries them out in the background.
 */
public final class DoorForwards {
    private DoorForwards() {}

    public static final String PREFS = "dexicon_doors";
    public static final String MXUSD = "0x7D39745FBD29049BE29850B55A18BF550E4D442F930F86266E34193D89042A90";
    public static final String NOTIFY_AMOUNT = "0.0001";   // every AtomiX claim pays this to the notify sink, in the claimed coin
    public static final int MAX_ATTEMPTS = 20;
    public static final String PENDING = "pending", SENT = "sent", FAILED = "failed", VOID = "void",
            /** a Minima send whose reply was lost: it may have gone through — never retried automatically */
            UNCERTAIN = "uncertain";

    /** Display label for an engine token label or token id. */
    public static String label(String t) {
        String s = t == null ? "" : t.trim(), l = s.toLowerCase(Locale.ROOT);
        if (l.equals("mxusdt") || l.equals("mxusd") || l.equals(MXUSD.toLowerCase(Locale.ROOT))) return "MxUSD";
        if (l.equals("minima") || l.equals("0x00")) return "MINIMA";
        if (l.equals("usdt") || l.startsWith("eth:")) return "USDT";
        return s;
    }
    public static boolean isEthAddr(String a) { return a != null && a.trim().matches("0x[0-9a-fA-F]{40}"); }
    /** A Minima address as the node prints it (Mx…) or its 0x hex form. Interpolated into `send` — nothing else passes. */
    public static boolean isMxAddr(String a) { return a != null && a.trim().matches("Mx[0-9A-Z]{20,80}|0x[0-9a-fA-F]{64}"); }
    public static String norm(String h) { String s = h == null ? "" : h.trim().toLowerCase(Locale.ROOT); return s.startsWith("0x") ? s.substring(2) : s; }
    static String key(String hash) { return "fwd_" + norm(hash); }

    /** Full transaction ids per chain from a swap's event log: [0] = Ethereum, [1] = Minima. Deduped, never shortened. */
    public static List<List<String>> txIds(List<SwapDb.Event> events) {
        List<String> eth = new ArrayList<>(), minima = new ArrayList<>();
        if (events != null) for (SwapDb.Event e : events) {
            String id = e.note == null ? "" : e.note, tok = e.token == null ? "" : e.token;
            if (!id.matches("0x[0-9a-fA-F]{64}")) continue;
            List<String> b = tok.toUpperCase(Locale.ROOT).startsWith("ETH") ? eth : minima;
            if (!b.contains(id)) b.add(id);
        }
        List<List<String>> out = new ArrayList<>(); out.add(eth); out.add(minima);
        return out;
    }

    /** Plain-language state of a door swap (Dexicon is always the taker / INITIATOR). */
    public static String statusText(SwapDb.Swap s, JSONObject fwd) {
        String sell = label(s.sellToken), buy = label(s.buyToken), st = s.status == null ? "" : s.status;
        switch (st) {
            case SwapDb.ST_STARTED: return "Locked your " + s.sellAmount + " " + sell + " — waiting for the AtomiX maker to lock " + buy + ".";
            case SwapDb.ST_LOCKED: case SwapDb.ST_CLAIMING: return "Maker locked " + buy + " — claiming your " + s.buyAmount + " " + buy + " now.";
            case SwapDb.ST_COMPLETE:
                if (fwd == null) return "Done — " + s.buyAmount + " " + buy + " received in this node’s wallet" + (!"USDT".equals(buy) ? " (less the " + NOTIFY_AMOUNT + " " + buy + " notify output)." : "’s ETH deposit address.");
                String f = fwd.optString("status");
                if (SENT.equals(f)) return "Done — forwarded " + fwd.optString("amount") + " " + buy + " to " + fwd.optString("to") + ".";
                if (FAILED.equals(f)) return "Swap done, forward FAILED: " + fwd.optString("err", "unknown") + " — tap RETRY.";
                if (UNCERTAIN.equals(f)) return "Swap done, forward UNCONFIRMED: the node lost the reply to the send (" + fwd.optString("err") + "). Check this node’s wallet history before you RETRY — it may already have gone.";
                return "Done — forwarding " + buy + " to " + fwd.optString("to") + (fwd.optString("err").isEmpty() ? "…" : " (retrying: " + fwd.optString("err") + ")");
            case SwapDb.ST_REFUNDED: return "Timed out — your " + s.sellAmount + " " + sell + " was refunded to this node" + ("USDT".equals(sell) ? "’s ETH deposit address." : "’s wallet.");
            case SwapDb.ST_ERROR: return "Failed — see the recorded transactions; the counterparty took the leg you paid or refunded theirs.";
            default: return st;
        }
    }

    /** The stage pill: STARTED (lock sent) → LOCKED (maker locked) → CLAIMING → DONE / FORWARDING, or REFUNDED / ERROR. */
    public static String pill(SwapDb.Swap s, JSONObject fwd) {
        if (!SwapDb.ST_COMPLETE.equals(s.status)) return s.status;
        String f = fwd == null ? "" : fwd.optString("status");
        return PENDING.equals(f) ? "FORWARDING" : FAILED.equals(f) ? "FORWARD FAILED" : UNCERTAIN.equals(f) ? "FORWARD UNCONFIRMED" : "DONE";
    }

    // ------------------------------------------------------------------ the queue

    /** Queue a forward for a swap just started. kind "minima" (coin → Mx address) or "usdt" (USDT → ETH address). */
    public static boolean queue(SharedPreferences p, String hash, String kind, String to) {
        to = to == null ? "" : to.trim();
        if ("minima".equals(kind) ? !isMxAddr(to) : !isEthAddr(to)) return false;
        try {
            JSONObject f = new JSONObject().put("hash", norm(hash)).put("swap", hash).put("kind", kind).put("to", to).put("status", PENDING).put("attempts", 0).put("ts", System.currentTimeMillis());
            return p.edit().putString(key(hash), f.toString()).commit();
        } catch (Exception e) { return false; }
    }
    public static JSONObject get(SharedPreferences p, String hash) {
        try { String v = p.getString(key(hash), null); return v == null ? null : new JSONObject(v); } catch (Exception e) { return null; }
    }
    public static void save(SharedPreferences p, JSONObject f) { p.edit().putString(key(f.optString("hash")), f.toString()).commit(); }
    /** All forward records, newest first. */
    public static List<JSONObject> list(SharedPreferences p) {
        List<JSONObject> out = new ArrayList<>();
        for (Map.Entry<String, ?> e : p.getAll().entrySet()) {
            if (!e.getKey().startsWith("fwd_") || !(e.getValue() instanceof String)) continue;
            try { out.add(new JSONObject((String) e.getValue())); } catch (Exception ignored) {}
        }
        Collections.sort(out, (a, b) -> Long.compare(b.optLong("ts"), a.optLong("ts")));
        return out;
    }
    /** A failed / unconfirmed forward back to pending so the next background pass retries it (user action only). */
    public static void retry(SharedPreferences p, String hash) {
        JSONObject f = get(p, hash);
        if (f == null) return;
        try { f.put("status", PENDING).put("attempts", 0).put("err", ""); save(p, f); } catch (Exception ignored) {}
    }

    /** What to do with a pending forward given its swap's status: "void" (refunded / failed swap), "send", or "wait". */
    public static String decide(JSONObject f, String swapStatus) {
        if (f == null || !PENDING.equals(f.optString("status"))) return "wait";
        if (SwapDb.ST_REFUNDED.equals(swapStatus) || SwapDb.ST_ERROR.equals(swapStatus)) return "void";
        return SwapDb.ST_COMPLETE.equals(swapStatus) ? "send" : "wait";
    }

    /** Record one attempt's outcome. {@code uncertain}: the write may have happened — stop, never auto-retry. */
    public static void outcome(JSONObject f, String err, String tx, boolean uncertain) {
        try {
            f.put("attempts", f.optInt("attempts") + 1);
            if (err == null) { f.put("status", SENT).put("tx", tx == null ? "" : tx).put("err", ""); return; }
            f.put("err", err);
            if (uncertain) f.put("status", UNCERTAIN);
            else if (f.optInt("attempts") >= MAX_ATTEMPTS) f.put("status", FAILED);
        } catch (Exception ignored) {}
    }
}
