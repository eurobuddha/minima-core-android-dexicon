package com.eurobuddha.dexicon;

import android.annotation.SuppressLint;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SwitchCompat;

import com.eurobuddha.atomix.MarketDraft;
import com.eurobuddha.atomix.TradingContext;
import com.eurobuddha.atomix.swap.Order;
import com.eurobuddha.atomix.swap.PriceOracle;
import com.eurobuddha.atomix.swap.SwapOrderBook;
import com.eurobuddha.dexicon.doors.DoorHost;
import com.eurobuddha.dexicon.doors.DoorPlan;
import com.eurobuddha.dexicon.ui.Ui;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * ① MARKET — AtomiX's market screen (apks/atomix MainActivity renderMarketTab, orderLadder/depthRow, editOrderDialog),
 * redrawn in Dexicon's Design: the active market's AtomiX order book, your offer, the ladder / peg editor and
 * Publish / Withdraw. Every action goes through the one engine ({@link DoorHost}); the background maker keeps a
 * published order live. Maker keys are shown in full (tap copies).
 */
@SuppressLint("ViewConstructor")
public final class MarketTab extends LinearLayout {
    private static final long REFRESH_MS = 60_000;
    private final MainActivity act;
    private final DoorHost host;
    private final LinearLayout body;
    private final TextView status, publish;
    private String orderStatus;
    private long lastRefresh;

    public MarketTab(MainActivity act) {
        super(act);
        this.act = act;
        this.host = DoorHost.get(act);
        setOrientation(VERTICAL);
        int pad = Design.dp(act, 12);
        setPadding(pad, pad, pad, pad);

        Ui.why(this, "YOUR AtomiX MARKET", "Here you are the maker: publish a ladder of prices at which you sell and buy the active coin "
                + "against ERC20 USDT. Takers lock their side first; this phone answers automatically, even with Dexicon closed, so keep "
                + "the node online. Peg the ladder to the live price and it reprices itself; if the price feed fails, it is withdrawn "
                + "for safety. Withdraw takes your offer off the book in about a block. One market at a time: the ⇄ pill switches it.");

        body = new LinearLayout(act);
        body.setOrientation(VERTICAL);
        addView(body);

        LinearLayout btns = new LinearLayout(act);
        TextView edit = button("Edit my order", false), withdraw = button("Withdraw", false);
        publish = button("Publish", true);
        edit.setOnClickListener(v -> editOrderDialog());
        publish.setOnClickListener(v -> host.publishOrder(this::say));
        withdraw.setOnClickListener(v -> host.withdrawOrder(this::say));
        btns.addView(edit, weight(0)); btns.addView(publish, weight(6)); btns.addView(withdraw, weight(6));
        LayoutParams bl = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT); bl.topMargin = Design.dp(act, 14);
        addView(btns, bl);

