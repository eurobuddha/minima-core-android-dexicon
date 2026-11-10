// VENDORED VERBATIM from apks/pandapools 0.10.9 app/src/main/java/com/eurobuddha/pandapools/HistoryEntry.java by scripts/vendor-pandapools.sh — do not edit here; re-vendor.
package com.eurobuddha.pandapools;

import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.util.Iterator;

/**
 * One on-chain transaction in the Activity tab's PERSONAL history — a faithful port of the standalone
 * Minima History app's entry model, so PandaPools shows the SAME numbers the SAME way as the History app.
 * Direction + amount come straight from the node's {@code details.difference} (the net per-token effect on
 * the wallet); the primary token is the one with the largest absolute move. Persisted in {@link HistoryDb}
 * (keyed by txpowid) so it accumulates + shows instantly, even offline. Your pool swaps / creates / closes
 * all appear here as ordinary wallet transactions.
 */
public class HistoryEntry {

    public String txpowid;
    public String transactionId = "";
    public int verifiedDepth = -1;
    public long verifiedAt;
    public long block, timemilli, syncedAt;
    public String direction;        // received | sent | self
    public boolean incoming;
    public String tokenid, tokenName, amount;   // primary token moved (amount is the absolute value)
    public String deltas;           // JSON { tokenid: signedAmount } — full per-token effect
    public String counterparty;     // display address of the other side
    public String inputs, outputs;  // JSON arrays [{addr, amount, tokenid}] for the detail view

    public static HistoryEntry from(JSONObject txpow, JSONObject detail) {
        HistoryEntry e = new HistoryEntry();
        e.txpowid = txpow.optString("txpowid", "");
        e.transactionId = ActivityLog.transactionId(txpow);
        JSONObject hdr = txpow.optJSONObject("header");
        if (hdr != null) { e.block = hdr.optLong("block", 0); e.timemilli = hdr.optLong("timemilli", 0); }

        JSONObject diff = detail != null ? detail.optJSONObject("difference") : null;
        e.deltas = diff != null ? diff.toString() : "{}";

        // primary token = the largest |net amount| in the difference map
        String pTid = "0x00";
        BigDecimal pAmt = BigDecimal.ZERO;
        if (diff != null) {
            for (Iterator<String> it = diff.keys(); it.hasNext(); ) {
                String tid = it.next();
                BigDecimal a = bd(diff.optString(tid, "0"));
                if (a.abs().compareTo(pAmt.abs()) > 0) { pAmt = a; pTid = tid; }
            }
        }
        e.tokenid = pTid;
        e.amount = pAmt.signum() == 0 ? "0" : pAmt.abs().stripTrailingZeros().toPlainString();
        int sign = pAmt.signum();
        e.incoming = sign > 0;
        e.direction = sign > 0 ? "received" : sign < 0 ? "sent" : "self";
        e.tokenName = tokenNameFor(txpow, pTid);

        JSONObject txn = txn(txpow);
        JSONArray ins = txn != null ? txn.optJSONArray("inputs") : null;
        JSONArray outs = txn != null ? txn.optJSONArray("outputs") : null;
        e.inputs = coins(ins);
        e.outputs = coins(outs);
        // received → show a sender (input) address; sent/self → show a recipient (output) address
        e.counterparty = firstAddr(e.incoming ? ins : outs);

        e.syncedAt = System.currentTimeMillis();
        return e;
    }

    private static JSONObject txn(JSONObject txpow) {
        JSONObject body = txpow.optJSONObject("body");
        return body != null ? body.optJSONObject("txn") : null;
    }

    private static String tokenNameFor(JSONObject txpow, String tid) {
        if (Util.isMinima(tid)) return "Minima";
        JSONObject txn = txn(txpow);
        JSONArray outs = txn != null ? txn.optJSONArray("outputs") : null;
        if (outs != null) for (int i = 0; i < outs.length(); i++) {
            JSONObject o = outs.optJSONObject(i);
            if (o != null && tid.equals(o.optString("tokenid"))) {
                String n = Util.tokenName(o.opt("token"), tid);
                if (n != null && !n.isEmpty()) return n;
            }
        }
        return tid;
    }

