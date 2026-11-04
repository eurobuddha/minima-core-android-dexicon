package com.eurobuddha.dexicon;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.eurobuddha.atomix.SwapLog;
import com.eurobuddha.atomix.eth.EthNet;
import com.eurobuddha.atomix.eth.EthRpc;
import com.eurobuddha.atomix.eth.EthSend;
import com.eurobuddha.atomix.eth.EthTx;
import com.eurobuddha.atomix.eth.EthWallet;
import com.eurobuddha.comms.QrUtil;
import com.eurobuddha.dexicon.doors.DoorHost;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AtomiX's Wallet actions for the node's Ethereum wallet (apks/atomix MainActivity receiveDialog / sendDialog /
 * sendReviewDialog / exportKeyDialog), on the same node-derived wallet and connection Dexicon's AtomiX swaps use.
 * Owner, 4 Nov: full AtomiX functionality — without these, USDT or ETH left at the address (a refunded buy, spare
 * gas) could only leave through a swap. Every check, guard and message is AtomiX's own.
 */
final class WalletActions {
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "dexicon-ethwallet"); t.setDaemon(true); return t; });
    private static long generation;

    private final MainActivity act;
    private final DoorHost host;
    private final Handler ui = new Handler(Looper.getMainLooper());

    WalletActions(MainActivity act) { this.act = act; this.host = DoorHost.get(act); }

    private int dp(int v) { return Design.dp(act, v); }
    private AlertDialog.Builder dialog() { return new AlertDialog.Builder(act, Design.dialogTheme()); }
    private ScrollView wrap(LinearLayout box) { ScrollView sv = new ScrollView(act); sv.addView(box); return sv; }
    private TextView text(String s, int color, float size) { TextView t = new TextView(act); t.setText(s); t.setTextColor(color); t.setTextSize(size); return t; }

    // ---- receive / fund ----

    void receive() {
        String addr = host.ethAddress();
        if (addr == null) { act.toast("ETH wallet not ready yet"); return; }
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(20), dp(12), dp(20), dp(4)); box.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView a = text(addr, Design.TEXT(), 13f);
        a.setTypeface(Design.mono()); a.setTextIsSelectable(true); a.setGravity(Gravity.CENTER);
        box.addView(a);
        Bitmap qr = QrUtil.qr(addr, dp(220));
        if (qr != null) {
            ImageView iv = new ImageView(act);
            iv.setImageBitmap(qr);
            LinearLayout.LayoutParams q = new LinearLayout.LayoutParams(dp(220), dp(220)); q.topMargin = dp(16);
            iv.setLayoutParams(q); iv.setPadding(dp(8), dp(8), dp(8), dp(8));
            iv.setBackgroundColor(0xFFFFFFFF);   // white quiet zone so the dark theme doesn't break scanning
            iv.setContentDescription("QR code of " + addr);
            box.addView(iv);
        }
        TextView note = text("Same address on all EVM networks — fund it with " + host.net.label + " ETH and USDT. A little ETH pays the gas for swaps and sends.", Design.DIM2(), 12f);
        note.setGravity(Gravity.CENTER); note.setPadding(0, dp(14), 0, 0);
        box.addView(note);
        act.track(dialog().setTitle("Receive / Fund · " + host.net.label).setView(wrap(box))
                .setPositiveButton("Copy address", (d, w) -> act.copy("Ethereum address", addr))
                .setNegativeButton("Close", null).show());
    }

    // ---- manual send (AtomiX ManualSendContext: the owner, connection and network captured once, re-checked at every step) ----

    private final class SendContext {
        final org.web3j.crypto.Credentials owner = host.wallet().creds();
        final EthRpc connection = host.rpc();
        final EthNet network = host.net;
        final EthNet.Token token = network.tokens[0];
        final long gen = ++generation;
        volatile boolean active = true;
        boolean current() {
            return active && owner != null && owner == host.wallet().creds() && connection == host.rpc() && network == host.net
                    && token == network.tokens[0] && gen == generation && !act.isFinishing() && !act.isDestroyed();
        }
        void cancel() { active = false; }
    }

    private void run(SendContext c, Runnable task) {
        if (!c.current()) { act.toast("Wallet session changed. Review again."); return; }
        try { IO.execute(task); }
        catch (java.util.concurrent.RejectedExecutionException stopped) { c.cancel(); act.toast("Wallet session changed. Review again."); }
    }

    void send() {
        if (!host.wallet().ready()) { act.toast("ETH wallet not ready yet"); return; }
        final SendContext c = new SendContext();
        final AtomicBoolean reviewing = new AtomicBoolean();
        final long[] maxRequest = {0};
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(20), dp(12), dp(20), dp(4));

        final boolean[] ethSel = {true};
        LinearLayout toggles = new LinearLayout(act);
        final TextView ethPill = Design.pill(act, "ETH", Design.ACCENT(), Design.ON_ACCENT());
        final TextView usdtPill = Design.pill(act, "USDT", Design.SURFACE2(), Design.DIM());
        Runnable restyle = () -> {
            ethPill.setBackground(Design.roundBg(act, ethSel[0] ? Design.ACCENT() : Design.SURFACE2(), 14)); ethPill.setTextColor(ethSel[0] ? Design.ON_ACCENT() : Design.DIM());
            usdtPill.setBackground(Design.roundBg(act, !ethSel[0] ? Design.ACCENT() : Design.SURFACE2(), 14)); usdtPill.setTextColor(!ethSel[0] ? Design.ON_ACCENT() : Design.DIM());
        };
        ethPill.setOnClickListener(v -> { ethSel[0] = true; maxRequest[0]++; restyle.run(); });
        usdtPill.setOnClickListener(v -> { ethSel[0] = false; maxRequest[0]++; restyle.run(); });
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT); gap.rightMargin = dp(8);
        toggles.addView(ethPill, gap); toggles.addView(usdtPill);
        restyle.run();
        box.addView(toggles);

        final EditText toE = new EditText(act);
        toE.setHint("0x… recipient"); toE.setTextColor(Design.TEXT()); toE.setHintTextColor(Design.DIM2()); toE.setTextSize(13f); toE.setTypeface(Design.mono());
        toE.setSingleLine(true); toE.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        box.addView(toE);

        LinearLayout amtRow = new LinearLayout(act); amtRow.setGravity(Gravity.CENTER_VERTICAL);
        final EditText amtE = new EditText(act);
        amtE.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        amtE.setHint("0.00"); amtE.setTextColor(Design.TEXT()); amtE.setHintTextColor(Design.DIM2()); amtE.setTextSize(17f); amtE.setTypeface(Design.mono());
        TextView maxB = Design.pill(act, "Max", Design.SURFACE2(), Design.TEXT());
        maxB.setOnClickListener(v -> {
            final boolean eth = ethSel[0];
            final String typed = amtE.getText().toString();
            final long request = ++maxRequest[0];
            if (!c.current()) { act.toast("Wallet session changed. Review again."); return; }
            run(c, () -> {
                try {
                    EthTx.requireAuthorized(c::current);
                    final String amount;
                    if (eth) {
                        BigInteger gp = EthRpc.hexToBig(c.connection.callStr("eth_gasPrice", new org.json.JSONArray()));
                        BigInteger bf = c.connection.baseFeePerGasOrZero();
                        EthTx.requireFees(EthSend.effectiveGasPriceWei(gp, bf), EthSend.GAS_ETH);
                        BigInteger balance = c.connection.getBalance(c.owner.getAddress());
                        amount = EthWallet.format(EthSend.maxEthSendWei(balance, gp, bf), 18, 8);
                    } else {
                        BigInteger balance = EthWallet.erc20BalanceRaw(c.connection, c.token.address, c.owner.getAddress());
                        amount = EthWallet.format(balance, c.token.decimals, c.token.decimals);
                    }
                    ui.post(() -> { if (c.current() && !reviewing.get() && request == maxRequest[0] && eth == ethSel[0] && typed.equals(amtE.getText().toString())) amtE.setText(amount); });
                } catch (Exception e) { ui.post(() -> { if (c.current()) act.toast("Could not read gas price — try again"); }); }
            });
        });
        amtRow.addView(amtE, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        amtRow.addView(maxB);
        box.addView(amtRow);
        TextView warn = text("Sends are irreversible. Double-check the address.", Design.DIM(), 12f);
        warn.setPadding(0, dp(8), 0, 0);
        box.addView(warn);

        // Review is attached after show, so an invalid form shows its error WITHOUT dismissing the dialog (AtomiX).
        final AlertDialog d = dialog().setTitle("Send from this wallet").setView(wrap(box))
                .setPositiveButton("Review", null).setNegativeButton("Cancel", null)
                .setOnDismissListener(ignored -> { if (!reviewing.get()) c.cancel(); })
                .show();
        act.track(d);
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (!c.current()) { act.toast("Wallet session changed. Review again."); return; }
            d.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            final boolean eth = ethSel[0];
            final String to = toE.getText().toString().trim(), amt = amtE.getText().toString().trim();
            run(c, () -> {
                try {
                    EthTx.requireAuthorized(c::current);
                    BigInteger gp = EthRpc.hexToBig(c.connection.callStr("eth_gasPrice", new org.json.JSONArray()));
                    BigInteger bf = c.connection.baseFeePerGasOrZero();   // the same effective price as the broadcast
                    final BigInteger gasPrice = EthSend.effectiveGasPriceWei(gp, bf);
                    EthTx.requireFees(gasPrice, eth ? EthSend.GAS_ETH : EthSend.GAS_ERC20);
                    BigInteger ethBalance = c.connection.getBalance(c.owner.getAddress());
                    BigInteger tokenBalance = eth ? BigInteger.ZERO : EthWallet.erc20BalanceRaw(c.connection, c.token.address, c.owner.getAddress());
                    final String err = EthSend.checkSend(eth, to, amt, ethBalance, tokenBalance, c.token.decimals, gp, bf);
                    if (err != null) throw new IllegalArgumentException(err);
                    final String fee = EthWallet.format(EthSend.gasReserveWei(gp, bf, eth ? EthSend.GAS_ETH : EthSend.GAS_ERC20), 18, 8);
                    final String amount = new BigDecimal(EthSend.parseUnits(amt, eth ? 18 : c.token.decimals), eth ? 18 : c.token.decimals).stripTrailingZeros().toPlainString();
                    ui.post(() -> {
                        if (!d.isShowing()) return;
                        d.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                        if (!c.current() || eth != ethSel[0] || !to.equals(toE.getText().toString().trim()) || !amt.equals(amtE.getText().toString().trim())) {
                            act.toast("Wallet session changed. Review again."); return;
                        }
                        reviewing.set(true); d.dismiss(); review(c, eth, to, amount, fee, gasPrice);
                    });
                } catch (Exception e) { ui.post(() -> {
                    if (d.isShowing()) { d.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true); act.toast(c.current() ? e.getMessage() : "Wallet session changed. Review again."); }
                }); }
            });
        });
    }

    private void review(SendContext c, boolean eth, String to, String amt, String feeEth, BigInteger gasPrice) {
        if (!c.current()) { act.toast("Wallet session changed. Review again."); return; }
        final AtomicBoolean submitted = new AtomicBoolean();
        String sym = eth ? "ETH" : "USDT";
        act.track(dialog().setTitle("Review — Send " + sym)
                .setMessage("From  " + c.owner.getAddress() + "\n\nSend  " + amt + " " + sym + "\nTo  " + to + "\n\nNetwork fee  ≈ " + feeEth + " ETH\nThis cannot be undone.")
                .setPositiveButton("Send now", (dd, w) -> {
                    if (!c.current() || !submitted.compareAndSet(false, true)) { act.toast("Wallet session changed. Review again."); return; }
                    run(c, () -> {
                        try {
                            BigInteger amount = EthSend.parseUnits(amt, eth ? 18 : c.token.decimals);
                            String tx = EthTx.send(c.connection, c.owner, c.network.chainId, eth ? to : c.token.address,
                                    eth ? "" : EthSend.buildTransferData(to, amount), eth ? amount : BigInteger.ZERO,
                                    eth ? EthSend.GAS_ETH : EthSend.GAS_ERC20, gasPrice, c::current);
                            SwapLog.d("manual send " + sym + " " + amt + " tx=" + tx);
                            // RULE 1: a tx hash in a toast is unrecoverable — the FULL hash goes to the clipboard
                            ui.post(() -> { act.copy("Ethereum tx", tx); act.toast("Sent — full tx hash copied (updates once mined)"); host.readBalances(act::repaint); });
                        } catch (Exception e) {
                            if (e instanceof com.eurobuddha.minimaapi.ethereum.EthereumTransactions.Uncertain) {
                                String hash = ((com.eurobuddha.minimaapi.ethereum.EthereumTransactions.Uncertain) e).hash;
                                SwapLog.w("manual send outcome unknown tx=" + hash);
                                ui.post(() -> { act.copy("Ethereum tx", hash); act.toast("Transaction outcome unknown — full hash copied. Check it before sending again."); });
                            } else ui.post(() -> act.toast("Send failed: " + e.getMessage()));
                        } finally { c.cancel(); }
                    });
                })
                .setOnDismissListener(ignored -> { if (!submitted.get()) c.cancel(); })
                .setNegativeButton("Back", (dd, w) -> send())
                .show());
    }

    // ---- export the key ----

    void exportKey() {
        if (!host.wallet().ready()) { act.toast("ETH wallet not ready yet"); return; }
        act.track(dialog().setTitle("Export ETH private key")
                .setMessage("This key controls your ETH funds. Anyone who sees it can take them. It is also derived from your Minima node seed. Never share it or type it into a website.")
                .setPositiveButton("Reveal key", (d, w) -> reveal())
                .setNegativeButton("Cancel", null).show());
    }

    private void reveal() {
        final String pk = host.wallet().privateKeyHex();
        if (pk == null) { act.toast("ETH wallet not ready yet"); return; }
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(20), dp(12), dp(20), dp(4));
        TextView warn = text("⚠ Keep this secret.", Design.RED(), 12.5f); warn.setPadding(0, 0, 0, dp(10));
        box.addView(warn);
        TextView key = text(pk, Design.TEXT(), 13f); key.setTypeface(Design.mono()); key.setTextIsSelectable(true);
        box.addView(key);
        AlertDialog secret = dialog().setTitle("ETH private key").setView(wrap(box))
                .setPositiveButton("Copy key", (d, w) -> copySecret(pk)).setNegativeButton("Close", null).create();
        if (secret.getWindow() != null) secret.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);   // no screenshots
        secret.show();
        act.track(secret);
    }

    private void copySecret(String value) {
        ClipboardManager cm = (ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) return;
        ClipData clip = ClipData.newPlainText("Dexicon", value);
        if (Build.VERSION.SDK_INT >= 33) {   // Android 13+: the clipboard preview hides it
            android.os.PersistableBundle extras = new android.os.PersistableBundle();
            extras.putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true);
            clip.getDescription().setExtras(extras);
        }
        cm.setPrimaryClip(clip);
        act.toast("Private key copied");
    }
}
