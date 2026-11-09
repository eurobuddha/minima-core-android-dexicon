package com.eurobuddha.dexicon.account;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.text.InputType;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.eurobuddha.dexicon.Design;
import com.eurobuddha.dexicon.NodeApi;

import org.json.JSONObject;

/**
 * First run on any node: create the Trading Account Dexicon signs with. Three ways in (owner, 9 Nov: offer both seed
 * sources): a NEW 24-word phrase (default — never needs ADMIN), RESTORE those 24 words, or derive it from the node's
 * own seed with ONE temporary ADMIN grant. A restored or node-derived account may have signed on another install: the
 * key-use TIME FLOOR (AccountKeys) puts every signature beyond anything an earlier install signed.
 */
public final class AccountSetup {
    private AccountSetup() {}


    public interface Done { void ready(); }
    private static NodeApi lastNode;

    public static void show(Activity a, NodeApi node, Done done) { show(a, node, done, false, null); }

    /** Where Back returns to: the restore-only screen once a lost account is being restored. */
    private static boolean restoreOnly;
    private static String restoreOpen;
    private static void back(Activity a, Done done) { show(a, null, done, restoreOnly, restoreOpen); }

    /** {@code restoreOnly}: the account on this phone was lost (keystore) while {@code open} (positions, or "funds")
     *  remain locked to ITS keys — only the SAME account may be restored (checked by fingerprint); a new account is
     *  offered only on an explicit confirmation, and only when no position is open. */
    public static void show(Activity a, NodeApi node, Done done, boolean ro, String open) {
        if (node != null) lastNode = node; else node = lastNode;
        final NodeApi n = node;
        restoreOnly = ro; restoreOpen = open;
        if (ro) { restoreOnlyDialog(a, n, done, open); return; }
        boolean tradedBefore = !a.getSharedPreferences("dexicon_doors", Context.MODE_PRIVATE).getString("swap_pk", "").isEmpty();
        TextView body = text(a, "Dexicon keeps its own Trading Account and signs every trade itself. Your Minima node only "
                + "reads the chain and posts what Dexicon has already signed — so no other app on this phone can touch "
                + "these funds, and Dexicon needs no ADMIN rights.\n\nMove funds in from your Minima wallet with "
                + "RECEIVE; send them back any time."
                + (tradedBefore ? "\n\n⚠ You have swapped with AtomiX here before. The Trading Account has its OWN Ethereum "
                + "wallet: after this step Dexicon shows the new one, and any ETH / USDT left in the old AtomiX Ethereum wallet "
                + "is reachable only from the standalone AtomiX app or the AtomiX MiniDapp on your node (both derive it from "
                + "your node's seed)." : ""), false);
        AlertDialog main = new AlertDialog.Builder(a, Design.dialogTheme()).setTitle("Your Trading Account").setView(pad(a, body)).setCancelable(false)
                .setPositiveButton("Create new", (d, w) -> create(a, done))
                .setNeutralButton("Restore 24 words", (d, w) -> restore(a, done))
                .setNegativeButton("Use node seed", (d, w) -> fromNode(a, n, done))
                .show();
        if (tradedBefore) {
            new AlertDialog.Builder(a, Design.dialogTheme()).setTitle("Before you continue").setCancelable(false)
                    .setMessage("Is there ETH or USDT in the old AtomiX Ethereum wallet that you still need to move out? "
                            + "You can come back to this step any time.")
                    .setPositiveButton("No — continue", null)
                    .setNegativeButton("Yes — move it first", (d, w) -> { main.dismiss(); a.finish(); }).show();
        }
    }

