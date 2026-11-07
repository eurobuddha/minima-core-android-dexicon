// VENDORED VERBATIM from apks/pandapools 0.9.89 app/src/main/java/com/eurobuddha/pandapools/CollectionEvidence.java by scripts/vendor-pandapools.sh — do not edit here; re-vendor.
package com.eurobuddha.pandapools;

import org.json.JSONArray;
import org.json.JSONObject;
import java.math.BigDecimal;
import java.math.BigInteger;

/** Read-only evidence gates reused from NFT TransactionEvidence and PandaPools funding checks. */
final class CollectionEvidence {
    private CollectionEvidence() {}
    static boolean id(String value) {
        return value != null && value.matches("0x[0-9a-fA-F]{64}")
                && new BigInteger(value.substring(2), 16).signum() > 0;
    }
    static String draftTransaction(JSONObject reply, String handle) {
        JSONObject row = reply == null ? null : reply.optJSONObject("response");
        JSONObject transaction = row == null ? null : row.optJSONObject("transaction");
        String id = transaction == null ? "" : transaction.optString("transactionid", "");
        return Boolean.TRUE.equals(reply == null ? null : reply.opt("status")) && row != null
                && handle.equals(row.opt("id")) && id(id) ? id : "";
    }
    /** Same strict block/tip/depth consistency check as NFT TransactionEvidence.inclusion. */
    static boolean confirmed(JSONObject reply) {
        if (reply == null || !Boolean.TRUE.equals(reply.opt("status"))) return false;
        JSONObject value = reply.optJSONObject("response");
        if (value == null || !Boolean.TRUE.equals(value.opt("found")) || !id(value.optString("blockid"))) return false;
        try {
            BigInteger block = whole(value, "block"), tip = whole(value, "tip"), depth = whole(value, "confirmations");
            return block.signum() > 0 && depth.compareTo(BigInteger.valueOf(ActivityLog.CONFIRM_BLOCKS)) >= 0
                    && tip.subtract(block).equals(depth);
        } catch (IllegalArgumentException invalid) { return false; }
    }
    private static BigInteger whole(JSONObject value, String key) {
        Object raw = value.opt(key);
        if (!(raw instanceof String) || !((String)raw).matches("[0-9]{1,30}")) throw new IllegalArgumentException();
        return new BigInteger((String) raw);
    }
    /** An omitted, negative, fractional, overflowing or malformed row never proves emptiness. */
    static Integer remaining(JSONObject reply) {
        if (reply == null || !Boolean.TRUE.equals(reply.opt("status"))) return null;
        JSONArray rows = FundingCoins.rows(reply);
        if (rows == null) return null;
        int total = 0;
        try {
            for (int i = 0; i < rows.length(); i++) {
                int count = new BigDecimal(rows.getJSONObject(i).get("coins").toString()).intValueExact();
                if (count < 0) return null;
                total = Math.addExact(total, count);
            }
            return total;
        } catch (Exception invalid) { return null; }
    }
}
