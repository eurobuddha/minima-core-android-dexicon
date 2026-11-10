// VENDORED VERBATIM from apks/pandapools 0.10.12 app/src/main/java/com/eurobuddha/pandapools/TrackHygiene.java by scripts/vendor-pandapools.sh — do not edit here; re-vendor.
package com.eurobuddha.pandapools;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure decision logic for keeping the node's tracked-script set hygienic: a plain swapper's node must not
 * adopt a stranger's pool reserves into its wallet balance. Core facts this encodes: {@code newscript}
 * REPLACES an existing row (so re-registering an owned pool would downgrade its {@code trackall:true}), a
 * missing {@code scripts address:} row is a CommandException → {@code {"status":false}} delivered via the
 * SUCCESS callback, and a demoted address stays in core's in-memory relevance cache until the node
 * restarts (which is why the launch sweep re-runs every session). All methods are static and side-effect
 * free so the JVM suite can cover them.
 */
public final class TrackHygiene {

    private TrackHygiene() {}

    /** One covenant row from the node's {@code scripts} reply, carried VERBATIM for safe re-registration. */
    public static final class Row {
        public final String address;   // 0x… as the node returned it
        public final String script;    // the row's exact script — a legacy-fee covenant reconstructs differently
        public final boolean track;
        Row(String address, String script, boolean track) {
            this.address = address; this.script = script; this.track = track;
        }
    }

    // Owner key extractor — same literal the covenant embeds and PoolBook's Source-1 parser matches.
    private static final Pattern P_OPK = Pattern.compile("SIGNEDBY\\((0x[0-9A-Fa-f]+)\\)");

    /** Same covenant fingerprint PoolBook uses to recognise a PandaPools pool among tracked scripts. */
    static boolean isCovenantScript(String s) {
        return s != null && s.contains("VERIFYOUT(@INPUT @ADDRESS") && s.contains("GTE MAX(x*y");
    }

    /** The covenant's embedded owner public key, or null when it can't be extracted. */
    static String opkOf(String script) {
        if (script == null) return null;
        Matcher m = P_OPK.matcher(script);
        return m.find() ? m.group(1) : null;
    }

    /**
     * The FOREIGN pool covenants among the node's script rows. A row is foreign only when its owner key is
     * provably not ours: not in the node keystore, not an OwnPoolStore opk, and its address not an
     * OwnPoolStore address. Rows whose opk can't be parsed are skipped — never touched — and non-covenant
     * rows are ignored entirely. Callers must pass a NON-EMPTY key set (an empty/failed {@code keys} read
     * means ownership can't be proven, so the sweep must abort with zero writes).
     */
    static List<Row> classifyForeign(JSONArray scriptsReply, Set<String> myKeysLower,
                                     Set<String> ownAddrsLower, Set<String> ownOpksLower) {
        List<Row> out = new ArrayList<>();
        if (scriptsReply == null || myKeysLower == null || myKeysLower.isEmpty()) return out;
        for (int i = 0; i < scriptsReply.length(); i++) {
            JSONObject s = scriptsReply.optJSONObject(i);
            if (s == null) continue;
            String sc = s.optString("script", "");
            if (!isCovenantScript(sc)) continue;
            String addr = s.optString("address", "");
            String opk = opkOf(sc);
            if (addr.isEmpty() || opk == null) continue;
            String a = addr.toLowerCase(), k = opk.toLowerCase();
            if (myKeysLower.contains(k)) continue;                                        // own — by keystore
            if (ownOpksLower != null && ownOpksLower.contains(k)) continue;               // own — by recipe opk
            if (ownAddrsLower != null && ownAddrsLower.contains(a)) continue;             // own — by recipe address
            out.add(new Row(addr, sc, TxPost.truthy(s, "track")));   // track arrives boolean OR string
        }
        return out;
    }

    /** Coinids of relevant coins sitting at foreign covenant addresses (both legs, any tokenid). */
    static List<String> coinsToUntrack(JSONArray coinsRelevantReply, Set<String> foreignAddrsLower) {
        List<String> out = new ArrayList<>();
        if (coinsRelevantReply == null || foreignAddrsLower == null || foreignAddrsLower.isEmpty()) return out;
        for (int i = 0; i < coinsRelevantReply.length(); i++) {
            JSONObject c = coinsRelevantReply.optJSONObject(i);
            if (c == null) continue;
            String coinid = c.optString("coinid", "");
            String addr = c.optString("address", "").toLowerCase();
            if (!coinid.isEmpty() && foreignAddrsLower.contains(addr)) out.add(coinid);
        }
        return out;
    }

    /**
     * True iff a {@code scripts address:} reply found a row — any track value. The node answers a missing
     * row with a CommandException, i.e. {@code {"status":false}} through the SUCCESS callback; a found row
     * is a single response object (tolerate an array shape defensively).
     */
    static boolean rowExists(JSONObject scriptsAddressReply) {
        if (scriptsAddressReply == null || !TxPost.truthy(scriptsAddressReply, "status")) return false;
        Object resp = scriptsAddressReply.opt("response");
        if (resp instanceof JSONArray) return ((JSONArray) resp).length() > 0;
        return resp instanceof JSONObject;
    }

    /**
     * True iff a {@code scripts address:} reply AFFIRMATIVELY says the row does not exist (the node's
     * "Script with that address not found" CommandException). Any other failed reply — timeout-shaped,
     * permission, IPC overflow — is ambiguous: the row may exist, so the caller must NOT write
     * ({@code newscript} REPLACES an existing row). A genuinely missing row then just fails closed at
     * {@code txnbasics} and nothing posts.
     */
    static boolean isNotFound(JSONObject scriptsAddressReply) {
        if (scriptsAddressReply == null || TxPost.truthy(scriptsAddressReply, "status")) return false;
        return scriptsAddressReply.optString("error", "").toLowerCase().contains("not found");
    }
}
