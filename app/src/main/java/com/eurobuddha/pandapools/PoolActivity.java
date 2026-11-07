// VENDORED VERBATIM from apks/pandapools 0.9.86 app/src/main/java/com/eurobuddha/pandapools/PoolActivity.java by scripts/vendor-pandapools.sh — do not edit here; re-vendor.
package com.eurobuddha.pandapools;

import org.json.JSONArray;
import org.json.JSONObject;
import java.math.BigDecimal;
import java.util.*;

/** Public pool movements from individual transaction inputs/outputs, reusing the accounting
 * classifier's exact per-pool reserve deltas. No device observation times or scan-difference guesses. */
final class PoolActivity {
    static final class Event {
        final HistoryEntry transaction;
        final String pool, kind, tokenid;
        final BigDecimal minima, token;
        final boolean minimaIn;
        Event(HistoryEntry tx, String pool, String kind, String tokenid, BigDecimal m, BigDecimal t) {
            transaction = tx; this.pool = pool; this.kind = kind; this.tokenid = tokenid;
            minima = m.abs(); token = t.abs(); minimaIn = m.signum() < 0;
        }
    }
    static List<Event> from(HistoryEntry tx, TxClassifier.Addrs addrs) {
        List<Event> out = new ArrayList<>();
        for (Map.Entry<String, BigDecimal[]> movement : TxClassifier.poolFlows(tx, addrs).entrySet()) {
            String pool = movement.getKey();
            BigDecimal m = movement.getValue()[0], t = movement.getValue()[1];
            if (m.signum() == 0 && t.signum() == 0) continue; // identical reserves: maintenance
            boolean in = contains(tx.inputs, pool), output = contains(tx.outputs, pool);
            String kind;
            if (!in && output) kind = GlobalFeed.CREATE;
            else if (in && !output) kind = GlobalFeed.WITHDRAW;
            else if (m.signum() < 0 && t.signum() < 0) kind = GlobalFeed.ADD;
            else if (m.signum() > 0 && t.signum() > 0) kind = GlobalFeed.WITHDRAW;
            else if (m.signum() * t.signum() < 0) kind = GlobalFeed.SWAP;
            else kind = "CHANGE";
            out.add(new Event(tx, pool, kind, token(tx, pool), m, t));
        }
        return out;
    }
    private static boolean contains(String coins, String pool) {
        try {
            JSONArray rows = new JSONArray(coins);
            for (int i = 0; i < rows.length(); i++)
                if (pool.equalsIgnoreCase(rows.getJSONObject(i).optString("addr"))) return true;
        } catch (Exception ignored) { }
        return false;
    }
    private static String token(HistoryEntry tx, String pool) {
        for (String coins : new String[]{tx.outputs, tx.inputs}) try {
            JSONArray rows = new JSONArray(coins);
            for (int i = 0; i < rows.length(); i++) {
                JSONObject c = rows.getJSONObject(i); String id = c.optString("tokenid", "0x00");
                if (pool.equalsIgnoreCase(c.optString("addr")) && !Util.isMinima(id)) return id;
            }
        } catch (Exception ignored) { }
        return "";
    }
}
