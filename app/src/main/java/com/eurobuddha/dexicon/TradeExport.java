package com.eurobuddha.dexicon;

import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * CSV export of my fills. COLUMN NAMES ARE STABLE (tools/dexHistory parses exports by name): append columns,
 * never rename or reorder. Modelled on PandaDEX TradeExport confirmed_trades.csv; the dollar columns are mxusd_*.
 */
public final class TradeExport {
    private TradeExport() {}

    public static final String HEADER = "timestamp_utc,block,side,role,minima_delta,mxusd_delta,minima_amount,price,mxusd_notional,"
            + "order_id,txpowid,proceeds_coinids,counterparty,status\n";

    public static String csv(List<JSONObject> fills) {
        StringBuilder sb = new StringBuilder(HEADER);
        for (JSONObject f : fills) sb.append(row(f));
        return sb.toString();
    }

    /** side = the side of MINIMA I took (buy → +MINIMA −MxUSD). role = maker|taker. */
    static String row(JSONObject f) {
        boolean buy = "buy".equals(f.optString("side"));
        BigDecimal minima = Util.dec(f.optString("filled", "0"));
        BigDecimal price = Util.dec(f.optString("price", "0"));
        boolean maker = "maker".equals(f.optString("role"));
        // The exact MxUSD leg: maker side knows received/paid; taker side knows gave/got.
        BigDecimal usd = maker ? Util.dec(buy ? f.optString("paid", "0") : f.optString("received", "0"))
                               : Util.dec(buy ? f.optString("gave", "0") : f.optString("got", "0"));
        if (usd.signum() == 0) usd = minima.multiply(price);
        BigDecimal md = buy ? minima : minima.negate(), ud = buy ? usd.negate() : usd;
        JSONArray coins = f.optJSONArray("coins");
        StringBuilder pc = new StringBuilder();
        if (coins != null) for (int i = 0; i < coins.length(); i++) { if (i > 0) pc.append(' '); pc.append(coins.optString(i)); }
        return csv(utc(f.optLong("ts", 0) * 1000)) + ',' + csv(f.optString("block", "")) + ',' + (buy ? "BUY" : "SELL") + ',' + (maker ? "MAKER" : "TAKER") + ','
                + csv(Util.fmt(md)) + ',' + csv(Util.fmt(ud)) + ',' + csv(Util.fmt(minima)) + ',' + csv(price.toPlainString()) + ',' + csv(Util.fmt(usd)) + ','
                + csv(f.optString("orderId", "")) + ',' + csv(f.optString("txpowid", "")) + ',' + csv(pc.toString()) + ',' + csv(f.optString("counterparty", "")) + ','
                + csv(f.optString("status", "")) + '\n';
    }

    static String utc(long ms) {
        SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        df.setTimeZone(TimeZone.getTimeZone("UTC"));
        return df.format(new Date(ms));
    }

    static String csv(String s) {
        if (s == null) return "";
        if (s.indexOf(',') < 0 && s.indexOf('"') < 0 && s.indexOf('\n') < 0) return s;
        return '"' + s.replace("\"", "\"\"") + '"';
    }
}
