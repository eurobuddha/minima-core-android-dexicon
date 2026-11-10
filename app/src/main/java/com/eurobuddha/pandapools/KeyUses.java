// VENDORED VERBATIM from apks/pandapools 0.10.0 app/src/main/java/com/eurobuddha/pandapools/KeyUses.java by scripts/vendor-pandapools.sh — do not edit here; re-vendor.
package com.eurobuddha.pandapools;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Reads a key's one-time-signature use counter out of a {@code keys action:list} reply. Read-only.
 *
 * WHY THE COUNTER MATTERS. Minima signatures are stateful: each key is a 64-ary, 3-level Winternitz tree and
 * every signature must consume the NEXT leaf. Signing one leaf twice, over two different messages, leaks that
 * leaf's private key. Observed in the wild — see the 0.9.22 entry in CHANGELOG.md, where 7 of 64 default keys on
 * a live node were flagged {@code RE-USED x2}.
 *
 * A pool's owner key ($OPK) is minted with {@code newaddress}, so a seed-only re-sync does not bring it back:
 * {@code initDefaultKeys} rebuilds only the 64 default keys. Worse, a node inserts every new key at
 * {@code uses = 0}:
 *
 * <pre>Wallet.createNewKey:  SQL_CREATE_PUBLIC_KEY.setInt(3, treekey.getUses());   // a fresh TreeKey -> 0</pre>
 *
 * A key re-minted that way is byte-identical to the one that already signed, but its counter has forgotten
 * everything — so the next owner action would sign a leaf that was already spent on a different transaction.
 * The node's own restore margins do not help: {@code restore.java} (+256) and {@code vault.java} (=100) both
 * run BEFORE an owner key is re-minted, and only touch keys that exist at that moment.
 *
 * WHAT THIS CLASS DELIBERATELY DOES NOT DO. It never advances or guesses a Classic signing counter.
 * BlockOwnerRecovery only locates the matching key. It never signs or advances a counter.
 *
 * There is no command to set one — {@code keys} offers only {@code list / genkey / checkkeys / new /
 * createallkeys}, {@code updateAllKeyUses} rewrites all 64 defaults at once, and the private key cannot be
 * fetched to sign locally ({@code KeyRow.toJSON()} has {@code privatekey} commented out). The only way a node
 * exposes to move a counter is to actually spend the leaves, by repeatedly calling {@code sign}.
 *
 * 0.9.23 did exactly that: it estimated where a restored key should resume (recorded count + elapsed blocks /
 * REFRESH_BLOCKS + slack) and burned leaves up to that target. 0.9.47 reversed it, and the burn code is deleted
 * as of 0.9.48, because the approach cannot work:
 *
 *   - burning cannot undo a reuse that already happened;
 *   - the target is a guess — a signature the original node made that never reached the chain, or one made
 *     after the last backup, is invisible to every term in that sum;
 *   - and burning IS signing, so climbing past an unknown high-water mark re-signs every leaf on the way up.
 *
 * So signing state is never reconstructed or estimated. It is either present, as the matching current
 * MinimaCore wallet backup, or the recipe stays signing-quarantined. {@link OwnerKeyRecovery} reads counters
 * through {@link #extractUses} and fails closed; a counter below a recipe's recorded floor blocks signing
 * outright. Do not reintroduce a burn path.
 */
public final class KeyUses {

    private KeyUses() {}

    /** Capacity of a legacy 64x3 Winternitz tree - every key minted before block-as-key-uses. */
    static final int LEGACY_TREE_USES = 262144;

    /** Capacity of a block-as-key-uses 128x4 tree (minima-core 1.1.2.31+, -blockaskeyuses). On such a
     *  node a key's `uses` tracks the chain tip BLOCK NUMBER (~millions), so judging exhaustion against
     *  the legacy 262,144 would wrongly brand every healthy block-mode key as spent. */
    static final int MAX_TREE_USES = 268435456;

    /** This key's one-time-signature capacity, read from its own row (size^depth). Falls back to the
     *  legacy 64x3 capacity when the row does not carry size/depth (pre-1.1.2.31 nodes, which only
     *  ever mint legacy keys). */
    static int capacityOf(JSONObject reply, String publickey) {
        JSONArray arr = rows(reply);
        if (arr == null || publickey == null) return LEGACY_TREE_USES;
        String want = publickey.toLowerCase();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject k = arr.optJSONObject(i);
            if (k == null || !want.equals(k.optString("publickey", "").toLowerCase())) continue;
            int size = k.optInt("size", 0), depth = k.optInt("depth", 0);
            if (size <= 1 || depth <= 0 || depth > 8) return LEGACY_TREE_USES;
            long cap = 1;
            //Clamped at 128^4: upstream briefly built 192x4 trees (never released). A clamped
            //capacity under-reports such a key and its uses beyond the parse bound read as
            //unreadable - both fail CLOSED, which is the right direction for a signing guard.
            for (int d = 0; d < depth; d++) { cap *= size; if (cap > MAX_TREE_USES) return MAX_TREE_USES; }
            return (int) cap;
        }
        return LEGACY_TREE_USES;
    }

    /** Pull one key's `uses` out of a `keys action:list` reply. The response nests the rows under
     *  `response.keys`; a publickey-filtered call returns just the one. Null means UNKNOWN — never 0, because
     *  treating an absent key as "zero uses" would resume at the first leaf, which is the leak. */
    static Integer extractUses(JSONObject j, String publickey) {
        if (!TxPost.truthy(j, "status") || publickey == null) return null;
        Object resp = j.opt("response");
        JSONArray arr = null;
        if (resp instanceof JSONArray) arr = (JSONArray) resp;
        else if (resp instanceof JSONObject) arr = ((JSONObject) resp).optJSONArray("keys");
        if (arr == null) return null;
        String want = publickey.toLowerCase();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject k = arr.optJSONObject(i);
            if (k == null) continue;
            if (want.equals(k.optString("publickey", "").toLowerCase()) && k.has("uses")) {
                try {
                    int uses = new java.math.BigDecimal(k.get("uses").toString()).intValueExact();
                    //The bound is the LARGEST tree's capacity, so a corrupt legacy counter in
                    //(262144, 268435456] parses and classifies as KEY_EXHAUSTED rather than
                    //NODE_UNREADABLE - slightly over-confident wording for corruption, but both
                    //fail closed, and for the common cause (a block-mode number on a legacy row)
                    //"exhausted" is the actionable answer.
                    return uses >= 0 && uses <= MAX_TREE_USES ? uses : null;
                } catch (Exception invalid) { return null; }
            }
        }
        return null;
    }

    /** The key rows of a {@code keys} reply — the response is either the array itself or nests it under
     *  {@code keys} (both shapes exist in the wild). Null means the reply was unreadable, which every caller
     *  must treat as "we do not know what this wallet holds", never as "it holds nothing". */
    static JSONArray rows(JSONObject reply) {
        Object resp = reply == null ? null : reply.opt("response");
        if (resp instanceof JSONArray) return (JSONArray) resp;
        if (resp instanceof JSONObject) return ((JSONObject) resp).optJSONArray("keys");
        return null;
    }

    /** The derivation index ({@code modifier}) of one held key in a {@code keys} reply, or -1 if the reply is
     *  unreadable, the key is absent, or the modifier will not parse. Recorded into a recipe as {@code kidx} so
     *  {@link OwnerKeyRecovery} can tell a key this wallet simply has not reached from one it can never derive. */
    static int modifierOf(JSONObject keysReply, String publickey) {
        JSONArray arr = rows(keysReply);
        if (arr == null || publickey == null || publickey.isEmpty()) return -1;
        String want = publickey.toLowerCase();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject k = arr.optJSONObject(i);
            if (k == null || !want.equals(k.optString("publickey", "").toLowerCase())) continue;
            java.math.BigInteger m = parseModifier(k.optString("modifier", ""));
            return (m != null && m.signum() >= 0 && m.bitLength() < 31) ? m.intValue() : -1;
        }
        return -1;
    }

    /** A MiniData modifier ("0x00", "0x41", …) as a number, or null if unparsable. */
    private static java.math.BigInteger parseModifier(String s) {
        if (s == null) return null;
        String h = s.trim();
        if (h.startsWith("0x") || h.startsWith("0X")) h = h.substring(2);
        if (h.isEmpty()) return null;
        try { return new java.math.BigInteger(h, 16); } catch (NumberFormatException e) { return null; }
    }
}