    private static void restoreOnlyDialog(Activity a, NodeApi n, Done done, String open) {
        AccountVault v = new AccountVault(a);
        boolean nodeMode = AccountVault.MODE_NODE.equals(v.lostMode());
        boolean nothingOpen = open == null || "funds".equals(open);
        AlertDialog.Builder b = new AlertDialog.Builder(a, Design.dialogTheme()).setTitle("Restore your Trading Account").setCancelable(false)
                .setView(pad(a, text(a, "This phone's Trading Account can no longer be unlocked" + (nothingOpen ? "" : ", and it still has " + open)
                        + ". Restore THE SAME account: " + (nodeMode ? "it came from your node's seed — use Use node seed." : "its 24 words.")
                        + " (The locked copy is kept.)", false)))
                .setPositiveButton(nodeMode ? "Use node seed" : "Restore 24 words", (d, w) -> { if (nodeMode) fromNode(a, n, done); else restore(a, done); });
        if (nothingOpen) b.setNegativeButton("Lost the words?", (d, w) -> new AlertDialog.Builder(a, Design.dialogTheme()).setTitle("Start a NEW account?")
                .setMessage("Anything still at the old Trading Account's addresses stays there, reachable ONLY with its own words "
                        + "(restore them in Dexicon on a fresh install). Start a new, empty account anyway?").setCancelable(false)
                .setPositiveButton("Start new", (x, y) -> create(a, done))
                .setNegativeButton("Back", (x, y) -> back(a, done)).show());
        b.show();
    }

    /** A restore while recovering a LOST account must be that account (same fingerprint). */
    private static String wrongAccount(Activity a, String fingerprint) {
        if (!restoreOnly) return null;
        java.util.Set<String> known = new AccountVault(a).knownFingerprints();
        return known.isEmpty() || known.contains(fingerprint.toLowerCase(java.util.Locale.ROOT)) ? null
                : "That is not this phone's Trading Account — its positions need the SAME account. Nothing was changed.";
    }

