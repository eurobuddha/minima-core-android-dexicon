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

    /**
     * Security review 7 Nov (CRITICAL): the order names the "maker" coins and scripts, so a hostile order could list MY
     * coins (signed by publickey:auto, the take came back complete and the "maker" just posted it) or MY script (newscript
     * REPLACES an existing row as non-simple, key 0x00 — my address drops out of send/balance). Against the node's fresh
     * `scripts` table: refuse a maker coin at any of my key addresses and an order script equal to one of mine, and return
     * the keys of MY input addresses — the only keys I sign with. Returns the refusal, or null with {@code keysOut} filled.
     */
    static String takerGuard(List<JSONObject> rows, FillPlan.Plan p, List<String> keysOut) {
        java.util.Map<String, String> myKeyByAddr = new java.util.HashMap<>();
        java.util.Set<String> myScripts = new java.util.HashSet<>();
        for (JSONObject r : rows) {
            String pk = r.optString("publickey", "");
            if (!r.optBoolean("simple", false) || !pk.matches("0x[0-9A-Fa-f]{64}")) continue;   // newscript rows: simple:false, key 0x00
            myKeyByAddr.put(r.optString("address", "").toLowerCase(java.util.Locale.ROOT), pk);
            myScripts.add(norm(r.optString("script", "")));
        }
        for (String s : p.scripts) if (myScripts.contains(norm(s))) return "refused: the order lists one of your own scripts";
        for (String a : p.makerAddrs) if (myKeyByAddr.containsKey(a)) return "refused: the order lists a coin at one of your own addresses";
        for (String a : p.takerAddrs) {
            String pk = myKeyByAddr.get(a);
            if (pk == null) return "refused: no signing key for your coin address " + a;
            if (!keysOut.contains(pk)) keysOut.add(pk);
        }
        return keysOut.isEmpty() ? "refused: none of your coins to sign" : null;
    }
    private static String norm(String s) { return s == null ? "" : s.trim().replaceAll("\\s+", " ").toUpperCase(java.util.Locale.ROOT); }

    /** Taker: check the order against my wallet, register the maker's scripts, build, sign MY inputs only, export. */
    public static void build(NodeApi node, FillPlan.Plan p, HexCb cb) {
        node.cmdR("scripts", new NodeApi.Cb() {
            public void onResult(JSONObject sj) {
                List<String> keys = new ArrayList<>();
                String refusal = takerGuard(NodeApi.rows(sj), p, keys);
                if (refusal != null) { cb.done(refusal, null); return; }
                buildSigned(node, p, keys, cb);
            }
            public void onError(String m) { cb.done("cannot read your wallet scripts: " + m, null); }
        });
    }

    private static void buildSigned(NodeApi node, FillPlan.Plan p, List<String> keys, HexCb cb) {
        SignGate.submit(release -> {
            final String id = txnId("dx");
            List<String> steps = new ArrayList<>();
            for (String s : p.scripts) steps.add("newscript script:\"" + s + "\" trackall:false");
            steps.add("txncreate id:" + id);
            for (String c : p.inputs) steps.add("txninput id:" + id + " coinid:" + c + " scriptmmr:true");
            for (FillPlan.Output o : p.outputs) steps.add(outputCmd(id, o));
            for (String k : keys) steps.add("txnsign id:" + id + " publickey:" + k);   // never auto: only MY input keys
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
                // review 7 Nov (R2-3): scripts run on the MMR-proof coin, and core checks only amount/address/token/coinid of
                // `transaction.inputs` against it — a taker can blank the state there. Read state from the proofs themselves.
                if (proofCarriesState(resp.optJSONObject("witness"))) { cb.done("refused: an input carries state", null, null, null); return; }
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
                            // security review 7 Nov: check BEFORE signing — a take with invented inputs (no valid MMR proof)
                            // or bad amounts used to cost a one-time key leaf per order key and fail only at the post
                            node.cmdR("txncheck id:" + id, new NodeApi.Cb() {
                                public void onResult(JSONObject pre) {
                                    if (!preSignValid(pre.optJSONObject("response"))) { fail("refused: the take's inputs or amounts are not valid"); return; }
                                    sign();
                                }
                                public void onError(String m) { fail("txncheck: " + m); }
                            });
                        }
                        private void sign() {
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

    /** Pre-signature gate: everything txncheck can prove before my signature exists (basic, MMR proofs, amounts). */
    static boolean preSignValid(JSONObject chk) {
        if (chk == null) return false;
        JSONObject v = chk.optJSONObject("valid");
        return v != null && v.optBoolean("basic") && v.optBoolean("mmrproofs") && chk.optBoolean("validamounts");
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

    /** True when any witness MMR-proof coin has a non-empty state (the state its script will actually read). */
    static boolean proofCarriesState(JSONObject witness) {
        JSONArray proofs = witness == null ? null : witness.optJSONArray("mmrproofs");
        if (proofs == null) return false;
        for (int i = 0; i < proofs.length(); i++) {
            JSONObject p = proofs.optJSONObject(i), c = p == null ? null : p.optJSONObject("coin");
            JSONArray st = c == null ? null : c.optJSONArray("state");
            if (st != null && st.length() > 0) return true;
        }
        return false;
    }

    static List<Coin> coins(JSONArray a) {
        List<Coin> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) { JSONObject o = a.optJSONObject(i); if (o != null) out.add(Coin.of(o)); }
        return out;
    }
}
