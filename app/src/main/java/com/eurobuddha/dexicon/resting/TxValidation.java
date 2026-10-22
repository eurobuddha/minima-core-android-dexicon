package com.eurobuddha.dexicon.resting;
/* Vendored from apks/pandadex app/src/main/java/com/eurobuddha/pandadex/TxValidation.java (PandaDEX 0.4.23).
 * Keep as close to verbatim as possible; only the package, the NodeApi/SignGate imports and the minimal Dexicon
 * glue noted inline differ. Per the family naming rule the Minima dollar token is MxUSD in identifiers too
 * (PandaDEX MXUSD_ID/MXUSD_DP/*Mxusd are MXUSD_ID/MXUSD_DP/*Mxusd here). Re-copy rather than hand-edit when PandaDEX changes. */


import org.json.JSONObject;

/** Verdict checks reused from PandaPools TxPost. */
final class TxValidation {
    static String checkFailure(JSONObject reply) {
        JSONObject r = reply == null ? null : reply.optJSONObject("response");
        JSONObject valid = r == null ? null : r.optJSONObject("valid");
        if (!truthy(reply, "status")) return "The node could not check the transaction. Nothing was posted.";
        if (!truthy(valid, "mmrproofs")) return "An input coin was already spent or its proof is invalid. Refresh and retry; nothing was posted.";
        if (!truthy(r, "validamounts")) return "The transaction amounts do not balance. Nothing was posted.";
        if (!truthy(valid, "scripts")) return "The covenant rejects this transaction. Nothing was posted.";
        if (!truthy(valid, "basic") || !truthy(r, "allsignaturesvalid") || !truthy(r, "validtransaction"))
            return "The node did not validate the complete transaction and signatures. Nothing was posted.";
        return null;
    }

    static String postError(JSONObject reply) {
        String error = reply == null ? "" : reply.optString("error", "");
        if (error.contains("TxPoW size too large"))
            return "Minima rejected the transaction at txnpost: " + error
                    + ". These are serialized TxPoW bytes / the chain maximum. Nothing was broadcast. "
                    + "Use MinimaCore consolidation to combine fewer coins, wait for confirmation, then retry. "
                    + "A coin-count limit does not guarantee that proofs and signatures fit.";
        return "Node rejected txnpost" + (error.isEmpty() ? "." : ": " + error);
    }

    static boolean truthy(JSONObject o, String key) {
        Object v = (o == null) ? null : o.opt(key);
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof Number) return "1".equals(v.toString());
        if (v instanceof String) { String s = ((String) v).trim(); return s.equals("1") || s.equalsIgnoreCase("true"); }
        return false;
    }
}
