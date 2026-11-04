package com.eurobuddha.dexicon;

import android.annotation.SuppressLint;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.eurobuddha.atomix.swap.OtcController;
import com.eurobuddha.atomix.swap.OtcDb;
import com.eurobuddha.atomix.swap.OtcOffer;
import com.eurobuddha.dexicon.doors.DoorHost;
import com.eurobuddha.dexicon.doors.DoorPlan;
import com.eurobuddha.dexicon.ui.Ui;

/**
 * ① OTC — AtomiX's OTC screen (apks/atomix MainActivity renderOtcTab: renderOtcAvailability / renderOtcProviders /
 * renderOtcDeals, otcProposeDialog, otcCounterDialog) redrawn in Dexicon's Design. Negotiation runs in the one engine's
 * OtcController ({@link DoorHost#otc()}); the background maker keeps the availability on the board and answers messages.
 * LP keys are shown in full (tap copies).
 */
@SuppressLint("ViewConstructor")
public final class OtcTab extends LinearLayout {
    private static final long REFRESH_MS = 60_000;
    private final MainActivity act;
    private final DoorHost host;
    private final LinearLayout body;
    private final EditText sellE, buyE;
    private final TextView live, golive, withdraw, status;
    private long lastRefresh;

    public OtcTab(MainActivity act) {
        super(act);
        this.act = act;
        this.host = DoorHost.get(act);
        setOrientation(VERTICAL);
        int pad = Design.dp(act, 12);
        setPadding(pad, pad, pad, pad);

        Ui.why(this, "OTC — A PRIVATE DEAL", "Negotiate a custom size and price directly with a liquidity provider (LP), then settle "
                + "as the same trustless HTLC swap as everything else in ①: each side locks, the secret releases both, and a stalled deal "
                + "refunds. Go live with your own availability and other people can propose deals to you; this phone answers and settles "
                + "in the background.");

        // MY AVAILABILITY — built once, so typing is never interrupted by a repaint (AtomiX keeps a draft for the same reason)
        addView(head("MY AVAILABILITY"));
        LinearLayout box = Ui.card(this);
        box.addView(Ui.label(act, "I SELL up to"));
        sellE = input(); box.addView(sellE);
        box.addView(Ui.label(act, "I BUY up to"));
        buyE = input(); box.addView(buyE);
        sellE.setText(host.prefs().getString("otc_sell_size", "")); buyE.setText(host.prefs().getString("otc_buy_size", ""));
        live = Ui.text(act, "", 12f, Design.DIM(), Design.sans());
        live.setPadding(0, Design.dp(act, 8), 0, Design.dp(act, 8));
        box.addView(live);
        LinearLayout btns = new LinearLayout(act);
        golive = Design.pill(act, "Go live", Design.SURFACE2(), Design.TEXT());
        golive.setMinHeight(Design.dp(act, 44));
        golive.setOnClickListener(v -> {
            double sell = sellE.length() == 0 ? 0 : MarketTab.parseD(sellE.getText().toString(), Double.NaN), buy = buyE.length() == 0 ? 0 : MarketTab.parseD(buyE.getText().toString(), Double.NaN);
            host.publishOtcOffer(sell, buy, this::say);
            act.repaint();
        });
        withdraw = Design.pill(act, "Withdraw", Design.SURFACE2(), Design.TEXT());
        withdraw.setMinHeight(Design.dp(act, 44));
        withdraw.setOnClickListener(v -> { host.withdrawOtcOffer(this::say); act.repaint(); });
        Design.pressable(golive); Design.pressable(withdraw);
        btns.addView(golive);
        LayoutParams wl = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT); wl.leftMargin = Design.dp(act, 8);
        btns.addView(withdraw, wl);
        box.addView(btns);
        status = Ui.text(act, "", 12f, Design.DIM(), Design.sans());
        status.setTextIsSelectable(true);
        box.addView(status, Ui.wide(act, 6, 0));

