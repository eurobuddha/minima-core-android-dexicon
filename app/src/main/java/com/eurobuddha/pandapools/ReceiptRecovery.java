// VENDORED VERBATIM from apks/pandapools 0.10.0 app/src/main/java/com/eurobuddha/pandapools/ReceiptRecovery.java by scripts/vendor-pandapools.sh — do not edit here; re-vendor.
package com.eurobuddha.pandapools;

import com.eurobuddha.maxima.core.codec.*;
import com.eurobuddha.maxima.core.msg.*;
import org.bouncycastle.crypto.digests.SHA3Digest;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.math.BigDecimal;

/** Read-only recovery of txnpost's pre-mining header ID. The Maxima wire codecs are reused
 * unchanged. A candidate must reproduce the complete mined header hash BEFORE recovering an ID.
 * This establishes identity, not chain inclusion: txpow onchain remains mandatory. */
final class ReceiptRecovery {
    private ReceiptRecovery() {}

    static String submittedId(JSONObject txpow) {
        try {
            String mined = txpow.getString("txpowid");
            if (!mined.matches("0x[0-9a-fA-F]{64}")) return "";
            JSONObject j = txpow.getJSONObject("header");
            TxHeader h = parse(j);
            JSONObject magic = j.getJSONObject("magic");
            // JSON drops decimal trailing zeros; binary MiniNumbers retain their scale.
            // Current chain Magic arithmetic normally leaves 44 decimal places. Try that first.
            int hashes = 0;
            for (int attempt = 0; attempt < 45; attempt++) {
                int scale = attempt == 0 ? 44 : attempt - 1;
                h.mMagic.mCurrentMaxTxPoWSize = number(magic, "currentmaxtxpowsize", scale);
                h.mMagic.mCurrentMaxKISSVMOps = number(magic, "currentmaxkissvmops", scale);
                h.mMagic.mCurrentMaxTxnPerBlock = number(magic, "currentmaxtxn", scale);
                BigDecimal nonce = new BigDecimal(j.getString("nonce"));
                BigDecimal total = new BigDecimal(j.getString("total"));
                // Bounded recovery of stripped zeros on the other fractional fields.
                for (int ns = Math.max(0, nonce.scale()); ns <= 44; ns++) {
                    h.mNonce = new MiniNumber(nonce.setScale(ns));
                    for (int ts = Math.max(0, total.scale()); ts <= 44; ts++) {
                        if (++hashes > 4096) return "";
                        h.mMMRTotal = new MiniNumber(total.setScale(ts));
                        if (!mined.equalsIgnoreCase(hash(h))) continue;
                        h.mNonce = new MiniNumber(0);
                        h.mTxBodyHash = new MiniData("0x00");
                        return hash(h);
                    }
                }
            }
        } catch (Exception malformed) { /* Unsupported or incomplete header: do not guess. */ }
        return "";
    }

    private static TxHeader parse(JSONObject j) throws Exception {
        TxHeader h = new TxHeader();
        h.mNonce = new MiniNumber(j.getString("nonce"));
        h.mChainID = data(j, "chainid");
        h.mTimeMilli = new MiniNumber(j.getString("timemilli"));
        h.mBlockNumber = new MiniNumber(j.getString("block"));
        h.mBlockDifficulty = data(j, "blkdiff");
        h.mMMRRoot = data(j, "mmr");
        h.mMMRTotal = new MiniNumber(j.getString("total"));
        h.mCustomHash = data(j, "customhash");
        h.mTxBodyHash = data(j, "txbodyhash");
        JSONArray parents = j.getJSONArray("superparents"); int n = 0;
        if (parents.length() > 32) throw new IllegalArgumentException("Too many parents");
        for (int i = 0; i < parents.length(); i++) {
            JSONObject p = parents.getJSONObject(i); int count = p.getInt("count");
            if (count <= 0 || count > 32 - n) throw new IllegalArgumentException("Bad parent count");
            MiniData parent = data(p, "parent");
            for (int k = 0; k < count; k++) h.mSuperParents[n++] = parent;
        }
        if (n != 32) throw new IllegalArgumentException("Incomplete parents");
        JSONObject m = j.getJSONObject("magic");
        h.mMagic.mCurrentMinTxPoWWork = data(m, "currentmintxpowwork");
        h.mMagic.mDesiredMaxTxPoWSize = new MiniNumber(m.getString("desiredmaxtxpowsize"));
        h.mMagic.mDesiredMaxKISSVMOps = new MiniNumber(m.getString("desiredmaxkissvmops"));
        h.mMagic.mDesiredMaxTxnPerBlock = new MiniNumber(m.getString("desiredmaxtxn"));
        h.mMagic.mDesiredMinTxPoWWork = data(m, "desiredmintxpowwork");
        return h;
    }
    private static MiniNumber number(JSONObject j, String key, int scale) throws Exception {
        BigDecimal n = new BigDecimal(j.getString(key));
        return new MiniNumber(n.setScale(Math.max(scale, n.scale())));
    }
    private static MiniData data(JSONObject j, String key) throws Exception {
        String s = j.getString(key);
        if (!s.matches("0x[0-9a-fA-F]{1,128}")) throw new IllegalArgumentException("Invalid header hash");
        return new MiniData(s);
    }
    private static String hash(TxHeader h) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        h.writeDataStream(new DataOutputStream(bytes));
        byte[] raw = bytes.toByteArray();
        // Reused from PocketWeb PocketProtocol.sha3: no Samsung JCE provider dependency.
        SHA3Digest d = new SHA3Digest(256);
        d.update(raw, 0, raw.length);
        byte[] out = new byte[d.getDigestSize()]; d.doFinal(out, 0);
        return Hex.encode(out);
    }
}
