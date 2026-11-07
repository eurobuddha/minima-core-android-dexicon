// VENDORED VERBATIM from apks/pandapools 0.9.86 app/src/main/java/com/eurobuddha/pandapools/BackupCheck.java by scripts/vendor-pandapools.sh — do not edit here; re-vendor.
package com.eurobuddha.pandapools;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Checks a backup file WITHOUT changing anything: no recipe is saved, no covenant is tracked, no coin proof is
 * imported, no transaction is built and nothing is signed.
 *
 * Why this exists as its own path. Restore is the only thing that has ever read a backup file, and it writes —
 * it saves recipes, tracks covenants and imports proofs. So the only way to find out whether a backup was any
 * good was to commit to restoring it. Once Restore also withdraws, that becomes unacceptable: "let me check my
 * file" must not be a fund-moving operation. This is the look-only path, and it ships before Restore ever spends.
 *
 * It also answers the question a user actually has, which "the file exists" does not: can this file still
 * recover my pool on this node, today. That means checking the recipe re-derives its covenant, whether the
 * reserves are visible here, whether this wallet even holds the owner key, and — the one that matters most —
 * whether the node's signature counter has fallen BELOW what the backup recorded, which is the shape that
 * precedes a key-reuse accident.
 *
 * Read-only-ness is enforced by {@link #readOnly}, an allowlist, rather than by review. Every command this class
 * issues goes through it, so a future edit that reaches for {@code coinimport}, {@code newscript} or {@code sign}
 * fails closed instead of quietly writing during a "check".
 */
final class BackupCheck {

    interface Cb {
        void onProgress(String line);
        void onDone(String report, int usable, int total);
    }

    private final NodeApi node;

    BackupCheck(NodeApi node) { this.node = node; }

    /**
     * The only commands a check may ever issue. Anything that tracks, imports, mints, builds or signs is absent
     * by design — and `keys` is restricted to `action:list`, because bare `keys` has subcommands that create.
     */
    static boolean readOnly(String cmd) {
        if (cmd == null) return false;
        String c = cmd.trim();
        if (c.isEmpty() || c.contains(";") || c.contains("\n") || c.contains("\r")) return false;

        // runscript only evaluates a script and returns its address; the script argument is quoted and may
        // contain spaces, so it is checked by prefix. Everything else is matched on its EXACT shape rather than
        // a prefix: `coins address:<hex>` and `coins address:<hex> megammr:true` share a prefix but not a
        // meaning, and a check has no business widening its reads to an archive.
        if (c.startsWith("runscript script:")) return true;

        String[] t = c.split(" +");
        switch (t[0]) {
            case "coins":
                return t.length == 2 && (hexArg(t[1], "address:") || hexArg(t[1], "coinid:"));
            case "balance":
                return t.length == 2 && hexArg(t[1], "address:");
            case "keys":
                if (t.length < 2 || !"action:list".equals(t[1])) return false;
                return t.length == 2 || (t.length == 3 && hexArg(t[2], "publickey:"));
            default:
                return false;
        }
    }

    private static boolean hexArg(String token, String name) {
        return token.startsWith(name) && FundingCoins.hex(token.substring(name.length()));
    }

    private void run(String cmd, NodeApi.Cb cb) {
        if (!readOnly(cmd)) { cb.onError("Refused a command that is not read-only: " + cmd); return; }
        node.cmd(cmd, cb);
    }

    /** Check every pool in {@code json}. Nothing is persisted, whatever the outcome. */
    void check(String json, Cb cb) {
        JSONArray pools;
        int version;
        try {
            JSONObject root = new JSONObject(json);
            version = root.optInt("pandapools_backup", -1);
            pools = root.optJSONArray("pools");
        } catch (Exception bad) {
            cb.onDone("That file is not readable JSON, so it is not a PandaPools backup.", 0, 0);
            return;
        }
        if (version < 1 || pools == null || pools.length() == 0) {
            cb.onDone("That file is not a PandaPools backup, or it contains no pools.", 0, 0);
            return;
        }
        if (pools.length() > 500) {
            cb.onDone("That file claims " + pools.length() + " pools, which is more than a backup can hold.", 0, 0);
            return;
        }

        StringBuilder report = new StringBuilder();
        report.append("Backup format version ").append(version);
        if (version < Recovery.BACKUP_VERSION)
            report.append(" (older than this app writes — still readable, but it may not record the owner key's "
                    + "derivation index or its signature count)");
        report.append('\n').append(pools.length()).append(pools.length() == 1 ? " pool" : " pools").append(" in the file.\n");

        one(pools, 0, report, new int[]{0}, cb);
    }

    private void one(JSONArray pools, int i, StringBuilder report, int[] usable, Cb cb) {
        if (i >= pools.length()) {
            report.append("\n").append(usable[0]).append(" of ").append(pools.length())
                    .append(pools.length() == 1 ? " pool looks recoverable from this file on this node."
                                                : " pools look recoverable from this file on this node.");
            report.append("\n\nNothing was changed. This check saves nothing, imports nothing and signs nothing.");
            cb.onDone(report.toString(), usable[0], pools.length());
            return;
        }
        JSONObject e = pools.optJSONObject(i);
        final String addr = e == null ? "" : e.optString("addr", "");
        report.append("\n————————————————————\nPool: ").append(addr.isEmpty() ? "(no address in this entry)" : addr).append('\n');

        if (e == null || addr.isEmpty()) {
            report.append("  ✗ unusable entry — no pool address\n");
            one(pools, i + 1, report, usable, cb); return;
        }
        String script = e.optString("script", "");
        if (script.isEmpty()) {
            report.append("  ✗ no covenant script in the backup — this pool cannot be rebuilt from this file\n");
            one(pools, i + 1, report, usable, cb); return;
        }
        if (!Recovery.validRecipe(e)) {
            report.append("  ✗ the recipe fields do not describe this covenant — this entry cannot be used\n");
            one(pools, i + 1, report, usable, cb); return;
        }
        report.append("  ✓ recipe fields are valid\n");

        // Re-derive the address from the script. Same rule Restore applies, so a pass here means Restore would
        // accept it too.
        run("runscript script:" + Util.scriptArg(script), new NodeApi.Cb() {
            @Override public void onResult(JSONObject reply) {
                JSONObject r = reply == null ? null : reply.optJSONObject("response");
                JSONObject sc = r == null ? null : r.optJSONObject("script");
                boolean derived = TxPost.truthy(reply, "status") && TxPost.truthy(r, "parseok") && sc != null
                        && addr.equalsIgnoreCase(sc.optString("address", ""));
                if (!derived) {
                    report.append("  ✗ the covenant script does not produce this pool address — the entry has "
                            + "been altered or corrupted\n");
                    one(pools, i + 1, report, usable, cb); return;
                }
                report.append("  ✓ the covenant script rebuilds this exact pool address\n");
                reserves(pools, i, e, addr, report, usable, cb);
            }
            @Override public void onError(String error) {
                report.append("  ? could not check the covenant: ").append(error).append('\n');
                one(pools, i + 1, report, usable, cb);
            }
        });
    }

    private void reserves(JSONArray pools, int i, JSONObject e, String addr,
                          StringBuilder report, int[] usable, Cb cb) {
        run("coins address:" + addr, new NodeApi.Cb() {
            @Override public void onResult(JSONObject reply) {
                JSONArray rows = FundingCoins.rows(reply);
                int n = rows == null ? -1 : rows.length();
                if (n < 0) report.append("  ? could not read this pool's coins on this node\n");
                else if (n == 0) report.append("  — no reserve coins visible on this node. That does NOT mean the "
                        + "funds are gone: coins older than about 1700 blocks are only visible to a MegaMMR "
                        + "archive. Recovery would need one.\n");
                else report.append("  ✓ ").append(n).append(n == 1 ? " coin" : " coins")
                        .append(" visible on this node at this pool address\n");
                ownerKey(pools, i, e, addr, report, usable, cb);
            }
            @Override public void onError(String error) {
                report.append("  ? could not read this pool's coins: ").append(error).append('\n');
                ownerKey(pools, i, e, addr, report, usable, cb);
            }
        });
    }

    private void ownerKey(JSONArray pools, int i, JSONObject e, String addr,
                          StringBuilder report, int[] usable, Cb cb) {
        final String opk = e.optString("opk", "");
        final int recorded = e.has("opkuses") ? e.optInt("opkuses", -1) : -1;
        report.append("  Owner key: ").append(opk).append('\n');
        if (!FundingCoins.hex(opk)) {
            report.append("  ✗ the owner key in this entry is not a valid public key\n");
            one(pools, i + 1, report, usable, cb); return;
        }
        run("keys action:list publickey:" + opk, new NodeApi.Cb() {
            @Override public void onResult(JSONObject reply) {
                Integer uses = KeyUses.extractUses(reply, opk);
                if (uses == null) {
                    report.append("  — this wallet does not hold the owner key, so this node could not withdraw "
                            + "the pool. Restore the MinimaCore wallet backup that created it first.\n");
                } else {
                    report.append("  ✓ this wallet holds the owner key (")
                            .append(uses).append(" of ").append(KeyUses.capacityOf(reply, opk))
                            .append(" one-time signatures used)\n");
                    if (recorded < 0) {
                        report.append("  ⚠ this backup records NO signature count, so nothing here can detect a "
                                + "counter that has gone backwards. PandaPools will not withdraw automatically "
                                + "from a recipe like this.\n");
                    } else if (uses < recorded) {
                        report.append("  ⚠ DO NOT RESTORE THIS ONTO THIS NODE. The backup recorded ")
                                .append(recorded).append(" signatures used, but this node's key reports ")
                                .append(uses).append(". Signing here would reuse a one-time signature and could "
                                + "expose the key. Find the newest matching wallet backup — the one from the "
                                + "device that used this pool most recently.\n");
                    } else {
                        report.append("  ✓ the node's signature count (").append(uses)
                                .append(") is not below what this backup recorded (").append(recorded)
                                .append("). No problem detected — which is not proof the key is unused.\n");
                    }
                }
                proofs(pools, i, e, report, usable, cb);
            }
            @Override public void onError(String error) {
                report.append("  ? could not read the owner key: ").append(error).append('\n');
                proofs(pools, i, e, report, usable, cb);
            }
        });
    }

    private void proofs(JSONArray pools, int i, JSONObject e, StringBuilder report, int[] usable, Cb cb) {
        boolean cm = !e.optString("cm", "").isEmpty(), ct = !e.optString("ct", "").isEmpty();
        if (cm && ct) report.append("  ✓ both reserve coin proofs are in the file (a shortcut; they expire)\n");
        else if (cm || ct) report.append("  — only one reserve coin proof is in the file; recovery would fall "
                + "back to an archive lookup\n");
        else report.append("  — no coin proofs in the file. Expected, and not a problem: they expire, and "
                + "recovery works from the recipe plus an archive lookup.\n");
        String warn = e.optString("proof_warning", "");
        if (!warn.isEmpty()) report.append("  note from when this backup was taken: ").append(warn).append('\n');
        int kidx = e.optInt("kidx", -1);
        if (kidx >= 0) report.append("  ✓ the owner key's derivation index is recorded (").append(kidx).append(")\n");
        else report.append("  — the owner key's derivation index is not recorded (an older backup format)\n");
        usable[0]++;
        one(pools, i + 1, report, usable, cb);
    }
}
