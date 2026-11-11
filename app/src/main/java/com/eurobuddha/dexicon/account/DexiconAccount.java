package com.eurobuddha.dexicon.account;

import android.app.Activity;
import android.content.Context;
import android.graphics.Typeface;

import com.eurobuddha.account.Account;
import com.eurobuddha.account.AccountSetup;
import com.eurobuddha.account.VirtualWallet;
import com.eurobuddha.dexicon.ActivityLog;
import com.eurobuddha.dexicon.DexDb;
import com.eurobuddha.dexicon.Design;
import com.eurobuddha.dexicon.fill.Tokens;

import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

/** Dexicon's settings for the shared Trading Account ({@link Account#configure}): applied when NodeApi loads. */
public final class DexiconAccount {
    private DexiconAccount() {}

    public static Account.Config config() {
        return new Account.Config("Dexicon", Arrays.asList(com.eurobuddha.atomix.swap.MinimaHtlc.HTLC_ADDRESSES),
                DexiconAccount::received, LOOK, DexiconAccount::oldAtomixNotice, com.eurobuddha.account.AccountKeys.SLOT_DEXICON);
    }

    private static final AccountSetup.Look LOOK = new AccountSetup.Look() {
        public int dialogTheme() { return Design.dialogTheme(); }
        public int text() { return Design.TEXT(); }
        public Typeface mono() { return Design.mono(); }
        public Typeface sans() { return Design.sans(); }
    };

    private static DexDb db;

    /** One ACTIVITY row per token per scan (amounts summed exactly, every coin and receiving address in full). */
    private static synchronized void received(Context app, String tokenid, BigDecimal amount, JSONArray coins, long block,
                                              List<String> addresses, long now) {
        try {
            if (db == null) db = new DexDb(app);
            // any order on record (live, just filled or cancelled): its proceeds can arrive without our signature
            boolean orders = !db.myOrders().isEmpty();
            String name = "0x00".equals(tokenid) ? "MINIMA" : tokenid.equalsIgnoreCase(Tokens.MXUSD()) ? "MxUSD" : tokenid;
            ActivityLog.addWallet(db, new JSONObject().put("id", "recv_" + VirtualWallet.norm(coins.getString(0)))
                    .put("kind", "received").put("amount", amount.stripTrailingZeros().toPlainString()).put("token", name)
                    .put("tokenid", tokenid).put("ts", now).put("stage", ActivityLog.POSTED)
                    .put("block", block).put("outs", coins).put("maybeFill", orders)
                    .put("tos", new JSONArray(addresses)));
        } catch (Exception e) { throw new IllegalStateException("could not record received coins: " + e, e); }
    }

    /** Swapped with the node-key AtomiX before: its Ethereum wallet came from the node's seed, not this account. */
    private static String oldAtomixNotice(Activity a) {
        boolean tradedBefore = !a.getSharedPreferences("dexicon_doors", Context.MODE_PRIVATE).getString("swap_pk", "").isEmpty();
        return !tradedBefore ? null : "You have swapped with AtomiX here before. The Trading Account has its OWN Ethereum "
                + "wallet: after this step Dexicon shows the new one, and any ETH / USDT left in the old AtomiX Ethereum wallet "
                + "is reachable only from an AtomiX that still uses your node's keys, or the AtomiX MiniDapp on your node "
                + "(both derive it from your node's seed).";
    }
}
