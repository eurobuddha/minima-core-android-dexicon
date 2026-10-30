package com.eurobuddha.dexicon.fill;

import com.eurobuddha.dexicon.NodeApi;
import com.eurobuddha.dexicon.SignGate;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * The node command sequences of the fill (SPEC §5, port of fill.js build()/cosign()). Both run under
 * {@link SignGate} and ALWAYS end with txndelete of the temp id (family rule: every txn error path runs txndelete).
 *
 * Taker: `newscript script:"<maker script>" trackall:false` for each advertised script (the node cannot txninput
 * a coin whose address script it does not know), `txninput … scriptmmr:true` (script + MMR proof travel in the
 * witness so the maker must NOT run txnbasics), sign own inputs, export.
 * Maker: txnview → verify → txnimport → txnsign → txncheck → txnpost. The txpowid txnpost returns is the PRE-MINING
 * id; settlement is tracked by the deterministic output coin ids.
 */
public final class FillTxn {
    private FillTxn() {}

    public interface HexCb { void done(String err, String hex); }
    /** {@code wallet}: EVERY address this node holds a script for, read fresh for this take (never a cache). */
    public interface Verifier { FillPlan.Verdict verify(List<Coin> inputs, List<Coin> outputs, boolean hasState, java.util.Set<String> wallet); }

    private static final java.util.regex.Pattern SIGNEDBY = java.util.regex.Pattern.compile("^RETURN SIGNEDBY\\((0x[0-9A-Fa-f]{64})\\)$");

    /** The public keys of an order's own simple scripts — the only keys the maker signs a take with. */
    public static List<String> signKeys(List<String> scripts) {
        List<String> out = new ArrayList<>();
        if (scripts != null) for (String s : scripts) { java.util.regex.Matcher m = SIGNEDBY.matcher(s == null ? "" : s.trim()); if (m.matches() && !out.contains(m.group(1))) out.add(m.group(1)); }
        return out;
    }
    public interface CosignCb { void done(String err, String txpowid, FillPlan.Verdict verdict, List<String> outputCoinIds); }

    static String txnId(String prefix) { return prefix + Long.toString(System.currentTimeMillis(), 36) + Long.toString((long) (Math.random() * 1e6), 36); }

    static String outputCmd(String id, FillPlan.Output o) {
        return "txnoutput id:" + id + " amount:" + o.amount + " address:" + o.address + (Tokens.isMinima(o.tokenid) ? "" : " tokenid:" + o.tokenid);
    }

    /** Taker: register the maker's scripts, build, sign own inputs, export. cb(err, hex). */
    public static void build(NodeApi node, FillPlan.Plan p, HexCb cb) {
        SignGate.submit(release -> {
            final String id = txnId("dx");
            List<String> steps = new ArrayList<>();
            for (String s : p.scripts) steps.add("newscript script:\"" + s + "\" trackall:false");
            steps.add("txncreate id:" + id);
            for (String c : p.inputs) steps.add("txninput id:" + id + " coinid:" + c + " scriptmmr:true");
            for (FillPlan.Output o : p.outputs) steps.add(outputCmd(id, o));
            steps.add("txnsign id:" + id + " publickey:auto");
            node.seq(steps, new NodeApi.Cb() {
                public void onResult(JSONObject j) {
                    node.cmdR("txnexport id:" + id, new NodeApi.Cb() {
                        public void onResult(JSONObject r) {
                            JSONObject resp = r.optJSONObject("response");
                            String hex = resp == null ? r.optString("response", "") : resp.optString("data", resp.optString("txn", ""));
                            finish(hex.isEmpty() ? "txnexport returned no data" : null, hex);
                        }
                        public void onError(String m) { finish(m, null); }
                    });
                }
                public void onError(String m) { finish(m, null); }
                private void finish(String err, String hex) {
                    node.cmd("txndelete id:" + id, new NodeApi.Cb() {
                        public void onResult(JSONObject j) { release.free(); cb.done(err, hex); }
                        public void onError(String m) { release.free(); cb.done(err, hex); }
                    });
                }
            });
        });
    }

    /** Maker: decode, verify, import, co-sign, check, post. Any failure after import ends with txndelete.
     *  Security review 30 Oct: inputs are classified against the wallet's FULL, fresh script table (a cached address
     *  list let a taker pass off a coin of mine as its own), and only {@code keys} — the order's own — sign, never
     *  publickey:auto (which signs every input the node holds a key for). */
    public static void cosign(NodeApi node, String hex, List<String> keys, Verifier verifier, CosignCb cb) {
        if (keys == null || keys.isEmpty()) { cb.done("refused: order has no signing key", null, null, null); return; }
        node.cmdR("scripts", new NodeApi.Cb() {
            public void onResult(JSONObject sj) {
                java.util.Set<String> wallet = new java.util.HashSet<>();
                for (JSONObject r : NodeApi.rows(sj)) { String a = r.optString("address", ""); if (!a.isEmpty()) wallet.add(a.toLowerCase(java.util.Locale.ROOT)); }
                if (wallet.isEmpty()) { cb.done("refused: cannot read my wallet addresses", null, null, null); return; }
                cosignWith(node, hex, keys, wallet, verifier, cb);
            }
            public void onError(String m) { cb.done("refused: cannot read my wallet addresses (" + m + ")", null, null, null); }
        });
    }

