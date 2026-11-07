// VENDORED VERBATIM from apks/pandapools 0.9.90 app/src/main/java/com/eurobuddha/pandapools/PoolStatement.java by scripts/vendor-pandapools.sh — do not edit here; re-vendor.
package com.eurobuddha.pandapools;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * A per-pool statement: what you put in, your trades, what is in the pool now, and the profit.
 *
 * One CSV, one block per pool you own. Nothing about the rest of the wallet — the node wallet also serves
 * the other Minima apps on this device, so a wallet-wide view is noise.
 *
 * Pure logic: no Android, no file I/O, no node call, so it is unit-testable and an export is deterministic.
 *
 * <h3>Nothing here is estimated</h3>
 * Every figure traces to either the stored on-chain history or the live pool scan. There is no fee
 * approximation, no "value ≈ 2 × reserveM" shortcut, and no assumed price for a transaction that doesn't
 * carry one. Where a value cannot be obtained the file says so rather than printing something that merely
 * looks derived.
 *
 * <h3>Two profit figures, because they answer different questions</h3>
 * <ul>
 *   <li><b>Pool profit (vs holding)</b> — the pool's value today against what the same coins would be worth
 *       had you simply held them. MINIMA's own price move cancels out, leaving fees minus impermanent loss.
 *       This is what providing liquidity actually earned.</li>
 *   <li><b>Change in market value</b> — the pool's value today against what it cost when you put it in.
 *       Includes MINIMA's own price movement, which you would have been exposed to either way.</li>
 * </ul>
 *
 * <h3>Scope of the trade rows</h3>
 * YOUR transactions only. The node's relevant-history does not reliably retain other people's trades
 * against a pool, and a silently-incomplete accounting file is the worst possible failure. The file says so
 * in its own header. The profit figures are unaffected: reserves are read LIVE, so a stranger's trade is
 * already reflected in what is in the pool now, without needing its transaction.
 */
public final class PoolStatement {

    private static final MathContext MC = new MathContext(30, RoundingMode.HALF_UP);
    /** Money is reported to 6dp; a derived price to 12dp. Amounts as they moved on-chain are never rounded. */
    private static final int MONEY_DP = 6;
    private static final int PRICE_DP = 12;
    private static final String EOL = "\r\n";
    private static final String BOM = "\uFEFF";     // Excel needs this to read UTF-8

    private PoolStatement() {}

    /** One pool the statement covers: its live reserves, or {@code null} reserves when unknown. */
    public static final class PoolInfo {
        public final String address, tokenSymbol;
        public final BigDecimal reserveM, reserveT;   // null when the pool wasn't in the latest scan
        public final boolean closed;                  // spent out — reserves are exactly zero, not unknown
        public final String migratedTo;               // non-null if this position moved to a new address
        public PoolInfo(String address, String tokenSymbol, BigDecimal reserveM, BigDecimal reserveT,
                        boolean closed, String migratedTo) {
            this.address = address; this.tokenSymbol = tokenSymbol;
            this.reserveM = reserveM; this.reserveT = reserveT;
            this.closed = closed; this.migratedTo = migratedTo;
        }
    }

    public static final class Params {
        public List<HistoryEntry> rows = new ArrayList<>();   // chronological, oldest first
        public TxClassifier.Addrs addrs;
        public List<PoolInfo> pools = new ArrayList<>();      // the pools you own
        public String appVersion = "?";
        public long exportedAtMs;
        public boolean backfillDone;
    }

    public static final class Report {
        public String csv;
        public int poolCount, tradeRows, unreconciled;
    }

    // ---- build ----

    public static Report build(Params p) {
        Report r = new Report();
        StringBuilder b = new StringBuilder(BOM);

        b.append(cells(text("PandaPools statement"), text(date(p.exportedAtMs)), text("v" + p.appVersion)));
        b.append(cells(text("Trades listed are YOUR transactions only — not every trade against the pool.")));
        if (!p.backfillDone) {
            b.append(cells(text("History is still syncing — older transactions of yours may be missing.")));
        }
        b.append(EOL);

        for (PoolInfo pool : p.pools) {
            block(b, pool, p, r);
            b.append(EOL);
        }
        if (p.pools.isEmpty()) b.append(cells(text("You don't own any pools.")));

        r.csv = b.toString();
        r.poolCount = p.pools.size();
        return r;
    }

