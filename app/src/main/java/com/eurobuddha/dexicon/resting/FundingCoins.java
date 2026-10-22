package com.eurobuddha.dexicon.resting;
/* Vendored from apks/pandadex app/src/main/java/com/eurobuddha/pandadex/FundingCoins.java (PandaDEX 0.4.23).
 * Keep as close to verbatim as possible; only the package, the NodeApi/SignGate imports and the minimal Dexicon
 * glue noted inline differ. Per the family naming rule the Minima dollar token is MxUSD in identifiers too
 * (PandaDEX MXUSD_ID/MXUSD_DP/*Mxusd are MXUSD_ID/MXUSD_DP/*Mxusd here). Re-copy rather than hand-edit when PandaDEX changes. */


import com.eurobuddha.dexicon.NodeApi;
import org.json.JSONArray;
import org.json.JSONObject;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Proactive adaptation of utxo/CoinLoader's token/address tiers. Never try the large list first.
 * Uses the node's balance count before listing, and derives wallet addresses from public keys using
 * runscript (PandaAddrBook holds POOL addresses). No scripts-table scan or new key is needed.
 *
 * Eight coins leaves 12,500 UTF-16 chars per coin under the family's 100,000-char inline ceiling,
 * compared with about 400 for an ordinary MINIMA coin. This is conservative headroom, NOT a byte
 * guarantee: arbitrary state/token metadata and a balance/list race remain legacy-node limitations.
 * Re-derive this guard from Coin.toJSON and representative token metadata before changing it.
 */
final class FundingCoins {
    static final int SAFE_COINS = 8;
    static final int MAX_INPUTS = 20; // wallet/CoinSelector mobile transaction cap
    interface Command { void run(String command, NodeApi.Cb cb); }
    interface Done { void ok(List<JSONObject> coins, BigDecimal sum); void fail(String message); }
    private final Command node;
    private final String token;
    private final BigDecimal need;
    private final Set<String> exclude;
    private final Done done;
    private final List<JSONObject> candidates = new ArrayList<>();
    private final Set<String> ids = new HashSet<>();
    private final List<Slice> slices = new ArrayList<>();
    private int expected, skipped;
    private int maxInputs = MAX_INPUTS;
    private boolean finished;