        body = new LinearLayout(act);
        body.setOrientation(VERTICAL);
        addView(body);
    }

    private void say(String s) { Ui.set(status, DoorHost.mx(s)); status.setTextColor(s.startsWith("✓") ? Design.IN() : Design.DIM()); act.repaint(); }

    private TextView head(String s) {
        TextView t = Ui.text(act, s, 12f, Design.DIM(), Design.sansBold());
        t.setLetterSpacing(0.05f); t.setPadding(0, Design.dp(act, 8), 0, Design.dp(act, 6));
        return t;
    }

    private EditText input() {
        EditText e = new EditText(act);
        MarketTab.decimalInput(e, false);
        e.setTextColor(Design.TEXT()); e.setHintTextColor(Design.DIM2()); e.setTextSize(15f); e.setTypeface(Design.mono());
        return e;
    }

    private String ccy() { return DoorPlan.coin(host.market()); }

    public void render() {
        long now = System.currentTimeMillis();
        if (now - lastRefresh > REFRESH_MS) {
            lastRefresh = now;
            host.readBalances(null);
            host.scanOtcBoard(act::repaint);
        }
        String c = ccy();
        boolean enabled = host.prefs().getBoolean("otc_enable", false);
        sellE.setHint("max " + c + " (blank = off)"); buyE.setHint("max " + c + " (blank = off)");
        Ui.set(live, enabled ? "● Live — LPs can negotiate with you" : "○ Off");
        live.setTextColor(enabled ? Design.IN() : Design.DIM());
        golive.setText(enabled ? "Update" : "Go live");
        golive.setTextColor(DoorsTab.ink(host.market())); golive.setBackground(Design.roundBg(act, DoorsTab.acc(host.market()), 14));
        withdraw.setVisibility(enabled ? VISIBLE : GONE);

        body.removeAllViews();
        if (host.gated()) body.addView(Ui.text(act, "One trading app per phone: while AtomiX or PandaPools is present, nothing is published here.", 11.5f, Design.RED(), Design.sansBold()), Ui.wide(act, 6, 0));
        renderProviders(c);
        renderDeals();
    }

    private void renderProviders(String c) {
        body.addView(head("LIQUIDITY PROVIDERS"));
        boolean any = false;
        for (final OtcOffer o : host.otcBoard().values()) {
            if (!o.hasLiquidity() || o.commsPublicId == null || o.commsPublicId.isEmpty()) continue;
            any = true;
            LinearLayout box = Ui.card(body);
            StringBuilder sides = new StringBuilder();
            if (o.sells()) sides.append("sells up to ").append(DoorHost.sig(o.sellSize)).append(' ').append(c);
            if (o.buys()) { if (sides.length() > 0) sides.append("   ·   "); sides.append("buys up to ").append(DoorHost.sig(o.buySize)).append(' ').append(c); }
            box.addView(Ui.text(act, sides.toString(), 14.5f, Design.TEXT(), Design.sansBold()));
            if (o.signerPk != null && !o.signerPk.isEmpty()) box.addView(Ui.evidence(act, "LP key", o.signerPk));   // RULE 1: in full, tap copies
            LinearLayout row = new LinearLayout(act);
            LayoutParams m = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT); m.rightMargin = Design.dp(act, 8);
            if (o.sells()) row.addView(pill("Buy " + c, true, () -> propose(o, OtcOffer.LP_SELLS_MINIMA)), m);   // LP sells → I buy
            if (o.buys()) row.addView(pill("Sell " + c, false, () -> propose(o, OtcOffer.LP_BUYS_MINIMA)), m);   // LP buys → I sell
            box.addView(row);
        }
        if (!any) body.addView(Ui.text(act, act.paired() ? "No OTC LPs online right now." : "Connect your node to see the OTC board.", 13f, Design.DIM(), Design.sans()), Ui.wide(act, 0, 10));
    }

    private TextView pill(String s, boolean cta, Runnable on) {
        TextView b = Design.pill(act, s, cta ? DoorsTab.acc(host.market()) : Design.SURFACE2(), cta ? DoorsTab.ink(host.market()) : Design.TEXT());
        b.setMinHeight(Design.dp(act, 44));
        b.setOnClickListener(v -> on.run());
        Design.pressable(b);
        return b;
    }

    private void renderDeals() {
        body.addView(head("MY DEALS"));
        boolean any = false;
        for (final OtcDb.Deal d : host.otcDeals()) {
            any = true;
            LinearLayout box = Ui.card(body);
            boolean instig = OtcDb.ROLE_INSTIGATOR.equals(d.role);
            String dir = OtcOffer.LP_SELLS_MINIMA.equals(d.side) ? (instig ? "BUY " : "SELL ") : (instig ? "SELL " : "BUY ");
            String cur = d.market() == null ? "Unknown currency" : DoorPlan.coin(d.market());
            box.addView(Ui.text(act, dir + d.amount + " " + cur + " @ " + d.price + " USDT", 15f, Design.TEXT(), Design.sansBold()));
            TextView st = Ui.text(act, statusText(d), 12.5f, statusColor(d), Design.sans());
            box.addView(st, Ui.wide(act, 4, 2));
            if (d.hash != null && !d.hash.isEmpty()) box.addView(Ui.evidence(act, "Hashlock", d.hash));
            if (!d.inActiveMarket())
                box.addView(Ui.label(act, d.market() == null ? "The currency for this deal is not recognised. Trading actions are disabled." : "Switch to " + cur + " with the ⇄ pill to manage this deal."));
            if (d.inActiveMarket() && OtcDb.TURN_ME.equals(d.whoseTurn) && (OtcDb.ST_PROPOSED.equals(d.status) || OtcDb.ST_COUNTERED.equals(d.status))) {
                LinearLayout row = new LinearLayout(act); row.setPadding(0, Design.dp(act, 8), 0, 0);
                LayoutParams m = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT); m.rightMargin = Design.dp(act, 8);
                row.addView(pill("Accept", true, () -> {
                    if (refuse()) return;
                    String fundErr = host.otcCheckFunds(d.role, d.side, d.amount, d.price);
                    if (fundErr != null) { act.toast(fundErr); return; }
                    // the gate as of the moment ACCEPT is sent (an instigator locks leg 1 on its confirmation; the engine's
                    // own host gate takes over once AtomiX 0.1.91+ is re-vendored — see DoorHost.gated)
                    if (refuse()) return;
                    host.otc().accept(d, res("Accepted"));
                }), m);
                row.addView(pill("Counter", false, () -> counter(d)), m);
                row.addView(pill("Reject", false, () -> host.otc().reject(d, res("Rejected"))));
                box.addView(row);
            }
        }
        if (!any) body.addView(Ui.text(act, "No OTC deals yet.", 13f, Design.DIM(), Design.sans()));
    }

    private OtcController.SendResult res(String ok) {
        return new OtcController.SendResult() {
            @Override public void ok() { post(() -> { act.toast(ok); act.repaint(); }); }
            @Override public void err(String m) { post(() -> { act.toast("OTC: " + DoorHost.mx(m)); act.repaint(); }); }
        };
    }

    static String statusText(OtcDb.Deal d) {
        if (OtcDb.ST_PROPOSED.equals(d.status) || OtcDb.ST_COUNTERED.equals(d.status))
            return OtcDb.TURN_ME.equals(d.whoseTurn) ? "Your move — accept, counter or reject" : "Waiting for the counterparty…";
        if (OtcDb.ST_AGREED.equals(d.status)) return "Agreed — settling";
        if (OtcDb.ST_EXECUTING.equals(d.status)) return "Executing — HTLC legs locking";
        if (OtcDb.ST_COMPLETE.equals(d.status)) return "Complete";
        if (OtcDb.ST_REJECTED.equals(d.status)) return "Rejected";
        if (OtcDb.ST_EXPIRED.equals(d.status)) return "Expired";
        return d.status;
    }
    private static int statusColor(OtcDb.Deal d) {
        if (OtcDb.ST_COMPLETE.equals(d.status)) return Design.IN();
        if (OtcDb.ST_REJECTED.equals(d.status) || OtcDb.ST_EXPIRED.equals(d.status)) return Design.RED();
        return Design.DIM();
    }

    /** Amount + price entry (AtomiX otcProposeDialog / otcCounterDialog share it). */
    private void terms(String title, String amountLabel, String amount, String price, String ok, java.util.function.BiConsumer<Double, Double> send) {
        final com.eurobuddha.atomix.TradingContext m = host.market();
        LinearLayout box = new LinearLayout(act); box.setOrientation(VERTICAL);
        int p = Design.dp(act, 20); box.setPadding(p, Design.dp(act, 8), p, 0);
        box.addView(Ui.label(act, amountLabel));
        EditText amtE = input(); amtE.setText(amount); box.addView(amtE);
        box.addView(Ui.label(act, "Price (USDT per " + ccy() + ")"));
        EditText prE = input(); prE.setText(price); box.addView(prE);
        act.track(new AlertDialog.Builder(act, Design.dialogTheme()).setTitle(title).setView(box)
                .setPositiveButton(ok, (dg, w) -> {
                    if (refuse() || host.market() != m) { if (host.market() != m) act.toast("The market was switched — nothing was sent."); return; }
                    double a = MarketTab.parseD(amtE.getText().toString(), 0), pr = MarketTab.parseD(prE.getText().toString(), 0);
                    if (!(a > 0) || !(pr > 0) || !Double.isFinite(a) || !Double.isFinite(pr) || !OtcController.validTerms(DoorHost.plain(a), DoorHost.sig(pr))) { act.toast("Enter amount + price"); return; }
                    send.accept(a, pr);
                })
                .setNegativeButton("Cancel", null)
                .setOnDismissListener(dg -> act.repaint())
                .show());
    }

    /** Nothing is agreed while the gate is shut or a ⇄ switch is under way. */
    private boolean refuse() {
        if (host.gated()) { act.toast("One trading app per phone — remove AtomiX / PandaPools first."); return true; }
        if (host.switching()) { act.toast(DoorHost.SWITCHING); return true; }
        return false;
    }

    private void propose(OtcOffer lp, String dealSide) {
        if (refuse()) return;
        final double cap = lp.capFor(dealSide);
        String c = ccy();
        terms(OtcOffer.LP_SELLS_MINIMA.equals(dealSide) ? "Buy " + c : "Sell " + c, c + " amount (≤ " + DoorHost.sig(cap) + ")", "", "", "Send offer", (a, pr) -> {
            if (a > cap + 1e-9) { act.toast("Above the LP's max size"); return; }
            String fundErr = host.otcCheckFunds(OtcDb.ROLE_INSTIGATOR, dealSide, DoorHost.plain(a), DoorHost.sig(pr));
            if (fundErr != null) { act.toast(fundErr); return; }
            host.otc().propose(lp, dealSide, DoorHost.plain(a), DoorHost.sig(pr), res("Offer sent"));
        });
    }

    private void counter(OtcDb.Deal d) {
        if (refuse()) return;
        if (!d.inActiveMarket()) { act.toast(DoorHost.mx(OtcController.MARKET_ERROR)); return; }
        terms("Counter-offer", ccy() + " amount", d.amount, d.price, "Send counter", (a, pr) -> {
            String fundErr = host.otcCheckFunds(d.role, d.side, DoorHost.plain(a), DoorHost.sig(pr));
            if (fundErr != null) { act.toast(fundErr); return; }
            host.otc().counter(d, DoorHost.plain(a), DoorHost.sig(pr), res("Counter sent"));
        });
    }
}
