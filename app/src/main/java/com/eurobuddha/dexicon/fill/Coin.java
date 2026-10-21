package com.eurobuddha.dexicon.fill;

import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.util.Locale;

/** One node coin row (from `coins …` or a txnview input/output), reduced to what the fill math needs. */
public final class Coin {
    public final String coinid, address, tokenid;
    /** The human value: MINIMA uses amount, a token uses tokenamount. */
    public final BigDecimal value;
    public final boolean spent, hasState;
    public final long created;
    /** The simple address script, when known (maker backing only). */
    public String script;

    public Coin(String coinid, String address, String tokenid, BigDecimal value, boolean spent, boolean hasState, long created) {
        this.coinid = coinid; this.address = address; this.tokenid = tokenid; this.value = value; this.spent = spent; this.hasState = hasState; this.created = created;
    }

    public static Coin of(JSONObject c) {
        String tok = c.optString("tokenid", "0x00");
        String amt = Tokens.isMinima(tok) ? c.optString("amount", "0") : c.optString("tokenamount", c.optString("amount", "0"));
        JSONArray st = c.optJSONArray("state");
        long created = 0; try { created = Long.parseLong(c.optString("created", "0")); } catch (Exception ignored) {}
        return new Coin(c.optString("coinid", ""), c.optString("address", ""), tok, FillPlan.d(amt), c.optBoolean("spent", false), st != null && st.length() > 0, created);
    }

    public boolean sameToken(String tok) { return tokenid.toLowerCase(Locale.ROOT).equals(String.valueOf(tok).toLowerCase(Locale.ROOT)); }
    public boolean spendable() { return !spent && !hasState; }
}
