package com.eurobuddha.dexicon.maker;

import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * The fast market maker's ladder — AtomiX's market editor model (apks/atomix MainActivity.editOrderDialog +
 * PriceOracle.applyPeg) published as Dexicon FAST orders, one order per rung:
 *   ASKS A1..A6 (you SELL MINIMA, above mid), BIDS B1..B6 (you BUY MINIMA, below mid);
 *   pegged: rungs regenerate around the MEXC mid × (1 + skew %), spaced step % (× the staleness widening);
 *   manual: the rungs are exactly the rows you typed (mid / step / levels / sizes only auto-fill them).
 * Sizes are MINIMA per rung; a side with size 0 (or no rows) is not quoted. Asks round UP, bids DOWN, to the
 * 8 decimals an order carries — always in the maker's favour, never a rounded display.
 */
public final class Ladder {
    private Ladder() {}

    public static final int MAX_LEVELS = 6;   // AtomiX Order.MAX_LEVELS
    static final MathContext MC = new MathContext(34);

    public static final class Rung {
        public final String key, side; public final BigDecimal price, amount;
        Rung(String key, String side, BigDecimal price, BigDecimal amount) { this.key = key; this.side = side; this.price = price; this.amount = amount; }
        public String toString() { return key + " " + side + " " + amount.toPlainString() + " @ " + price.toPlainString(); }
    }

    /** Saved settings (DexDb meta "mm_config"). Number fields are kept as the strings typed. */
    public static final class Config {
        public boolean enabled, peg;
        public String mid = "", step = "", levels = "1", askSize = "", bidSize = "", skew = "", reprice = "1";
        public final String[][] asks = new String[MAX_LEVELS][], bids = new String[MAX_LEVELS][];
        public Config() { for (int i = 0; i < MAX_LEVELS; i++) { asks[i] = new String[]{"", ""}; bids[i] = new String[]{"", ""}; } }

        public JSONObject toJson() {
            try {
                JSONArray a = new JSONArray(), b = new JSONArray();
                for (int i = 0; i < MAX_LEVELS; i++) { a.put(new JSONArray().put(asks[i][0]).put(asks[i][1])); b.put(new JSONArray().put(bids[i][0]).put(bids[i][1])); }
                return new JSONObject().put("enabled", enabled).put("peg", peg).put("mid", mid).put("step", step).put("levels", levels)
                        .put("askSize", askSize).put("bidSize", bidSize).put("skew", skew).put("reprice", reprice).put("asks", a).put("bids", b);
            } catch (Exception e) { throw new RuntimeException(e); }
        }
        public static Config fromJson(String s) {
            Config c = new Config();
            try {
                JSONObject j = new JSONObject(s == null || s.isEmpty() ? "{}" : s);
                c.enabled = j.optBoolean("enabled"); c.peg = j.optBoolean("peg");
                c.mid = j.optString("mid", ""); c.step = j.optString("step", ""); c.levels = j.optString("levels", "1");
                c.askSize = j.optString("askSize", ""); c.bidSize = j.optString("bidSize", ""); c.skew = j.optString("skew", ""); c.reprice = j.optString("reprice", "1");
                JSONArray a = j.optJSONArray("asks"), b = j.optJSONArray("bids");
                for (int i = 0; i < MAX_LEVELS; i++) {
                    JSONArray ra = a == null ? null : a.optJSONArray(i), rb = b == null ? null : b.optJSONArray(i);
                    if (ra != null) c.asks[i] = new String[]{ra.optString(0, ""), ra.optString(1, "")};
                    if (rb != null) c.bids[i] = new String[]{rb.optString(0, ""), rb.optString(1, "")};
                }
            } catch (Exception ignored) {}
            return c;
        }
        public int levelCount() { int n = (int) num(levels, 1).doubleValue(); return Math.max(1, Math.min(MAX_LEVELS, n)); }
    }

    /** A non-negative decimal, or {@code def} when blank; null when malformed or negative (signed allows a minus). */
    public static BigDecimal parse(String s, boolean signed) {
        if (s == null || s.trim().isEmpty()) return BigDecimal.ZERO;
        String v = s.trim();
        if (!v.matches((signed ? "-?" : "") + "(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)")) return null;
        try { return new BigDecimal(v); } catch (Exception e) { return null; }
    }
    static BigDecimal num(String s, double def) { BigDecimal b = parse(s, false); return b == null || (s == null || s.trim().isEmpty()) ? BigDecimal.valueOf(def) : b; }