    private static String coins(JSONArray arr) {
        JSONArray out = new JSONArray();
        if (arr != null) for (int i = 0; i < arr.length(); i++) {
            JSONObject c = arr.optJSONObject(i);
            if (c == null) continue;
            try {
                JSONObject o = new JSONObject();
                String tid = c.optString("tokenid", "0x00");
                o.put("addr", c.optString("miniaddress", c.optString("address", "")));
                // A token coin carries BOTH `amount` (the internal coloured-coin value) and `tokenamount`
                // (the actual quantity) — and `optString(k, fallback)` only falls back when the key is
                // ABSENT, so reading `amount` first silently stored the wrong number for every token coin.
                // Same rule as Coin.from() and PoolBook: MINIMA → amount, token → tokenamount.
                o.put("amount", Util.isMinima(tid)
                        ? c.optString("amount", "0")
                        : c.optString("tokenamount", c.optString("amount", "0")));
                o.put("tokenid", tid);
                out.put(o);
            } catch (Exception ignored) {}
        }
        return out.toString();
    }

    private static String firstAddr(JSONArray arr) {
        if (arr != null && arr.length() > 0) {
            JSONObject c = arr.optJSONObject(0);
            if (c != null) return c.optString("miniaddress", c.optString("address", ""));
        }
        return "";
    }

    private static BigDecimal bd(String s) { try { return new BigDecimal(s); } catch (Exception e) { return BigDecimal.ZERO; } }

    // ----- split / consolidation display (a self-only coin reshuffle) -----
    private static int count(String json) {
        try { return json == null ? 0 : new JSONArray(json).length(); } catch (Exception e) { return 0; }
    }
    public boolean isSplit() { return "self".equals(direction) && count(outputs) > count(inputs) && count(outputs) > 1; }
    public boolean isConsolidation() { return "self".equals(direction) && count(inputs) > count(outputs) && count(inputs) > 1; }
    public boolean isReshuffle() { return isSplit() || isConsolidation(); }
    public String reshuffleLabel() {
        return isSplit() ? ("Split · " + count(outputs) + " coins") : ("Consolidation · " + count(inputs) + " coins");
    }
    /** The two sides of a two-token swap: what left the wallet and what arrived. Amounts are as stored
     *  (negAmount is negative, posAmount positive). */
    public static final class Legs {
        public final String posTokenid, negTokenid;
        public final BigDecimal posAmount, negAmount;
        Legs(String posTid, BigDecimal posAmt, String negTid, BigDecimal negAmt) {
            this.posTokenid = posTid; this.posAmount = posAmt;
            this.negTokenid = negTid; this.negAmount = negAmt;
        }
    }

    /** If this tx is a two-token SWAP (EXACTLY two opposite-sign non-zero deltas — e.g. MINIMA↔token),
     *  the two legs; otherwise null. Reads the persisted {@link #deltas} map only — no extra node call.
     *  Both {@link #swapLegsDisplay()} (UI) and the accounting export read this, so the shape test lives
     *  in one place. */
    public Legs legs() {
        try {
            JSONObject d = new JSONObject(deltas == null ? "{}" : deltas);
            String posTid = null, negTid = null;
            BigDecimal posAmt = BigDecimal.ZERO, negAmt = BigDecimal.ZERO;
            int nonzero = 0;
            for (Iterator<String> it = d.keys(); it.hasNext(); ) {
                String tid = it.next();
                BigDecimal a = bd(d.optString(tid, "0"));
                if (a.signum() == 0) continue;
                nonzero++;
                if (a.signum() > 0) { posTid = tid; posAmt = a; }
                else { negTid = tid; negAmt = a; }
            }
            if (nonzero != 2 || posTid == null || negTid == null) return null;
            return new Legs(posTid, posAmt, negTid, negAmt);
        } catch (Exception e) { return null; }
    }

