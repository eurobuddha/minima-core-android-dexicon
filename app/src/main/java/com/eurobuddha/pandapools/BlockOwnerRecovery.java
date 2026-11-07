// VENDORED VERBATIM from apks/pandapools 0.9.84 app/src/main/java/com/eurobuddha/pandapools/BlockOwnerRecovery.java by scripts/vendor-pandapools.sh — do not edit here; re-vendor.
package com.eurobuddha.pandapools;

import org.json.JSONArray;
import org.json.JSONObject;
import java.math.BigDecimal;
import java.util.*;
import java.util.function.Consumer;

/** Diagnostic file restore: bounded deterministic key discovery only.
 * Never signs, changes signing counters, clears a signing hold, or posts a transaction.
 * A matching key establishes identity, not which one-time signing indexes were almatched used.
 */
final class BlockOwnerRecovery {
    enum Mode { UNKNOWN, CLASSIC, BLOCK }
    // Public, deliberately non-secret input. genkey does not store a key or touch the wallet.
    // Roots reproduced with Pandamonium's bundled TreeKey.createDefault (64x3 and 128x4).
    static final String MODE_COMMAND = "keys action:genkey phrase:\"PandaPools public recovery mode probe v1\"";
    static final String BLOCK_PROBE = "0x3FFDB1A06A2DCA90BEE0D7C0F5BA62B9AF23BF5D73A23D63555492F88D1E3828";
    static final String CLASSIC_PROBE = "0x8B0CF5A33D71DBB1E986219C57E6B97ED9489F699DE7ED8189BEF7F168A5DA76";
    static final int MAX_MINTS = 2048, UNKNOWN_INDEX_BUDGET = 256;
    interface State {
        /** Persist the absolute search ceiling BEFORE minting, so retries cannot grow the wallet forever. */
        int searchLimit(String fingerprint, String owner, int initialTotal);
        boolean recordMatch(String owner, int index);
    }
    static final class Result {
        Mode mode = Mode.UNKNOWN;
        final Set<String> matched = new LinkedHashSet<>();
        final Map<String, String> problems = new LinkedHashMap<>();
        boolean matched(Pool p) { return p != null && p.opk != null && matched.contains(normal(p.opk)); }
    }
    private final FundingCoins.Command node;
    private final State state;
    private final Consumer<String> progress;
    private final Consumer<Result> done;
    private final List<Pool> targets;
    private final Result result = new Result();
    private final Map<String, Pool> owners = new LinkedHashMap<>();
    private Iterator<Pool> iterator;
    private String fingerprint;
    private int minted;
    private boolean finished;
    private boolean existingOnly;

