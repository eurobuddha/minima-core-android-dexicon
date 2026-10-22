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

/** ASSETS tab: available / in orders / settling per token, the Minima receive address (Mx…), the node's Ethereum wallet
 *  (address, ETH, ERC20 USDT — the AtomiX tab's deposit address), the Dexicon public id (all full, tap-to-copy), and —
 *  folded under ADVANCED — the dollar-token override that only a test chain needs. */
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
        Funds f = Funds.of(act);   // the same numbers the TRADE funds panel shows
        assetCard("MINIMA", Tokens.MINIMA, act.minimaCoins, f.minimaAvailable, act.minimaConfirmed(), f.minimaInOrders, f.minimaSettling);
        assetCard("MxUSD", Tokens.MXUSD(), act.mxusdCoins, f.mxusdAvailable, act.mxusdConfirmed(), f.mxusdInOrders, f.mxusdSettling);

        LinearLayout recv = card();
        recv.addView(t("RECEIVE · MINIMA AND MxUSD", Design.DIM(), 10f, Design.sansBold()));
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

        ethCard();

        // ADVANCED (folded): the dollar-token override exists only for TEST chains, where mainnet MxUSD does not exist and
        // a test token stands in for it. On mainnet it must stay empty.
        LinearLayout adv = card();
        adv.addView(t(advOpen ? "ADVANCED  ▾" : "ADVANCED  ▸", Design.DIM(), 10f, Design.sansBold()));
        adv.setOnClickListener(v -> { advOpen = !advOpen; render(); });
        Design.pressable(adv);
        if (!advOpen) return;
        adv.addView(t("Dollar token override — for TEST chains only. Mainnet MxUSD (" + Tokens.MXUSD_MAINNET + ") does not exist on a test chain, so a test token can stand in for it there. Leave it empty on mainnet.", Design.DIM2(), 9.5f, Design.sans()));
        EditText in = new EditText(getContext());
        in.setHint("0x… test tokenid"); in.setText(RelayStore.mxusdOverride(getContext()));
        in.setTextColor(Design.TEXT()); in.setHintTextColor(Design.DIM2()); in.setTypeface(Design.mono()); in.setTextSize(10f);
        in.setBackground(Design.stroked(getContext(), Design.SURFACE2(), 8));
        int p = Design.dp(getContext(), 8); in.setPadding(p, p, p, p);
        in.setOnClickListener(v -> {});   // typing must not fold the card
        adv.addView(in);
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
        adv.addView(save, sl);
    }

    private boolean advOpen;
    private static long ethReadMs;

    /** The node's Ethereum wallet (seedrandom:ethbridge — the same key the AtomiX tab and the AtomiX app derive): address,
     *  ETH for gas and ERC20 USDT. Read through the AtomiX engine at most once a minute. */
    private void ethCard() {
        com.eurobuddha.dexicon.doors.DoorHost host = com.eurobuddha.dexicon.doors.DoorHost.get(act);
        if (System.currentTimeMillis() - ethReadMs > 60_000) { ethReadMs = System.currentTimeMillis(); host.readBalances(() -> act.repaint()); }
        LinearLayout c = card();
        c.addView(t("ETHEREUM · ERC20 USDT + ETH for gas", Design.DIM(), 10f, Design.sansBold()));
        c.addView(t("USDT " + (host.usdtRaw == null ? "—" : com.eurobuddha.atomix.eth.EthWallet.format(host.usdtRaw, 6, 6)), Design.IN(), 18f, Design.monoBold()));
        c.addView(t("ETH " + (host.ethWei == null ? "—" : com.eurobuddha.atomix.eth.EthWallet.format(host.ethWei, 18, 6)), Design.TEXT(), 12f, Design.mono()));
        String a = host.ethAddress();
        c.addView(t(a != null ? a : host.bootNote().isEmpty() ? "deriving…" : host.bootNote(), Design.TEXT(), 10f, Design.mono()));
        c.addView(t(a != null ? "Tap to copy · derived from this node's seed, the same address as the ATOMIX tab and the AtomiX app" : "", Design.ACCENT(), 9.5f, Design.sans()));
        if (a != null) { c.setOnClickListener(v -> act.copy("Ethereum address", a)); Design.pressable(c); }
    }

    private void assetCard(String name, String tokenid, int coins, BigDecimal sendable, BigDecimal confirmed, BigDecimal inOrders, BigDecimal settling) {
        LinearLayout c = card();
        c.addView(t(name + " · available to trade", Design.DIM(), 10f, Design.sansBold()));
        if (act.balanceAtMs() <= 0) {
            c.addView(t("—", Design.TEXT(), 18f, Design.monoBold()));
            c.addView(t(act.paired() ? "Waiting for wallet balances from Minima Core." : "Pair with Minima Core (Apps → enable Dexicon).", Design.DIM2(), 10f, Design.sans()));
            return;
        }
        c.addView(t(Util.fmtBal(sendable), Design.IN(), 18f, Design.monoBold()));
        c.addView(t("confirmed " + Util.fmtBal(confirmed) + "  ·  updated " + Util.ago(System.currentTimeMillis() - act.balanceAtMs()), Design.DIM2(), 9.5f, Design.mono()));
        c.addView(t("in orders " + Util.fmtBal(inOrders) + "  ·  settling +" + Util.fmtBal(settling), Design.ACCENT(), 9.5f, Design.mono()));
        c.addView(t(coins + (coins == 1 ? " coin" : " coins") + " · orders are backed by wallet coins that stay spendable; the same coin cannot back two orders.", Design.DIM2(), 9f, Design.sans()));
        // the family's wallet tools (PandaPools consolidate, PandaDEX split) — no trip to another app
        LinearLayout tools = new LinearLayout(getContext());
        TextView cons = tool("CONSOLIDATE"), split = tool("SPLIT ×" + WalletTools.SPLIT_COUNT);
        cons.setOnClickListener(v -> WalletTools.consolidate(act, tokenid, name, coins));
        split.setOnClickListener(v -> WalletTools.split(act, tokenid, name, sendable));
        LayoutParams l1 = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f); l1.rightMargin = Design.dp(getContext(), 6);
        tools.addView(cons, l1); tools.addView(split, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        LayoutParams tl = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT); tl.topMargin = Design.dp(getContext(), 8);
        c.addView(tools, tl);
    }

    private TextView tool(String label) {
        TextView b = t(label, Design.ACCENT(), 10.5f, Design.monoBold());
        b.setGravity(android.view.Gravity.CENTER);
        int p = Design.dp(getContext(), 8); b.setPadding(0, p, 0, p);
        b.setBackground(Design.stroked(getContext(), Design.SURFACE2(), 10));
        Design.pressable(b);
        return b;
    }
}