    private static void cosignWith(NodeApi node, String hex, List<String> keys, java.util.Set<String> wallet, Verifier verifier, CosignCb cb) {
        node.cmdR("txnview data:" + hex, new NodeApi.Cb() {
            public void onResult(JSONObject view) {
                JSONObject resp = view.optJSONObject("response");
                JSONObject txn = resp == null ? null : resp.optJSONObject("transaction");
                if (txn == null || txn.optJSONArray("inputs") == null || txn.optJSONArray("outputs") == null) { cb.done("txnview returned no transaction", null, null, null); return; }
                List<Coin> ins = coins(txn.optJSONArray("inputs")), outs = coins(txn.optJSONArray("outputs"));
                JSONArray st = txn.optJSONArray("state");
                boolean hasState = false;
                if (st != null) for (int i = 0; i < st.length(); i++) if (!st.isNull(i)) hasState = true;
                FillPlan.Verdict verdict;
                try { verdict = verifier.verify(ins, outs, hasState, wallet); } catch (RuntimeException e) { verdict = FillPlan.Verdict.bad("verify failed: " + e.getMessage()); }
                if (verdict == null || !verdict.ok) { cb.done("refused: " + (verdict == null ? "no verdict" : verdict.reason), null, verdict, null); return; }
                List<String> outCoins = new ArrayList<>();
                for (Coin c : outs) if (!c.coinid.isEmpty()) outCoins.add(c.coinid);
                final FillPlan.Verdict v = verdict;
                SignGate.submit(release -> {
                    final String id = txnId("dm");
                    node.cmdR("txnimport id:" + id + " data:" + hex, new NodeApi.Cb() {
                        public void onResult(JSONObject j) {
                            signEach(node, id, keys, 0, new NodeApi.Cb() {
                                public void onResult(JSONObject j2) {
                                    node.cmdR("txncheck id:" + id, new NodeApi.Cb() {
                                        public void onResult(JSONObject chkReply) {
                                            JSONObject chk = chkReply.optJSONObject("response");
                                            if (!checkValid(chk)) { fail("txncheck not valid: " + (chk == null ? "null" : String.valueOf(chk.opt("valid")))); return; }
                                            node.cmdR("txnpost id:" + id, new NodeApi.Cb() {
                                                public void onResult(JSONObject post) {
                                                    JSONObject pr = post.optJSONObject("response");
                                                    String txpowid = pr == null ? "" : pr.optString("txpowid", "");
                                                    if (txpowid.isEmpty() && pr != null && pr.optJSONObject("txpow") != null) txpowid = pr.optJSONObject("txpow").optString("txpowid", "");
                                                    final String tp = txpowid.isEmpty() ? "posted-" + id : txpowid;
                                                    node.cmd("txndelete id:" + id, new NodeApi.Cb() {
                                                        public void onResult(JSONObject x) { release.free(); cb.done(null, tp, v, outCoins); }
                                                        public void onError(String m) { release.free(); cb.done(null, tp, v, outCoins); }
                                                    });
                                                }
                                                public void onError(String m) { fail("txnpost: " + m); }
                                            });
                                        }
                                        public void onError(String m) { fail("txncheck: " + m); }
                                    });
                                }
                                public void onError(String m) { fail("txnsign: " + m); }
                            });
                        }
                        public void onError(String m) { fail("txnimport: " + m); }
                        private void fail(String err) {
                            node.cmd("txndelete id:" + id, new NodeApi.Cb() {
                                public void onResult(JSONObject x) { release.free(); cb.done(err, null, v, null); }
                                public void onError(String m) { release.free(); cb.done(err, null, v, null); }
                            });
                        }
                    });
                });
            }
            public void onError(String m) { cb.done("txnview: " + m, null, null, null); }
        });
    }

    /** txnsign with each key in turn (the order's own keys only). */
    private static void signEach(NodeApi node, String id, List<String> keys, int i, NodeApi.Cb cb) {
        if (i >= keys.size()) { cb.onResult(new JSONObject()); return; }
        node.cmdR("txnsign id:" + id + " publickey:" + keys.get(i), new NodeApi.Cb() {
            public void onResult(JSONObject j) { signEach(node, id, keys, i + 1, cb); }
            public void onError(String m) { cb.onError(m); }
        });
    }

    /** txncheck gate: valid.basic && valid.mmrproofs && valid.scripts && validamounts && every signature valid. */
    static boolean checkValid(JSONObject chk) {
        if (chk == null) return false;
        JSONObject v = chk.optJSONObject("valid");
        if (v == null || !v.optBoolean("basic") || !v.optBoolean("mmrproofs") || !v.optBoolean("scripts") || !chk.optBoolean("validamounts")) return false;
        JSONArray sigs = v.optJSONArray("signatures");
        if (sigs != null) for (int i = 0; i < sigs.length(); i++) { JSONObject s = sigs.optJSONObject(i); if (s != null && !s.optBoolean("valid", false)) return false; }
        return true;
    }

    static List<Coin> coins(JSONArray a) {
        List<Coin> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) { JSONObject o = a.optJSONObject(i); if (o != null) out.add(Coin.of(o)); }
        return out;
    }
}