    /** The full per-token effect on the wallet as {tokenid → signed amount}, from the persisted
     *  {@link #deltas}. This is the ledger primitive: summed chronologically it IS the balance.
     *
     *  Ordered MINIMA first, then by tokenid — JSON object key order is not guaranteed, and an export
     *  whose rows shuffle between runs is not something anyone can reconcile against. */
    public java.util.Map<String, BigDecimal> deltaMap() {
        java.util.List<String> tids = new java.util.ArrayList<>();
        java.util.Map<String, BigDecimal> raw = new java.util.HashMap<>();
        try {
            JSONObject d = new JSONObject(deltas == null ? "{}" : deltas);
            for (Iterator<String> it = d.keys(); it.hasNext(); ) {
                String tid = it.next();
                tids.add(tid);
                raw.put(tid, bd(d.optString(tid, "0")));
            }
        } catch (Exception ignore) {}
        java.util.Collections.sort(tids, (a, b) -> {
            boolean am = Util.isMinima(a), bm = Util.isMinima(b);
            if (am != bm) return am ? -1 : 1;
            return a.compareToIgnoreCase(b);
        });
        java.util.Map<String, BigDecimal> out = new java.util.LinkedHashMap<>();
        for (String tid : tids) out.put(tid, raw.get(tid));
        return out;
    }

    /** Format BOTH legs "−A TOK  ·  +B TOK" so a swap shows what went out AND what came in (not just the
     *  single largest leg). Returns null for non-swap-shaped txns (caller keeps the single-token display). */
    public String swapLegsDisplay() {
        Legs l = legs();
        if (l == null) return null;
        return "−" + Util.tidyAmount(l.negAmount.abs().stripTrailingZeros().toPlainString()) + "  " + legName(l.negTokenid)
             + "   ·   +" + Util.tidyAmount(l.posAmount.stripTrailingZeros().toPlainString()) + "  " + legName(l.posTokenid);
    }

    /** MINIMA burned by this transaction = sum(inputs) − sum(outputs) over tokenid 0x00, from the stored
     *  coin arrays. Zero when it can't be determined (missing/partial arrays) — never negative. */
    public BigDecimal burn() {
        BigDecimal in = sumToken(inputs, Util.MINIMA_TOKENID);
        BigDecimal out = sumToken(outputs, Util.MINIMA_TOKENID);
        if (in == null || out == null) return BigDecimal.ZERO;
        BigDecimal d = in.subtract(out);
        return d.signum() > 0 ? d : BigDecimal.ZERO;
    }

    /** Total of one token across a stored coin array, or null if the array is missing/unparseable. */
    private static BigDecimal sumToken(String coinsJson, String tokenid) {
        if (coinsJson == null || coinsJson.isEmpty()) return null;
        try {
            JSONArray a = new JSONArray(coinsJson);
            BigDecimal sum = BigDecimal.ZERO;
            for (int i = 0; i < a.length(); i++) {
                JSONObject c = a.optJSONObject(i);
                if (c == null) continue;
                if (!tokenid.equalsIgnoreCase(c.optString("tokenid", "0x00"))) continue;
                sum = sum.add(bd(c.optString("amount", "0")));
            }
            return sum;
        } catch (Exception e) { return null; }
    }

    private String legName(String tid) {
        if (Util.isMinima(tid)) return "Minima";
        if (tid.equals(tokenid) && tokenName != null && !tokenName.isEmpty() && !tokenName.equals("Token")) return tokenName;
        // secondary leg (e.g. USDT on a MINIMA→USDT swap): use the name learned from coin/pool scans
        String cached = Util.tokenNameCached(tid);
        if (cached != null && !cached.isEmpty()) return cached;
        return tid;
    }

    /** For a reshuffle, the GROSS amount + token of the dominant output token (e.g. "500000  Minima") —
     *  more informative than the net "0" a self-only transaction otherwise shows. */
    public String grossDisplay() {
        try {
            JSONArray outs = new JSONArray(outputs);
            java.util.Map<String, BigDecimal> sums = new java.util.HashMap<>();
            for (int i = 0; i < outs.length(); i++) {
                JSONObject o = outs.optJSONObject(i);
                if (o == null) continue;
                sums.merge(o.optString("tokenid", "0x00"), bd(o.optString("amount", "0")), BigDecimal::add);
            }
            String domTid = "0x00"; BigDecimal domSum = BigDecimal.ZERO;
            for (java.util.Map.Entry<String, BigDecimal> en : sums.entrySet())
                if (en.getValue().compareTo(domSum) > 0) { domSum = en.getValue(); domTid = en.getKey(); }
            String name = Util.isMinima(domTid) ? "Minima" : domTid.equals(tokenid) ? tokenName : domTid;
            return Util.tidyAmount(domSum.stripTrailingZeros().toPlainString()) + "  " + name;
        } catch (Exception e) { return Util.tidyAmount(amount) + "  " + tokenName; }
    }
}
