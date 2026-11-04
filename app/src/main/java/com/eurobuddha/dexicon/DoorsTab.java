package com.eurobuddha.dexicon;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.eurobuddha.atomix.IdentityWatch;
import com.eurobuddha.atomix.TradingContext;
import com.eurobuddha.atomix.eth.EthHtlc;
import com.eurobuddha.atomix.eth.EthSend;
import com.eurobuddha.atomix.eth.EthWallet;
import com.eurobuddha.atomix.swap.Order;
import com.eurobuddha.atomix.swap.SwapDb;
import com.eurobuddha.atomix.swap.SwapVisibility;
import com.eurobuddha.dexicon.doors.DoorForwards;
import com.eurobuddha.dexicon.doors.DoorHost;
import com.eurobuddha.dexicon.doors.DoorPlan;
import com.eurobuddha.dexicon.doors.DoorStatus;
import com.eurobuddha.dexicon.ui.LedgerView;
import com.eurobuddha.dexicon.ui.Ui;

import org.json.JSONObject;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * ① DOLLARS IN › SWAP — AtomiX's own Swap tab (apks/atomix MainActivity.renderSwapTab), on the vendored AtomiX engine
 * ({@link DoorHost}). Owner, 4 Nov: the AtomiX people already know, with every function: Sell / Buy {coin} toggle, a
 * "You send ⇅ You receive" card where either side can be typed, the best-price line, buy slippage 2% / 4.2% / Custom
 * (AtomiX's pref and default), a review that lists every part with its maker key in full, and the YOUR SWAP stages.
 * Dexicon keeps what it adds on top: multi-maker sweeps for both sides, the funds/gas checks inline, an optional
 * forward address, and the IN PROGRESS lines with Refund now. Everything quotes the ONE active AtomiX market (⇄ pill).
 */
@SuppressLint("ViewConstructor")
public final class DoorsTab extends LinearLayout {

    private static final long REFRESH_MS = 60_000;
    private static final long PANEL_GRACE_MS = 30_000;   // AtomiX SWAP_PANEL_GRACE_MS: a finished swap stays this long
    private static final int USDT_TEAL = 0xFF26A69A, WARN = 0xFFE6A23C;   // AtomiX's USDT chip and stage-warning colours
    private static final int STG_PENDING = 0, STG_ACTIVE = 1, STG_DONE = 2, STG_WARN = 3;
    private final MainActivity act;
    private final DoorHost host;
    private final SharedPreferences prefs;
    private final TextView flip;
    private final LinearLayout rescue;
    private String rescueSig = "";
    private final TextView launch, head, sellTab, buyTab, bestLine, planLine, toLabel, review;
    private final TextView setupTitle, setupBody, emptyTitle;
    private final LinearLayout swapCard, setupCard, emptyCard, slipRow, stages, list;
    private final Chip sendChip, recvChip;
    private final TextView progressLabel, finished;
    private final Swaps hooks;
    private final EditText send, recv, to;
    private boolean sell = false;   // ① is "Dollars in": it opens on Buy
    private boolean syncing;
    /** Which field the user typed last (null: neither) — the other follows the CURRENT best price on every re-quote. */
    private Boolean typedSend;
    private TradingContext lastMarket;
    private String stagesSig = "";
    private long lastRefresh;

    /** AtomiX's token chip: coloured coin mark, ticker, "avail" balance. */
    private final class Chip {
        final LinearLayout root; final TextView mark, ticker, avail;
        Chip() {
            Context c = getContext();
            root = new LinearLayout(c); root.setGravity(Gravity.CENTER_VERTICAL);
            mark = t("", 0xFFFFFFFF, 15f, Design.sansBold()); mark.setGravity(Gravity.CENTER);
            LayoutParams ml = new LayoutParams(Design.dp(c, 34), Design.dp(c, 34)); ml.rightMargin = Design.dp(c, 10);
            root.addView(mark, ml);
            LinearLayout col = new LinearLayout(c); col.setOrientation(VERTICAL);
            ticker = t("", Design.TEXT(), 15f, Design.sansBold()); avail = t("", Design.DIM(), 11f, Design.sans());
            col.addView(ticker); col.addView(avail);
            root.addView(col);
        }
        void set(boolean coinSide) {
            android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
            g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            g.setColor(coinSide ? acc(route()) : USDT_TEAL);
            mark.setBackground(g);
            mark.setText(coinSide ? "M" : "$"); mark.setTextColor(coinSide ? ink(route()) : 0xFFFFFFFF);
            ticker.setText(coinSide ? coin() : "USDT");
            avail.setText("avail " + (coinSide ? coinHeld() : host.usdtRaw == null ? "—" : EthWallet.format(host.usdtRaw, 6, 6)));
        }
    }

    public DoorsTab(MainActivity act) {
        super(act);
        this.act = act;
        this.host = DoorHost.get(act);
        this.prefs = act.getSharedPreferences("atomix_swap_ui", Context.MODE_PRIVATE);
        setOrientation(VERTICAL);
        int pad = Design.dp(act, 12);
        setPadding(pad, pad, pad, pad);

        rescue = new LinearLayout(act); rescue.setOrientation(VERTICAL); rescue.setVisibility(GONE);
        addView(rescue);
        launch = t("", Design.TEXT(), 11f, Design.sansBold());
        int lp = Design.dp(act, 10); launch.setPadding(lp, lp, lp, lp);
        launch.setVisibility(GONE);
        LayoutParams ll = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT); ll.bottomMargin = Design.dp(act, 10);
        addView(launch, ll);

        Ui.why(this, "WHY THIS STEP", "Dexicon's exchange (③) trades MINIMA against MxUSD, Minima's own dollar. If your dollars are USDT on "
                + "Ethereum, buy MxUSD with them here first; sell MxUSD here to turn it back into USDT. Both sides lock their funds in a "
                + "time-locked contract; if anything stalls, you are refunded automatically. Already hold MxUSD? Go straight to ③. "
                + "Just swapping? You can stop here. Your Ethereum wallet (the USDT you swap from and to) is in Assets.", true);

        // ---- AtomiX header + Sell / Buy toggle ----
        head = t("", Design.TEXT(), 18f, Design.sansBold()); head.setPadding(0, Design.dp(act, 4), 0, Design.dp(act, 2));
        addView(head);
        TextView sh = t("Enter an amount — see exactly what you'll get at the best price.", Design.DIM(), 12.5f, Design.sans());
        sh.setPadding(0, 0, 0, Design.dp(act, 12));
        addView(sh);
        LinearLayout seg = new LinearLayout(act);
        sellTab = segBtn("", () -> setSide(true));
        buyTab = segBtn("", () -> setSide(false));
        LayoutParams s1 = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f); s1.rightMargin = Design.dp(act, 5);
        LayoutParams s2 = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f); s2.leftMargin = Design.dp(act, 5);
        seg.addView(sellTab, s1); seg.addView(buyTab, s2);
        addView(seg);

        // ---- "Setting up AtomiX…" (first start) and "No one is quoting" (AtomiX's empty card) ----
        setupCard = bigCard(this);
        setupTitle = t("Setting up AtomiX…", Design.TEXT(), 14.5f, Design.sansBold()); setupCard.addView(setupTitle);
        setupBody = t("", Design.DIM(), 12.5f, Design.sans()); setupBody.setPadding(0, Design.dp(act, 6), 0, 0);
        setupCard.addView(setupBody);
        emptyCard = bigCard(this);
        emptyTitle = t("", Design.TEXT(), 14.5f, Design.sansBold()); emptyCard.addView(emptyTitle);
        TextView emptySub = t("Check back soon, or open Market to place your own order and wait for a match.", Design.DIM(), 12.5f, Design.sans());
        emptySub.setPadding(0, Design.dp(act, 6), 0, Design.dp(act, 2)); emptyCard.addView(emptySub);
        TextView go = Design.pill(act, "Open Market", Design.SURFACE2(), Design.TEXT());
        go.setOnClickListener(v -> act.showTab(MainActivity.TAB_MARKET)); Design.pressable(go);
        LayoutParams gl = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT); gl.topMargin = Design.dp(act, 12);
        emptyCard.addView(go, gl);

        // ---- the dual-amount card: YOU SEND / ⇅ / YOU RECEIVE ----
        swapCard = bigCard(this);
        swapCard.addView(fieldLabel("YOU SEND"));
        LinearLayout sendRow = new LinearLayout(act); sendRow.setGravity(Gravity.CENTER_VERTICAL);
        sendRow.setPadding(0, Design.dp(act, 6), 0, Design.dp(act, 4));
        sendChip = new Chip(); sendRow.addView(sendChip.root);
        send = bigInput(Design.TEXT());
        sendRow.addView(send, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        swapCard.addView(sendRow);

        LinearLayout flipRow = new LinearLayout(act); flipRow.setGravity(Gravity.CENTER_VERTICAL);
        flipRow.setPadding(0, Design.dp(act, 10), 0, Design.dp(act, 10));
        View l1 = new View(act); l1.setBackgroundColor(Design.BORDER());
        View l2 = new View(act); l2.setBackgroundColor(Design.BORDER());
        flip = t("⇅", Design.ACCENT(), 16f, Design.sansBold()); flip.setGravity(Gravity.CENTER);
        flip.setBackground(Design.stroked(act, Design.SURFACE2(), 19));
        flip.setContentDescription("Flip: swap the other way");
        flip.setOnClickListener(v -> setSide(!sell)); Design.pressable(flip);
        int fs = Design.dp(act, 38);
        LayoutParams fl = new LayoutParams(fs, fs); fl.leftMargin = Design.dp(act, 12); fl.rightMargin = Design.dp(act, 12);
        flipRow.addView(l1, new LayoutParams(0, Design.dp(act, 1), 1f)); flipRow.addView(flip, fl); flipRow.addView(l2, new LayoutParams(0, Design.dp(act, 1), 1f));
        swapCard.addView(flipRow);

        swapCard.addView(fieldLabel("YOU RECEIVE (estimate)"));
        LinearLayout recvRow = new LinearLayout(act); recvRow.setGravity(Gravity.CENTER_VERTICAL);
        recvRow.setPadding(0, Design.dp(act, 6), 0, Design.dp(act, 2));
        recvChip = new Chip(); recvRow.addView(recvChip.root);
        recv = bigInput(Design.ACCENT());
        recvRow.addView(recv, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        swapCard.addView(recvRow);

        bestLine = t("", Design.DIM2(), 11.5f, Design.sans()); bestLine.setPadding(0, Design.dp(act, 12), 0, 0);
        swapCard.addView(bestLine);
        slipRow = new LinearLayout(act); slipRow.setGravity(Gravity.CENTER_VERTICAL);
        LayoutParams sr = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT); sr.topMargin = Design.dp(act, 10);
        swapCard.addView(slipRow, sr);
        planLine = t("", Design.DIM(), 10.5f, Design.mono()); planLine.setPadding(0, Design.dp(act, 10), 0, 0);
        planLine.setTextIsSelectable(true);
        swapCard.addView(planLine);
        toLabel = t("", Design.DIM(), 9.5f, Design.mono()); toLabel.setPadding(0, Design.dp(act, 12), 0, Design.dp(act, 4));
        swapCard.addView(toLabel);
        to = input("", false); swapCard.addView(to);
        review = t("Review swap", Design.ON_ACCENT(), 13f, Design.sansBold());
        review.setGravity(Gravity.CENTER); int bp = Design.dp(act, 13); review.setPadding(0, bp, 0, bp);
        Design.pressable(review);
        review.setOnClickListener(v -> openReview());
        LayoutParams rv = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT); rv.topMargin = Design.dp(act, 16);
        swapCard.addView(review, rv);

        stages = new LinearLayout(act); stages.setOrientation(VERTICAL);
        LayoutParams stl = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT); stl.topMargin = Design.dp(act, 18);
        addView(stages, stl);

        progressLabel = Ui.label(act, "IN PROGRESS");
        progressLabel.setPadding(Design.dp(act, 4), Design.dp(act, 14), 0, Design.dp(act, 6));
        addView(progressLabel);
        list = new LinearLayout(act); list.setOrientation(VERTICAL);
        addView(list);
        hooks = new Swaps(act, host);
        finished = t("", Design.DIM(), 12.5f, Design.sans());
        finished.setMinHeight(Design.dp(act, 44)); finished.setGravity(Gravity.CENTER_VERTICAL);
        finished.setPadding(Design.dp(act, 4), 0, 0, 0);
        finished.setOnClickListener(v -> { if (!host.swaps().isEmpty()) act.showTab(MainActivity.TAB_HISTORY); });
        addView(finished);

        // Either side can be typed (AtomiX): the other follows at the best price. The plan below re-quotes on every key.
        send.addTextChangedListener(watcher(() -> { if (!syncing) { typedSend = true; mirror(true); } quote(); }));
        recv.addTextChangedListener(watcher(() -> { if (!syncing) { typedSend = false; mirror(false); } quote(); }));
        to.addTextChangedListener(watcher(this::quote));
    }

    // ------------------------------------------------------------------ view helpers

    // Each door wears its AtomiX market's own identity (apks/atomix TradingContext): the MxUSD track is Tether green
    // with white ink, the MINIMA track keeps Minima orange on black. Owner, 2026-10-22.
    static final int USD_GREEN = 0xFF26A17B, USD_GREEN_TOP = 0xFF3DBF93, USD_INK = 0xFFFFFFFF;
    static int acc(TradingContext m) { return m == TradingContext.MXUSDT ? USD_GREEN : Design.ACCENT(); }
    static int soft(TradingContext m) { return m == TradingContext.MXUSDT ? (Design.isDark() ? 0x3326A17B : 0x1F26A17B) : Design.ACCENT_SOFT(); }
    static int ink(TradingContext m) { return m == TradingContext.MXUSDT ? USD_INK : Design.ON_ACCENT(); }
    private android.graphics.drawable.Drawable cta(TradingContext m) {
        if (m != TradingContext.MXUSDT) return Design.gradientCta(getContext());
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, new int[]{USD_GREEN_TOP, USD_GREEN});
        d.setCornerRadius(Design.dp(getContext(), 14));
        return d;
    }

    private TextView t(String s, int color, float size, android.graphics.Typeface tf) {
        TextView v = new TextView(getContext()); v.setText(s); v.setTextColor(color); v.setTextSize(size); v.setTypeface(tf); return v;
    }
    private TextView fieldLabel(String s) { TextView v = t(s, Design.DIM(), 11f, Design.sans()); v.setLetterSpacing(0.06f); return v; }
    private TextView segBtn(String s, Runnable on) {
        TextView b = t(s, Design.DIM(), 13f, Design.sansBold());
        b.setGravity(Gravity.CENTER); int p = Design.dp(getContext(), 11); b.setPadding(0, p, 0, p); b.setMinHeight(Design.dp(getContext(), 46));
        b.setOnClickListener(v -> on.run()); Design.pressable(b);
        return b;
    }
    private void mark(TextView b, boolean on) {
        b.setTextColor(on ? ink(route()) : Design.DIM());
        b.setBackground(on ? Design.roundBg(getContext(), acc(route()), 12) : Design.stroked(getContext(), Design.SURFACE2(), 12));
    }
    private EditText input(String hint, boolean decimal) {
        EditText e = new EditText(getContext());
        e.setHint(hint); e.setTextColor(Design.TEXT()); e.setHintTextColor(Design.DIM2()); e.setTypeface(Design.mono()); e.setTextSize(12f);
        if (decimal) MarketTab.decimalInput(e, false); else e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        e.setBackground(Design.stroked(getContext(), Design.SURFACE2(), 8));
        int p = Design.dp(getContext(), 9); e.setPadding(p, p, p, p);
        return e;
    }
    /** AtomiX's big amount field: 26sp mono, right-aligned, no box. */
    private EditText bigInput(int color) {
        EditText e = new EditText(getContext());
        MarketTab.decimalInput(e, false);   // AtomiX decimalInput: Samsung keyboards drop "." on a number field
        e.setHint("0.00"); e.setHintTextColor(Design.DIM2());
        e.setTextColor(color); e.setTextSize(26f); e.setTypeface(Design.monoBold());
        e.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        e.setBackground(null); e.setPadding(Design.dp(getContext(), 8), 0, 0, 0);
        return e;
    }
    private LinearLayout card(LinearLayout parent) {
        LinearLayout c = new LinearLayout(getContext());
        c.setOrientation(VERTICAL);
        c.setBackground(Design.card(getContext(), 12));
        int p = Design.dp(getContext(), 12); c.setPadding(p, p, p, p);
        LayoutParams lp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT); lp.bottomMargin = Design.dp(getContext(), 10);
        parent.addView(c, lp);
        return c;
    }
    private LinearLayout bigCard(LinearLayout parent) {
        LinearLayout c = new LinearLayout(getContext());
        c.setOrientation(VERTICAL);
        c.setBackground(Design.card(getContext(), 18));
        c.setPadding(Design.dp(getContext(), 16), Design.dp(getContext(), 14), Design.dp(getContext(), 16), Design.dp(getContext(), 16));
        LayoutParams lp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT); lp.topMargin = Design.dp(getContext(), 14);
        parent.addView(c, lp);
        return c;
    }
    private static TextWatcher watcher(Runnable r) {
        return new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(Editable e) { r.run(); }
        };
    }

    // ------------------------------------------------------------------ state

    private TradingContext route() { return host.market(); }
    private String coin() { return DoorPlan.coin(route()); }
    private Map<String, Order> book() { return host.book(route()); }
    private String me() { return host.myPublicId(route()); }
    private String coinHeld() { String h = host.coinBalance(route()); return route() == TradingContext.MINIMA ? minima12(h) : nz(h); }
    private static String trim6(double v) { return Double.isFinite(v) ? BigDecimal.valueOf(v).setScale(6, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() : "0"; }
    private static String px(double v) { return Double.isFinite(v) && v > 0 ? new BigDecimal(v).round(new java.math.MathContext(6)).stripTrailingZeros().toPlainString() : "0"; }
    private static String eth(BigInteger wei) { return wei == null ? "?" : EthWallet.format(wei, 18, 6); }

    /** AtomiX's buy slippage (pref buy_slippage_pct, 0.1–50 %, default 4.2 %) as a fraction. */
    double slippage() {
        float pct = prefs.getFloat("buy_slippage_pct", 4.2f);
        // via the float's own decimal string: 4.2f is 4.19999980926…, and the plan must use exactly 4.2 %
        return Float.isFinite(pct) && pct >= 0.1f && pct <= 50 ? Double.parseDouble(Float.toString(pct)) / 100.0 : 0.042;
    }
    private void setSlippage(double pct) {
        if (!Double.isFinite(pct)) return;
        prefs.edit().putFloat("buy_slippage_pct", (float) Math.max(0.1, Math.min(50, pct))).apply();
        render();
    }
    private static String pct(double v) { return BigDecimal.valueOf(v).setScale(2, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString(); }

    /** Sell / Buy (or ⇅): the amounts clear, as in AtomiX, so a number typed for one direction is never sent the other. */
    private void setSide(boolean s) {
        if (sell != s) { sell = s; clearAmounts(); }
        render();
    }

    /** Both amounts cleared (side or market changed): a number typed for one never carries over to the other. */
    private void clearAmounts() { syncing = true; send.setText(""); recv.setText(""); syncing = false; typedSend = null; }

    /** The coin amount the swap is sized by (AtomiX: always the {coin} side — sent when selling, received when buying). */
    String coinAmount() { return (sell ? send : recv).getText().toString().trim(); }

    /** Type either side; the other follows at the best price (AtomiX computeUsdt / computeMinima). */
    private void mirror(boolean fromSend) {
        double best = best();
        String in = (fromSend ? send : recv).getText().toString().trim(), out;
        boolean inIsCoin = fromSend == sell;   // selling: send is the coin; buying: receive is the coin
        if (in.isEmpty() || !(best > 0)) out = "";
        else out = inIsCoin ? DoorPlan.computeUsdt(in, best) : DoorPlan.computeMinima(in, best);
        EditText other = fromSend ? recv : send;
        String now = out == null ? "" : out;
        if (now.equals(other.getText().toString())) return;   // unchanged: no needless edit (or re-quote)
        syncing = true;
        other.setText(now);
        syncing = false;
    }

    private double best() {
        DoorPlan.Best q = DoorPlan.bestMakers(book(), me());
        return sell ? q.bestBid : q.bestAsk;
    }

    /** Re-read books, balances and settlement at most once a minute while the tab is open. */
    private void refresh(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - lastRefresh < REFRESH_MS) return;
        lastRefresh = now;
        host.poll();
        host.readBalances(this::paintTop);
        host.scanBooks(this::render);
    }

    public void render() {
        refresh(false);
        if (lastMarket != route()) { if (lastMarket != null) clearAmounts(); lastMarket = route(); }   // ⇄ switched: fresh amounts
        paintTop();
        String c = coin();
        head.setText("Swap " + c + " ⇄ USDT");
        sellTab.setText("Sell " + c); buyTab.setText("Buy " + c);
        mark(sellTab, sell); mark(buyTab, !sell);
        review.setBackground(cta(route())); review.setTextColor(ink(route()));
        flip.setTextColor(acc(route()));
        sendChip.set(sell); recvChip.set(!sell);
        toLabel.setText(sell ? "SEND THE USDT TO · optional — leave empty to keep it in your Ethereum wallet above"
                             : "SEND THE " + c + " TO · optional — leave empty to keep it in this node’s wallet");
        to.setHint(sell ? "0x… Ethereum address" : "Mx… Minima address");

        boolean ready = act.paired() && host.ready();
        double best = best();
        setupCard.setVisibility(ready ? GONE : VISIBLE);
        emptyCard.setVisibility(ready && !(best > 0) ? VISIBLE : GONE);
        swapCard.setVisibility(ready && best > 0 ? VISIBLE : GONE);
        if (!act.paired()) {
            setupTitle.setText("Node not connected");
            setupBody.setText("Pair Dexicon with your node to see AtomiX's makers and swap.");
        } else if (!ready) {
            setupTitle.setText("Setting up AtomiX…");
            String note = host.bootNote();
            setupBody.setText("Preparing your Ethereum wallet and swap keys on this node, then reading the market. The first start takes a minute or two."
                    + (note.isEmpty() || note.startsWith("Waiting") ? "" : "\n\n" + note));
        }
        emptyTitle.setText("No one is quoting a " + (sell ? "buy" : "sell") + " price right now.");
        paintSlippage();
        quote();
        paintStages();
        renderSwaps(list);
    }

    private void paintSlippage() {
        slipRow.removeAllViews();
        slipRow.setVisibility(sell ? GONE : VISIBLE);
        if (sell) return;
        double cur = slippage() * 100;
        boolean is2 = Math.abs(cur - 2) < 0.001, is42 = Math.abs(cur - 4.2) < 0.001;
        slipRow.addView(t("Max slippage", Design.DIM(), 11.5f, Design.sans()));
        slipRow.addView(slipPill("2%", is2, () -> setSlippage(2)));
        slipRow.addView(slipPill("4.2%", is42, () -> setSlippage(4.2)));
        slipRow.addView(slipPill(!is2 && !is42 ? pct(cur) + "%" : "Custom", !is2 && !is42, this::customSlippage));
    }
    private TextView slipPill(String label, boolean on, Runnable tap) {
        TextView p = Design.pill(getContext(), label, on ? soft(route()) : Design.SURFACE2(), on ? acc(route()) : Design.DIM());
        LayoutParams l = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT); l.leftMargin = Design.dp(getContext(), 6);
        p.setLayoutParams(l); p.setOnClickListener(v -> tap.run()); Design.pressable(p);
        return p;
    }
    private void customSlippage() {
        LinearLayout box = new LinearLayout(act); box.setOrientation(VERTICAL);
        int p = Design.dp(act, 20); box.setPadding(p, Design.dp(act, 12), p, Design.dp(act, 4));
        TextView h = t("The most above the best price you'll pay per " + coin() + " when a buy fills across deeper levels (0.1–50 %).", Design.DIM(), 12.5f, Design.sans());
        h.setPadding(0, 0, 0, Design.dp(act, 10)); box.addView(h);
        EditText e = input("%", true); e.setTextSize(16f); e.setText(pct(slippage() * 100)); box.addView(e);
        act.track(new AlertDialog.Builder(act, Design.dialogTheme()).setTitle("Max slippage").setView(box)
                .setPositiveButton("Set", (d, w) -> {
                    try { setSlippage(Double.parseDouble(e.getText().toString().trim())); }
                    catch (NumberFormatException ex) { act.toast("Enter a percentage, e.g. 3"); }
                })
                .setNegativeButton("Cancel", null).show());
    }

    private void paintTop() {
        String ln = host.launchNote();
        launch.setVisibility(ln == null ? GONE : VISIBLE);
        if (ln != null) { launch.setText(ln); launch.setBackground(Design.roundBg(getContext(), soft(TradingContext.MINIMA), 10)); }
        paintRescue();
        if (sendChip != null) { sendChip.set(sell); recvChip.set(!sell); }
    }
    private static String nz(String s) { return s == null ? "—" : s; }
    /** A MINIMA amount cut to at most 12 decimals, never rounded (owner 4 Nov): the node's sendable carries up to 44. */
    static String minima12(String s) {
        if (s == null) return "—";
        try { return new BigDecimal(s).setScale(12, java.math.RoundingMode.DOWN).stripTrailingZeros().toPlainString(); }
        catch (NumberFormatException e) { return s; }
    }

    // ------------------------------------------------------------------ quote (the active market)

    /** AtomiX's plan for the {coin} amount: SELL sweeps it at any bid; BUY delivers it across asks within the slippage. */
    DoorPlan.Plan plan() {
        String amt = coinAmount();
        if (amt.isEmpty() || !DoorPlan.positiveSwapAmount(amt)) return null;
        DoorPlan.Plan p = DoorPlan.buildSweepPlan(book(), sell, amt, sell ? 0 : slippage(), me());
        // A buy sized by the USDT typed: each part locks ceil6(coin × price), so the total can overshoot what was typed by a
        // micro-USDT per part — shave once, as DoorPlan.route does (typing your whole balance must not be refused).
        if (!sell && Boolean.TRUE.equals(typedSend) && !p.legs.isEmpty()) {
            double typed = DoorPlan.parseD(send.getText().toString(), 0), ask = DoorPlan.bestMakers(book(), me()).bestAsk;
            if (typed > 0 && ask > 0 && p.totalUsdt > typed + 1e-9) {
                String less = DoorPlan.legMinima(DoorPlan.parseD(amt, 0) - (p.totalUsdt - typed) / ask - 1e-6);
                if (DoorPlan.positiveSwapAmount(less)) p = DoorPlan.buildSweepPlan(book(), false, less, slippage(), me());
            }
        }
        return p;
    }

    private void quote() {
        // the field the user did NOT type follows the current best price (a rescan moves it) — AtomiX recomputes per render
        if (!syncing && typedSend != null) mirror(typedSend);
        String c = coin();
        DoorPlan.Best q = DoorPlan.bestMakers(book(), me());
        double best = sell ? q.bestBid : q.bestAsk, cap = sell ? q.bidCap : q.askCap, depth = DoorPlan.sweepDepthMinima(book(), sell, me());
        int makers = DoorPlan.liveMakers(book(), sell, me());
        bestLine.setText(best > 0 ? "Best price " + px(best) + " USDT/" + c + "  ·  up to ~" + trim6(cap) + " at best"
                + (depth > cap + 1e-9 ? ", ~" + trim6(depth) + " across the book" : "") + "  ·  " + makers + (makers == 1 ? " maker" : " makers")
                + (sell ? "" : "  ·  ETH gas per part") : "");
        String amt = coinAmount();
        if (amt.isEmpty()) { planLine.setText(""); review.setAlpha(0.5f); return; }
        DoorPlan.Plan p = plan();
        if (p == null) { say("Enter a valid " + c + " amount.", true); return; }
        if (p.legs.isEmpty()) { say("below-min".equals(p.stopReason) ? "That's below the makers' minimum trade size." : "No liquidity available to fill that right now.", true); return; }
        String v = validate(p);
        StringBuilder b = new StringBuilder();
        b.append(p.legs.size()).append(p.legs.size() == 1 ? " part" : " parts").append(" · avg ").append(px(p.avgPrice)).append(" · worst ").append(px(p.worstPrice)).append(" USDT/").append(c);
        b.append(sell ? "\nYou receive ≈ " + trim6(p.totalUsdt) + " USDT" : "\nYou pay ≈ " + trim6(p.totalUsdt) + " USDT for " + trim6(p.filledMinima) + " " + c);
        if (p.partial) b.append("\nPARTIAL: fills ").append(trim6(p.filledMinima)).append(" of ").append(trim6(p.target)).append(' ').append(c)
                .append(!sell && "slippage".equals(p.stopReason) ? " — the rest is priced beyond your " + pct(slippage() * 100) + "% slippage." : " — the rest isn't in the book right now.");
        if (!v.isEmpty()) b.append("\n\n").append(v);
        planLine.setTextColor(v.isEmpty() ? (p.partial ? WARN : Design.DIM()) : Design.RED());
        planLine.setText(b.toString());
        review.setAlpha(v.isEmpty() ? 1f : 0.5f);
    }
    private void say(String s, boolean bad) { planLine.setTextColor(bad ? Design.RED() : Design.DIM()); planLine.setText(s); review.setAlpha(0.5f); }

    /** Fund + address checks for the plan; every unreadable balance fails CLOSED. "" when OK. */
    private String validate(DoorPlan.Plan p) { return fundsCheck(host, p, to.getText().toString().trim(), slippage()); }

    /** The same checks for any AtomiX take on the active market (Swap's form, Market's tap-to-take). */
    static String fundsCheck(DoorHost host, DoorPlan.Plan p, String dest, double slippage) {
        TradingContext m = host.market();
        String c = DoorPlan.coin(m), h = host.coinBalance(m), held = m == TradingContext.MINIMA ? minima12(h) : nz(h);
        if (IdentityWatch.halted()) return "Halted: this node no longer owns the keys AtomiX uses here.";
        if (host.busy()) return "An AtomiX swap is still starting — wait for it to finish.";
        if (!dest.isEmpty() && (p.sell ? !DoorForwards.isEthAddr(dest) : !DoorForwards.isMxAddr(dest)))
            return p.sell ? "The address to send the USDT to must be an Ethereum address (0x + 40 hex)." : "The address to send the " + c + " to must be a Minima address (Mx… or 0x + 64 hex).";
        if (host.ethWei == null) return "Cannot read the ETH balance of your Ethereum wallet right now — try again shortly.";
        if (p.sell) {
            String have = host.coinBalance(m);
            if (have == null) return "Cannot read your " + c + " balance right now.";
            if (p.filledMinima > Double.parseDouble(have) + 1e-9) return "You only have " + held + " " + c + " available to sell.";
            // every part is its own Ethereum HTLC: one withdraw each, and one forward each when an address is named
            int n = p.legs.size();
            BigInteger g = host.gasReserve(EthHtlc.GAS_WITHDRAW.add(dest.isEmpty() ? BigInteger.ZERO : EthSend.GAS_ERC20).multiply(BigInteger.valueOf(n)));
            if (g == null) return "Cannot read the Ethereum gas price right now.";
            if (g.compareTo(host.ethWei) > 0) return "Not enough ETH in your Ethereum wallet for " + n + " withdraw" + (dest.isEmpty() ? "" : " + send") + (n == 1 ? "" : "s") + " — send a little ETH there first.";
        } else {
            if (host.usdtRaw == null) return "Cannot read your USDT balance right now.";
            if (EthSend.parseUnits(trim6(p.totalUsdt), 6).compareTo(host.usdtRaw) > 0)
                return "Need ≈ " + trim6(p.totalUsdt) + " USDT for " + trim6(p.filledMinima) + " " + c + " (within " + pct(slippage * 100) + "%) — you have " + EthWallet.format(host.usdtRaw, 6, 6) + ".";
            BigInteger g = host.gasReserve(EthHtlc.GAS_LOCK.multiply(BigInteger.valueOf(p.legs.size())));
            if (g == null) return "Cannot read the Ethereum gas price right now.";
            if (g.compareTo(host.ethWei) > 0) return "Not enough ETH in your Ethereum wallet for " + p.legs.size() + " lock transaction" + (p.legs.size() == 1 ? "" : "s") + " — send a little ETH there first.";
        }
        return "";
    }

    // ------------------------------------------------------------------ review → start (AtomiX reviewSweep)

    private void openReview() {
        if (!host.ready()) { act.toast("AtomiX is still setting up — " + (host.bootNote().isEmpty() ? "one moment." : host.bootNote())); return; }
        String c = coin();
        if (coinAmount().isEmpty()) { act.toast("Enter how much " + c + " to " + (sell ? "sell" : "buy")); return; }
        DoorPlan.Plan plan = plan();
        if (plan == null) { act.toast("Enter a valid " + c + " amount."); return; }
        if (plan.legs.isEmpty()) { act.toast("below-min".equals(plan.stopReason) ? "That's below the makers' minimum trade size." : "No liquidity available to fill that right now."); return; }
        String v = validate(plan);
        if (!v.isEmpty()) { act.toast(v); return; }
        host.scanBooks(null);   // AtomiX: a fresh book by the time you confirm; each part re-checks its maker before locking
        final TradingContext market = route();
        final String dest = to.getText().toString().trim();
        final boolean s = plan.sell;

        LinearLayout box = new LinearLayout(act); box.setOrientation(VERTICAL);
        int pd = Design.dp(act, 20); box.setPadding(pd, Design.dp(act, 12), pd, Design.dp(act, 4));
        TextView hd = t(s ? "Sell " + trim6(plan.filledMinima) + " " + c + " in " + plan.legs.size() + (plan.legs.size() == 1 ? " part" : " parts")
                          : "Buy ≈ " + trim6(plan.filledMinima) + " " + c + " for ≈ " + trim6(plan.totalUsdt) + " USDT in " + plan.legs.size() + (plan.legs.size() == 1 ? " part" : " parts"),
                Design.TEXT(), 15f, Design.sansBold());
        hd.setPadding(0, 0, 0, Design.dp(act, 6)); box.addView(hd);
        TextView metrics = t("Avg " + px(plan.avgPrice) + "  ·  worst " + px(plan.worstPrice) + " USDT/" + c
                + (!s && plan.slippagePct > 0 ? "  ·  within " + pct(plan.slippagePct) + "% slippage" : ""), Design.DIM(), 12.5f, Design.sans());
        metrics.setPadding(0, 0, 0, Design.dp(act, 8)); box.addView(metrics);
        for (int i = 0; i < plan.legs.size(); i++) {
            DoorPlan.Leg leg = plan.legs.get(i);
            TextView r = t("Part " + (i + 1) + " · " + leg.minima + " " + c + " @ " + px(leg.price) + " → " + leg.usdt + " USDT · " + leg.maker.signerPk,
                    Design.TEXT(), 12f, Design.mono());
            r.setPadding(0, Design.dp(act, 2), 0, Design.dp(act, 2));
            final String pk = leg.maker.signerPk;   // RULE 1: in full; tap copies it
            if (pk != null && !pk.isEmpty()) r.setOnClickListener(x -> act.copy("Counterparty key", pk));
            box.addView(r);
        }
        TextView totals = t(s ? "Total: sell " + trim6(plan.filledMinima) + " " + c + " · receive ≈ " + trim6(plan.totalUsdt) + " USDT"
                              : "Total: pay ≈ " + trim6(plan.totalUsdt) + " USDT · receive ≈ " + trim6(plan.filledMinima) + " " + c, acc(market), 12.5f, Design.sansBold());
        totals.setPadding(0, Design.dp(act, 8), 0, Design.dp(act, 2)); box.addView(totals);
        if (plan.partial) {
            TextView pw = t("Fills " + trim6(plan.filledMinima) + " of " + trim6(plan.target) + " " + c + " — "
                    + (!s && "slippage".equals(plan.stopReason) ? "the rest is priced beyond your " + pct(plan.slippagePct) + "% slippage." : "the rest isn't available in the book right now."),
                    Design.RED(), 12f, Design.sans());
            pw.setPadding(0, Design.dp(act, 8), 0, Design.dp(act, 2)); box.addView(pw);
        }
        String gas;
        if (s) {
            BigInteger wd = host.gasReserve(EthHtlc.GAS_WITHDRAW), fw = host.gasReserve(EthSend.GAS_ERC20);
            int n = plan.legs.size();
            gas = "ETH gas from your Ethereum wallet: withdraw ≈ " + eth(wd) + " ETH" + (dest.isEmpty() ? "" : " + send ≈ " + eth(fw) + " ETH")
                    + (n == 1 ? "" : " per part, × " + n + " parts");
        } else {
            gas = "Each part is a separate Ethereum transaction — you pay ETH gas " + plan.legs.size() + (plan.legs.size() == 1 ? " time" : " times")
                    + " (≈ " + eth(host.gasReserve(EthHtlc.GAS_LOCK)) + " ETH each, + a one-time USDT approval on first use).";
        }
        gas += " You hold " + (host.ethWei == null ? "—" : eth(host.ethWei)) + " ETH.";
        TextView g = t(gas, Design.DIM2(), 11.5f, Design.sans()); g.setPadding(0, Design.dp(act, 8), 0, Design.dp(act, 2)); box.addView(g);
        String where = s ? (dest.isEmpty() ? "The USDT lands in your Ethereum wallet above." : "The USDT is then sent to " + dest + ".")
                         : (dest.isEmpty() ? "The " + c + " lands in this node’s wallet." : "The " + c + " is then sent to " + dest + ".");
        TextView wh = t(where, Design.TEXT(), 12f, Design.sans()); wh.setPadding(0, Design.dp(act, 8), 0, Design.dp(act, 2)); wh.setTextIsSelectable(true); box.addView(wh);
        TextView note = t((s ? "This locks your " + c + " on Minima now (refund after 144 blocks if the maker never locks)."
                             : "This locks your USDT on Ethereum now (refund after 2 h if the maker never locks).")
                + " Parts run one at a time. If one can't start we stop and you keep the rest; unmatched parts auto-refund after the timelock. Keep this node running.",
                Design.DIM2(), 11.5f, Design.sans());
        note.setPadding(0, Design.dp(act, 8), 0, Design.dp(act, 2)); box.addView(note);
        android.widget.ScrollView sv = new android.widget.ScrollView(act); sv.addView(box);

        act.track(new AlertDialog.Builder(act, Design.dialogTheme()).setTitle(s ? "Review — Sell " + c : "Review — Buy " + c).setView(sv)
                .setPositiveButton("Start swap", (d, w) -> {
                    String err = host.take(market, plan, dest, new DoorHost.Progress() {
                        @Override public void stage(String st) { act.setStage(st); }
                        @Override public void done(String m, boolean error) { act.setStage(""); act.toast(m); if (error) Notifier.alert(act, "AtomiX swap stopped", m); act.repaint(); }
                    });
                    if (err != null) act.toast(err);
                    else { syncing = true; send.setText(""); recv.setText(""); syncing = false; act.ensureKeepAlive(); }
                    render();
                })
                .setNegativeButton("Cancel", null).show());
    }

    // ------------------------------------------------------------------ halted: AtomiX renderMismatchBlocker

    /** The node's seed no longer matches the keys AtomiX holds: trading stops, in-flight swaps keep settling, and the
     *  RESCUE comes before any wipe — the stale ETH key exists only in memory, the claim secrets only in this app. */
    private void paintRescue() {
        boolean h = IdentityWatch.halted();
        int unsettled = 0;
        if (h) for (SwapDb.Swap s : host.swaps()) if (!SwapDb.ST_COMPLETE.equals(s.status) && !SwapDb.ST_REFUNDED.equals(s.status) && !SwapDb.ST_ERROR.equals(s.status)) unsettled++;
        String sig = h + "|" + IdentityWatch.minimaMismatch() + IdentityWatch.ethMismatch() + IdentityWatch.orphanedPk() + IdentityWatch.staleEth() + IdentityWatch.nodeEth() + unsettled;
        if (sig.equals(rescueSig)) return;
        rescueSig = sig;
        rescue.removeAllViews();
        rescue.setVisibility(h ? VISIBLE : GONE);
        if (!h) return;
        LinearLayout c = bigCard(rescue);
        ((LayoutParams) c.getLayoutParams()).topMargin = 0;
        c.addView(t("⛔  Wallet mismatch — AtomiX is halted", Design.RED(), 18f, Design.sansBold()));
        TextView why = t("Your node's seed no longer matches the keys AtomiX is holding. Trading, publishing and new swaps are stopped. "
                + "Swaps already in flight keep settling — that part is safe to leave running.", Design.DIM(), 13f, Design.sans());
        why.setPadding(0, Design.dp(act, 8), 0, Design.dp(act, 12)); c.addView(why);
        StringBuilder d = new StringBuilder();   // RULE 1: the fund-recovery moment — every key in full, selectable, copyable
        if (IdentityWatch.minimaMismatch()) d.append("Minima identity: ORPHANED\n  in use    ").append(IdentityWatch.orphanedPk()).append("\n  your node does not own it\n\n");
        if (IdentityWatch.ethMismatch()) d.append("ETH wallet: STALE\n  in use    ").append(IdentityWatch.staleEth()).append("\n  node now  ").append(IdentityWatch.nodeEth()).append('\n');
        TextView det = t(d.toString().trim(), Design.TEXT(), 12f, Design.mono());
        det.setTextIsSelectable(true);
        det.setOnLongClickListener(v -> { act.copy("Recovery details", det.getText().toString()); return true; });
        c.addView(det);
        TextView warn = t("⚠  Clearing Dexicon's storage or reinstalling it wipes the claim secrets for " + unsettled + " unsettled swap" + (unsettled == 1 ? "" : "s")
                + (IdentityWatch.ethMismatch() ? ", the stale ETH key itself (it is never stored — only your OLD node seed could rebuild it)" : "")
                + ", your pools' local records and your exchange orders. Rescue what you need FIRST: export the key, sweep the funds, and back up your pools "
                + "(② Pools › My pools › Recovery › Back up).\n\nIf this warning returns after a reinstall, Android restored old settings from a backup — clear storage instead.",
                Design.RED(), 12f, Design.sans());
        warn.setPadding(0, Design.dp(act, 14), 0, Design.dp(act, 14)); c.addView(warn);
        WalletActions w = new WalletActions(act);
        if (IdentityWatch.ethMismatch()) {
            c.addView(rescuePill("🔑  Export the stale ETH key", Design.SURFACE2(), Design.TEXT(), w::exportKey));
            c.addView(rescuePill("↑  Sweep funds to the node wallet", Design.SURFACE2(), Design.TEXT(), () -> w.send(IdentityWatch.nodeEth())));
        }
        c.addView(rescuePill("🧹  Clear app storage (App info)", Design.SURFACE2(), Design.TEXT(), () -> act.track(new AlertDialog.Builder(act, Design.dialogTheme())
                .setTitle("Clear Dexicon storage")
                .setMessage("This is the step that always works.\n\nTap “Open App info”, then Storage → Clear storage. That wipes the saved AtomiX identity so it "
                        + "re-derives cleanly from your node.\n\nIt also deletes your swap history, the claim secrets for any unsettled swap, your pools' local "
                        + "records and your exchange orders, and the stale ETH key is not stored anywhere — rescue and back up first.")
                .setPositiveButton("Open App info", (x, y) -> openAppInfo()).setNegativeButton("Cancel", null).show())));
        c.addView(rescuePill("Uninstall & reinstall Dexicon", Design.RED(), 0xFFFFFFFF, () -> act.track(new AlertDialog.Builder(act, Design.dialogTheme())
                .setTitle("Reinstall Dexicon")
                .setMessage("This removes Dexicon and its data — the claim secrets for any unsettled swap, the stale ETH key, your pools' local records and "
                        + "your exchange orders. Only continue once you have exported, swept and backed up what you need.\n\nThen install Dexicon again: it picks "
                        + "up your node's current keys cleanly. If this warning comes back, Android restored old settings — clear storage instead.")
                .setPositiveButton("Uninstall now", (x, y) -> {
                    try { act.startActivity(new android.content.Intent(android.content.Intent.ACTION_DELETE, android.net.Uri.parse("package:" + act.getPackageName()))); }
                    catch (Exception e) { act.toast("Couldn't open the uninstaller — remove Dexicon from Android Settings › Apps"); }
                }).setNegativeButton("Cancel", null).show())));
    }
    private TextView rescuePill(String label, int bg, int fg, Runnable r) {
        TextView p = Design.pill(act, label, bg, fg);
        p.setMinHeight(Design.dp(act, 48)); p.setGravity(Gravity.CENTER);
        p.setOnClickListener(v -> r.run()); Design.pressable(p);
        LayoutParams lp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT); lp.topMargin = Design.dp(act, 8);
        p.setLayoutParams(lp);
        return p;
    }
    private void openAppInfo() {
        try { act.startActivity(new android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:" + act.getPackageName()))); }
        catch (Exception e) { act.toast("Open Android Settings › Apps › Dexicon › Storage › Clear storage"); }
    }

    // ------------------------------------------------------------------ YOUR SWAP (AtomiX swapStages)

    private void paintStages() {
        StringBuilder sig = new StringBuilder();
        List<SwapDb.Swap> all = host.liveListSwaps();
        SwapDb.Swap sw = latestRelevant(all);
        boolean paired = act.paired();
        boolean coinOk = DoorPlan.parseD(host.coinBalance(route()), 0) > 0;
        boolean usdtOk = host.usdtRaw != null && host.usdtRaw.signum() > 0, ethOk = host.ethWei != null && host.ethWei.signum() > 0;
        int more = 0;
        for (SwapDb.Swap s : all) if (!SwapVisibility.isTerminal(s)) more++;
        if (sw != null && !SwapVisibility.isTerminal(sw)) more--;
        sig.append(sell).append(paired).append(coinOk).append(usdtOk).append(ethOk).append(coin()).append(more);
        if (sw != null) sig.append(sw.hash).append(sw.status).append(sw.sellAmount).append(sw.buyAmount);
        if (sig.toString().equals(stagesSig)) return;   // unchanged: no relayout under a finger that is typing
        stagesSig = sig.toString();

        stages.removeAllViews();
        TextView hd = t("YOUR SWAP", Design.DIM(), 11f, Design.sans()); hd.setLetterSpacing(0.06f);
        hd.setPadding(Design.dp(act, 2), 0, 0, Design.dp(act, 8)); stages.addView(hd);
        stages.addView(stage("Node connected", paired ? STG_DONE : STG_WARN));
        if (sell) stages.addView(stage(coin() + " ready to sell", coinOk ? STG_DONE : STG_WARN));
        else {
            stages.addView(stage("USDT ready to spend", usdtOk ? STG_DONE : STG_WARN));
            stages.addView(stage("ETH for gas", ethOk ? STG_DONE : STG_WARN));
        }
        if (sw != null) {
            String st = DoorForwards.label(sw.sellToken), bt = DoorForwards.label(sw.buyToken);
            TextView which = t(sw.sellAmount + " " + st + "  →  " + sw.buyAmount + " " + bt + "   ·   " + (sw.role == null ? "" : sw.role.toLowerCase(Locale.ROOT)),
                    Design.TEXT(), 13f, Design.mono());
            which.setPadding(Design.dp(act, 2), Design.dp(act, 8), 0, Design.dp(act, 6)); stages.addView(which);
            if (SwapDb.ST_REFUNDED.equals(sw.status)) stages.addView(stage("Timed out — " + sw.sellAmount + " " + st + " refunded", STG_WARN));
            else if (SwapDb.ST_ERROR.equals(sw.status)) stages.addView(stage("Stopped — see the swap below for what happened", STG_WARN));
            else {
                int a = stageIndex(sw.status);
                stages.addView(stage("Locked your " + sw.sellAmount + " " + st, legState(1, a)));
                stages.addView(stage("Counterparty locks their side", legState(2, a)));
                stages.addView(stage("Claim your " + sw.buyAmount + " " + bt, legState(3, a)));
                stages.addView(stage("Swap complete", legState(4, a)));
            }
            if (!SwapVisibility.isTerminal(sw)) {
                TextView chk = Design.pill(act, "Check now", Design.SURFACE2(), Design.TEXT());
                LayoutParams cl = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT); cl.topMargin = Design.dp(act, 8);
                chk.setLayoutParams(cl);
                final String hash = sw.hash;
                chk.setOnClickListener(v -> Swaps.checkNow(act, host, hash)); Design.pressable(chk);
                stages.addView(chk);
            }
            if (more > 0) {
                TextView m = t("+ " + more + " more swap" + (more == 1 ? "" : "s") + " in flight — see Activity", Design.DIM2(), 11.5f, Design.sans());
                m.setPadding(Design.dp(act, 2), Design.dp(act, 8), 0, 0);
                m.setOnClickListener(v -> act.showTab(MainActivity.TAB_ACTIVITY)); Design.pressable(m);
                stages.addView(m);
            }
        } else stages.addView(stage("Enter an amount and tap Review to begin", STG_PENDING));
    }

    /** AtomiX latestRelevantSwap: an in-flight swap first; else one that finished in the last 30 s, so you see it land. */
    private static SwapDb.Swap latestRelevant(List<SwapDb.Swap> all) {
        SwapDb.Swap active = null, done = null;
        long now = System.currentTimeMillis();
        for (SwapDb.Swap s : all) {
            if (!SwapVisibility.isTerminal(s)) { if (active == null || s.created > active.created) active = s; }
            else if (now - s.updated <= PANEL_GRACE_MS && (done == null || s.updated > done.updated)) done = s;
        }
        return active != null ? active : done;
    }
    private static int stageIndex(String status) {
        if (SwapDb.ST_LOCKED.equals(status) || SwapDb.ST_CLAIMING.equals(status)) return 3;
        if (SwapDb.ST_COMPLETE.equals(status)) return 5;
        return 2;
    }
    private static int legState(int i, int active) { return i < active ? STG_DONE : i == active ? STG_ACTIVE : STG_PENDING; }

    private LinearLayout stage(String label, int state) {
        LinearLayout row = new LinearLayout(act); row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Design.dp(act, 2), Design.dp(act, 5), Design.dp(act, 2), Design.dp(act, 5));
        View dot = new View(act);
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        if (state == STG_DONE) g.setColor(Design.IN());
        else if (state == STG_ACTIVE) g.setColor(acc(route()));
        else if (state == STG_WARN) g.setColor(WARN);
        else { g.setColor(0); g.setStroke(Math.max(1, Design.dp(act, 1)), Design.DIM2()); }
        dot.setBackground(g);
        LayoutParams dl = new LayoutParams(Design.dp(act, 9), Design.dp(act, 9)); dl.leftMargin = Design.dp(act, 2); dl.rightMargin = Design.dp(act, 12);
        row.addView(dot, dl);
        TextView tv = t(label, state == STG_WARN ? WARN : state == STG_PENDING ? Design.DIM() : Design.TEXT(), 13f, state == STG_ACTIVE ? Design.sansBold() : Design.sans());
        row.addView(tv);
        return row;
    }

    // ------------------------------------------------------------------ door swaps (IN PROGRESS here; the same lines in ACTIVITY / HISTORY)

    private void renderSwaps(LinearLayout into) {
        List<SwapDb.Swap> swaps = host.swaps();
        List<Ledger.Item> live = new java.util.ArrayList<>();
        for (SwapDb.Swap s : host.liveListSwaps()) { Ledger.Item it = hooks.item(s); it.hideable = true; if (it.live) live.add(it); }
        LedgerView.sync(act, into, live, "door", hooks);
        progressLabel.setVisibility(live.isEmpty() ? GONE : VISIBLE);
        finished.setText(swaps.isEmpty() ? "No AtomiX swaps yet. ENTRY brings ERC20 USDT in as " + coin() + "; EXIT takes it out as USDT. MINIMA and MxUSD holders need no AtomiX swap."
                : "Finished swaps are in History ›");
        finished.setTextColor(swaps.isEmpty() ? Design.DIM2() : Design.DIM());
    }

    /** "Refund now" answers, per swap hash, so they survive the repaint that follows. */
    private static final java.util.Map<String, String> ANSWERS = java.util.Collections.synchronizedMap(new java.util.HashMap<>());

    /** A door swap's ledger line + its actions (refund now, retry forward) and the per-second re-derive while it waits
     *  on its maker ({@link DoorStatus}). Shared by ACTIVITY, HISTORY and this tab's IN PROGRESS. */
    public static final class Swaps implements LedgerView.Hooks {
        private final MainActivity act;
        private final DoorHost host;
        private final java.util.Map<String, SwapDb.Swap> byId = new java.util.HashMap<>();
        public Swaps(MainActivity act, DoorHost host) { this.act = act; this.host = host; }

        public Ledger.Item item(SwapDb.Swap s) {
            Ledger.Item it = Ledger.swap(s, host.events(s.hash), host.forward(s.hash), System.currentTimeMillis() / 1000, host.lastMinimaBlock(),
                    host.ethAddress(), ANSWERS.get(s.hash), java.util.TimeZone.getDefault());
            byId.put(it.id, s);
            return it;
        }
        public SwapDb.Swap swap(String id) { return byId.get(id); }
        @Override public Ledger.Item tick(String id) { SwapDb.Swap s = byId.get(id); return s == null ? null : item(s); }
        @Override public void refund(String id) {
            SwapDb.Swap s = byId.get(id);
            if (s == null) return;
            ANSWERS.put(s.hash, "Asking for the refund…");
            act.repaint();
            host.refundNow(s.hash, answer -> { ANSWERS.put(s.hash, answer); act.repaint(); });
        }
        /** AtomiX dismissSwap / confirmHideSwap: a finished card hides at once; a still-open one asks first. */
        @Override public void hide(String id) {
            SwapDb.Swap s = byId.get(id);
            if (s == null) return;
            if (SwapVisibility.isTerminal(s)) { host.dismiss(s.hash); act.repaint(); return; }
            act.track(new AlertDialog.Builder(act, Design.dialogTheme()).setTitle("Hide this swap?")
                    .setMessage("This only removes the card from Swap and Activity. If any funds were locked on-chain they still auto-refund at the timelock — hiding doesn't touch them. "
                            + ("RESPONDER".equals(s.role) ? "It stays in History with Check now; your own lock returns by itself after its timelock." : "It stays in History, with Check now and Refund now."))
                    .setPositiveButton("Hide", (d, w) -> { host.dismiss(s.hash); act.repaint(); })
                    .setNegativeButton("Cancel", null).show());
        }

        @Override public void check(String id) { SwapDb.Swap s = byId.get(id); if (s != null) checkNow(act, host, s.hash); }

        /** AtomiX checkNow + showReport: "Swap status", the inspector's lines, Check again / Close. */
        static void checkNow(MainActivity act, DoorHost host, String hash) {
            act.toast("Checking…");
            host.readBalances(act::repaint);
            host.inspect(hash, lines -> {
                StringBuilder sb = new StringBuilder();
                for (String l : lines) sb.append(DoorHost.mx(l)).append("\n\n");   // RULE 2: the engine says mxUSDT; the token is MxUSD
                TextView t = Ui.text(act, sb.toString().trim(), 13f, Design.TEXT(), Design.sans());
                t.setTextIsSelectable(true);
                int p = Design.dp(act, 20); t.setPadding(p, Design.dp(act, 12), p, Design.dp(act, 4));
                android.widget.ScrollView sv = new android.widget.ScrollView(act); sv.addView(t);
                act.track(new AlertDialog.Builder(act, Design.dialogTheme()).setTitle("Swap status").setView(sv)
                        .setPositiveButton("Check again", (d, w) -> checkNow(act, host, hash))
                        .setNegativeButton("Close", null).show());
                act.repaint();
            });
        }

        @Override public void retry(String id) {
            SwapDb.Swap s = byId.get(id);
            if (s == null) return;
            host.retryForward(s.hash);
            act.toast("Forward queued again — the service retries on its next pass.");
            act.repaint();
        }
    }
}
