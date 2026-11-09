package com.eurobuddha.dexicon.account;

import org.json.JSONArray;
import org.json.JSONObject;

/** Test-only door into the account package for engine tests in other packages. */
public final class AccountTestAccess {
    public static String boundedAdd(String json, java.util.Collection<String> add) { return VirtualWallet.boundedAdd(json, add); }

    public static AccountKeys keys() { return AccountKeys.fromPhrase(AccountKeysTest.PHRASE, new AccountKeysTest.Mem(), null); }

    /** A Trading Account wallet over a node that answers only the chain reads a wallet setup needs. */
    public static VirtualWallet wallet(AccountKeys keys) {
        VirtualWallet.Store store = new VirtualWallet.Store() {
            int n; public int keyCount() { return n; } public void setKeyCount(int x) { n = x; }
        };
        VirtualWallet.Node node = cmd -> {
            if (cmd.startsWith("newscript")) return new JSONObject().put("status", true).put("response", new JSONObject());
            if (cmd.startsWith("coins") || cmd.startsWith("balance")) return new JSONObject().put("status", true).put("response", new JSONArray());
            if (cmd.equals("block")) return new JSONObject().put("status", true).put("response", new JSONObject().put("block", "100").put("timemilli", String.valueOf(System.currentTimeMillis())));
            return new JSONObject().put("status", false).put("error", "not found");
        };
        return new VirtualWallet(node, keys, store, java.util.Collections.emptySet());
    }
}