    BlockOwnerRecovery(FundingCoins.Command node, State state, List<Pool> targets,
                       Consumer<String> progress, Consumer<Result> done) {
        this.node = node; this.state = state; this.targets = targets;
        this.progress = progress; this.done = done;
    }
    BlockOwnerRecovery existingOnly() { existingOnly = true; return this; }
    private boolean supportedShape(JSONObject row) {
        int size = result.mode == Mode.CLASSIC ? 64 : 128;
        int depth = result.mode == Mode.CLASSIC ? 3 : 4;
        return row != null && integer(row, "size", -1) == size && integer(row, "depth", -1) == depth;
    }
    void start() {
        for (Pool p : targets) {
            if (p == null || !key(p.opk)) continue;
            Pool old = owners.get(normal(p.opk));
            if (old != null && old.kidx >= 0 && p.kidx >= 0 && old.kidx != p.kidx) {
                result.problems.put(normal(p.opk), "The backup contains conflicting owner-key indexes.");
            } else if (old == null || old.kidx < 0) owners.put(normal(p.opk), p);
        }
        if (owners.isEmpty()) { finish(); return; }
        request(MODE_COMMAND, reply -> {
            result.mode = mode(reply);
            if (result.mode == Mode.CLASSIC && !existingOnly) { failAll("Classic node: diagnostic restore does not regenerate keys or withdraw pools. Owner signing remains paused."); return; }
            if (result.mode == Mode.UNKNOWN) { failAll("Could not identify this node's key mode. No owner keys were changed."); return; }
            request("status", status -> {
                JSONObject r = status.optJSONObject("response");
                if (r == null || !Boolean.FALSE.equals(r.opt("locked"))) {
                    failAll("Unlock your wallet in MinimaCore, then restore the pool file again."); return;
                }
                request("keys", keys -> {
                    fingerprint = fingerprint(keys);
                    if (fingerprint == null || total(keys) < 0) { failAll("Could not read this wallet's keys."); return; }
                    iterator = owners.values().iterator(); next();
                }, this::failAll);
            }, this::failAll);
        }, this::failAll);
    }
    private void next() {
        if (!iterator.hasNext()) { finish(); return; }
        Pool p = iterator.next();
        if (result.problems.containsKey(normal(p.opk))) { next(); return; }
        progress.accept("Recovering owner key for " + p.address);
        find(p);
    }
    private void find(Pool p) {
        request("keys", reply -> {
            if (!sameWallet(reply)) { failAll("The wallet changed or its keys became unreadable. Restore again on the original wallet."); return; }
            JSONObject row = row(reply, p.opk);
            if (row != null) { recordMatch(p, reply, row); return; }
            if (existingOnly) {
                problem(p, "OWNER_KEY_MISSING: this wallet does not contain the pool owner key. Restore the matching complete wallet backup; no replacement key was generated."); return;
            }
            int count = total(reply);
            if (p.kidx >= 0 && count > p.kidx) {
                problem(p, "This wallet did not reproduce the pool's owner key. Restore the original seed in its original key mode."); return;
            }
            int limit = p.kidx >= 0 ? p.kidx + 1 : state.searchLimit(fingerprint, normal(p.opk), count);
            if (limit <= count || (long) limit - count > MAX_MINTS || minted >= MAX_MINTS) {
                problem(p, "The owner key was not found within the saved search limit. No funds were moved."); return;
            }
            request("newaddress", made -> {
                JSONObject r = made.optJSONObject("response");
                if (r == null || !key(r.optString("publickey")) || integer(r, "total", -1) != count + 1) {
                    failAll("The node did not confirm the regenerated key. Restore again to check its current state."); return;
                }
                minted++;
                find(p); // Read back: never trust a newaddress reply as evidence of ownership by itself.
            }, this::failAll);
        }, this::failAll);
    }
    private void recordMatch(Pool p, JSONObject keys, JSONObject row) {
        Integer uses = KeyUses.extractUses(keys, p.opk);
        int index = KeyUses.modifierOf(keys, p.opk);
        if (!supportedShape(row)) {
            problem(p, "This pool uses a Classic owner key. Diagnostic recovery leaves it paused."); return;
        }
        if (uses == null || index < 0 || (p.kidx >= 0 && index != p.kidx)) {
            problem(p, "The owner key's index or counter could not be read."); return;
        }
        request(MODE_COMMAND, probe -> {
            if (mode(probe) != result.mode) { failAll("The node's key mode changed. Restore again in block mode. Nothing signed."); return; }
            request("keys", after -> {
                JSONObject actualRow = row(after, p.opk);
                Integer actual = KeyUses.extractUses(after, p.opk);
                if (!sameWallet(after) || !supportedShape(actualRow) || KeyUses.modifierOf(after, p.opk) != index
                        || actual == null) {
                    failAll("The wallet or owner key changed during the check. Signing remains paused."); return;
                }
                if (!state.recordMatch(p.opk, index)) {
                    problem(p, "Could not save the matched key index. Signing remains paused."); return;
                }
                result.matched.add(normal(p.opk));
                progress.accept("Owner key matched at index " + index + "; node counter " + actual
                        + ". Signing remains paused. No recovery signature or withdrawal was sent.");
                next();
            }, this::failAll);
        }, this::failAll);
    }
    private boolean sameWallet(JSONObject reply) { return fingerprint != null && fingerprint.equals(fingerprint(reply)) && total(reply) >= 0; }
    private void problem(Pool p, String text) { result.problems.put(normal(p.opk), text); progress.accept(text); next(); }
    private void failAll(String text) {
        if (finished) return;
        for (String owner : owners.keySet()) if (!result.matched.contains(owner)) result.problems.put(owner, text);
        try { progress.accept(text); } finally { finish(); }
    }
    private void finish() { if (!finished) { finished = true; done.accept(result); } }
    private void request(String command, Consumer<JSONObject> ok, Consumer<String> fail) {
        if (finished) return;
        node.run(command, new NodeApi.Cb() {
            boolean delivered;
            public void onResult(JSONObject j) {
                if (delivered || finished) return; delivered = true;
                if (!TxPost.truthy(j, "status") || TxPost.truthy(j, "pending")) { fail.accept("MinimaCore could not complete pool recovery. " + (j == null ? "" : j.optString("error", ""))); return; }
                try { ok.accept(j); }
                catch (RuntimeException invalid) {
                    failAll("Recovery could not verify or save its state. Restore again to check the pool.");
                }
            }
            public void onError(String error) { if (!delivered && !finished) { delivered = true; fail.accept(error); } }
        });
    }
    static Mode mode(JSONObject reply) {
        if (!TxPost.truthy(reply, "status") || TxPost.truthy(reply, "pending")) return Mode.UNKNOWN;
        JSONObject r = reply.optJSONObject("response");
        String pk = r == null ? "" : r.optString("publickey");
        return BLOCK_PROBE.equalsIgnoreCase(pk) ? Mode.BLOCK : CLASSIC_PROBE.equalsIgnoreCase(pk) ? Mode.CLASSIC : Mode.UNKNOWN;
    }
    static JSONObject row(JSONObject keys, String owner) {
        JSONArray rows = TxPost.truthy(keys, "status") ? KeyUses.rows(keys) : null;
        if (rows != null) for (int i = 0; i < rows.length(); i++) {
            JSONObject r = rows.optJSONObject(i);
            if (r != null && owner.equalsIgnoreCase(r.optString("publickey"))) return r;
        }
        return null;
    }
    // Same seed fingerprint and numeric modifier rule as the former HuntBudget; require index zero.
    static String fingerprint(JSONObject keys) {
        JSONArray rows = TxPost.truthy(keys, "status") ? KeyUses.rows(keys) : null;
        if (rows != null) for (int i = 0; i < rows.length(); i++) {
            JSONObject r = rows.optJSONObject(i);
            if (r != null && key(r.optString("publickey")) && KeyUses.modifierOf(keys, r.optString("publickey")) == 0)
                return normal(r.optString("publickey"));
        }
        return null;
    }
    static int total(JSONObject keys) {
        JSONArray rows = TxPost.truthy(keys, "status") ? KeyUses.rows(keys) : null;
        JSONObject r = keys == null ? null : keys.optJSONObject("response");
        if (rows == null || rows.length() == 0) return -1;
        if (r != null && r.has("total") && integer(r, "total", -1) != rows.length()) return -1;
        return rows.length();
    }
    static int integer(JSONObject o, String name, int fallback) {
        try { return new BigDecimal(o.get(name).toString()).intValueExact(); } catch (Exception bad) { return fallback; }
    }
    private static boolean key(String s) { return s != null && s.matches("0x[0-9a-fA-F]{64}"); }
    private static String normal(String s) { return s.toLowerCase(Locale.ROOT); }
}
