// VENDORED VERBATIM from apks/pandapools 0.9.88 app/src/main/java/com/eurobuddha/pandapools/PoolHistorySync.java by scripts/vendor-pandapools.sh — do not edit here; re-vendor.
package com.eurobuddha.pandapools;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.*;

/** Reuses Block Explorer's native txpow-address lookup for public pool activity. Unlike wallet
 * history it also finds transactions relevant to other users. The SDK stays in the private reply
 * process; a stock-node size rejection leaves a visible error, never an empty-success result. */
final class PoolHistorySync {
    private final MainActivity act;
    private final PandaAddrBook addresses;
    private final Runnable changed;
    private final Map<String, Long> checked = new HashMap<>();
    private List<Pool> live = Collections.emptyList();
    private boolean running, closed;
    private String error = "";

    PoolHistorySync(MainActivity act, PandaAddrBook addresses, Runnable changed) {
        this.act = act; this.addresses = addresses; this.changed = changed;
    }
    void livePools(List<Pool> pools) { live = new ArrayList<>(pools); }
    String status() { return error.isEmpty() ? running ? "Checking public pool transactions…" : "" : error; }
    void close() { closed = true; }

    void start(boolean force) {
        if (running || closed) return;
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        for (Pool pool : live) add(candidates, pool.address);
        for (GlobalFeed.Event event : GlobalFeed.list(act)) add(candidates, event.pool);
        for (Pool pool : OwnPoolStore.all(act)) add(candidates, pool.address);
        long now = android.os.SystemClock.elapsedRealtime();
        List<String> batch = new ArrayList<>();
        for (String address : candidates) {
            if (!force && now - checked.getOrDefault(address, -120_000L) < 120_000L) continue;
            batch.add(address); if (batch.size() == 4) break;
        }
        if (batch.isEmpty()) return;
        running = true; error = ""; changed.run(); next(batch, 0);
    }
    private static void add(Set<String> out, String address) {
        if (address != null && address.matches("0x[0-9a-fA-F]{64}")) out.add(address.toLowerCase(Locale.ROOT));
    }
    private void next(List<String> batch, int index) {
        if (closed || act.isDestroyed() || act.isFinishing()) { running = false; return; }
        if (index == batch.size()) {
            running = false; changed.run();
            ActivityLog.verify(act, act.node(), act::confirmationsChanged); return;
        }
        String address = batch.get(index);
        checked.put(address, android.os.SystemClock.elapsedRealtime());
        act.node().cmd("txpow address:" + address, new NodeApi.Cb() {
            public void onResult(JSONObject reply) {
                if (closed || act.isDestroyed() || act.isFinishing()) { running = false; return; }
                JSONArray found = reply == null ? null : reply.optJSONArray("response");
                if (!TxPost.truthy(reply, "status") || found == null) { onError("No public transaction list returned."); return; }
                boolean learned = addresses.add(address);
                for (int i = 0; i < found.length(); i++) {
                    JSONObject tx = found.optJSONObject(i);
                    if (tx == null || !touches(tx, address)) continue;
                    learned |= learnAliases(tx, address);
                    HistoryEntry row = HistoryEntry.from(tx, null);
                    if (!FundingCoins.hex(row.txpowid)) continue;
                    act.history().upsertPublic(row);
                    ActivityLog.observeHistory(act, tx);
                }
                if (learned) addresses.persist();
                changed.run();
                act.ui().postDelayed(() -> next(batch, index + 1), 450);
            }
            public void onError(String message) {
                running = false; error = "Public pool lookup incomplete: " + message;
                if (!closed && !act.isDestroyed() && !act.isFinishing()) changed.run();
            }
        });
    }
    static boolean touches(JSONObject tx, String address) {
        if (tx == null || address == null || address.isEmpty()) return false;
        JSONObject body = tx.optJSONObject("body"), txn = body == null ? null : body.optJSONObject("txn");
        if (txn == null) return false;
        for (String key : new String[]{"inputs", "outputs"}) {
            JSONArray coins = txn.optJSONArray(key); if (coins == null) continue;
            for (int i = 0; i < coins.length(); i++) {
                JSONObject coin = coins.optJSONObject(i);
                if (coin != null && address.equalsIgnoreCase(coin.optString("address"))) return true;
            }
        }
        return false;
    }
    private boolean learnAliases(JSONObject tx, String address) {
        boolean changed = false;
        JSONObject txn = tx.optJSONObject("body").optJSONObject("txn");
        for (String key : new String[]{"inputs", "outputs"}) {
            JSONArray coins = txn.optJSONArray(key); if (coins == null) continue;
            for (int i = 0; i < coins.length(); i++) {
                JSONObject coin = coins.optJSONObject(i);
                if (coin != null && address.equalsIgnoreCase(coin.optString("address")))
                    changed |= addresses.add(coin.optString("miniaddress"));
            }
        }
        return changed;
    }
}
