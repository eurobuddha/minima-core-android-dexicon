// VENDORED VERBATIM from apks/pandapools 0.10.0 app/src/main/java/com/eurobuddha/pandapools/ReserveRecovery.java by scripts/vendor-pandapools.sh — do not edit here; re-vendor.
package com.eurobuddha.pandapools;

import org.json.JSONArray;
import org.json.JSONObject;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** Restores coin knowledge only. The receiving node validates every imported proof; no signing here.
 * Reuses PoolRefresher's live reserve selection and FundingCoins' bounded address queries.
 * Snapshot proofs are optional accelerators. The verified covenant address is the durable identity. */
final class ReserveRecovery {
    static final int MAX_PROOF_CHARS = 32000;
    interface Done { void done(boolean reservesVerified, String detail); }
    private final FundingCoins.Command local, archive;
    private final Pool pool;
    private final JSONObject snapshot;
    private final Done done;
    private final List<String> notes = new ArrayList<>();
    private String hintM, hintT;
    private boolean finished;

    ReserveRecovery(FundingCoins.Command local, FundingCoins.Command archive, Pool pool,
                    JSONObject snapshot, Done done) {
        this.local = local; this.archive = archive; this.pool = pool;
        this.snapshot = snapshot == null ? new JSONObject() : snapshot; this.done = done;
        hintM = pool == null ? null : pool.coinidM; hintT = pool == null ? null : pool.coinidT;
    }

    void start() {
        if (pool == null || !FundingCoins.hex(pool.address) || !FundingCoins.hex(pool.tok)) {
            finish(false, "Invalid pool address or token."); return;
        }
        readLocal(() -> importSnapshot(0));
    }

    private void readLocal(Runnable missing) {
        readCoins(local, pool.address, false, FundingCoins.SAFE_COINS, hintM, hintT, new NodeApi.Cb() {
            public void onResult(JSONObject j) {
                if (PoolRefresher.fillReserves(pool, j) && completeReserves(pool)) {
                    finish(true, "Both reserve coins verified on this node. Withdrawal still requires the matching wallet's current signing state.");
                } else missing.run();
            }
            public void onError(String m) {
                PoolRefresher.fillReserves(pool, null); notes.add("Local lookup: " + JSONObject.quote(m)); missing.run();
            }
        });
    }

    private void importSnapshot(int leg) {
        if (leg == 2) { readLocal(() -> tryArchive(local, true)); return; }
        String data = snapshot.optString(leg == 0 ? "cm" : "ct", "");
        String token = leg == 0 ? "0x00" : pool.tok;
        if (data.isEmpty()) { importSnapshot(leg + 1); return; }
        checkedImport(data, token, null, () -> importSnapshot(leg + 1));
    }

    private void tryArchive(FundingCoins.Command source, boolean isLocal) {
        call(source, "status", new NodeApi.Cb() {
            public void onResult(JSONObject j) {
                JSONObject r = j.optJSONObject("response");
                if (!TxPost.truthy(j, "status") || !TxPost.truthy(r, "megammr")) {
                    if (!isLocal) notes.add("The configured archive did not confirm MegaMMR support.");
                    nextSource(isLocal); return;
                }
                readCoins(source, pool.address, true, isLocal ? FundingCoins.SAFE_COINS : 512, hintM, hintT, new NodeApi.Cb() {
                    public void onResult(JSONObject coins) {
                        Pool live = Recovery.poolFrom(snapshotFor(pool));
                        if (!PoolRefresher.fillReserves(live, coins)) {
                            notes.add("Archive returned an invalid coin list."); nextSource(isLocal); return;
                        }
                        exportLeg(source, live, 0, () -> readLocal(() -> nextSource(isLocal)));
                    }
                    public void onError(String m) { notes.add("Archive lookup: " + JSONObject.quote(m)); nextSource(isLocal); }
                });
            }
            public void onError(String m) { if (!isLocal) notes.add("Archive status: " + JSONObject.quote(m)); nextSource(isLocal); }
        });
    }