        status = Ui.text(act, "", 12.5f, Design.DIM(), Design.sans());
        status.setPadding(Design.dp(act, 2), Design.dp(act, 10), Design.dp(act, 2), 0);
        status.setTextIsSelectable(true);
        addView(status);
    }

    private void say(String s) { orderStatus = DoorHost.mx(s); act.repaint(); }

    private LayoutParams weight(int left) { LayoutParams lp = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f); lp.leftMargin = Design.dp(act, left); return lp; }

    private TextView button(String s, boolean cta) {
        TextView b = Ui.text(act, s, 12.5f, cta ? DoorsTab.ink(host.market()) : Design.TEXT(), Design.sansBold());
        b.setGravity(Gravity.CENTER); b.setMinHeight(Design.dp(act, 48));
        int p = Design.dp(act, 8); b.setPadding(p, p, p, p);
        b.setBackground(cta ? Design.roundBg(act, DoorsTab.acc(host.market()), 14) : Design.roundBg(act, Design.SURFACE2(), 14));
        Design.pressable(b);
        return b;
    }

    private String ccy() { return DoorPlan.coin(host.market()); }

    // ------------------------------------------------------------------ render

    public void render() {
        long now = System.currentTimeMillis();
        if (now - lastRefresh > REFRESH_MS) {
            lastRefresh = now;
            PriceOracle.refreshAsync();   // keep the reference price current while the page is watched (rate-limited)
            host.readBalances(null);
            host.scanBooks(act::repaint);
        }
        body.removeAllViews();
        TextView hint = Ui.text(act, "The live USDT ⇄ " + ccy() + " order book. Tap a level to take it, use the Swap page, or publish your own offer.", 12.5f, Design.DIM(), Design.sans());
        body.addView(hint);
        if (PriceOracle.mid() > 0) body.addView(Ui.text(act, DoorHost.mx(PriceOracle.describe()), 11.5f, Design.DIM(), Design.mono()), Ui.wide(act, 2, 2));
        if (host.gated()) body.addView(Ui.text(act, "One trading app per phone: while AtomiX or PandaPools is present, nothing is published or armed here.", 11.5f, Design.RED(), Design.sansBold()), Ui.wide(act, 6, 0));
        String blocked = AtomixConsolidate.warning(host.market(), host.market() == TradingContext.MINIMA ? act.minimaCoins : act.mxusdCoins);
        if (blocked != null) {   // AtomiX: a maker with too many coins declines every buy — say so where the market is run
            TextView w = Design.pill(act, blocked, Design.SURFACE2(), Design.RED());
            w.setOnClickListener(v -> AtomixConsolidate.dialog(act)); Design.pressable(w);
            body.addView(w, Ui.wide(act, 6, 0));
        }

        renderMyOffer();

        Map<String, Order> book = host.book(host.market());
        LinearLayout ob = new LinearLayout(act); ob.setGravity(Gravity.CENTER_VERTICAL);
        ob.addView(Ui.text(act, "Order book  ·  " + book.size() + " live", 16f, Design.TEXT(), Design.sansBold()), new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        TextView refresh = Design.pill(act, "Refresh", Design.SURFACE2(), Design.DIM());
        refresh.setMinHeight(Design.dp(act, 44));
        refresh.setOnClickListener(v -> { orderStatus = "Reading the book…"; host.scanBooks(() -> { orderStatus = null; act.repaint(); }); act.repaint(); });
        ob.addView(refresh);
        body.addView(ob, Ui.wide(act, 14, 2));
        if (book.isEmpty()) body.addView(Ui.text(act, act.paired() ? "No live orders yet. Publish one, or wait for a counterparty." : "Connect your node to see the order book.", 12.5f, Design.DIM(), Design.sans()), Ui.wide(act, 6, 0));
        else orderLadder(book);

        publish.setTextColor(DoorsTab.ink(host.market())); publish.setBackground(Design.roundBg(act, DoorsTab.acc(host.market()), 14));   // follows the market
        Ui.set(status, orderStatus == null ? "" : orderStatus);
        status.setTextColor(orderStatus != null && orderStatus.startsWith("✓") ? Design.IN() : Design.DIM());
    }

    /** Your offer at a glance: on/off, best levels, live or withdrawn. */
    private void renderMyOffer() {
        LinearLayout c = Ui.card(body);
        c.addView(Ui.label(act, "YOUR OFFER · USDT ⇄ " + ccy()));
        Order o = host.loadOrder();
        Order.Pair p = o.pairs.isEmpty() ? null : o.pairs.values().iterator().next();
        android.content.SharedPreferences pr = host.prefs();
        boolean live = pr.getBoolean("auto_publish", false), pegged = pr.getBoolean(PriceOracle.P_ENABLE, false), withdrawn = pr.getBoolean(PriceOracle.P_WITHDRAWN, false);
        String state = p == null || !p.enable ? "Off — edit your order, then Publish."
                : !live ? "Saved, not published."
                : pegged && withdrawn ? "Withdrawn — waiting for the " + PriceOracle.source() + " price feed."
                : "Live" + (pegged ? " · pegged to " + PriceOracle.source() : "") + " · kept published by the background maker.";
        c.addView(Ui.text(act, state, 12.5f, live && p != null && p.enable ? Design.IN() : Design.DIM(), Design.sans()), Ui.wide(act, 4, 2));
        if (p != null && p.enable) {
            String asks = p.asks.isEmpty() ? "none" : p.asks.size() + " lvl · best " + fmtPrice(p.asks.get(0).price);
            String bids = p.bids.isEmpty() ? "none" : p.bids.size() + " lvl · best " + fmtPrice(p.bids.get(0).price);
            c.addView(Ui.text(act, "ASKS  " + asks + "\nBIDS  " + bids + "\n(USDT per " + ccy() + ")", 11.5f, Design.DIM(), Design.mono()));
        }
        long ok = pr.getLong("last_publish_ok", 0);
        if (ok > 0) c.addView(Ui.text(act, "Last confirmed publish " + ((System.currentTimeMillis() - ok) / 60_000) + " min ago", 11f, Design.DIM2(), Design.sans()), Ui.wide(act, 4, 0));
    }

    // ------------------------------------------------------------------ the book (AtomiX orderLadder / depthRow)

    private List<Object[]> aggSide(Map<String, Order> book, boolean sell) {
        List<Object[]> agg = new ArrayList<>();
        for (Order o : book.values()) for (Order.Level l : (sell ? o.effectiveBids(DoorPlan.SYM) : o.effectiveAsks(DoorPlan.SYM))) agg.add(new Object[]{o, l});
        Collections.sort(agg, (a, b) -> SwapOrderBook.compareForFill((Order) a[0], (Order.Level) a[1], (Order) b[0], (Order.Level) b[1], sell));
        return agg;
    }

    private void orderLadder(Map<String, Order> book) {
        List<Object[]> bids = aggSide(book, true), asks = aggSide(book, false);
        if (bids.isEmpty() && asks.isEmpty()) return;
        double bestBid = bids.isEmpty() ? 0 : ((Order.Level) bids.get(0)[1]).price, bestAsk = asks.isEmpty() ? 0 : ((Order.Level) asks.get(0)[1]).price;
        if (bestBid > 0 && bestAsk > 0)
            body.addView(Ui.text(act, "spread " + fmtPrice(bestAsk - bestBid) + " USDT  ·  USDT per " + ccy() + ", size in " + ccy(), 11f, Design.DIM2(), Design.sans()), Ui.wide(act, 4, 4));
        LinearLayout legend = new LinearLayout(act);
        TextView l = Ui.text(act, "SELL " + ccy() + " (bid)", 11f, Design.IN(), Design.sansBold()), r = Ui.text(act, "BUY " + ccy() + " (ask)", 11f, Design.RED(), Design.sansBold());
        r.setGravity(Gravity.END);
        legend.addView(l, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)); legend.addView(r, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        body.addView(legend);
        int rows = Math.max(bids.size(), asks.size()), shown = Math.min(rows, 12);
        for (int i = 0; i < shown; i++) depthRow(i < bids.size() ? bids.get(i) : null, i < asks.size() ? asks.get(i) : null, i == 0);
        if (rows > shown) body.addView(Ui.text(act, "+ " + (rows - shown) + " more level" + (rows - shown == 1 ? "" : "s"), 11f, Design.DIM2(), Design.sans()), Ui.wide(act, 6, 0));
    }

    private void depthRow(Object[] bid, Object[] ask, boolean best) {
        LinearLayout box = new LinearLayout(act); box.setOrientation(VERTICAL);
        box.setBackground(best ? Design.stroked(act, Design.SURFACE2(), 12) : Design.card(act, 12));
        int p = Design.dp(act, 8); box.setPadding(Design.dp(act, 10), p, Design.dp(act, 10), p);
        LinearLayout row = new LinearLayout(act); row.setGravity(Gravity.TOP);
        row.addView(half(bid, true, best), new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        TextView div = Ui.text(act, "│", 14f, Design.DIM2(), Design.sans()); div.setPadding(Design.dp(act, 8), 0, Design.dp(act, 8), 0);
        row.addView(div);
        row.addView(half(ask, false, best), new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        box.addView(row);
        body.addView(box, Ui.wide(act, 6, 0));
    }

    private LinearLayout half(Object[] mr, boolean isBid, boolean best) {
        LinearLayout h = new LinearLayout(act); h.setOrientation(VERTICAL);
        int pc = isBid ? Design.IN() : Design.RED();
        if (mr == null) { h.addView(quote("—", "—", pc, false, isBid)); return h; }
        final Order maker = (Order) mr[0];
        final Order.Level lvl = (Order.Level) mr[1];
        // display clamp: never advertise more than the maker's live balance can back (the guard/chain still enforce)
        double cap = isBid ? (lvl.price > 0 ? Math.min(lvl.amount, maker.usdtAvail / lvl.price) : 0) : Math.min(lvl.amount, maker.minimaAvail);
        h.addView(isBid ? quote(abbrev(cap), fmtPrice(lvl.price), pc, best, true) : quote(fmtPrice(lvl.price), abbrev(cap), pc, best, false));
        String me = host.myPublicId(host.market());
        boolean mine = me != null && me.equals(maker.commsPublicId);
        // RULE 1: the maker's key in FULL (wrapped), tap copies the complete value.
        TextView tag = Ui.text(act, mine ? "you" : maker.signerPk == null ? "" : maker.signerPk, 9f, mine ? DoorsTab.acc(host.market()) : Design.DIM2(), Design.mono());
        tag.setGravity(isBid ? Gravity.START : Gravity.END);
        if (!mine && maker.signerPk != null && !maker.signerPk.isEmpty()) tag.setOnClickListener(v -> act.copy("Maker key", maker.signerPk));
        h.addView(tag);
        // AtomiX: tap a level to take it — a bid is where you SELL, an ask where you BUY (never your own order)
        if (!mine && cap > 0) {
            h.setOnClickListener(v -> takeDialog(maker, lvl, isBid, cap));
            h.setContentDescription((isBid ? "Sell " : "Buy ") + ccy() + " at " + fmtPrice(lvl.price) + " USDT");
            Design.pressable(h);
        }
        return h;
    }

    /** AtomiX takeOrderDialog: the level's side, price, cap and the maker's minimum, the full maker key, an amount in the
     *  coin with "You receive / pay ≈", then Start swap. Taken through the same engine path and checks as the Swap page,
     *  against that one maker, never worse than the tapped price (a better level of the same maker fills first). */
    private void takeDialog(Order maker, Order.Level lvl, boolean sell, double cap) {
        final TradingContext market = host.market();
        final String c = ccy(), sym = DoorPlan.SYM;
        final double price = lvl.price;
        if (!Double.isFinite(price) || price <= 0) return;
        Order.Pair pair = maker.pairs.get(sym);
        final double min = pair != null ? pair.min : 0;
        LinearLayout box = new LinearLayout(act); box.setOrientation(LinearLayout.VERTICAL);
        int pd = Design.dp(act, 20); box.setPadding(pd, Design.dp(act, 12), pd, Design.dp(act, 4));
        TextView info = Ui.text(act, (sell ? "Sell " + c + " → " + sym : "Buy " + c + " ← " + sym) + "\nPrice: " + fmtPrice(price) + " " + sym + " per " + c
                + "\nThis level: up to " + abbrev(cap) + " " + c + (min > 0 ? "\nMin: " + fmtPrice(min) + " " + c : ""), 13f, Design.DIM(), Design.sans());
        info.setPadding(0, 0, 0, Design.dp(act, 10));
        box.addView(info);
        final EditText amt = new EditText(act);
        amt.setHint(c + " to " + (sell ? "sell" : "buy"));
        decimalInput(amt, false);
        amt.setTextColor(Design.TEXT()); amt.setHintTextColor(Design.DIM2()); amt.setTextSize(16f); amt.setTypeface(Design.mono());
        box.addView(amt);
        final TextView out = Ui.text(act, "", 14f, DoorsTab.acc(market), Design.sans());
        out.setPadding(0, Design.dp(act, 10), 0, 0);
        box.addView(out);
        amt.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence x, int a, int b, int d) {}
            public void onTextChanged(CharSequence x, int a, int b, int d) {}
            public void afterTextChanged(android.text.Editable e) {
                String u = DoorPlan.computeUsdt(amt.getText().toString(), price);
                out.setTextColor(DoorsTab.acc(market));
                out.setText(u == null ? "" : (sell ? "You receive ≈ " : "You pay ≈ ") + u + " " + sym);
            }
        });
        if (maker.signerPk != null && !maker.signerPk.isEmpty()) {   // RULE 1: who you lock with, in full; tap copies
            TextView cp = Ui.text(act, "Counterparty\n" + maker.signerPk, 11f, Design.DIM(), Design.mono());
            cp.setPadding(0, Design.dp(act, 8), 0, 0);
            cp.setOnClickListener(v -> act.copy("Counterparty key", maker.signerPk));
            box.addView(cp);
        }
        final AlertDialog d = act.track(new AlertDialog.Builder(act, Design.dialogTheme()).setTitle(sell ? "Sell " + c : "Buy " + c).setView(box)
                .setPositiveButton("Start swap", null).setNegativeButton("Cancel", null).show());
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String raw = amt.getText().toString().trim();
            if (!DoorPlan.positiveSwapAmount(raw)) { bad(out, "Enter a valid " + c + " amount"); return; }
            double m = DoorPlan.parseD(raw, 0);
            if (m > cap + 1e-9) { bad(out, "This level takes up to " + abbrev(cap) + " " + c + " — more will be auto-declined"); return; }
            if (min > 0 && m < min) { bad(out, "Below the maker's minimum (" + fmtPrice(min) + " " + c + ")"); return; }
            if (market != host.market()) { bad(out, "The market was switched — tap the level again."); return; }
            Map<String, Order> one = new java.util.HashMap<>();
            for (Map.Entry<String, Order> e : host.book(market).entrySet()) if (e.getValue() == maker) one.put(e.getKey(), maker);
            if (one.isEmpty()) { bad(out, "That offer just left the book — refresh and try again."); return; }
            DoorPlan.Best q = DoorPlan.bestMakers(one, host.myPublicId(market));
            double slip = sell ? 0 : Math.max(0, price / q.bestAsk - 1);   // a buy fills at or below the tapped price
            DoorPlan.Plan plan = DoorPlan.buildSweepPlan(one, sell, raw, slip, host.myPublicId(market));
            if (plan.legs.isEmpty()) { bad(out, "below-min".equals(plan.stopReason) ? "That's below the maker's minimum trade size." : "That level can't fill this right now."); return; }
            // AtomiX takes the tapped level: never a part priced worse than the tap (a sell could add a dust part at a lower
            // bid), never a partial fill passed off as the whole
            for (DoorPlan.Leg leg : plan.legs)
                if (sell ? leg.price < price - 1e-12 : leg.price > price + 1e-12) { bad(out, "That maker can't fill " + raw + " " + c + " at " + fmtPrice(price) + " right now — try a smaller amount."); return; }
            if (plan.partial) { bad(out, "That level can fill only " + plan.filledMinima + " " + c + " right now — enter that or less."); return; }
            String err = DoorsTab.fundsCheck(host, plan, "", sell ? 0 : slip);
            if (!err.isEmpty()) { bad(out, err); return; }
            err = host.take(market, plan, "", new DoorHost.Progress() {
                @Override public void stage(String st) { act.setStage(st); }
                @Override public void done(String msg, boolean error) { act.setStage(""); act.toast(msg); if (error) Notifier.alert(act, "AtomiX swap stopped", msg); act.repaint(); }
            });
            if (err != null) { bad(out, err); return; }
            d.dismiss();
            act.ensureKeepAlive();   // a swap is now open: its claim / refund needs the background service (weekly ask at most)
            act.toast("Swap started — follow it on Swap or Activity");
            act.repaint();
        });
    }
    private static void bad(TextView out, String s) { out.setTextColor(Design.RED()); out.setText(s); }

    private LinearLayout quote(String a, String b, int priceColor, boolean best, boolean left) {
        LinearLayout h = new LinearLayout(act);
        h.setGravity((left ? Gravity.END : Gravity.START) | Gravity.CENTER_VERTICAL);
        TextView size = Ui.text(act, left ? a : b, 12f, Design.DIM(), Design.mono());
        TextView price = Ui.text(act, left ? b : a, 15f, priceColor, best ? Design.monoBold() : Design.mono());
        if (left) { size.setPadding(0, 0, Design.dp(act, 8), 0); h.addView(size); h.addView(price); }
        else { price.setPadding(0, 0, Design.dp(act, 8), 0); h.addView(price); h.addView(size); }
        return h;
    }

    static String abbrev(double v) {
        if (v <= 0) return "—";
        if (v >= 1_000_000) return trimNum(v / 1_000_000) + "M";
        if (v >= 1_000) return trimNum(v / 1_000) + "k";
        return trimNum(v);
    }
    private static String trimNum(double v) { return BigDecimal.valueOf(v).setScale(v < 10 ? 2 : 1, RoundingMode.DOWN).stripTrailingZeros().toPlainString(); }
    static String fmtPrice(double v) { return BigDecimal.valueOf(v).setScale(10, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString(); }
    static String trimSig(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return "";
        return new BigDecimal(v, new java.math.MathContext(10)).stripTrailingZeros().toPlainString();   // AtomiX trimSig (tidyAmount is a no-op after stripTrailingZeros)
    }
    static double parseD(String s, double def) { try { return Double.parseDouble(s.trim()); } catch (Exception e) { return def; } }

    // ------------------------------------------------------------------ the editor (AtomiX editOrderDialog)

    /** Samsung-safe decimal entry (AtomiX decimalInput): immediate commit, one plain decimal number. */
    static void decimalInput(EditText e, boolean signed) {
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        e.setTransformationMethod(null);
        final String re = (signed ? "-?" : "") + "[0-9]*\\.?[0-9]*";
        e.setFilters(new InputFilter[]{ (source, start, end, dest, dstart, dend) -> {
            String r = dest.toString().substring(0, dstart) + source.subSequence(start, end) + dest.toString().substring(dend);
            return (r.isEmpty() || r.matches(re)) ? null : "";
        }});
    }

    private EditText field(String hint, boolean signed) {
        EditText e = new EditText(act); decimalInput(e, signed);
        e.setHint(hint); e.setHintTextColor(Design.DIM2()); e.setTextColor(Design.TEXT()); e.setTextSize(14f); e.setTypeface(Design.mono()); e.setGravity(Gravity.CENTER);
        return e;
    }

    private EditText[] levelRow(LinearLayout parent, String label, double price, double amount, int color) {
        LinearLayout r = new LinearLayout(act); r.setGravity(Gravity.CENTER_VERTICAL);
        EditText pe = field("price", false), ae = field(ccy(), false);
        pe.setText(price > 0 ? trimSig(price) : ""); ae.setText(amount > 0 ? trimSig(amount) : "");
        pe.setGravity(Gravity.END); ae.setGravity(Gravity.END);
        TextView clr = Ui.text(act, "✕", 15f, Design.DIM2(), Design.sans());
        clr.setGravity(Gravity.CENTER); clr.setMinWidth(Design.dp(act, 44)); clr.setMinHeight(Design.dp(act, 44));
        clr.setOnClickListener(v -> { pe.setText(""); ae.setText(""); });
        r.addView(Ui.text(act, label, 12.5f, color, Design.mono()), new LayoutParams(Design.dp(act, 26), LayoutParams.WRAP_CONTENT));
        r.addView(pe, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        r.addView(ae, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        r.addView(clr);
        parent.addView(r);
        return new EditText[]{pe, ae};
    }

    private static TextWatcher onChange(Runnable r) {
        return new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(android.text.Editable e) { r.run(); }
        };
    }

    void editOrderDialog() {
        if (host.switching()) { act.toast(DoorHost.SWITCHING); return; }
        final TradingContext mkt = host.market();   // the market this editor prices; Save refuses any other
        final android.content.SharedPreferences prefs = host.prefs();
        final Order o = host.loadOrder();
        final Order.Pair p = o.pairs.values().iterator().next();   // single pair (USDT) today
        final String c = ccy();
        LinearLayout box = new LinearLayout(act); box.setOrientation(VERTICAL);
        int bp = Design.dp(act, 20); box.setPadding(bp, Design.dp(act, 8), bp, Design.dp(act, 8));
        TextView hint = Ui.para(act, "Your depth ladder for " + c + " / USDT  (price = USDT per " + c + "):\n"
                + "•  ASKS — where YOU SELL " + c + " (higher).   BIDS — where YOU BUY (lower).\n"
                + "•  Each level's amount is a per-take cap. Leave rows blank to skip them.\n"
                + "•  Tip: make your best level your largest — old-app takers only see your best price.");
        box.addView(hint, Ui.wide(act, 0, 10));

        final SwitchCompat sw = new SwitchCompat(act); sw.setText("Enabled"); sw.setTextColor(Design.DIM()); sw.setChecked(p.enable);
        box.addView(sw);

        box.addView(Ui.label(act, "AUTO MARKET-MAKE — PEG TO " + PriceOracle.source()));
        final SwitchCompat pegSw = new SwitchCompat(act);
        pegSw.setText("Peg ladder to " + PriceOracle.source() + " (auto-reprice)"); pegSw.setTextColor(Design.DIM());
        pegSw.setChecked(prefs.getBoolean(PriceOracle.P_ENABLE, false));
        box.addView(pegSw);
        final TextView pegPx = Ui.text(act, DoorHost.mx(PriceOracle.describe()), 11.5f, Design.DIM(), Design.mono());
        box.addView(pegPx);
        box.addView(Ui.text(act, "While pegged, the ladder regenerates around the " + PriceOracle.source() + " mid (± step %, your size per level) at every "
                + "publish, and re-publishes when the market moves ≥ your threshold. If the feed goes down your order is withdrawn for safety, then "
                + "restored when it recovers. " + PriceOracle.source() + "'s " + c + " market is THIN — keep step % above its typical spread and level "
                + "sizes small; you auto-trade at these prices.", 11f, Design.DIM2(), Design.sans()), Ui.wide(act, 0, 4));
        LinearLayout pegRow = new LinearLayout(act);
        final EditText biasE = field("skew ±%", true), repE = field("reprice ≥ %", false);
        biasE.setText(prefs.getString(PriceOracle.P_BIAS, "")); repE.setText(prefs.getString(PriceOracle.P_REPRICE, "1"));
        pegRow.addView(biasE, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)); pegRow.addView(repE, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        box.addView(pegRow);

        box.addView(Ui.label(act, "AUTO-FILL (mid · step % · levels, then ask/bid size)"));
        LinearLayout gen = new LinearLayout(act);
        final EditText midE = field("mid", false), stepE = field("step %", false), levelsE = field("levels", false);
        levelsE.setInputType(InputType.TYPE_CLASS_NUMBER);
        levelsE.setText(prefs.getString(PriceOracle.P_LEVELS, "1"));
        if (pegSw.isChecked()) stepE.setText(prefs.getString(PriceOracle.P_STEP, ""));
        gen.addView(midE, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)); gen.addView(stepE, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)); gen.addView(levelsE, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 0.6f));
        box.addView(gen);
        LinearLayout genS = new LinearLayout(act);
        final EditText askSizeE = field("ask size", false), bidSizeE = field("bid size", false);
        String legacySize = prefs.getString(PriceOracle.P_SIZE, "");
        askSizeE.setText(prefs.getString(PriceOracle.P_ASK_SIZE, legacySize)); bidSizeE.setText(prefs.getString(PriceOracle.P_BID_SIZE, legacySize));
        genS.addView(askSizeE, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)); genS.addView(bidSizeE, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        box.addView(genS);

        final double liveCoin = parseD(String.valueOf(host.coinBalance(host.market())), 0);
        final double liveUsdt = host.usdtRaw == null ? 0 : new BigDecimal(host.usdtRaw, 6).doubleValue();

        box.addView(Ui.text(act, "ASKS — you SELL " + c + " (higher price)", 12.5f, Design.RED(), Design.sansBold()), Ui.wide(act, 12, 2));
        final EditText[][] askRows = new EditText[Order.MAX_LEVELS][];
        for (int i = Order.MAX_LEVELS - 1; i >= 0; i--) {   // A6 at the top … A1 (best) by the spread
            boolean legacy0 = i == 0 && p.asks.isEmpty() && p.enable && p.buy > 0;
            askRows[i] = levelRow(box, "A" + (i + 1), i < p.asks.size() ? p.asks.get(i).price : (legacy0 ? p.buy : 0), i < p.asks.size() ? p.asks.get(i).amount : (legacy0 ? liveCoin : 0), Design.RED());
        }
        box.addView(Ui.text(act, "BIDS — you BUY " + c + " (lower price)", 12.5f, Design.IN(), Design.sansBold()), Ui.wide(act, 12, 2));
        final EditText[][] bidRows = new EditText[Order.MAX_LEVELS][];
        for (int i = 0; i < Order.MAX_LEVELS; i++) {
            boolean legacy0 = i == 0 && p.bids.isEmpty() && p.enable && p.sell > 0;
            bidRows[i] = levelRow(box, "B" + (i + 1), i < p.bids.size() ? p.bids.get(i).price : (legacy0 ? p.sell : 0), i < p.bids.size() ? p.bids.get(i).amount : (legacy0 && p.sell > 0 ? liveUsdt / p.sell : 0), Design.IN());
        }
        LinearLayout minRow = new LinearLayout(act); minRow.setGravity(Gravity.CENTER_VERTICAL);
        final EditText minE = field("0", false); minE.setText(p.min > 0 ? trimSig(p.min) : ""); minE.setGravity(Gravity.END);
        minRow.addView(Ui.text(act, "min " + c + " / trade", 13f, Design.DIM(), Design.sans()), new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        minRow.addView(minE, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        box.addView(minRow);
        final TextView pv = Ui.text(act, "", 12f, Design.DIM(), Design.sans());
        box.addView(pv, Ui.wide(act, 10, 2));

        final EditText[] params = {biasE, repE, midE, stepE, levelsE, askSizeE, bidSizeE, minE};
        final Runnable upd = () -> { if (!paramsValid(params, pegSw.isChecked())) invalid(pv); else preview(pv, askRows, bidRows); };
        TextWatcher w = onChange(upd);
        repE.addTextChangedListener(w); minE.addTextChangedListener(w);
        for (EditText[] row : askRows) { row[0].addTextChangedListener(w); row[1].addTextChangedListener(w); }
        for (EditText[] row : bidRows) { row[0].addTextChangedListener(w); row[1].addTextChangedListener(w); }
        upd.run();

        final boolean[] open = {true}, awaitFill = {false}, filling = {false};
        final Runnable fill = () -> {   // the rows from a price: the live oracle mid (pegged) or the typed mid
            if (!paramsValid(params, pegSw.isChecked())) { invalid(pv); return; }
            boolean pegged = pegSw.isChecked();
            double mid;
            if (pegged) {
                double m = PriceOracle.mid();
                if (!(m > 0) || !PriceOracle.fresh()) return;
                mid = m * (1 + Math.max(-20, Math.min(20, parseD(biasE.getText().toString(), 0))) / 100.0);
            } else mid = parseD(midE.getText().toString(), 0);
            double step = parseD(stepE.getText().toString(), 0), askSize = parseD(askSizeE.getText().toString(), 0), bidSize = parseD(bidSizeE.getText().toString(), 0);
            int n = Math.max(1, Math.min(Order.MAX_LEVELS, (int) parseD(levelsE.getText().toString(), 1)));
            if (pegged && !MarketDraft.generation(mid, step, askSize, bidSize, n)) { invalid(pv); return; }
            filling[0] = true;
            try {
                if (pegged) midE.setText(trimSig(mid));
                if (mid <= 0 || step <= 0 || !(askSize > 0 || bidSize > 0)) return;
                for (int i = 0; i < Order.MAX_LEVELS; i++) {
                    boolean askOn = i < n && askSize > 0, bidOn = i < n && bidSize > 0;   // a side sized 0 is blank → one-sided
                    askRows[i][0].setText(askOn ? trimSig(mid * (1 + (i + 1) * step / 100.0)) : ""); askRows[i][1].setText(askOn ? trimSig(askSize) : "");
                    bidRows[i][0].setText(bidOn ? trimSig(mid * (1 - (i + 1) * step / 100.0)) : ""); bidRows[i][1].setText(bidOn ? trimSig(bidSize) : "");
                }
                awaitFill[0] = false;
            } finally { filling[0] = false; }
            upd.run();
        };
        final Runnable pegUi = () -> { boolean on = pegSw.isChecked(); midE.setEnabled(!on); midE.setAlpha(on ? 0.5f : 1f); };
        pegUi.run();
        final Runnable[] tick = new Runnable[1];
        tick[0] = () -> {   // the peg price line, every 2 s while the editor is open (no network while backgrounded)
            if (!open[0]) return;
            if (MainActivity.FOREGROUND) {
                pegPx.setText(DoorHost.mx(PriceOracle.describe()));
                if (pegSw.isChecked()) { PriceOracle.refreshAsync(); if (awaitFill[0]) fill.run(); }
            }
            postDelayed(tick[0], 2000);
        };
        postDelayed(tick[0], 1000);
        PriceOracle.refreshAsync();
        pegSw.setOnCheckedChangeListener((b, on) -> {
            pegUi.run();
            if (on) { PriceOracle.refreshAsync(); if (PriceOracle.fresh()) fill.run(); else { awaitFill[0] = true; act.toast("Fetching " + PriceOracle.source() + " price…"); } }
        });
        // AUTO-GENERATE: any ladder-parameter edit rebuilds the rows. Watchers attach after seeding, so opening the
        // editor never stomps a saved (hand-tuned) ladder.
        TextWatcher gw = onChange(() -> {
            if (filling[0]) return;
            if (!paramsValid(params, pegSw.isChecked())) { invalid(pv); return; }
            upd.run();
            if (pegSw.isChecked() && !PriceOracle.fresh()) { awaitFill[0] = true; PriceOracle.refreshAsync(); }
            else fill.run();
        });
        midE.addTextChangedListener(gw); stepE.addTextChangedListener(gw); askSizeE.addTextChangedListener(gw);
        bidSizeE.addTextChangedListener(gw); biasE.addTextChangedListener(gw); levelsE.addTextChangedListener(gw);

        ScrollView sv = new ScrollView(act); sv.addView(box);
        final AlertDialog editor = act.track(new AlertDialog.Builder(act, Design.dialogTheme())
                .setTitle("My market / ladder · " + c).setView(sv)
                .setPositiveButton("Save", null).setNegativeButton("Cancel", null)
                .setOnDismissListener(d -> { open[0] = false; removeCallbacks(tick[0]); act.repaint(); })
                .show());
        editor.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (host.switching() || host.market() != mkt) { act.toast("The market was switched — nothing was saved. Open the editor again."); editor.dismiss(); return; }
            if (!paramsValid(params, pegSw.isChecked()) || !rowsValid(askRows, bidRows)) { invalid(pv); pv.post(() -> pv.requestRectangleOnScreen(new android.graphics.Rect(0, 0, pv.getWidth(), pv.getHeight()), true)); return; }
            p.enable = sw.isChecked();
            p.asks.clear(); p.bids.clear();
            for (EditText[] row : askRows) p.asks.add(new Order.Level(parseD(row[0].getText().toString(), 0), parseD(row[1].getText().toString(), 0)));
            for (EditText[] row : bidRows) p.bids.add(new Order.Level(parseD(row[0].getText().toString(), 0), parseD(row[1].getText().toString(), 0)));
            p.min = parseD(minE.getText().toString(), p.min);
            p.buy = 0; p.sell = 0;   // the editor is authoritative: a side left with no valid levels is OFF
            Order.sanitize(p);
            boolean pegOn = pegSw.isChecked();
            double pStep = parseD(stepE.getText().toString(), 0), pAsk = parseD(askSizeE.getText().toString(), 0), pBid = parseD(bidSizeE.getText().toString(), 0);
            if (pegOn && (pStep <= 0 || !(pAsk > 0 || pBid > 0))) { pegOn = false; act.toast("Peg needs a step % and an ask or bid size — saved with peg OFF"); }
            double pBias = Math.max(-20, Math.min(20, parseD(biasE.getText().toString(), 0))), pRep = Math.max(0.1, parseD(repE.getText().toString(), 1));
            int pLevels = Math.max(1, Math.min(Order.MAX_LEVELS, (int) parseD(levelsE.getText().toString(), 1)));
            double pSize = Math.max(pAsk, pBid);
            prefs.edit().putBoolean(PriceOracle.P_ENABLE, pegOn)
                    .putString(PriceOracle.P_STEP, pStep > 0 ? trimSig(pStep) : "").putString(PriceOracle.P_SIZE, pSize > 0 ? trimSig(pSize) : "")
                    .putString(PriceOracle.P_ASK_SIZE, pAsk > 0 ? trimSig(pAsk) : "").putString(PriceOracle.P_BID_SIZE, pBid > 0 ? trimSig(pBid) : "")
                    .putString(PriceOracle.P_LEVELS, String.valueOf(pLevels)).putString(PriceOracle.P_BIAS, pBias != 0 ? trimSig(pBias) : "")
                    .putString(PriceOracle.P_REPRICE, trimSig(pRep))
                    .putBoolean(PriceOracle.P_WITHDRAWN, pegOn && prefs.getBoolean(PriceOracle.P_WITHDRAWN, false))
                    .putBoolean(PriceOracle.P_TOMBSTONED, pegOn && prefs.getBoolean(PriceOracle.P_TOMBSTONED, false))
                    .apply();
            if (!host.saveOrder(o)) { act.toast(DoorHost.SWITCHING); editor.dismiss(); return; }
            act.toast(p.enable && p.asks.isEmpty() && p.bids.isEmpty() ? "Enabled, but no levels set — add a bid or ask to publish"
                    : Order.crossed(p) ? "⚠ Saved, but your best bid ≥ best ask (crossed market)" : "Ladder saved");
            if (prefs.getBoolean("auto_publish", false)) host.pushOrderEdit(o, this::say);   // a live order reflects the edit NOW
            editor.dismiss();
        });
    }

    private boolean paramsValid(EditText[] f, boolean pegged) {
        String[] raw = new String[f.length];
        for (int i = 0; i < f.length; i++) raw[i] = f[i].getText().toString();
        if (!MarketDraft.parameters(raw)) return false;
        if (!pegged) return true;
        double quoted = PriceOracle.mid() * (1 + Math.max(-20, Math.min(20, MarketDraft.number(raw[0], true, 0))) / 100.0);
        int levels = Math.max(1, Math.min(Order.MAX_LEVELS, (int) MarketDraft.number(raw[4], false, 1)));
        return MarketDraft.generation(quoted, MarketDraft.number(raw[3], false, 0), MarketDraft.number(raw[5], false, 0), MarketDraft.number(raw[6], false, 0), levels);
    }

    private static boolean rowsValid(EditText[][]... sides) {
        for (EditText[][] side : sides) {
            String[][] raw = new String[side.length][2];
            for (int i = 0; i < side.length; i++) { raw[i][0] = side[i][0].getText().toString(); raw[i][1] = side[i][1].getText().toString(); }
            if (!MarketDraft.rows(raw)) return false;
        }
        return true;
    }

    private static void invalid(TextView pv) { pv.setText("Check the numbers: one is invalid or too large. Your draft has not been saved."); pv.setTextColor(Design.RED()); }

    private void preview(TextView pv, EditText[][] askRows, EditText[][] bidRows) {
        if (!rowsValid(askRows, bidRows)) { invalid(pv); return; }
        int nA = 0, nB = 0; double bestAsk = Double.MAX_VALUE, bestBid = 0, sumA = 0, sumB = 0;
        for (EditText[] r : askRows) { double px = parseD(r[0].getText().toString(), 0), am = parseD(r[1].getText().toString(), 0); if (px > 0 && am > 0) { nA++; sumA += am; bestAsk = Math.min(bestAsk, px); } }
        for (EditText[] r : bidRows) { double px = parseD(r[0].getText().toString(), 0), am = parseD(r[1].getText().toString(), 0); if (px > 0 && am > 0) { nB++; sumB += am; bestBid = Math.max(bestBid, px); } }
        if (nA > 0 && nB > 0 && bestBid >= bestAsk) {
            pv.setText("⚠ Crossed — best bid " + fmtPrice(bestBid) + " ≥ best ask " + fmtPrice(bestAsk) + ": you'd sell cheaper than you buy");
            pv.setTextColor(Design.RED()); return;
        }
        String c = ccy();
        pv.setText("BIDS  " + (nB > 0 ? nB + " lvl · best " + fmtPrice(bestBid) + " · " + abbrev(sumB) + " " + c : "none")
                + "\nASKS  " + (nA > 0 ? nA + " lvl · best " + fmtPrice(bestAsk) + " · " + abbrev(sumA) + " " + c : "none"));
        pv.setTextColor(Design.DIM());
    }
}
