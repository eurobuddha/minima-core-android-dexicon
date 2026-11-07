// VENDORED VERBATIM from apks/pandapools 0.9.87 app/src/main/java/com/eurobuddha/pandapools/LpStore.java by scripts/vendor-pandapools.sh — do not edit here; re-vendor.
package com.eurobuddha.pandapools;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * A tiny local record of each pool's position, keyed by the pool's covenant address:
 *   - the OPENING reserves + block (for impermanent-loss-vs-hold and pool age), set on create/migrate;
 *   - a fee-baseline product K (for "fees earned"), reset on every liquidity change (create/migrate AND
 *     deposit) — because a deposit legitimately raises K without being a fee, so fees must be measured
 *     against the product at the last liquidity event, not the raw KMIN floor.
 * Purely local, best-effort: a missing snapshot just hides IL/age (fees then fall back to K vs KMIN).
 */
public final class LpStore {

    private static final String PREFS = "pandapools_lp";
    private static final MathContext MC = new MathContext(30, RoundingMode.DOWN);

    private LpStore() {}

    public static final class Snapshot {
        public final BigDecimal initM, initT, initPrice, feeBaseK;
        public final int block;
        Snapshot(BigDecimal m, BigDecimal t, BigDecimal feeBaseK, int block) {
            this.initM = m; this.initT = t; this.block = block;
            this.initPrice = (m != null && m.signum() > 0 && t != null) ? t.divide(m, MC) : BigDecimal.ZERO;
            this.feeBaseK = feeBaseK != null ? feeBaseK
                    : (m != null && t != null ? m.multiply(t) : BigDecimal.ZERO);
        }
    }

    /** Record a freshly opened (create) or re-opened (migrate) pool: opening reserves + fee baseline. */
    public static void record(Context c, String address, BigDecimal initM, BigDecimal initT, int block) {
        if (c == null || address == null || address.isEmpty() || initM == null || initT == null) return;
        put(c, address, initM, initT, initM.multiply(initT), block);
    }

    /** Reset only the fee baseline after a deposit (keeps the original opening reserves/price/age). */
    public static void updateFeeBase(Context c, String address, BigDecimal newM, BigDecimal newT) {
        Snapshot s = get(c, address);
        if (s == null || newM == null || newT == null) return;
        put(c, address, s.initM, s.initT, newM.multiply(newT), s.block);
    }

    public static void remove(Context c, String address) {
        if (c == null || address == null) return;
        prefs(c).edit().remove(key(address)).apply();
    }

    public static Snapshot get(Context c, String address) {
        if (c == null || address == null) return null;
        String s = prefs(c).getString(key(address), null);
        if (s == null) return null;
        try {
            JSONObject o = new JSONObject(s);
            BigDecimal fk = o.has("fk") ? new BigDecimal(o.getString("fk")) : null;
            return new Snapshot(new BigDecimal(o.getString("m")), new BigDecimal(o.getString("t")), fk, o.optInt("b", 0));
        } catch (Exception e) { return null; }
    }

    private static void put(Context c, String address, BigDecimal m, BigDecimal t, BigDecimal fk, int block) {
        try {
            JSONObject o = new JSONObject();
            o.put("m", m.toPlainString());
            o.put("t", t.toPlainString());
            o.put("fk", fk.toPlainString());
            o.put("b", block);
            prefs(c).edit().putString(key(address), o.toString()).apply();
        } catch (Exception ignore) {}
    }

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }
    private static String key(String address) { return address.toLowerCase(); }
}
