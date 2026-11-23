package com.eurobuddha.account;

import org.json.JSONArray;
import org.json.JSONObject;
import org.minima.objects.base.MiniData;
import org.minima.objects.base.MiniNumber;
import org.minima.objects.keys.Signature;
import org.minima.objects.keys.SignatureProof;
import org.minima.objects.keys.TreeKey;
import org.minima.objects.mmr.MMRData;
import org.minima.objects.mmr.MMRProof;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads the one-time leaf a Trading Account signature used, from a TxPoW as the node prints it ({@code history} /
 * {@code txpow txpowid:} JSON: {@code body.txn.transactionid}, {@code body.witness.signatures[]}). Second-signer
 * detection (review 23 Nov 2026): every spend of an account coin carries its signature on chain, and a signature by an
 * account key over a transaction this install never signed means another install signs with the same words.
 *
 * <p>The leaf: a 128x4 TreeKey signature holds one SignatureProof per tree level (minima {@code TreeKey.sign}); each
 * proof's MMR proof is the path of that level's key number inside its 128-leaf MMR (one peak, 7 chunks), and chunk
 * {@code i} is "left" exactly when the key's entry is the RIGHT child at row {@code i} (MMR.getProofToPeak), so bit
 * {@code i} of the key number is {@code left}. leaf = sum of keynum(level) * 128^(3 - level), the inverse of
 * TreeKey.baseConversion. Every signature is VERIFIED against its transaction id before it counts: an unverifiable or
 * oddly shaped one is ignored (a forged leaf must never raise our counter, nor halt us).
 */
final class SignerWatch {
    private SignerWatch() {}

    /** One verified signature by an account key. */
    static final class Seen {
        final int key, leaf; final String txnId;
        Seen(int key, int leaf, String txnId) { this.key = key; this.leaf = leaf; this.txnId = txnId; }
    }

    interface KeyIndex { int of(String pubkey); }

    /** Every verified account-key signature in {@code txpow} (node JSON); empty when none or unreadable. */
    static List<Seen> signatures(JSONObject txpow, KeyIndex keys) {
        List<Seen> out = new ArrayList<>();
        JSONObject body = txpow == null ? null : txpow.optJSONObject("body");
        JSONObject txn = body == null ? null : body.optJSONObject("txn"), wit = body == null ? null : body.optJSONObject("witness");
        String txnId = txn == null ? "" : txn.optString("transactionid", "");
        JSONArray sigs = wit == null ? null : wit.optJSONArray("signatures");
        if (sigs == null || !txnId.matches("0x[0-9A-Fa-f]{64}")) return out;
        for (int i = 0; i < sigs.length(); i++) {
            JSONArray proofs = sigs.optJSONObject(i) == null ? null : sigs.optJSONObject(i).optJSONArray("signatures");
            if (proofs == null) continue;
            try {
                int leaf = leafOf(proofs);
                if (leaf < 0) continue;
                Signature s = signatureOf(proofs);
                int key = keys.of(s.getRootPublicKey().to0xString());   // computed from the proofs, not the printed rootkey
                if (key < 0) continue;
                TreeKey verifier = new TreeKey();
                verifier.setPublicKey(s.getRootPublicKey());
                if (verifier.verify(new MiniData(txnId), s)) out.add(new Seen(key, leaf, txnId));
            } catch (Exception unreadable) { /* not a signature we can read: ignored */ }
        }
        return out;
    }

    /** The leaf index of a 128x4 signature's proofs (JSON), or -1 when it is not that shape. */
    static int leafOf(JSONArray proofs) {
        if (proofs.length() != AccountKeys.LEVELS) return -1;
        int bits = Integer.numberOfTrailingZeros(AccountKeys.KEYS_PER_LEVEL);   // 7
        long leaf = 0;
        for (int level = 0; level < proofs.length(); level++) {
            JSONObject proof = proofs.optJSONObject(level) == null ? null : proofs.optJSONObject(level).optJSONObject("proof");
            JSONArray chunks = proof == null ? null : proof.optJSONArray("proof");
            if (chunks == null || chunks.length() != bits) return -1;
            int keynum = 0;
            for (int i = 0; i < bits; i++) {
                JSONObject c = chunks.optJSONObject(i);
                if (c == null || !c.has("left")) return -1;
                if (c.optBoolean("left")) keynum |= 1 << i;
            }
            leaf = leaf * AccountKeys.KEYS_PER_LEVEL + keynum;
        }
        return (int) leaf;
    }

    /** The minima Signature back from its JSON (publickey, signature, proof.blocktime, proof.proof[left, data.data, data.value]). */
    static Signature signatureOf(JSONArray proofs) throws org.json.JSONException {
        Signature s = new Signature();
        for (int level = 0; level < proofs.length(); level++) {
            JSONObject p = proofs.getJSONObject(level), pj = p.getJSONObject("proof");
            MMRProof mmr = new MMRProof(new MiniNumber(pj.optString("blocktime", "0")));
            JSONArray chunks = pj.getJSONArray("proof");
            for (int i = 0; i < chunks.length(); i++) {
                JSONObject c = chunks.getJSONObject(i), d = c.getJSONObject("data");
                mmr.addProofChunk(c.getBoolean("left"), new MMRData(new MiniData(d.getString("data")), new MiniNumber(d.optString("value", "0"))));
            }
            s.addSignatureProof(new SignatureProof(new MiniData(p.getString("publickey")), new MiniData(p.getString("signature")), mmr));
        }
        return s;
    }

    /** True when every input of {@code txpow}'s transaction is at an account address: its outputs are then internal
     *  (change, a consolidation, the sibling app's own move on this phone) - never a deposit. */
    static boolean fundedByAccount(JSONObject txpow, KeyIndex addresses) {
        JSONObject body = txpow == null ? null : txpow.optJSONObject("body");
        JSONObject txn = body == null ? null : body.optJSONObject("txn");
        JSONArray ins = txn == null ? null : txn.optJSONArray("inputs");
        if (ins == null || ins.length() == 0) return false;
        for (int i = 0; i < ins.length(); i++) {
            JSONObject c = ins.optJSONObject(i);
            if (c == null || addresses.of(c.optString("address", "")) < 0) return false;
        }
        return true;
    }

    /** The output coin ids of {@code txpow}'s transaction, as the node printed them. */
    static List<String> outputIds(JSONObject txpow) {
        List<String> out = new ArrayList<>();
        JSONObject body = txpow == null ? null : txpow.optJSONObject("body");
        JSONObject txn = body == null ? null : body.optJSONObject("txn");
        JSONArray outs = txn == null ? null : txn.optJSONArray("outputs");
        for (int i = 0; outs != null && i < outs.length(); i++) {
            String id = outs.optJSONObject(i) == null ? "" : outs.optJSONObject(i).optString("coinid", "");
            if (id.matches("0x[0-9A-Fa-f]{64}")) out.add(id);
        }
        return out;
    }
}
