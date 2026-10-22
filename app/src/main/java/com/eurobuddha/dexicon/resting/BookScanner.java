package com.eurobuddha.dexicon.resting;
/* Vendored from apks/pandadex app/src/main/java/com/eurobuddha/pandadex/BookScanner.java (PandaDEX 0.4.23).
 * Keep as close to verbatim as possible; only the package, the NodeApi/SignGate imports and the minimal Dexicon
 * glue noted inline differ. Per the family naming rule the Minima dollar token is MxUSD in identifiers too
 * (PandaDEX MXUSD_ID/MXUSD_DP/*Mxusd are MXUSD_ID/MXUSD_DP/*Mxusd here). Re-copy rather than hand-edit when PandaDEX changes. */


import com.eurobuddha.dexicon.NodeApi;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The bounded V5 book scan — TWO commands total (Limit fired ten):
 *
 *   1. coins simplestate:true order:desc depth:<SCAN_DEPTH> address:<V5>   — the book
 *   2. coins relevant:true address:<V5>                                    — ownership belt
 *
 * SCAN_DEPTH sits under the node's ~1024-block visibility trim and above EXPIRY_BLOCKS, so
 * the scan sees every live order: GTC renewal keeps
 * every live order coin younger than expiry, and anything older is dead (expiry-sweepable)
 * anyway. Bounded because the upstream node's 256KB Binder overflow is an uncatchable
 * app-kill (HARD upstream-node constraint).
 *
 * The `truncated` flag distinguishes transport failure from a genuinely empty book so the
 * caller keeps its last-good view (Limit's stale-empty lesson).
 */
public final class BookScanner {

    public interface Cb {
        void onBook(Map<String, Order5> orders, boolean truncated, List<String> rawJsons);
    }

    private BookScanner() {}

    /** Narrowest window worth asking for before admitting defeat. `depth` bounds how far BACK
     *  the walk goes, not how many coins come back, and the reply must fit the 256KB IPC
     *  ceiling — at roughly 800 bytes a row that is ~300 orders. */
    private static final int MIN_SLICE_BLOCKS = 12;

    public static void scan(NodeApi node, Cb cb) {
        final Map<String, Order5> found = new LinkedHashMap<>();
        final java.util.List<String> raw = new java.util.ArrayList<>();
        final boolean[] truncated = {false};
        // ADAPTIVE: one query covers the whole range while the book fits a single reply, which
        // is the normal case — slicing unconditionally would issue five queries every poll and
        // make the node re-walk the chain for each. We only pay that cost once the book has
        // actually outgrown the IPC ceiling, which previously froze the book permanently.
        slice(node, found, truncated, raw, 0, DexContract.SCAN_DEPTH, cb);
    }

    /**
     * Walk SCAN_DEPTH in windows of [coinage, coinage+width]. `depth:D coinage:C` selects the
     * block range between them, so consecutive windows tile the whole range.
     */
    private static void slice(NodeApi node, Map<String, Order5> found, boolean[] truncated,
                              List<String> raw, int fromAge, int width, Cb cb) {
        if (fromAge >= DexContract.SCAN_DEPTH) {          // whole range covered
            cb.onBook(found, truncated[0], raw);
            return;
        }
        int depth = Math.min(fromAge + width, DexContract.SCAN_DEPTH);
        node.cmd("coins simplestate:true order:desc address:" + DexContract.ADDR_V5
                + " coinage:" + fromAge + " depth:" + depth, new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                Object resp = json.opt("response");
                if (!TxValidation.truthy(json, "status") || !(resp instanceof JSONArray)) {
                    truncated[0] = true;
                    cb.onBook(found, true, raw);
                    return;
                }
                JSONArray arr = (JSONArray) resp;
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject c = arr.optJSONObject(i);
                    if (c == null) continue;
                    Order5 o = Order5.from(c);
                    if (o == null) {
                        // a coin at the book address we can't read = an incomplete view;
                        // callers must not treat the missing entry as a departed order
                        continue;
                    }
                    if (!found.containsKey(o.coinid)) {
                        found.put(o.coinid, o);
                        raw.add(c.toString());
                    }
                }
                slice(node, found, truncated, raw, depth, width, cb);
            }
            @Override public void onError(String message) {
                // An oversized reply is killed by the IPC ceiling and surfaces here. Halving
                // the window is the difference between a book that recovers and one that
                // freezes on its last good cache forever once it outgrows a single reply.
                if (NodeApi.ERR_TOO_LONG.equals(message) && width > MIN_SLICE_BLOCKS) {
                    slice(node, found, truncated, raw, fromAge, Math.max(MIN_SLICE_BLOCKS, width / 2), cb);
                    return;
                }
                truncated[0] = true;                     // even the narrowest window failed
                cb.onBook(found, true, raw);
            }
        });
    }

}
