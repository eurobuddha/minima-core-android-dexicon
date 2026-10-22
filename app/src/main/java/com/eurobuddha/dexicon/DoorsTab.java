package com.eurobuddha.dexicon;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
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
import com.eurobuddha.atomix.swap.SwapDb;
import com.eurobuddha.dexicon.doors.DoorForwards;
import com.eurobuddha.dexicon.doors.DoorHost;
import com.eurobuddha.dexicon.doors.DoorPlan;

import org.json.JSONObject;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * DOORS tab — how ERC20 USDT users enter and leave Dexicon through the vendored AtomiX TAKER engine ({@link DoorHost}).
 * The same three panes as the MiniDapp (mds/dexicon ui/doors.js): ENTER (USDT in), EXIT (out to USDT, forwarded to a
 * typed Ethereum address), SWAP (either direction, delivered where the user says). Every pane quotes BOTH AtomiX
 * markets side by side — USDT ⇄ MxUSD and USDT ⇄ MINIMA — and the user taps the route to take. Settlement runs in the
 * background (MakerService → DoorHost.poll). The ETH deposit address and every id are shown in full, tap to copy.
 */
@SuppressLint("ViewConstructor")
public final class DoorsTab extends LinearLayout {

    private static final long REFRESH_MS = 60_000;
    private final MainActivity act;
    private final DoorHost host;
    private final TextView halt, ethAddr, bals, title, desc, details, review, amtLabel, toLabel, dirIn, dirOut;
    private final TextView[] paneBtn = new TextView[3];
    private final LinearLayout dirRow, routes, list;
    private final EditText amt, to;
    private String pane = "enter";
    private boolean inward = true;
    private TradingContext route = TradingContext.MXUSDT;
    private long lastRefresh;
    private static final SimpleDateFormat FMT = new SimpleDateFormat("dd MMM HH:mm:ss", Locale.US);