    private static void block(StringBuilder b, PoolInfo pool, Params p, Report r) {
        // ---- gather this pool's transactions, each split to ITS share of a possibly-routed trade ----
        List<Row> rows = new ArrayList<>();
        BigDecimal createM = BigDecimal.ZERO, createT = BigDecimal.ZERO;
        BigDecimal addM = BigDecimal.ZERO, addT = BigDecimal.ZERO;
        BigDecimal wdM = BigDecimal.ZERO, wdT = BigDecimal.ZERO;
        BigDecimal tradeM = BigDecimal.ZERO, tradeT = BigDecimal.ZERO;
        BigDecimal maint = BigDecimal.ZERO;
        int maintCount = 0;
        BigDecimal openingValue = BigDecimal.ZERO;
        long openedMs = 0;

        for (HistoryEntry e : p.rows) {
            TxClassifier.Tx t = TxClassifier.classify(e, p.addrs);
            if (!t.isPandaPools() || !t.movedOurFunds()) continue;      // not ours → not our statement

            Map<String, BigDecimal[]> flows = TxClassifier.poolFlows(e, p.addrs);
            BigDecimal[] mine = null;
            for (Map.Entry<String, BigDecimal[]> en : flows.entrySet())
                if (en.getKey().equalsIgnoreCase(pool.address)) mine = en.getValue();
            if (mine == null) continue;                                 // this trade didn't touch this pool

            boolean ties = TxClassifier.splitReconciles(t, flows);
            if (!ties) r.unreconciled++;

            BigDecimal fM = mine[0], fT = mine[1];
            if (openedMs == 0) openedMs = e.timemilli;

            switch (t.type) {
                case TxClassifier.POOL_CREATE:   createM = createM.add(fM); createT = createT.add(fT); break;
                case TxClassifier.POOL_DEPOSIT:  addM = addM.add(fM);       addT = addT.add(fT);       break;
                case TxClassifier.POOL_WITHDRAW:
                case TxClassifier.POOL_MIGRATE:  wdM = wdM.add(fM);         wdT = wdT.add(fT);         break;
                case TxClassifier.POOL_KEEPALIVE: maint = maint.add(fM);    maintCount++;              break;
                default:                         tradeM = tradeM.add(fM);   tradeT = tradeT.add(fT);   break;
            }

            // What this leg cost at the price the transaction ITSELF implies. A create costs the value
            // deposited; a swap contributes zero, because equal value was exchanged at the pool price of
            // that moment. Maintenance has one leg only, so no price exists — excluded rather than guessed.
            if (TxClassifier.POOL_KEEPALIVE.equals(t.type)) continue;   // counted above; no effect to list

            BigDecimal price = legPrice(fM, fT);
            if (price != null && ties) {
                openingValue = openingValue.subtract(fM.multiply(price, MC).add(fT));
            }
            rows.add(new Row(e.timemilli, t.type, fM, fT, price, flows.size(), ties, e.txpowid));
        }
        r.tradeRows += rows.size();

        // ---- summary ----
        BigDecimal netPutInM = createM.add(addM).add(wdM).add(tradeM).add(maint).negate();
        BigDecimal netPutInT = createT.add(addT).add(wdT).add(tradeT).negate();

        b.append(cells(text("POOL"), text("MINIMA / " + pool.tokenSymbol), text(pool.address)));
        if (openedMs > 0) b.append(cells(text("Opened"), text(dateOnly(openedMs))));
        if (pool.migratedTo != null) b.append(cells(text("Migrated to"), text(pool.migratedTo)));
        b.append(EOL);

        String sym = pool.tokenSymbol;
        b.append(amountRow("Created with", createM.negate(), createT.negate(), sym));
        b.append(amountRow("Added later", addM.negate(), addT.negate(), sym));
        b.append(amountRow("Withdrawn", wdM, wdT, sym));
        b.append(amountRow("Your trades (net)", tradeM, tradeT, sym));
        // Keep-fresh recreates the reserves untouched and pays its beacon dust to the shared registry, not
        // to this pool — so it moves nothing here. Reported as a count for completeness, with no amount,
        // because attributing the dust to this pool would be wrong.
        if (maintCount > 0)
            b.append(cells(text("Maintenance"), ours(maintCount + " txns"),
                    text("keep-alive — pays the registry, not this pool")));
        b.append(amountRow("Net put in", netPutInM, netPutInT, sym));
        b.append(EOL);

        if (pool.reserveM == null || pool.reserveT == null) {
            b.append(cells(text("Now in pool"), text("reserves unavailable — pool not in the latest scan")));
            b.append(cells(text("No profit figures: they need the pool's current reserves.")));
        } else {
            BigDecimal nowM = pool.reserveM, nowT = pool.reserveT;
            b.append(amountRow("Now in pool", nowM, nowT, sym));
            BigDecimal price = (nowM.signum() > 0) ? nowT.divide(nowM, MC) : null;
            if (price == null) {
                b.append(cells(text("Pool price"), text(pool.closed ? "pool is closed — no price" : "unavailable")));
            } else {
                b.append(cells(text("Pool price"), price(price), text(sym + " per MINIMA")));
            }
            b.append(EOL);

            if (price != null) {
                BigDecimal today = nowM.multiply(price, MC).add(nowT);
                BigDecimal held = netPutInM.multiply(price, MC).add(netPutInT);
                b.append(cells(text("Value at opening price"), money(openingValue), text(sym)));
                b.append(cells(text("Value if you'd simply held those coins"), money(held), text(sym)));
                b.append(cells(text("Value of the pool today"), money(today), text(sym)));
                b.append(pnlRow("Pool profit (vs holding)", today.subtract(held), held, sym));
                b.append(pnlRow("Change in market value", today.subtract(openingValue), openingValue, sym));
            }
        }

        // ---- transactions ----
        if (!rows.isEmpty()) {
            b.append(EOL);
            b.append(cells(text("date"), text("type"), text("minima"), text(sym.toLowerCase(Locale.ENGLISH)),
                    text("price"), text("pools_in_trade"), text("txpowid")));
            for (Row row : rows) {
                b.append(cells(text(dateOnly(row.ms)), text(row.ties ? row.type : row.type + " (does not reconcile — excluded)"),
                        num(row.m), num(row.t), price(row.price), num(row.pools), text(row.txpowid)));
            }
        }
    }

