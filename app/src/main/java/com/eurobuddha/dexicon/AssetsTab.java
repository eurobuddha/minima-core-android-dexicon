package com.eurobuddha.dexicon;

import android.annotation.SuppressLint;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.eurobuddha.dexicon.fill.FillPlan;
import com.eurobuddha.dexicon.fill.Tokens;
import com.eurobuddha.dexicon.maker.MyOrder;
import com.eurobuddha.dexicon.relay.RelayStore;

import org.json.JSONObject;

import java.math.BigDecimal;

/** ASSETS tab: available / in orders / settling per token, receive address and Dexicon public id (full, tap-to-copy),
 *  and the DEV dollar-token override (test chains only). */
@SuppressLint("ViewConstructor")
public final class AssetsTab extends LinearLayout {

    private final MainActivity act;
    private final LinearLayout body;

    public AssetsTab(MainActivity act) {
        super(act);
        this.act = act;
        setOrientation(VERTICAL);
        int pad = Design.dp(act, 12);
        setPadding(pad, pad, pad, pad);
        body = new LinearLayout(act); body.setOrientation(VERTICAL);
        addView(body);
    }

    private TextView t(String s, int color, float size, android.graphics.Typeface tf) {
        TextView v = new TextView(getContext());
        v.setText(s); v.setTextColor(color); v.setTextSize(size); v.setTypeface(tf);
        return v;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(getContext());
        c.setOrientation(VERTICAL);
        c.setBackground(Design.card(getContext(), 12));
        int p = Design.dp(getContext(), 12); c.setPadding(p, p, p, p);
        LayoutParams lp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT); lp.bottomMargin = Design.dp(getContext(), 10);
        body.addView(c, lp);
        return c;
    }

    public void render() {
        body.removeAllViews();
        // in my resting orders
        BigDecimal ordersM = BigDecimal.ZERO, ordersU = BigDecimal.ZERO;
        for (MyOrder o : act.myOrders()) {
            if (!o.live()) continue;
            if ("sell".equals(o.side)) ordersM = ordersM.add(Util.dec(o.remaining));
            else ordersU = ordersU.add(FillPlan.up8(Util.dec(o.remaining).multiply(Util.dec(o.price), FillPlan.MC)));
        }
        // settling: what my unconfirmed fills will bring in
        BigDecimal settM = BigDecimal.ZERO, settU = BigDecimal.ZERO;
        for (JSONObject f : act.db().fills(500)) {
            if (!"settling".equals(f.optString("status"))) continue;
            boolean buy = "buy".equals(f.optString("side")), maker = "maker".equals(f.optString("role"));
            BigDecimal m = Util.dec(f.optString("filled", "0"));
            BigDecimal u = maker ? Util.dec(f.optString("received", "0")) : Util.dec(f.optString("got", "0"));
            if (buy) settM = settM.add(m); else settU = settU.add(u);
        }
        assetCard("MINIMA", act.minimaSendable(), act.minimaConfirmed(), ordersM, settM);
        assetCard("MxUSD", act.mxusdSendable(), act.mxusdConfirmed(), ordersU, settU);

        LinearLayout recv = card();
        recv.addView(t("RECEIVE", Design.DIM(), 10f, Design.sansBold()));
        String addr = act.receiveAddress();
        recv.addView(t(addr.isEmpty() ? "Address not loaded" : addr, Design.TEXT(), 10f, Design.mono()));
        recv.addView(t(addr.isEmpty() ? (act.paired() ? "Reading from the node…" : "Pair with Minima Core first") : "Tap to copy", Design.ACCENT(), 9.5f, Design.sans()));
        if (!addr.isEmpty()) { recv.setOnClickListener(v -> act.copy("Address", addr)); Design.pressable(recv); }

        LinearLayout idc = card();
        idc.addView(t("DEXICON IDENTITY (publicId = boxPk‖signPk)", Design.DIM(), 10f, Design.sansBold()));
        String pid = act.identity() == null ? "" : act.identity().publicId();
        idc.addView(t(pid.isEmpty() ? "Not derived yet (needs the node seed: enable Dexicon with WRITE trust)" : pid, Design.TEXT(), 10f, Design.mono()));
        idc.addView(t(pid.isEmpty() ? "" : "Tap to copy · derived from your node seed, HKDF context \"decks\"", Design.ACCENT(), 9.5f, Design.sans()));
        if (!pid.isEmpty()) { idc.setOnClickListener(v -> act.copy("publicId", pid)); Design.pressable(idc); }

        LinearLayout dev = card();
        dev.addView(t("DEV · DOLLAR TOKEN OVERRIDE", Design.DIM(), 10f, Design.sansBold()));
        dev.addView(t("Mainnet MxUSD is " + Tokens.MXUSD_MAINNET + ". On a test chain point the dollar leg at a test token here (empty = mainnet). Never on mainnet.", Design.DIM2(), 9.5f, Design.sans()));
        EditText in = new EditText(getContext());
        in.setHint("0x… test tokenid"); in.setText(RelayStore.mxusdOverride(getContext()));
        in.setTextColor(Design.TEXT()); in.setHintTextColor(Design.DIM2()); in.setTypeface(Design.mono()); in.setTextSize(10f);
        in.setBackground(Design.stroked(getContext(), Design.SURFACE2(), 8));
        int p = Design.dp(getContext(), 8); in.setPadding(p, p, p, p);
        dev.addView(in);
        TextView save = t("SAVE", Design.ACCENT(), 10.5f, Design.mono());
        save.setGravity(android.view.Gravity.CENTER); save.setPadding(0, p, 0, p);
        save.setBackground(Design.stroked(getContext(), Design.SURFACE2(), 10));
        save.setOnClickListener(v -> {
            String tok = in.getText().toString().trim();
            if (!Tokens.setMxUsd(tok)) { act.toast("Not a 0x + 64 hex tokenid"); return; }
            RelayStore.setMxusdOverride(getContext(), tok);
            act.applySettings(); act.toast(tok.isEmpty() ? "Mainnet MxUSD" : "Dollar leg → " + tok); act.repaint();
        });
        Design.pressable(save);
        LayoutParams sl = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT); sl.topMargin = p;
        dev.addView(save, sl);
    }

    private void assetCard(String name, BigDecimal sendable, BigDecimal confirmed, BigDecimal inOrders, BigDecimal settling) {
        LinearLayout c = card();
        c.addView(t(name + " · available to trade", Design.DIM(), 10f, Design.sansBold()));
        if (act.balanceAtMs() <= 0) {
            c.addView(t("—", Design.TEXT(), 18f, Design.monoBold()));
            c.addView(t(act.paired() ? "Waiting for wallet balances from Minima Core." : "Pair with Minima Core (Apps → enable Dexicon).", Design.DIM2(), 10f, Design.sans()));
            return;
        }
        c.addView(t(Util.fmt(sendable), Design.IN(), 18f, Design.monoBold()));
        c.addView(t("confirmed " + Util.fmt(confirmed) + "  ·  updated " + Util.ago(System.currentTimeMillis() - act.balanceAtMs()), Design.DIM2(), 9.5f, Design.mono()));
        c.addView(t("in orders " + Util.fmt(inOrders) + "  ·  settling +" + Util.fmt(settling), Design.ACCENT(), 9.5f, Design.mono()));
        c.addView(t("Orders are backed by wallet coins that stay spendable; the same coin cannot back two orders.", Design.DIM2(), 9f, Design.sans()));
    }
}
