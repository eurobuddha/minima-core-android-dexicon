package com.eurobuddha.dexicon;

import android.annotation.SuppressLint;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.eurobuddha.atomix.TradingContext;
import com.eurobuddha.atomix.eth.EthWallet;
import com.eurobuddha.dexicon.doors.DoorHost;
import com.eurobuddha.dexicon.fill.Tokens;
import com.eurobuddha.dexicon.identity.DexIdentity;
import com.eurobuddha.dexicon.relay.RelayStore;
import com.eurobuddha.dexicon.ui.Ui;

import java.math.BigDecimal;

/** ASSETS tab: what you hold and who you are. Each asset card shows what is available to trade and folds the rest
 *  (confirmed, in orders, settling, coins, CONSOLIDATE / SPLIT); the Minima receive address and the node's Ethereum
 *  wallet in full with a Copy button; YOUR TRADING ID in plain English with the full id and keys folded; and — folded
 *  under Advanced — the dollar-token override that only a test chain needs. */
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

    private TextView t(String s, int color, float size, android.graphics.Typeface tf) { return Ui.text(getContext(), s, size, color, tf); }
    private LinearLayout card() { return Ui.card(body); }

    /** A full value on its own line: wrapped mono, tap copies the complete value, long-press selects. */
    private TextView full(String label, String value) {
        TextView v = t(value, Design.TEXT(), 11.5f, Design.mono());
        v.setLineSpacing(0, 1.2f);
        v.setTextIsSelectable(true);
        v.setFocusableInTouchMode(false);   // the first tap copies
        v.setOnClickListener(x -> act.copy(label, value));
        return v;
    }

    public void render() {
        body.removeAllViews();
        Funds f = Funds.of(act);   // the same numbers the TRADE funds panel shows
        assetCard("MINIMA", Tokens.MINIMA, act.minimaCoins, f.minimaAvailable, act.minimaConfirmed(), f.minimaInOrders, f.minimaSettling);
        assetCard("MxUSD", Tokens.MXUSD(), act.mxusdCoins, f.mxusdAvailable, act.mxusdConfirmed(), f.mxusdInOrders, f.mxusdSettling);

        LinearLayout recv = card();
        String addr = act.receiveAddress();
        recv.addView(Ui.copyRow(act, "RECEIVE MINIMA OR MxUSD", "Address", () -> addr));
        recv.addView(addr.isEmpty() ? Ui.para(act, act.paired() ? "Reading from the node…" : "Pair with Minima Core first") : full("Address", addr), Ui.wide(act, 6, 0));

        ethCard();
        identityCard();

        // Advanced (folded): the dollar-token override exists only for TEST chains, where mainnet MxUSD does not exist and
        // a test token stands in for it. On mainnet it must stay empty.
        LinearLayout adv = card();
        LinearLayout box = new LinearLayout(act); box.setOrientation(VERTICAL);
        TextView head = t("", Design.DIM(), 12f, Design.sansBold());
        head.setMinHeight(Design.dp(act, 44)); head.setGravity(Gravity.CENTER_VERTICAL);
        adv.addView(head); adv.addView(box);
        Ui.fold(head, box, head, "Advanced", "fold_assets_advanced");
        box.addView(Ui.para(act, "Dollar token override — for TEST chains only. Mainnet MxUSD (" + Tokens.MXUSD_MAINNET + ") does not exist on a test chain, so a test token can stand in for it there. Leave it empty on mainnet."));
        EditText in = new EditText(getContext());
        in.setHint("0x… test tokenid"); in.setText(RelayStore.mxusdOverride(getContext()));
        in.setTextColor(Design.TEXT()); in.setHintTextColor(Design.DIM2()); in.setTypeface(Design.mono()); in.setTextSize(11f);
        in.setBackground(Design.stroked(getContext(), Design.SURFACE2(), 8));
        int p = Design.dp(getContext(), 8); in.setPadding(p, p, p, p);
        box.addView(in, Ui.wide(act, 8, 0));
        TextView save = Ui.button(act, "Save");
        save.setOnClickListener(v -> {
            String tok = in.getText().toString().trim();
            if (!Tokens.setMxUsd(tok)) { act.toast("Not a 0x + 64 hex tokenid"); return; }
            RelayStore.setMxusdOverride(getContext(), tok);
            act.applySettings(); act.toast(tok.isEmpty() ? "Mainnet MxUSD" : "Dollar leg → " + tok); act.repaint();
        });
        box.addView(save, Ui.wide(act, 8, 0));
    }

    private static long ethReadMs;

    /** The node's Ethereum wallet (seedrandom:ethbridge — the same key the AtomiX tab and the AtomiX app derive): address,
     *  ETH for gas and ERC20 USDT. Read through the AtomiX engine at most once a minute. */
    private void ethCard() {
        DoorHost host = DoorHost.get(act);
        if (System.currentTimeMillis() - ethReadMs > 60_000) { ethReadMs = System.currentTimeMillis(); host.readBalances(() -> act.repaint()); }
        LinearLayout c = card();
        String a = host.ethAddress();
        c.addView(Ui.copyRow(act, "ETHEREUM", "Ethereum address", host::ethAddress));
        LinearLayout row = new LinearLayout(act); row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(t("USDT " + (host.usdtRaw == null ? "—" : EthWallet.format(host.usdtRaw, 6, 6)) + " · ETH " + (host.ethWei == null ? "—" : EthWallet.format(host.ethWei, 18, 6)),
                Design.TEXT(), 13f, Design.mono()), new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        c.addView(row, Ui.wide(act, 4, 0));
        Ui.info(row, c, "info_assets_eth", "ERC20 USDT and a little ETH for gas. This wallet is derived from this node's seed — the same address as the ATOMIX tab and the AtomiX app — and is never stored.");
        c.addView(a != null ? full("Ethereum address", a) : Ui.para(act, host.bootNote().isEmpty() ? "deriving…" : host.bootNote()), Ui.wide(act, 6, 0));
    }

    /** YOUR TRADING ID: what it is in plain words; the full id and its two keys folded. */
    private void identityCard() {
        LinearLayout c = card();
        DexIdentity me = act.identity();
        String pid = me == null ? "" : me.publicId();
        c.addView(Ui.copyRow(act, "YOUR TRADING ID", "Trading ID", () -> pid));
        c.addView(Ui.para(act, pid.isEmpty() ? "Not derived yet — it needs the node seed: enable Dexicon with WRITE trust."
                : "Other traders see this ID when you trade on the fast book. It is made from your node's seed, so it stays the same if you reinstall. It is not an address — never send coins to it."), Ui.wide(act, 6, 0));
        if (pid.isEmpty()) return;
        TextView head = t("", Design.DIM(), 12f, Design.sansBold());
        head.setMinHeight(Design.dp(act, 44)); head.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout box = new LinearLayout(act); box.setOrientation(VERTICAL);
        c.addView(head); c.addView(box);
        Ui.fold(head, box, head, "Show the full ID and keys", "fold_assets_ids");
        box.addView(Ui.evidence(act, "Trading ID", pid));
        String[] k = Ledger.keysOf(pid);
        if (k != null) {
            box.addView(Ui.evidence(act, "Encryption key (sealed messages to you)", k[0]));
            box.addView(Ui.evidence(act, "Signing key (proves your orders are yours)", k[1]));
        }
        DoorHost host = DoorHost.get(act);
        for (TradingContext m : DoorHost.MARKETS) {
            String id = host.myPublicId(m);
            if (id != null && !id.isEmpty()) box.addView(Ui.evidence(act, "AtomiX ID · " + (m == TradingContext.MXUSDT ? "MxUSD" : "MINIMA") + " market", id));
        }
        box.addView(Ui.para(act, "Derived with HKDF context \"" + DexIdentity.CONTEXT + "\" from the node seed."), Ui.wide(act, 4, 0));
    }

    private void assetCard(String name, String tokenid, int coins, BigDecimal sendable, BigDecimal confirmed, BigDecimal inOrders, BigDecimal settling) {
        LinearLayout c = card();
        LinearLayout head = new LinearLayout(act); head.setGravity(Gravity.CENTER_VERTICAL);
        head.setMinimumHeight(Design.dp(act, 44));
        head.addView(t(name, Design.TEXT(), 13f, Design.sansBold()), new LayoutParams(Design.dp(act, 72), LayoutParams.WRAP_CONTENT));
        boolean known = act.balanceAtMs() > 0;
        head.addView(t(known ? Util.fmtBal(sendable) : "—", Design.TEXT(), 16f, Design.monoBold()), new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        c.addView(head);
        if (!known) {
            c.addView(Ui.para(act, act.paired() ? "Waiting for wallet balances from Minima Core." : "Pair with Minima Core (Apps → enable Dexicon)."));
            return;
        }
        TextView chev = t("", Design.DIM2(), 11.5f, Design.sans());
        head.addView(chev);
        LinearLayout box = new LinearLayout(act); box.setOrientation(VERTICAL);
        c.addView(box);
        Ui.fold(head, box, chev, "available to trade", "fold_assets_" + name);
        box.addView(t("confirmed " + Util.fmtBal(confirmed) + "  ·  updated " + Util.ago(System.currentTimeMillis() - act.balanceAtMs()), Design.DIM(), 11.5f, Design.mono()), Ui.wide(act, 6, 0));
        box.addView(t("in orders " + Util.fmtBal(inOrders) + "  ·  settling +" + Util.fmtBal(settling), Design.DIM(), 11.5f, Design.mono()), Ui.wide(act, 4, 0));
        box.addView(Ui.para(act, coins + (coins == 1 ? " coin" : " coins") + " · orders are backed by wallet coins that stay spendable; the same coin cannot back two orders."), Ui.wide(act, 6, 0));
        // the family's wallet tools (PandaPools consolidate, PandaDEX split) — no trip to another app
        LinearLayout tools = new LinearLayout(getContext());
        TextView cons = Ui.button(act, "CONSOLIDATE"), split = Ui.button(act, "SPLIT ×" + WalletTools.SPLIT_COUNT);
        cons.setTextColor(Design.ACCENT()); split.setTextColor(Design.ACCENT());
        cons.setOnClickListener(v -> WalletTools.consolidate(act, tokenid, name, coins));
        split.setOnClickListener(v -> WalletTools.split(act, tokenid, name, sendable));
        LayoutParams l1 = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f); l1.rightMargin = Design.dp(getContext(), 6);
        tools.addView(cons, l1); tools.addView(split, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        box.addView(tools, Ui.wide(act, 8, 0));
    }
}