    private static final class Row {
        final long ms; final String type, txpowid;
        final BigDecimal m, t, price;
        final int pools; final boolean ties;
        Row(long ms, String type, BigDecimal m, BigDecimal t, BigDecimal price, int pools, boolean ties, String txpowid) {
            this.ms = ms; this.type = type; this.m = m; this.t = t;
            this.price = price; this.pools = pools; this.ties = ties; this.txpowid = txpowid;
        }
    }

    /** Price implied by one pool's two legs of a transaction, or null when either leg is zero. */
    private static BigDecimal legPrice(BigDecimal m, BigDecimal t) {
        if (m == null || t == null || m.signum() == 0 || t.signum() == 0) return null;
        return t.abs().divide(m.abs(), MC);
    }

    // ---- rows ----

    private static String amountRow(String label, BigDecimal m, BigDecimal t, String sym) {
        return cells(text(label), num(m), text("MINIMA"), num(t), text(sym));
    }

    private static String pnlRow(String label, BigDecimal value, BigDecimal base, String sym) {
        String pct = "";
        if (base != null && base.signum() != 0) {
            BigDecimal p = value.multiply(new BigDecimal("100")).divide(base.abs(), MC)
                                .setScale(2, RoundingMode.HALF_UP);
            pct = (p.signum() > 0 ? "+" : "") + p.toPlainString() + "%";
        }
        // ours(), not text(): a percentage starts with + or -, and formula-defusing our OWN generated
        // string would render it as '+0.21% in the sheet.
        return cells(text(label), money(value), text(sym), ours(pct));
    }

    // ---- formatting ----

    private static String cells(String... c) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < c.length; i++) { if (i > 0) b.append(','); b.append(c[i]); }
        return b.append(EOL).toString();
    }

    /**
     * A quoted TEXT cell. Token names come from on-chain metadata and are attacker-controllable, so a
     * leading =, +, - or @ is prefixed with an apostrophe — without it a spreadsheet evaluates the cell as
     * a formula on open.
     */
    static String text(String s) {
        if (s == null) s = "";
        if (!s.isEmpty()) {
            char c = s.charAt(0);
            if (c == '=' || c == '+' || c == '-' || c == '@') s = "'" + s;
        }
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    /** A quoted cell for a string WE generate (a percentage, a fixed label). Escaped but not formula-defused,
     *  because it can't carry attacker-controlled content and a leading +/- is meaningful here. */
    static String ours(String s) {
        if (s == null) s = "";
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    /** An on-chain amount, exactly as it moved. Never formula-escaped — a negative number must stay one. */
    static String num(BigDecimal d) {
        if (d == null) return "\"\"";
        BigDecimal s = d.stripTrailingZeros();
        return "\"" + (s.signum() == 0 ? "0" : s.toPlainString()) + "\"";
    }

    static String num(int v) { return "\"" + v + "\""; }

    /** A derived money figure, rounded so a division doesn't carry 30 meaningless digits into the sheet. */
    static String money(BigDecimal d) { return scaled(d, MONEY_DP); }

    /** A derived price. */
    static String price(BigDecimal d) { return scaled(d, PRICE_DP); }

    private static String scaled(BigDecimal d, int dp) {
        if (d == null) return "\"\"";
        BigDecimal r = d.setScale(dp, RoundingMode.HALF_UP).stripTrailingZeros();
        return "\"" + (r.signum() == 0 ? "0" : r.toPlainString()) + "\"";
    }

    private static String date(long ms) { return fmt(ms, "yyyy-MM-dd'T'HH:mm:ss'Z'"); }
    private static String dateOnly(long ms) { return fmt(ms, "yyyy-MM-dd"); }

    private static String fmt(long ms, String pattern) {
        if (ms <= 0) return "";
        SimpleDateFormat f = new SimpleDateFormat(pattern, Locale.ENGLISH);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(ms));
    }
}