    private void nextSource(boolean wasLocal) {
        if (wasLocal && archive != null) { tryArchive(archive, false); return; }
        finish(false, "Reserves are not fully visible on this node. The recipe is retained. "
                + "Use a synced MegaMMR archive or a fresh pool backup from one; an empty local lookup does not prove the funds are gone.\nRecovery tried the local node and eurobuddha.com:9001. An empty lookup does not prove the funds are gone. If fresh proofs fail local validation, let the node sync and retry. If its chain state needs rebuilding, retain the registered pool recipe and resync from eurobuddha.com:9001 while preserving the wallet. A resync does not recover missing signing history.\n"
                + String.join("\n", notes));
    }

    private void exportLeg(FundingCoins.Command source, Pool live, int leg, Runnable next) {
        if (leg == 2) { next.run(); return; }
        String id = leg == 0 ? live.coinidM : live.coinidT;
        String token = leg == 0 ? "0x00" : pool.tok;
        if (!FundingCoins.hex(id)) { notes.add("Archive could not find the " + token + " reserve."); exportLeg(source, live, leg + 1, next); return; }
        call(source, "coinexport coinid:" + id, new NodeApi.Cb() {
            public void onResult(JSONObject j) {
                JSONObject r = j.optJSONObject("response");
                if (!TxPost.truthy(j, "status") || r == null) {
                    notes.add("Archive export failed for " + id + "."); exportLeg(source, live, leg + 1, next); return;
                }
                checkedImport(r.optString("data", ""), token, id, () -> exportLeg(source, live, leg + 1, next));
            }
            public void onError(String m) { notes.add("Archive export: " + JSONObject.quote(m)); exportLeg(source, live, leg + 1, next); }
        });
    }

    private void checkedImport(String data, String token, String expectedId, Runnable next) {
        if (!validProofData(data)) { notes.add("Missing or malformed reserve proof for " + token + "."); next.run(); return; }
        call(local, "coincheck data:" + data, new NodeApi.Cb() {
            public void onResult(JSONObject j) {
                JSONObject r = j.optJSONObject("response");
                JSONObject coin = r == null ? null : r.optJSONObject("coin");
                if (!TxPost.truthy(j, "status") || !TxPost.truthy(r, "valid")
                        || !PoolRefresher.reserveCoin(pool, coin)
                        || !token.equalsIgnoreCase(coin.optString("tokenid"))
                        || (expectedId != null && !expectedId.equalsIgnoreCase(coin.optString("coinid")))) {
                    notes.add("Node rejected or could not verify the " + token + " proof for this pool."); notes.add("PROOF_NOT_VERIFIED: check node sync, proof freshness and the registered pool identity."); next.run(); return;
                }
                if ("0x00".equals(token)) hintM = coin.optString("coinid"); else hintT = coin.optString("coinid");
                call(local, "coinimport track:true data:" + data, new NodeApi.Cb() {
                    public void onResult(JSONObject result) {
                        if (!TxPost.truthy(result, "status")) {
                            notes.add("Coin import failed for " + coin.optString("coinid") + ".");
                            notes.add("Coin import: " + JSONObject.quote("PROOF_IMPORT_FAILED: " + result.optString("error", "No node reason returned.")));
                        }
                        next.run(); // final live read decides; 'already relevant' may legitimately reject an import
                    }
                    public void onError(String m) { notes.add("Coin import: " + JSONObject.quote(m)); next.run(); }
                });
            }
            public void onError(String m) { notes.add("Coin proof check: " + JSONObject.quote(m)); next.run(); }
        });
    }