    /** The rungs AtomiX's auto-fill builds around {@code mid}: step % spacing, {@code levels} per side. */
    public static List<Rung> generate(BigDecimal mid, BigDecimal stepPct, int levels, BigDecimal askSize, BigDecimal bidSize) {
        List<Rung> out = new ArrayList<>();
        if (mid == null || mid.signum() <= 0 || stepPct == null || stepPct.signum() <= 0) return out;
        BigDecimal ask8 = askSize == null ? BigDecimal.ZERO : askSize.setScale(8, RoundingMode.DOWN);
        BigDecimal bid8 = bidSize == null ? BigDecimal.ZERO : bidSize.setScale(8, RoundingMode.DOWN);
        for (int i = 1; i <= Math.max(1, Math.min(MAX_LEVELS, levels)); i++) {
            BigDecimal off = stepPct.multiply(BigDecimal.valueOf(i), MC).divide(BigDecimal.valueOf(100), MC);
            if (ask8.signum() > 0) {
                BigDecimal p = mid.multiply(BigDecimal.ONE.add(off), MC).setScale(8, RoundingMode.UP);
                out.add(new Rung("A" + i, "sell", p, ask8));
            }
            if (bid8.signum() > 0) {
                BigDecimal p = mid.multiply(BigDecimal.ONE.subtract(off), MC).setScale(8, RoundingMode.DOWN);
                if (p.signum() > 0) out.add(new Rung("B" + i, "buy", p, bid8));   // a step past the mid drops the bid (AtomiX Order.sanitize)
            }
        }
        return out;
    }

    /** The rungs to have live now. Pegged: around {@code feedMid} with the spread × {@code widen}; manual: the rows. */
    public static List<Rung> desired(Config c, double feedMid, double widen) {
        if (!c.enabled) return new ArrayList<>();
        if (c.peg) {
            BigDecimal step = parse(c.step, false), ask = parse(c.askSize, false), bid = parse(c.bidSize, false), skew = parse(c.skew, true);
            if (step == null || ask == null || bid == null || skew == null || !(feedMid > 0)) return new ArrayList<>();
            skew = skew.max(BigDecimal.valueOf(-20)).min(BigDecimal.valueOf(20));
            BigDecimal mid = BigDecimal.valueOf(feedMid).multiply(BigDecimal.ONE.add(skew.divide(BigDecimal.valueOf(100), MC)), MC);
            return generate(mid, step.multiply(BigDecimal.valueOf(Math.max(1.0, widen)), MC), c.levelCount(), ask, bid);
        }
        List<Rung> out = new ArrayList<>();
        for (int i = 0; i < MAX_LEVELS; i++) {
            addRow(out, "A" + (i + 1), "sell", c.asks[i], RoundingMode.UP);
            addRow(out, "B" + (i + 1), "buy", c.bids[i], RoundingMode.DOWN);
        }
        return out;
    }
    private static void addRow(List<Rung> out, String key, String side, String[] row, RoundingMode rm) {
        BigDecimal p = parse(row[0], false), a = parse(row[1], false);
        if (p == null || a == null || p.signum() <= 0 || a.signum() <= 0) return;
        out.add(new Rung(key, side, p.setScale(8, rm).stripTrailingZeros(), a.setScale(8, RoundingMode.DOWN)));
    }

    /** Does the live order need an edit? Pegged: price drifted ≥ the reprice % (min 0.1 %); manual: any price change.
     *  Either way the size is topped back up after a fill (AtomiX levels are standing per-take caps). */
    public static boolean needsEdit(Rung want, BigDecimal livePrice, BigDecimal liveRemaining, boolean peg, String reprice) {
        if (liveRemaining.compareTo(want.amount) != 0) return true;
        if (!peg) return livePrice.compareTo(want.price) != 0;
        BigDecimal th = parse(reprice, false);
        th = th == null || th.compareTo(new BigDecimal("0.1")) < 0 ? new BigDecimal("0.1") : th;
        BigDecimal drift = livePrice.subtract(want.price).abs().multiply(BigDecimal.valueOf(100)).divide(want.price, MC);
        return drift.compareTo(th) >= 0;
    }

    /** What the ladder commits: [MINIMA on asks, MxUSD on bids (rounded up)]. */
    public static BigDecimal[] commits(List<Rung> rungs) {
        BigDecimal m = BigDecimal.ZERO, u = BigDecimal.ZERO;
        for (Rung r : rungs) if ("sell".equals(r.side)) m = m.add(r.amount); else u = u.add(r.amount.multiply(r.price, MC).setScale(8, RoundingMode.UP));
        return new BigDecimal[]{m, u};
    }

    /** Settings check before saving: every number well-formed, and something to quote when enabled. Null = OK. */
    public static String validate(Config c) {
        String[] f = {c.mid, c.step, c.levels, c.askSize, c.bidSize, c.reprice};
        for (String s : f) if (parse(s, false) == null) return "A field holds something that is not a number: " + s;
        if (parse(c.skew, true) == null) return "Skew must be a number (it may be negative)";
        for (int i = 0; i < MAX_LEVELS; i++) for (String[] row : new String[][]{c.asks[i], c.bids[i]})
            if (parse(row[0], false) == null || parse(row[1], false) == null) return "Rung " + (i + 1) + " has a malformed price or amount";
        if (!c.enabled) return null;
        if (c.peg) {
            if (parse(c.step, false).signum() <= 0) return "Pegged needs a step % above 0";
            if (parse(c.askSize, false).signum() <= 0 && parse(c.bidSize, false).signum() <= 0) return "Pegged needs an ask size or a bid size";
            return null;
        }
        return desired(c, 0, 1).isEmpty() ? "No rung has both a price and an amount" : null;
    }
}