    private static final class Slice {
        final String address; final BigDecimal amount;
        Slice(String address, BigDecimal amount) { this.address = address; this.amount = amount; }
    }
    private FundingCoins(Command node, String token, BigDecimal need, Set<String> exclude, Done done) {
        this.node = node; this.token = token; this.need = need; this.done = done;
        this.exclude = new HashSet<>();
        if (exclude != null) for (String a : exclude) if (a != null) this.exclude.add(a.toLowerCase(Locale.ROOT));
    }
    static void select(Command node, String token, BigDecimal need, Set<String> exclude, int maxInputs, Done done) {
        FundingCoins scan = new FundingCoins(node, token, need, exclude, done);
        scan.maxInputs = Math.min(MAX_INPUTS, maxInputs);
        if (!hex(token) || need == null || need.signum() < 0 || maxInputs < 1) { scan.fail("Invalid funding request."); return; }
        if (need.signum() == 0) { done.ok(new ArrayList<>(), BigDecimal.ZERO); return; }
        scan.start();
    }
    static boolean hex(String value) { return value != null && value.length() <= 1026 && value.matches("0x(?:[0-9a-fA-F]{2})+"); }
    static boolean listIsSafe(int count) { return count >= 0 && count <= SAFE_COINS; }
    static JSONArray rows(JSONObject reply) {
        if (reply == null || !TxValidation.truthy(reply, "status")) return null;
        return reply.optJSONArray("response");
    }
    /** -1 means unknown, never permission to list. Empty valid response means zero coins. */
    static int coinsFor(JSONObject reply, String token) {
        JSONArray rows = rows(reply);
        if (rows == null) return -1;
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null) return -1;
            if (token.equalsIgnoreCase(row.optString("tokenid"))) {
                try { int count = new BigDecimal(row.get("coins").toString()).intValueExact(); return count < 0 ? -1 : count; }
                catch (Exception invalid) { return -1; }
            }
        }
        return 0;
    }
    private void start() {
        node.run("balance tokenid:" + token, new NodeApi.Cb() {
            public void onResult(JSONObject j) {
                expected = coinsFor(j, token);
                if (expected < 0) { fail("Could not read the wallet coin count."); return; }
                if (expected == 0) { finish(); return; }
                if (listIsSafe(expected)) fetch("", () -> finish());
                else addresses();
            }
            public void onError(String m) { fail(m); }
        });
    }
    private void addresses() {
        node.run("keys", new NodeApi.Cb() {
            public void onResult(JSONObject j) {
                if (j == null || !TxValidation.truthy(j, "status")) { fail("Could not read wallet addresses."); return; }
                Object r = j.opt("response");
                JSONArray keys = r instanceof JSONArray ? (JSONArray) r : r instanceof JSONObject ? ((JSONObject) r).optJSONArray("keys") : null;
                if (keys == null) { fail("Could not read wallet addresses."); return; }
                List<String> pubkeys = new ArrayList<>(); Set<String> seen = new HashSet<>();
                for (int i = 0; i < keys.length(); i++) {
                    JSONObject k = keys.optJSONObject(i);
                    String pk = k == null ? "" : k.optString("publickey");
                    if (!hex(pk)) { fail("Invalid wallet key reply."); return; }
                    if (seen.add(pk.toLowerCase(Locale.ROOT))) pubkeys.add(pk);
                }
                nextKey(pubkeys, 0);
            }
            public void onError(String m) { fail(m); }
        });
    }
    private void nextKey(List<String> keys, int i) {
        if (i == keys.size()) {
            slices.sort((a, b) -> b.amount.compareTo(a.amount));
            nextSlice(0); return;
        }
        node.run("runscript script:" + Util.scriptArg("RETURN SIGNEDBY(" + keys.get(i) + ")"), new NodeApi.Cb() {
            public void onResult(JSONObject j) {
                JSONObject r = j == null ? null : j.optJSONObject("response");
                JSONObject script = r == null ? null : r.optJSONObject("script");
                String addr = script == null ? "" : script.optString("address");
                if (!TxValidation.truthy(j, "status") || !TxValidation.truthy(r, "parseok") || !hex(addr)) { fail("Could not derive a wallet address."); return; }
                if (exclude.contains(addr.toLowerCase(Locale.ROOT))) { nextKey(keys, i + 1); return; }
                node.run("balance tokenid:" + token + " address:" + addr, new NodeApi.Cb() {
                    public void onResult(JSONObject b) {
                        int n = coinsFor(b, token);
                        if (n < 0) { fail("Could not count coins at a wallet address."); return; }
                        if (!listIsSafe(n)) skipped += n;
                        else if (n > 0) {
                            JSONArray arr = rows(b);
                            for (int k = 0; k < arr.length(); k++) {
                                JSONObject row = arr.optJSONObject(k);
                                if (token.equalsIgnoreCase(row.optString("tokenid"))) {
                                    BigDecimal amount = Util.balanceDecimal(row.opt("sendable"));
                                    if (amount == null) { fail("Could not read the available balance."); return; }
                                    if (amount.signum() > 0) slices.add(new Slice(addr, amount));
                                }
                            }
                        }
                        nextKey(keys, i + 1);
                    }
                    public void onError(String m) { fail(m); }
                });
            }
            public void onError(String m) { fail(m); }
        });
    }
    private void nextSlice(int i) {
        if (canCover() || i >= slices.size()) { finish(); return; }
        String addr = slices.get(i).address;
        // Counts gathered while ranking can grow stale. Recheck immediately before each slice.
        node.run("balance tokenid:" + token + " address:" + addr, new NodeApi.Cb() {
            public void onResult(JSONObject j) {
                int count = coinsFor(j, token);
                if (count < 0) { fail("Could not recheck wallet coins."); return; }
                if (!listIsSafe(count)) { skipped += count; nextSlice(i + 1); return; }
                fetch(addr, () -> nextSlice(i + 1));
            }
            public void onError(String m) { fail(m); }
        });
    }
    private void fetch(String address, Runnable next) {
        node.run("coins relevant:true sendable:true checkmempool:true coinage:3 tokenid:" + token
                + (address.isEmpty() ? "" : " address:" + address), new NodeApi.Cb() {
            public void onResult(JSONObject j) {
                JSONArray arr = rows(j);
                if (arr == null) { fail("The node could not return wallet coins."); return; }
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject raw = arr.optJSONObject(i);
                    if (raw == null) { fail("Invalid wallet coin reply."); return; }
                    JSONObject c;
                    try { c = fundingCoin(raw); }
                    catch (IllegalArgumentException invalid) { fail(invalid.getMessage()); return; }
                    if (!token.equalsIgnoreCase(c.optString("tokenid")) || !hex(c.optString("coinid")) || !hex(c.optString("address"))
                            || (!address.isEmpty() && !address.equalsIgnoreCase(c.optString("address")))) { fail("Unexpected wallet coin reply."); return; }
                    // Reuse PandaDEX's stateless funding rule: preserve state-bearing assets.
                    Object state = raw.opt("state");
                    if (raw.optBoolean("spent", false) || exclude.contains(c.optString("address").toLowerCase(Locale.ROOT))
                            || (state instanceof JSONArray && ((JSONArray) state).length() > 0)
                            || (state instanceof JSONObject && ((JSONObject) state).length() > 0)) continue;
                    BigDecimal amount = coinValue(c);
                    if (amount == null || amount.signum() <= 0) { fail("Invalid wallet coin amount."); return; }
                    if (!CoinLock.isReserved(c.optString("coinid")) && ids.add(c.optString("coinid").toLowerCase(Locale.ROOT))) candidates.add(c);
                }
                next.run();
            }
            public void onError(String m) { fail(m); }
        });
    }
    static JSONObject fundingCoin(JSONObject raw) {
        String token = raw.optString("tokenid", "");
        String field = Util.isMinima(token) ? "amount" : "tokenamount";
        if (!hex(token) || !raw.has(field)) throw new IllegalArgumentException("Missing human coin amount.");
        Object state = raw.opt("state");
        if (state != null && !(state instanceof JSONArray) && !(state instanceof JSONObject))
            throw new IllegalArgumentException("Invalid coin state.");
        BigDecimal amount = coinValue(raw);
        if (amount == null || amount.signum() <= 0) throw new IllegalArgumentException("Invalid coin amount.");
        return raw;
    }
    /** Node-reported coin amounts carry stock MiniNumber precision, not the stricter order limit. */
    static BigDecimal coinValue(JSONObject c) {
        String field = Util.MINIMA_TOKENID.equals(c.optString("tokenid")) ? "amount" : "tokenamount";
        return Util.balanceDecimal(c.opt(field));
    }
    private boolean canCover() {
        candidates.sort((a, b) -> coinValue(b).compareTo(coinValue(a)));
        BigDecimal sum = BigDecimal.ZERO; int n = 0;
        for (JSONObject c : candidates) if (!CoinLock.isReserved(c.optString("coinid"))) {
            if (++n > maxInputs) break;
            sum = sum.add(coinValue(c));
            if (sum.compareTo(need) >= 0) return true;
        }
        return false;
    }
    private void finish() {
        if (finished) return;
        CoinLock.prune();
        if (canCover()) {
            List<JSONObject> pick = new ArrayList<>(); BigDecimal sum = BigDecimal.ZERO;
            for (JSONObject c : candidates) if (!CoinLock.isReserved(c.optString("coinid"))) {
                pick.add(c); sum = sum.add(coinValue(c));
                if (sum.compareTo(need) >= 0) {
                    CoinLock.reserve(pick); finished = true; done.ok(pick, sum); return;
                }
            }
        }
        fail("Wallet has " + expected + " coins of this token. Could not select " + need.toPlainString()
                + " from at most " + maxInputs + " available, stateless coins"
                + (skipped > 0 ? " (" + skipped + " coins in large address groups were not scanned)" : "")
                + ". Wait for pending transactions, or consolidate this token in MinimaCore and retry.");
    }
    private void fail(String message) {
        if (finished) return;
        finished = true;
        done.fail(message + " Nothing was posted by this request.");
    }
}