    public DoorsTab(MainActivity act) {
        super(act);
        this.act = act;
        this.host = DoorHost.get(act);
        setOrientation(VERTICAL);
        int pad = Design.dp(act, 12);
        setPadding(pad, pad, pad, pad);

        halt = t("", Design.RED(), 10.5f, Design.sansBold()); halt.setVisibility(GONE);
        addView(halt);

        LinearLayout dep = card(this);
        dep.addView(t("YOUR ETH DEPOSIT ADDRESS · ERC20 USDT + a little ETH for gas", Design.DIM(), 10f, Design.sansBold()));
        ethAddr = t("—", Design.TEXT(), 12f, Design.mono());
        ethAddr.setOnClickListener(v -> { String a = host.ethAddress(); if (a != null) act.copy("ETH deposit address", a); });
        dep.addView(ethAddr);
        bals = t("", Design.DIM(), 10f, Design.mono());
        dep.addView(bals);
        dep.addView(t("Derived from this node’s seed by AtomiX’s rule (seedrandom:ethbridge), never stored — the same address the AtomiX app on this node uses. Tap to copy the full address.", Design.DIM2(), 9.5f, Design.sans()));

        LinearLayout seg = new LinearLayout(act);
        String[] names = {"ENTRY", "EXIT", "SWAP"}, keys = {"enter", "exit", "swap"};
        for (int i = 0; i < 3; i++) {
            final String k = keys[i];
            paneBtn[i] = segBtn(names[i], () -> { pane = k; render(); });
            seg.addView(paneBtn[i], new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        }
        LayoutParams sl = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT); sl.bottomMargin = Design.dp(act, 10);
        addView(seg, sl);

        LinearLayout p = card(this);
        title = t("", Design.TEXT(), 12f, Design.sansBold()); p.addView(title);
        desc = t("", Design.DIM2(), 9.5f, Design.sans()); p.addView(desc);
        dirRow = new LinearLayout(act);
        dirIn = segBtn("", () -> { inward = true; render(); });
        dirOut = segBtn("", () -> { inward = false; render(); });
        dirRow.addView(dirIn, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        dirRow.addView(dirOut, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        p.addView(dirRow);
        amtLabel = t("", Design.DIM(), 9.5f, Design.mono()); p.addView(amtLabel);
        amt = input("0.00", true); p.addView(amt);
        toLabel = t("", Design.DIM(), 9.5f, Design.mono()); p.addView(toLabel);
        to = input("", false); p.addView(to);
        routes = new LinearLayout(act);
        LayoutParams rl = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT); rl.topMargin = Design.dp(act, 10);
        p.addView(routes, rl);
        details = t("", Design.DIM(), 9.5f, Design.mono());
        details.setPadding(0, Design.dp(act, 8), 0, Design.dp(act, 8));
        details.setTextIsSelectable(true);
        p.addView(details);
        review = t("REVIEW", Design.ON_ACCENT(), 11f, Design.sansBold());
        review.setGravity(Gravity.CENTER); int bp = Design.dp(act, 12); review.setPadding(0, bp, 0, bp);
        review.setBackground(Design.gradientCta(act)); Design.pressable(review);
        review.setOnClickListener(v -> openReview());
        p.addView(review);

        addView(t("ATOMIX SWAPS — every AtomiX swap this node started, both legs’ full transaction ids. Settlement runs in the background.", Design.DIM(), 10f, Design.sansBold()));
        list = new LinearLayout(act); list.setOrientation(VERTICAL);
        LayoutParams ll = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT); ll.topMargin = Design.dp(act, 8);
        addView(list, ll);

        TextWatcher w = new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(Editable e) { quote(); }
        };
        amt.addTextChangedListener(w); to.addTextChangedListener(w);
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
    private TextView segBtn(String s, Runnable on) {
        TextView b = t(s, Design.DIM(), 10.5f, Design.sansBold());
        b.setGravity(Gravity.CENTER); int p = Design.dp(getContext(), 10); b.setPadding(0, p, 0, p); b.setMinHeight(Design.dp(getContext(), 44));
        b.setOnClickListener(v -> on.run());
        return b;
    }
    private void mark(TextView b, boolean on) { b.setTextColor(on ? ink(route) : Design.DIM()); b.setBackground(on ? Design.roundBg(getContext(), acc(route), 10) : null); }
    private EditText input(String hint, boolean decimal) {
        EditText e = new EditText(getContext());
        e.setHint(hint); e.setTextColor(Design.TEXT()); e.setHintTextColor(Design.DIM2()); e.setTypeface(Design.mono()); e.setTextSize(12f);
        e.setInputType(decimal ? InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        e.setBackground(Design.stroked(getContext(), Design.SURFACE2(), 8));
        int p = Design.dp(getContext(), 9); e.setPadding(p, p, p, p);
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

    // ------------------------------------------------------------------ state

    private boolean sell() { return "exit".equals(pane) || ("swap".equals(pane) && !inward); }
    private boolean needTo() { return sell(); }
    private String coin() { return DoorPlan.coin(route); }
    private static String trim6(double v) { return Double.isFinite(v) ? BigDecimal.valueOf(v).setScale(6, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() : "0"; }
    private static String px(double v) { return Double.isFinite(v) && v > 0 ? new BigDecimal(v).round(new java.math.MathContext(6)).stripTrailingZeros().toPlainString() : "0"; }
    private static String eth(BigInteger wei) { return wei == null ? "?" : EthWallet.format(wei, 18, 6); }

    /** Re-read books, balances and settlement at most once a minute while the tab is open. */
    private void refresh(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - lastRefresh < REFRESH_MS) return;
        lastRefresh = now;
        host.poll();
        host.readBalances(this::paintTop);
        host.scanBooks(this::quote);
    }

    public void render() {
        refresh(false);
        paintTop();
        review.setBackground(cta(route)); review.setTextColor(ink(route));
        String[] keys = {"enter", "exit", "swap"};
        for (int i = 0; i < 3; i++) mark(paneBtn[i], keys[i].equals(pane));
        String c = coin();
        dirRow.setVisibility("swap".equals(pane) ? VISIBLE : GONE);
        dirIn.setText("USDT → " + c); dirOut.setText(c + " → USDT"); mark(dirIn, inward); mark(dirOut, !inward);
        switch (pane) {
            case "enter":
                title.setText("ENTRY — USDT → MxUSD or MINIMA");
                desc.setText("For ERC20 USDT holders. Both AtomiX markets are quoted side by side; tap the route you want. Your USDT locks on Ethereum first (2 h refund window); the maker locks the coin; this node claims it into its wallet automatically.");
                break;
            case "exit":
                title.setText("EXIT — MxUSD or MINIMA → USDT");
                desc.setText("Both AtomiX markets, each quoted for the amount typed in its own coin; tap the route you want. Your coin locks on Minima (144-block refund window); the maker locks USDT; this node withdraws it and forwards it by one ERC20 transfer to the address you name (both gas fees shown).");
                break;
            default:
                title.setText("SWAP — the pure lane");
                desc.setText("USDT ⇄ MxUSD or MINIMA through AtomiX, delivered where you say: the coin to this node’s wallet or forwarded by one send to any Minima address (claimed amount less the 0.0001 notify output); USDT to any Ethereum address.");
        }
        amtLabel.setText("AMOUNT · " + (sell() ? c : "USDT (ERC20)"));
        boolean showTo = !"enter".equals(pane);
        toLabel.setVisibility(showTo ? VISIBLE : GONE); to.setVisibility(showTo ? VISIBLE : GONE);
        toLabel.setText(sell() ? "DESTINATION · Ethereum address, 0x + 40 hex" : "DESTINATION · Minima address — leave empty to keep the " + c + " in this node’s wallet");
        to.setHint(sell() ? "0x…" : "Mx…");
        review.setText("REVIEW " + ("enter".equals(pane) ? "ENTRY" : pane.toUpperCase(Locale.ROOT)) + "  →");
        quote();
        renderSwaps(list);
    }

    private void paintTop() {
        boolean h = IdentityWatch.halted();
        halt.setVisibility(h ? VISIBLE : GONE);
        if (h) halt.setText(IdentityWatch.fgLine() + " — new AtomiX swaps are stopped; in-flight swaps keep settling. Rescue funds, then reinstall Dexicon.");
        String a = host.ethAddress();
        ethAddr.setText(a != null ? a : host.bootNote().isEmpty() ? "deriving…" : host.bootNote());
        String usdt = host.usdtRaw == null ? "—" : EthWallet.format(host.usdtRaw, 6, 6);
        bals.setText("ETH " + (host.ethWei == null ? "—" : eth(host.ethWei)) + " · USDT " + usdt
                + " · MxUSD " + nz(host.coinBalance(TradingContext.MXUSDT)) + " · MINIMA " + nz(host.coinBalance(TradingContext.MINIMA))
                + (host.gasWei != null ? " · gas " + EthWallet.format(host.gasWei, 9, 3) + " gwei" : ""));
    }
    private static String nz(String s) { return s == null ? "—" : s; }

    // ------------------------------------------------------------------ quotes (both markets side by side)

    private DoorPlan.Route quoteFor(TradingContext m) {
        return DoorPlan.route(host.book(m), m, sell(), amt.getText().toString().trim(), host.myPublicId(m));
    }

    private void quote() {
        routes.removeAllViews();
        DoorPlan.Route picked = null;
        for (TradingContext m : DoorHost.MARKETS) {
            DoorPlan.Route r = quoteFor(m);
            if (m == route) picked = r;
            LayoutParams lp = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
            lp.rightMargin = m == DoorHost.MARKETS[0] ? Design.dp(getContext(), 4) : 0;
            lp.leftMargin = m == DoorHost.MARKETS[0] ? 0 : Design.dp(getContext(), 4);
            routes.addView(routeCard(r), lp);
        }
        if (picked == null) return;
        if (picked.plan == null) { details.setTextColor(picked.err == null ? Design.DIM() : Design.RED()); details.setText(picked.err == null ? "" : coin() + " route: " + picked.err); review.setAlpha(0.5f); return; }
        String v = validate(picked.plan);
        details.setTextColor(v.isEmpty() ? Design.TEXT() : Design.RED());
        details.setText(planText(picked.plan) + (v.isEmpty() ? "" : "\n\n" + v));
        review.setAlpha(v.isEmpty() ? 1f : 0.5f);
    }

    private View routeCard(DoorPlan.Route r) {
        boolean on = r.market == route, s = r.sell;
        LinearLayout c = new LinearLayout(getContext());
        c.setOrientation(VERTICAL);
        int p = Design.dp(getContext(), 9); c.setPadding(p, p, p, p);
        c.setBackground(on ? Design.roundBg(getContext(), soft(r.market), 10) : Design.stroked(getContext(), Design.SURFACE2(), 10));
        c.addView(t((on ? "● " : "○ ") + (s ? r.coin + " → USDT" : "USDT → " + r.coin), on ? acc(r.market) : Design.DIM(), 10f, Design.sansBold()));
        if (r.plan != null) {
            c.addView(t(s ? "≈ " + trim6(r.plan.totalUsdt) + " USDT" : trim6(r.plan.filledMinima) + " " + r.coin, Design.TEXT(), 13f, Design.monoBold()));
            c.addView(t(s ? "for " + trim6(r.plan.filledMinima) + " " + r.coin : "for ≈ " + trim6(r.plan.totalUsdt) + " USDT", Design.DIM(), 9.5f, Design.mono()));
            c.addView(t("avg " + px(r.plan.avgPrice) + " USDT/" + r.coin + " · " + r.plan.legs.size() + (r.plan.legs.size() == 1 ? " part" : " parts") + (r.plan.partial ? " · PARTIAL" : ""), Design.DIM(), 9.5f, Design.mono()));
        } else if (r.err != null) c.addView(t(r.err, Design.RED(), 9.5f, Design.mono()));
        else {
            c.addView(t(r.best > 0 ? px(r.best) + " USDT/" + r.coin : "no quote", Design.TEXT(), 13f, Design.monoBold()));
            c.addView(t(r.best > 0 ? "best " + (s ? "bid" : "ask") : "no maker " + (s ? "buying" : "selling"), Design.DIM(), 9.5f, Design.mono()));
        }
        c.addView(t("ceiling ~" + trim6(r.depth) + " " + r.coin + " · " + r.makers + (r.makers == 1 ? " maker" : " makers"), Design.DIM2(), 9f, Design.mono()));
        if (s) c.addView(t("you hold " + nz(host.coinBalance(r.market)) + " " + r.coin, Design.DIM2(), 9f, Design.mono()));
        c.setOnClickListener(v -> { route = r.market; render(); });
        Design.pressable(c);
        return c;
    }

    private String planText(DoorPlan.Plan p) {
        String c = coin(), dest = to.getText().toString().trim();
        StringBuilder b = new StringBuilder();
        b.append(p.sell ? "You give " + trim6(p.filledMinima) + " " + c + " → receive ≈ " + trim6(p.totalUsdt) + " USDT"
                        : "You pay ≈ " + trim6(p.totalUsdt) + " USDT → receive " + trim6(p.filledMinima) + " " + c).append("  (" + c + " market)\n");
        b.append("Avg ").append(px(p.avgPrice)).append(" · worst ").append(px(p.worstPrice)).append(" USDT per ").append(c).append(" · ")
                .append(p.legs.size()).append(p.legs.size() == 1 ? " part" : " parts").append(p.sell ? "" : " · within " + (int) (DoorPlan.BUY_SLIPPAGE * 100) + "% slippage").append('\n');
        if (p.partial) b.append("PARTIAL: fills ").append(trim6(p.filledMinima)).append(" of ").append(trim6(p.target)).append(' ').append(c)
                .append("slippage".equals(p.stopReason) ? " — the rest is priced beyond the slippage cap.\n" : " — the rest is not in the book right now.\n");
        for (int i = 0; i < p.legs.size(); i++) {
            DoorPlan.Leg l = p.legs.get(i);
            b.append("Part ").append(i + 1).append(": ").append(l.minima).append(' ').append(c).append(" @ ").append(px(l.price)).append(" → ").append(l.usdt).append(" USDT · maker ").append(l.maker.signerPk).append('\n');
        }
        if (p.sell) {
            BigInteger wd = host.gasReserve(EthHtlc.GAS_WITHDRAW), fw = host.gasReserve(EthSend.GAS_ERC20);
            b.append("ETH gas from the deposit address: withdraw ≈ ").append(eth(wd)).append(" ETH").append(dest.isEmpty() ? "" : " + forward ≈ " + eth(fw) + " ETH");
        } else {
            BigInteger lk = host.gasReserve(EthHtlc.GAS_LOCK);
            b.append("ETH gas from the deposit address: ≈ ").append(eth(lk)).append(" ETH per part (+ a one-time USDT approval on first use)");
        }
        b.append(" (you hold ").append(host.ethWei == null ? "—" : eth(host.ethWei)).append(" ETH)\n");
        if (!dest.isEmpty()) b.append(p.sell ? "USDT" : c).append(" will be forwarded to ").append(dest);
        else if (!p.sell) b.append(c).append(" lands in this node’s wallet.");
        return b.toString();
    }

    /** Fund + address checks for the PICKED route; every unreadable balance fails CLOSED. "" when OK. */
    private String validate(DoorPlan.Plan p) {
        String c = coin(), dest = to.getText().toString().trim();
        if (IdentityWatch.halted()) return "Halted: this node no longer owns the keys AtomiX uses here.";
        if (host.busy()) return "An AtomiX swap is still starting — wait for it to finish.";
        if (!dest.isEmpty() && (p.sell ? !DoorForwards.isEthAddr(dest) : !DoorForwards.isMxAddr(dest)))
            return p.sell ? "Destination must be an Ethereum address (0x + 40 hex)." : "Destination must be a Minima address (Mx… or 0x + 64 hex).";
        if (needTo() && dest.isEmpty()) return "Enter the destination address.";
        if (host.ethWei == null) return "Cannot read the ETH balance of the deposit address right now — try again shortly.";
        if (p.sell) {
            String have = host.coinBalance(route);
            if (have == null) return "Cannot read your " + c + " balance right now.";
            if (p.filledMinima > Double.parseDouble(have) + 1e-9) return "You hold " + have + " " + c + " — reduce the amount.";
            BigInteger g = host.gasReserve(EthHtlc.GAS_WITHDRAW.add(dest.isEmpty() ? BigInteger.ZERO : EthSend.GAS_ERC20));
            if (g == null) return "Cannot read the Ethereum gas price right now.";
            if (g.compareTo(host.ethWei) > 0) return "Not enough ETH at the deposit address for the withdraw" + (dest.isEmpty() ? "" : " + forward") + " gas — send a little ETH there first.";
        } else {
            if (host.usdtRaw == null) return "Cannot read your USDT balance right now.";
            if (EthSend.parseUnits(trim6(p.totalUsdt), 6).compareTo(host.usdtRaw) > 0) return "You hold " + EthWallet.format(host.usdtRaw, 6, 6) + " USDT at the deposit address — reduce the amount or top it up.";
            BigInteger g = host.gasReserve(EthHtlc.GAS_LOCK.multiply(BigInteger.valueOf(p.legs.size())));
            if (g == null) return "Cannot read the Ethereum gas price right now.";
            if (g.compareTo(host.ethWei) > 0) return "Not enough ETH at the deposit address for " + p.legs.size() + " lock transaction" + (p.legs.size() == 1 ? "" : "s") + " — send a little ETH there first.";
        }
        return "";
    }

    // ------------------------------------------------------------------ review → take

    private void openReview() {
        if (!host.ready()) { act.toast("AtomiX not ready — " + host.bootNote()); return; }
        DoorPlan.Route r = quoteFor(route);
        if (amt.getText().toString().trim().isEmpty()) { act.toast("Enter an amount first."); return; }
        if (r.plan == null) { act.toast(coin() + " route: " + r.err); return; }
        String v = validate(r.plan);
        if (!v.isEmpty()) { act.toast(v); return; }
        final DoorPlan.Plan plan = r.plan;
        final TradingContext market = route;
        final String dest = to.getText().toString().trim(), c = coin();
        String head = pane.toUpperCase(Locale.ROOT) + " — " + (plan.sell ? "sell " + trim6(plan.filledMinima) + " " + c + " for ≈ " + trim6(plan.totalUsdt) + " USDT"
                : "buy " + trim6(plan.filledMinima) + " " + c + " for ≈ " + trim6(plan.totalUsdt) + " USDT");
        String body = planText(plan) + "\n\n" + (plan.sell ? "This locks your " + c + " on Minima now (block + 144 refund window)." : "This locks your USDT on Ethereum now (2 h refund window).")
                + " The background service completes the swap; keep this node running.";
        TextView msg = t(body, Design.TEXT(), 10.5f, Design.mono());
        int p = Design.dp(getContext(), 18); msg.setPadding(p, p / 2, p, 0); msg.setTextIsSelectable(true);
        android.widget.ScrollView sv = new android.widget.ScrollView(getContext()); sv.addView(msg);
        new AlertDialog.Builder(act, Design.dialogTheme()).setTitle(head).setView(sv)
                .setPositiveButton("START", (d, w) -> {
                    String err = host.take(market, plan, dest, new DoorHost.Progress() {
                        @Override public void stage(String s) { act.setStage(s); }
                        @Override public void done(String m, boolean error) { act.setStage(""); act.toast(m); if (error) Notifier.alert(act, "AtomiX swap stopped", m); act.repaint(); }
                    });
                    if (err != null) act.toast(err); else amt.setText("");
                    render();
                })
                .setNegativeButton("Back", null).show();
    }

    // ------------------------------------------------------------------ door swap cards (shared with ACTIVITY)

    private void renderSwaps(LinearLayout into) {
        into.removeAllViews();
        List<SwapDb.Swap> swaps = host.swaps();
        if (swaps.isEmpty()) {
            into.addView(t("No AtomiX swaps yet. ENTRY brings ERC20 USDT in as MxUSD or MINIMA; EXIT takes either out as USDT. MINIMA and MxUSD holders need no AtomiX swap.", Design.DIM2(), 10f, Design.sans()));
            return;
        }
        for (SwapDb.Swap s : swaps) into.addView(swapCard(act, host, s));
    }

    /** One door swap: stage pill, plain status, then every id in full on its own line, tap to copy. */
    static View swapCard(MainActivity act, DoorHost host, SwapDb.Swap s) {
        JSONObject f = host.forward(s.hash);
        boolean in = "ERC20_TO_MINIMA".equals(s.direction);
        String pill = DoorForwards.pill(s, f);
        boolean usd = "MxUSD".equals(DoorForwards.label(s.buyToken)) || "MxUSD".equals(DoorForwards.label(s.sellToken));
        int live = usd ? USD_GREEN : Design.ACCENT();   // a dollar swap in progress is AtomiX green, a MINIMA one orange
        int tone = SwapDb.ST_COMPLETE.equals(s.status) ? Design.IN() : (SwapDb.ST_REFUNDED.equals(s.status) || SwapDb.ST_ERROR.equals(s.status)) ? Design.RED() : live;
        LinearLayout c = new LinearLayout(act);
        c.setOrientation(VERTICAL);
        c.setBackground(Design.card(act, 10));
        int p = Design.dp(act, 10); c.setPadding(p, p, p, p);
        LayoutParams lp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT); lp.bottomMargin = Design.dp(act, 9);
        c.setLayoutParams(lp);
        LinearLayout top = new LinearLayout(act); top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(tv(act, (in ? "ATOMIX ENTRY " : "ATOMIX EXIT ") + s.sellAmount + " " + DoorForwards.label(s.sellToken) + " → " + s.buyAmount + " " + DoorForwards.label(s.buyToken), 11f, tone, Design.monoBold()),
                new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        top.addView(Design.pill(act, pill, Design.SURFACE2(), tone));
        c.addView(top);
        c.addView(tv(act, "ATOMIX TAKER · " + FMT.format(new Date(s.created)), 9.5f, Design.DIM(), Design.mono()));
        c.addView(tv(act, DoorForwards.statusText(s, f), 10.5f, Design.TEXT(), Design.mono()));
        ident(act, c, "swap", s.hash);
        ident(act, c, "eth contract", s.contractId);
        ident(act, c, "maker", s.counterparty);
        List<List<String>> tx = DoorForwards.txIds(host.events(s.hash));
        for (String id : tx.get(0)) ident(act, c, "eth tx", id);
        for (String id : tx.get(1)) ident(act, c, "minima tx", id);
        if (f != null) {
            ident(act, c, "forward to", f.optString("to"));
            ident(act, c, "forward tx", f.optString("tx"));
            String st = f.optString("status");
            if (DoorForwards.FAILED.equals(st) || DoorForwards.UNCERTAIN.equals(st)) {
                TextView rb = tv(act, "RETRY FORWARD", 10f, live, Design.sansBold());
                int bp = Design.dp(act, 8); rb.setPadding(bp, bp, bp, bp);
                rb.setOnClickListener(v -> { host.retryForward(s.hash); act.toast("Forward queued again — the service retries on its next pass."); act.repaint(); });
                c.addView(rb);
            }
        }
        return c;
    }
    private static TextView tv(MainActivity act, String s, float size, int color, android.graphics.Typeface tf) {
        TextView t = new TextView(act); t.setText(s); t.setTextSize(size); t.setTextColor(color); t.setTypeface(tf); return t;
    }
    private static void ident(MainActivity act, LinearLayout c, String label, String value) {
        if (value == null || value.isEmpty()) return;
        TextView t = tv(act, label + " " + value, 8.5f, Design.DIM2(), Design.mono());
        t.setOnClickListener(v -> act.copy(label, value));
        c.addView(t);
    }
}
