// VENDORED VERBATIM from apks/pandapools 0.9.88 app/src/main/java/com/eurobuddha/pandapools/Coin.java by scripts/vendor-pandapools.sh — do not edit here; re-vendor.
package com.eurobuddha.pandapools;

import org.json.JSONObject;

/** A single UTXO as returned by the node's "coins relevant:true" command. */
public class Coin {

    public String coinid;
    public String address;
    public String miniaddress;
    public String tokenid;
    public String amount;       // human-readable (Minima "amount" or token "tokenamount")
    public String tokenName;
    public boolean confirmed = true;
    public boolean sendable = false;   // set from "coins relevant:true sendable:true"

    public static Coin from(JSONObject c) {
        Coin x = new Coin();
        x.coinid      = c.optString("coinid", "");
        x.address     = c.optString("address", "");
        x.miniaddress = c.optString("miniaddress", "");
        x.tokenid     = c.optString("tokenid", Util.MINIMA_TOKENID);

        boolean minima = Util.isMinima(x.tokenid);
        x.amount    = minima ? c.optString("amount", "0")
                             : c.optString("tokenamount", c.optString("amount", "0"));
        x.tokenName = Util.tokenName(c.opt("token"), x.tokenid);

        // The node's "coins" command returns confirmed, unspent coins — the Coin JSON has no
        // per-coin confirmation flag (verified against the node's command classes), so any coin
        // we get back here is confirmed. Spendability is gated separately by the sendable query.
        x.confirmed = true;
        return x;
    }
}
