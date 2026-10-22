package com.eurobuddha.dexicon.resting;
/* Vendored from apks/pandadex app/src/main/java/com/eurobuddha/pandadex/PoolBook.java (PandaDEX 0.4.23).
 * Keep as close to verbatim as possible; only the package, the NodeApi/SignGate imports and the minimal Dexicon
 * glue noted inline differ. Per the family naming rule the Minima dollar token is MxUSD in identifiers too
 * (PandaDEX MXUSD_ID/MXUSD_DP/*Mxusd are MXUSD_ID/MXUSD_DP/*Mxusd here). Re-copy rather than hand-edit when PandaDEX changes. */


import com.eurobuddha.dexicon.NodeApi;
import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Bounded PandaPools sentinel discovery for PandaDEX. No script enumeration, no APK dependency. */
public final class PoolBook {

    public interface Listener {
        void onPools(List<Pool> pools);
        void onError(String msg);
        default void onAddress(Pool pool) {}
    }

    interface Commander { void cmd(String command, NodeApi.Cb cb); }

    private final Commander node;
    static final int MAX_DISCOVERED_POOLS = 64;

    public PoolBook(NodeApi node) { this.node = node::cmd; }

    PoolBook(Commander node) { this.node = node; }

    public void scan(Listener listener) {
        final boolean[] completed = {false};
        Listener cb = new Listener() {
            public void onAddress(Pool pool) { if (!completed[0]) listener.onAddress(pool); }
            public void onPools(List<Pool> pools) { if (!completed[0]) { completed[0] = true; listener.onPools(pools); } }
            public void onError(String message) { if (!completed[0]) { completed[0] = true; listener.onError(message); } }
        };
        node.cmd("coins simplestate:true order:desc depth:" + PoolCovenant.SENTINEL_SCAN_DEPTH
                + " address:" + PoolCovenant.SENTINEL, new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                Object resp = j.opt("response");
                if (!TxValidation.truthy(j, "status") || !(resp instanceof JSONArray)) { cb.onError("pool scan returned no coin list"); return; }
                JSONArray coins = (JSONArray) resp;
                Map<String, String[]> params = new LinkedHashMap<>();
                for (int i = 0; i < coins.length(); i++) {
                    JSONObject c = coins.optJSONObject(i);
                    String tok = state(c, 2), oadr = state(c, 3), opk = state(c, 4), kmin = state(c, 5);
                    if (!PoolCovenant.validParams(opk, oadr, tok, kmin)) continue;
                    if (!DexContract.MXUSD_ID.equalsIgnoreCase(tok)) continue;
                    params.putIfAbsent((opk + "|" + oadr + "|" + tok + "|" + kmin).toLowerCase(java.util.Locale.ROOT),
                            new String[]{opk, oadr, tok, kmin});
                }
                if (params.size() > MAX_DISCOVERED_POOLS) {
                    cb.onError("Too many pool announcements to safely refresh. Pool quotes are unavailable."); return;
                }
                if (params.isEmpty()) { cb.onPools(new ArrayList<>()); return; }
                derive(new ArrayList<>(params.values()), cb);
            }
            @Override public void onError(String m) { cb.onError(m); }
        });
    }

    private void derive(List<String[]> params, Listener cb) {
        List<Pool> pools = new ArrayList<>();
        AtomicInteger pending = new AtomicInteger(params.size());
        for (String[] p : params) {
            final String opk = p[0], oadr = p[1], tok = p[2], kmin = p[3];
            final String script;
            try { script = PoolCovenant.script(opk, oadr, tok, kmin); }
            catch (Exception e) { if (pending.decrementAndGet() == 0) fund(pools, cb); continue; }
            node.cmd("runscript script:" + Util.scriptArg(script), new NodeApi.Cb() {
                @Override public void onResult(JSONObject j) {
                    try {
                        JSONObject resp = j.getJSONObject("response");
                        if (!TxValidation.truthy(j, "status")) { cb.onError("Pool address check failed"); return; }
                        if (TxValidation.truthy(resp, "parseok")) {
                            JSONObject sc = resp.getJSONObject("script");
                            Pool pool = new Pool();
                            pool.opk = opk; pool.oadr = oadr; pool.tok = tok; pool.kmin = kmin;
                            pool.address = sc.getString("address");
                            if (!FundingCoins.hex(pool.address)) { cb.onError("Invalid pool address reply"); return; }
                            pool.mxaddress = sc.optString("mxaddress", "");
                            pool.covenantScript = script;
                            cb.onAddress(pool);
                            synchronized (pools) { pools.add(pool); }
                        }
                        else { cb.onError("Pool covenant did not parse"); return; }
                    } catch (Exception invalid) { cb.onError("Malformed pool address reply"); return; }
                    if (pending.decrementAndGet() == 0) fund(pools, cb);
                }
                @Override public void onError(String m) { cb.onError(m); }
            });
        }
    }

    private void fund(List<Pool> pools, Listener cb) {
        if (pools.isEmpty()) { cb.onPools(pools); return; }
        AtomicInteger pending = new AtomicInteger(pools.size());
        for (Pool pool : pools) {
            node.cmd("coins simplestate:true depth:" + PoolCovenant.SENTINEL_SCAN_DEPTH + " address:" + pool.address, new NodeApi.Cb() {
                @Override public void onResult(JSONObject j) {
                    Object resp = j.opt("response");
                    if (!TxValidation.truthy(j, "status") || !(resp instanceof JSONArray)) {
                        cb.onError("Pool reserves could not be read"); return;
                    }
                    JSONArray cs = (JSONArray) resp;
                    int mb = 0, tb = 0;
                    for (int i = 0; i < cs.length(); i++) {
                        JSONObject c = cs.optJSONObject(i);
                        if (c == null || c.optBoolean("spent", false)) continue;
                        try { FundingCoins.fundingCoin(c); } catch (RuntimeException invalid) { continue; }
                        Object state = c.opt("state");
                        if (!FundingCoins.hex(c.optString("coinid")) || !pool.address.equalsIgnoreCase(c.optString("address"))
                                || (state instanceof JSONArray && ((JSONArray)state).length() > 0)
                                || (state instanceof JSONObject && ((JSONObject)state).length() > 0)) continue;
                        String tid = c.optString("tokenid", "");
                        if (Util.MINIMA_TOKENID.equals(tid)) {
                            BigDecimal amt = Util.dec(c.optString("amount", "0"));
                            if (pool.reserveM == null || amt.compareTo(pool.reserveM) > 0) {
                                pool.reserveM = amt; pool.coinidM = c.optString("coinid", ""); mb = c.optInt("created", 0);
                            }
                        } else if (pool.tok.equalsIgnoreCase(tid)) {
                            BigDecimal amt = Util.dec(c.optString("tokenamount", "0"));
                            if (pool.reserveT == null || amt.compareTo(pool.reserveT) > 0) {
                                pool.reserveT = amt; pool.coinidT = c.optString("coinid", ""); tb = c.optInt("created", 0);
                                pool.tokDecimals = tokenDecimals(c.opt("token"));
                            }
                        }
                    }
                    pool.reserveBlock = Math.max(mb, tb);
                    if (pending.decrementAndGet() == 0) done(pools, cb);
                }
                @Override public void onError(String m) { cb.onError(m); }
            });
        }
    }

    private void done(List<Pool> pools, Listener cb) {
        List<Pool> funded = new ArrayList<>();
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        for (Pool p : pools) {
            if (!p.funded()) continue;
            if (p.address != null && !seen.add(p.address.toLowerCase(java.util.Locale.ROOT))) continue;
            funded.add(p);
        }
        cb.onPools(funded);
    }

    static String state(JSONObject coin, int port) {
        if (coin == null) return null;
        JSONObject sm = coin.optJSONObject("state");
        if (sm != null) { String v = sm.optString(String.valueOf(port), ""); return v.isEmpty() ? null : v; }
        JSONArray sa = coin.optJSONArray("state");
        if (sa != null) for (int i = 0; i < sa.length(); i++) {
            JSONObject e = sa.optJSONObject(i);
            if (e != null && e.optInt("port", -1) == port) {
                String d = e.optString("data", ""); return d.isEmpty() ? null : d;
            }
        }
        return null;
    }

    private static boolean truthy(JSONObject o, String key) {
        Object v = o == null ? null : o.opt(key);
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof Number) return ((Number) v).intValue() == 1;
        if (v instanceof String) {
            String s = ((String) v).trim();
            return s.equals("1") || s.equalsIgnoreCase("true");
        }
        return false;
    }

    private static int tokenDecimals(Object token) {
        return PriceMath.MXUSD_DP;
    }
}
