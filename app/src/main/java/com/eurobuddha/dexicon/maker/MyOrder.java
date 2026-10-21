package com.eurobuddha.dexicon.maker;

import com.eurobuddha.dexicon.fill.FillPlan;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** One of MY resting orders (maker.js 'myorders' row). status: live | cancelled | filled. seq rises on every republish. */
public final class MyOrder {
    public String orderId, side, price, amount, remaining, address, status = "live";
    public List<String> coins = new ArrayList<>(), scripts = new ArrayList<>();
    public long seq, expiry, created;

    public boolean live() { return "live".equals(status); }

    public FillPlan.Order asFillOrder() { return new FillPlan.Order(side, price, remaining, address, coins, scripts); }

    public JSONObject toJson() {
        try {
            return new JSONObject().put("orderId", orderId).put("side", side).put("price", price).put("amount", amount).put("remaining", remaining)
                    .put("coins", new JSONArray(coins)).put("scripts", new JSONArray(scripts)).put("address", address).put("seq", seq)
                    .put("expiry", expiry).put("status", status).put("created", created);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    public static MyOrder fromJson(JSONObject j) {
        MyOrder o = new MyOrder();
        o.orderId = j.optString("orderId"); o.side = j.optString("side"); o.price = j.optString("price"); o.amount = j.optString("amount");
        o.remaining = j.optString("remaining", o.amount); o.address = j.optString("address"); o.status = j.optString("status", "live");
        o.seq = j.optLong("seq"); o.expiry = j.optLong("expiry"); o.created = j.optLong("created");
        o.coins = strings(j.optJSONArray("coins")); o.scripts = strings(j.optJSONArray("scripts"));
        return o;
    }

    static List<String> strings(JSONArray a) {
        List<String> out = new ArrayList<>();
        if (a != null) for (int i = 0; i < a.length(); i++) out.add(a.optString(i));
        return out;
    }
}
