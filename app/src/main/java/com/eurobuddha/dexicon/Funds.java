package com.eurobuddha.dexicon;

import com.eurobuddha.dexicon.fill.FillPlan;
import com.eurobuddha.dexicon.maker.MyOrder;

import org.json.JSONObject;

import java.math.BigDecimal;
import java.util.List;

/**
 * One calculation of "what do I have" for every screen (TRADE funds panel, order preview, ASSETS), so they can never
 * disagree. Available = the node's sendable balance; in orders = what my live FAST orders commit; settling = what my
 * unconfirmed fills will bring in; total = available + in orders + settling. FAST orders do not lock coins on chain —
 * a sendable coin can back an order — so "in orders" is shown alongside available, not subtracted from it.
 */
public final class Funds {
    public final BigDecimal minimaAvailable, mxusdAvailable;
    public final BigDecimal minimaInOrders, mxusdInOrders;
    public final BigDecimal minimaSettling, mxusdSettling;

    Funds(BigDecimal ma, BigDecimal ua, BigDecimal mo, BigDecimal uo, BigDecimal ms, BigDecimal us) {
        minimaAvailable = nz(ma); mxusdAvailable = nz(ua);
        minimaInOrders = nz(mo); mxusdInOrders = nz(uo);
        minimaSettling = nz(ms); mxusdSettling = nz(us);
    }

    public BigDecimal minimaTotal() { return minimaAvailable.add(minimaSettling); }
    public BigDecimal mxusdTotal() { return mxusdAvailable.add(mxusdSettling); }

    /** Portfolio value in MxUSD at a price (null if no price). */
    public BigDecimal valueAt(BigDecimal price) {
        if (price == null || price.signum() <= 0) return null;
        return mxusdTotal().add(minimaTotal().multiply(price));
    }

    /** Free to commit to a NEW order on a side: available minus what live orders on that side already commit. */
    public BigDecimal freeFor(boolean buy) {
        BigDecimal f = buy ? mxusdAvailable.subtract(mxusdInOrders) : minimaAvailable.subtract(minimaInOrders);
        return f.signum() < 0 ? BigDecimal.ZERO : f;
    }

    static Funds of(BigDecimal minimaSendable, BigDecimal mxusdSendable, List<MyOrder> orders, List<JSONObject> fills) {
        BigDecimal mo = BigDecimal.ZERO, uo = BigDecimal.ZERO, ms = BigDecimal.ZERO, us = BigDecimal.ZERO;
        for (MyOrder o : orders) {
            if (!o.live()) continue;
            if ("sell".equals(o.side)) mo = mo.add(Util.dec(o.remaining));
            else uo = uo.add(FillPlan.up8(Util.dec(o.remaining).multiply(Util.dec(o.price), FillPlan.MC)));
        }
        for (JSONObject f : fills) {
            if (!"settling".equals(f.optString("status"))) continue;
            boolean buy = "buy".equals(f.optString("side")), maker = "maker".equals(f.optString("role"));
            BigDecimal m = Util.dec(f.optString("filled", "0"));
            BigDecimal u = maker ? Util.dec(f.optString("received", "0")) : Util.dec(f.optString("got", "0"));
            if (buy) ms = ms.add(m); else us = us.add(u);
        }
        return new Funds(minimaSendable, mxusdSendable, mo, uo, ms, us);
    }

    static Funds of(MainActivity act) {
        return of(act.minimaSendable(), act.mxusdSendable(), act.myOrders(), act.db().fills(500));
    }

    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }
}