    private static void create(Activity a, Done done) {
        String phrase = AccountVault.newPhrase();
        LinearLayout box = new LinearLayout(a); box.setOrientation(LinearLayout.VERTICAL);
        box.addView(text(a, "Write these 24 words down, in order, and keep them offline. They are the ONLY way to recover "
                + "this Trading Account. Dexicon never shows them to anyone and never sends them anywhere.", false));
        TextView words = text(a, numbered(phrase), true);
        box.addView(words);
        CheckBox ok = new CheckBox(a); ok.setText("I have written down all 24 words"); ok.setTextColor(Design.TEXT());
        box.addView(ok);
        AlertDialog d = new AlertDialog.Builder(a, Design.dialogTheme()).setTitle("New Trading Account").setView(pad(a, box))
                .setCancelable(false).setPositiveButton("Create", null).setNegativeButton("Back", (x, w) -> back(a, done)).create();
        secure(d);
        d.setOnShowListener(x -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (!ok.isChecked()) { Toast.makeText(a, "Write the words down first.", Toast.LENGTH_LONG).show(); return; }
            try { new AccountVault(a).storeNewPhrase(phrase); restoreOnly = false; restoreOpen = null; d.dismiss(); done.ready(); }
            catch (Exception e) { Toast.makeText(a, "Could not save: " + e.getMessage(), Toast.LENGTH_LONG).show(); }
        }));
        d.show();
    }

    private static void restore(Activity a, Done done) {
        EditText in = new EditText(a);
        in.setHint("24 words, separated by spaces");
        in.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        in.setTextColor(Design.TEXT()); in.setMinLines(3);
        in.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        LinearLayout box = new LinearLayout(a); box.setOrientation(LinearLayout.VERTICAL);
        box.addView(text(a, "Restore a Trading Account. Only use it on ONE phone: stop using it everywhere else first.", false));
        box.addView(in);
        AlertDialog d = new AlertDialog.Builder(a, Design.dialogTheme()).setTitle("Restore Trading Account").setView(pad(a, box))
                .setCancelable(false).setPositiveButton("Restore", null).setNegativeButton("Back", (x, w) -> back(a, done)).create();
        secure(d);
        d.setOnShowListener(x -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                String p = AccountVault.cleanPhrase(in.getText().toString());
                String wrong = wrongAccount(a, Account.fingerprintOfPhrase(p));
                if (wrong != null) { Toast.makeText(a, wrong, Toast.LENGTH_LONG).show(); return; }
                new AccountVault(a).storeRestoredPhrase(p);
                d.dismiss(); done.ready();
            } catch (Exception e) { Toast.makeText(a, e.getMessage(), Toast.LENGTH_LONG).show(); }
        }));
        d.show();
    }

    private static void fromNode(Activity a, NodeApi node, Done done) {
        if (node == null) { back(a, done); return; }
        new AlertDialog.Builder(a, Design.dialogTheme()).setTitle("Derive from your node's seed")
                .setView(pad(a, text(a, "Dexicon will read ONE value derived from your node's seed. That needs ADMIN once: "
                        + "give Dexicon ADMIN in Minima Core → Apps, tap Derive, then switch ADMIN off again.\n\n"
                        + "Your node's 24 words will then also restore this account. Note: on Minima Core any app you have "
                        + "enabled could compute the same value, so a new phrase (Create new) is the safer choice.", false)))
                .setCancelable(false)
                .setPositiveButton("Derive", (d, w) -> { node.reRegister(); new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> node.cmdDirect("seedrandom modifier:" + AccountKeys.DOMAIN, null, 0, new NodeApi.Cb() {
                    public void onResult(JSONObject j) {
                        JSONObject r = j.optJSONObject("response");
                        String hex = r == null ? "" : r.optString("seedrandom", "");
                        try {
                            if (!hex.matches("0x[0-9a-fA-F]{64}")) throw new IllegalArgumentException(j.optString("error", "no derived seed"));
                            String wrong = wrongAccount(a, Account.fingerprintOfBase(hex));
                            if (wrong != null) { Toast.makeText(a, wrong, Toast.LENGTH_LONG).show(); back(a, done); return; }
                            new AccountVault(a).storeNodeBase(hex); done.ready();
                        }
                        catch (Exception e) { Toast.makeText(a, "Dexicon needs ADMIN for this step: " + e.getMessage(), Toast.LENGTH_LONG).show(); back(a, done); }
                    }
                    public void onError(String m) { Toast.makeText(a, "Dexicon needs ADMIN for this step: " + m, Toast.LENGTH_LONG).show(); back(a, done); }
                }), 2500); })
                .setNegativeButton("Back", (d, w) -> back(a, done)).show();
    }


    /** Show the 24 words again (phrase mode). No copy: they are written down, never put on a clipboard. */
    public static void reveal(Activity a) {
        try {
            String p = new AccountVault(a).revealPhrase();
            if (p == null) { Toast.makeText(a, "This account comes from your node's seed: your node's 24 words restore it.", Toast.LENGTH_LONG).show(); return; }
            // No Copy: the 24 words never go on a clipboard any other app can read. Write them down.
            AlertDialog d = new AlertDialog.Builder(a, Design.dialogTheme()).setTitle("Trading Account words").setView(pad(a, text(a, numbered(p), true)))
                    .setPositiveButton("Done", null).create();
            secure(d);
            d.show();
        } catch (Exception e) { Toast.makeText(a, e.getMessage(), Toast.LENGTH_LONG).show(); }
    }

    /** No screenshots, screen recordings or recents thumbnails of the words. */
    private static void secure(AlertDialog d) {
        if (d.getWindow() != null) d.getWindow().setFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE, android.view.WindowManager.LayoutParams.FLAG_SECURE);
    }

    private static String numbered(String phrase) {
        String[] w = phrase.trim().split("\\s+");
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < w.length; i++) b.append(String.format(java.util.Locale.ROOT, "%2d. %s", i + 1, w[i])).append(i % 2 == 1 ? "\n" : "     ");
        return b.toString();
    }

    private static TextView text(Context c, String s, boolean mono) {
        TextView t = new TextView(c);
        t.setText(s); t.setTextColor(Design.TEXT()); t.setTextSize(mono ? 15 : 14);
        t.setTypeface(mono ? Design.mono() : Design.sans(), Typeface.NORMAL);
        t.setTextIsSelectable(false);   // the 24 words are never selectable / copyable — write them down
        t.setPadding(0, Design.dp(c, 6), 0, Design.dp(c, 6));
        return t;
    }

    private static android.view.View pad(Context c, android.view.View v) {
        android.widget.ScrollView s = new android.widget.ScrollView(c);
        int p = Design.dp(c, 20);
        s.setPadding(p, Design.dp(c, 8), p, 0);
        s.addView(v);
        return s;
    }
}