    /** Count before listing; never widen a registry scan. Each source only sees this covenant. */
    static void readCoins(FundingCoins.Command source, String address, boolean mega, int limit,
                          String hintM, String hintT, NodeApi.Cb cb) {
        String suffix = " address:" + address + (mega ? " megammr:true" : "");
        command(source, "balance" + suffix, new NodeApi.Cb() {
            public void onResult(JSONObject j) {
                JSONArray rows = FundingCoins.rows(j);
                int count = 0;
                try {
                    if (rows == null) throw new IllegalArgumentException();
                    for (int i = 0; i < rows.length(); i++) {
                        int n = new BigDecimal(rows.getJSONObject(i).get("coins").toString()).intValueExact();
                        if (n < 0) throw new IllegalArgumentException();
                        count = Math.addExact(count, n);
                    }
                } catch (Exception invalid) { cb.onError("Could not establish the pool coin count."); return; }
                if (count > limit) {
                    if (FundingCoins.hex(hintM) && FundingCoins.hex(hintT) && !hintM.equalsIgnoreCase(hintT)) {
                        readIds(source, new String[]{hintM, hintT}, mega, 0, new JSONArray(), cb);
                    } else cb.onError("This pool has " + count + " coins; use archive discovery or fresh reserve proofs to check individual coin IDs safely.");
                    return;
                }
                command(source, "coins" + suffix, cb);
            }
            public void onError(String m) { cb.onError(m); }
        });
    }

    private static void readIds(FundingCoins.Command source, String[] ids, boolean mega, int i,
                                JSONArray coins, NodeApi.Cb cb) {
        if (i == ids.length) {
            JSONObject result = new JSONObject();
            try { result.put("status", true).put("response", coins); } catch (Exception impossible) { throw new IllegalStateException(impossible); }
            cb.onResult(result); return;
        }
        command(source, "coins coinid:" + ids[i] + (mega ? " megammr:true" : ""), new NodeApi.Cb() {
            public void onResult(JSONObject j) {
                JSONArray rows = FundingCoins.rows(j);
                if (rows == null || rows.length() > 1) { cb.onError("Could not verify a reserve coin ID."); return; }
                if (rows.length() == 1) {
                    JSONObject c = rows.optJSONObject(0);
                    if (c == null || !ids[i].equalsIgnoreCase(c.optString("coinid"))) { cb.onError("Unexpected reserve coin ID."); return; }
                    coins.put(c);
                }
                readIds(source, ids, mega, i + 1, coins, cb);
            }
            public void onError(String m) { cb.onError(m); }
        });
    }

    private void call(FundingCoins.Command source, String command, NodeApi.Cb cb) {
        if (finished) return;
        command(source, command, cb);
    }

    private static void command(FundingCoins.Command source, String command, NodeApi.Cb cb) {
        NodeApi.Cb once = new NodeApi.Cb() {
            boolean delivered;
            public void onResult(JSONObject j) {
                if (delivered) return; delivered = true;
                if (j == null || Boolean.TRUE.equals(j.opt("pending"))) cb.onError("Node reply missing or awaiting approval.");
                else cb.onResult(j);
            }
            public void onError(String m) { if (delivered) return; delivered = true; cb.onError(m); }
        };
        try { source.run(command, once); }
        catch (Exception failure) { once.onError("Node command failed."); }
    }

    private void finish(boolean ok, String detail) {
        if (finished) return; finished = true; done.done(ok, pool == null ? detail : pool.address + "\n" + detail);
    }

    static boolean validProofData(String data) {
        return data != null && data.length() <= MAX_PROOF_CHARS && FundingCoins.hex(data);
    }

    /** Dust sent to an old covenant must not masquerade as the missing original reserves. */
    static boolean completeReserves(Pool p) {
        if (p == null || !p.funded()) return false;
        try {
            BigDecimal floor = new BigDecimal(p.kmin);
            return floor.signum() > 0 && p.k().compareTo(floor) >= 0;
        } catch (Exception invalid) { return false; }
    }

    private static JSONObject snapshotFor(Pool p) {
        JSONObject j = new JSONObject();
        try { j.put("addr", p.address).put("tok", p.tok); } catch (Exception impossible) { throw new IllegalStateException(impossible); }
        return j;
    }
}
