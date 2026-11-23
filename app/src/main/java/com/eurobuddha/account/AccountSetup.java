package com.eurobuddha.account;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import org.json.JSONObject;

/**
 * First run on any node: create the Trading Account the app signs with (shared by Dexicon, AtomiX and PandaPools;
 * texts use {@link Account.Config#appName}). Three ways in (owner, 9 Nov: offer both seed
 * sources): a NEW 24-word phrase (default — never needs ADMIN), RESTORE those 24 words, or derive it from the node's
 * own seed with ONE temporary ADMIN grant. A restored or node-derived account may have signed on another install: the
 * key-use TIME FLOOR (AccountKeys) puts every signature beyond anything an earlier install signed.
 */
public final class AccountSetup {
    private AccountSetup() {}

    /** The app's dialog styling (Dexicon: its Design tokens). Null in the config = platform defaults. */
    public interface Look {
        int dialogTheme();
        int text();
        Typeface mono();
        Typeface sans();
    }

    public interface Done { void ready(); }

    /** The user chose to move funds out of an old wallet first: no setup offer again until the app restarts. */
    private static volatile boolean deferred;
    private static Transport lastNode;

    // ---- seeds, in plain words (owner, 23 Nov 2026): before the first choice, in "About", beside the same-words paths ----
    public static final String SEPARATE = "Your Trading Account is a separate wallet from your Minima node's wallet. Even with the "
            + "same 24 words it has its own addresses and its own balance: coins in your node wallet do not appear here until "
            + "you deposit them.";
    public static final String NEW_WORDS = "A new set of words for the Trading Account is the better choice: then one leaked "
            + "phrase cannot cost you both wallets.";
    public static final String SAME_WORDS = "If you do use the same words: write them down once and keep them as safe as you "
            + "would for both wallets.";
    /** True to AccountKeys and the second-signer watch (VirtualWallet.watchSigners). Each install's leaf counter is raised
     *  to a wall-clock floor (one leaf per 2 s) and a restore starts past the restore floor (the time floor + 1,800 +
     *  40,000 leaves), but that is no guard against two LIVE installs: two phones signing within seconds pick the same
     *  leaf, and a phone still running after the account was restored elsewhere reaches the restored phone's leaves about
     *  a day later. Text and the watch are the defence: once a signature by another install shows up on chain, this one
     *  raises its counters past it and (at or above its restore floor) stops signing until the person confirms it is the
     *  only device. A leaf two live phones already shared stays exposed - so the first phone must stop for good. */
    public static final String ONE_DEVICE = "Use a Trading Account on ONE device at a time. Restoring it on a second phone is "
            + "fine once the first has stopped using it for good. Two devices trading with the same Trading Account can "
            + "expose its keys; if the app sees another device signing, it stops signing here.";
    static final String[] SEEDS = {SEPARATE, NEW_WORDS, SAME_WORDS, ONE_DEVICE};
    /** The one-line reminder where the node's own words could be typed (Restore) or used (node seed). */
    public static final String SAME_WORDS_REMINDER = "Using your node's 24 words? This is still a separate wallet with its own "
            + "balance — keep the words as safe as you would for both wallets.";
    /** Under the words in "Show 24 words" (owner, 23 Nov 2026). */
    public static final String REVEAL_NOTE = "These words restore your Trading Account only. Your node's wallet is separate, "
            + "even if you used the same words.";
    public static final String NODE_SEED_NOTE = "This account comes from your node's seed: your node's 24 words restore it. "
            + "It is still a separate wallet with its own balance.";

    /** The four points as a short list. */
    static String seeds() {
        StringBuilder b = new StringBuilder();
        for (String s : SEEDS) b.append(b.length() == 0 ? "" : "\n").append("•  ").append(s);
        return b.toString();
    }

    /** The same text for an existing account (its info button): never shown again on its own. */
    public static void about(Activity a) {
        dialog(a).setTitle("About your Trading Account").setView(pad(a, text(a, seeds(), false))).setPositiveButton("OK", null).show();
    }

    /**
     * The account's warnings ({@link Account#warnings}), as a dialog: each text once per process, except "signing is
     * stopped" (another device signs with this account), which is shown on every call until the person confirms "This is
     * the only device now" - a second confirmation, then {@link Account#onlyDeviceNow}. Each app calls this when its
     * account check finds the account open.
     */
    public static void warnings(Activity a, Transport node) {
        Account acc = Account.get(a, node);
        if (acc == null) return;
        acc.warnings((text, stopped) -> {
            if (text == null || a.isFinishing() || a.isDestroyed()) return;
            AlertDialog open = warningDialog.get();
            if (open != null && open.isShowing()) return;
            if (!stopped && !warned.add(text)) return;
            AlertDialog.Builder b = dialog(a).setTitle(stopped ? "Signing stopped" : "Your Trading Account").setView(pad(a, text(a, text, false)));
            if (stopped) b.setPositiveButton(ONLY_DEVICE, (d, w) -> confirmOnlyDevice(a, node, acc)).setNegativeButton("Not now", null);
            else b.setPositiveButton("OK", null);
            warningDialog = new java.lang.ref.WeakReference<>(b.show());
        });
    }
    public static final String ONLY_DEVICE = "This is the only device now";
    static final String ONLY_DEVICE_CONFIRM = "Only continue if every other device has stopped using this Trading Account for good "
            + "(its app uninstalled, or its data cleared). This device then signs only past every one-time key the other one used.";
    private static final java.util.Set<String> warned = java.util.Collections.synchronizedSet(new java.util.HashSet<>());
    private static java.lang.ref.WeakReference<AlertDialog> warningDialog = new java.lang.ref.WeakReference<>(null);

    private static void confirmOnlyDevice(Activity a, Transport node, Account acc) {
        dialog(a).setTitle(ONLY_DEVICE + "?").setView(pad(a, text(a, ONLY_DEVICE_CONFIRM, false)))
                .setPositiveButton(ONLY_DEVICE, (d, w) -> acc.onlyDeviceNow(node, err -> {
                    if (a.isFinishing() || a.isDestroyed()) return;
                    Toast.makeText(a, err == null ? "Signing is on again on this device." : "Signing stays stopped: " + err, Toast.LENGTH_LONG).show();
                }))
                .setNegativeButton("Back", null).show();
    }

    /**
     * "Search the archive again" ({@link Account#searchArchive}), the same in every app: what it does, then progress and
     * the plain result in place. Closing the dialog never drops the answer: the account keeps the latest progress or
     * result and this shows it when opened again (a running search is followed again). The dialog is held only weakly,
     * so a running search never keeps a closed screen alive. {@code node}: the calling screen's connection.
     */
    public static void searchArchive(Activity a, Transport node) {
        Account acc = Account.get(a, node);
        if (acc == null) { Toast.makeText(a, "The Trading Account is not open yet. Try again in a moment.", Toast.LENGTH_LONG).show(); return; }
        String last = acc.archiveSearchText();
        AlertDialog d = dialog(a).setTitle("Search the archive again")
                .setMessage(last == null ? Account.ARCHIVE_SEARCH_ABOUT : last)
                .setPositiveButton(last == null ? "Search" : "Search again", null).setNegativeButton("Close", null).create();
        searchDialog = new java.lang.ref.WeakReference<>(d);
        d.setOnShowListener(x -> {
            android.widget.Button go = d.getButton(AlertDialog.BUTTON_POSITIVE);
            go.setEnabled(!acc.archiveSearching());
            go.setOnClickListener(v -> {
                go.setEnabled(false);
                acc.searchArchive(node, AccountSetup::searchShown, AccountSetup::searchEnded);   // static: captures no screen
            });
        });
        d.show();
    }
    private static java.lang.ref.WeakReference<AlertDialog> searchDialog = new java.lang.ref.WeakReference<>(null);
    private static void searchShown(String s) { AlertDialog d = searchDialog.get(); if (d != null && d.isShowing()) d.setMessage(s); }
    private static void searchEnded(String s) {
        AlertDialog d = searchDialog.get();
        if (d == null || !d.isShowing()) return;
        d.setMessage(s);
        android.widget.Button go = d.getButton(AlertDialog.BUTTON_POSITIVE);
        if (go != null) { go.setText("Search again"); go.setEnabled(true); }
    }

    private static String app() { return Account.config().appName; }
    private static AlertDialog.Builder dialog(Activity a) {
        Look l = Account.config().look;
        return l == null ? new AlertDialog.Builder(a) : new AlertDialog.Builder(a, l.dialogTheme());
    }
    private static void color(TextView t) { Look l = Account.config().look; if (l != null) t.setTextColor(l.text()); }
    private static int dp(Context c, int v) { return Math.round(v * c.getResources().getDisplayMetrics().density); }

    public static void show(Activity a, Transport node, Done done) { show(a, node, done, false, null); }

    /** Where Back returns to: the restore-only screen once a lost account is being restored. */
    private static boolean restoreOnly;
    private static String restoreOpen;
    private static void back(Activity a, Done done) { show0(a, null, done, restoreOnly, restoreOpen); }
    /** Pools carried to this phone without their account (a device transfer): the fingerprints / mode recorded on them. */
    private static java.util.Set<String> owners = java.util.Collections.emptySet();
    private static String ownersMode = "";

    /** Restore-only for pools that belong to a Trading Account this phone never held: only an account whose fingerprint
     *  is one of {@code fingerprints} is accepted (empty = not recorded, e.g. pools from an early test build); {@code mode}
     *  is how that account was made (AccountVault.MODE_NODE = from the node's seed; "" = unknown, both are offered). */
    public static void showForPools(Activity a, Transport node, Done done, String open, java.util.Set<String> fingerprints, String mode) {
        owners = fingerprints == null ? java.util.Collections.emptySet() : fingerprints;
        ownersMode = mode == null ? "" : mode;
        show0(a, node, done, true, open);
    }

    /** {@code restoreOnly}: the account on this phone was lost (keystore) while {@code open} (positions, or "funds")
     *  remain locked to ITS keys — only the SAME account may be restored (checked by fingerprint); a new account is
     *  offered only on an explicit confirmation, and only when no position is open. */
    public static void show(Activity a, Transport node, Done done, boolean ro, String open) {
        owners = java.util.Collections.emptySet(); ownersMode = "";
        show0(a, node, done, ro, open);
    }
    private static void show0(Activity a, Transport node, Done done, boolean ro, String open) {
        if (node != null) lastNode = node; else node = lastNode;
        String other = Family.otherInstalled(a);
        if (other != null) { blocked(a, node, done, ro, open, other); return; }
        final Transport n = node;
        restoreOnly = ro; restoreOpen = open;
        if (ro) { restoreOnlyDialog(a, n, done, open); return; }
        if (deferred) return;   // "move it first" this session: the app stays usable (its wallet screen) until restarted
        java.util.function.Function<Activity, String> noticeOf = Account.config().setupNotice;
        String notice = noticeOf == null ? null : noticeOf.apply(a);
        boolean tradedBefore = notice != null && !notice.isEmpty();
        TextView body = text(a, app() + " keeps its own Trading Account and signs every trade itself. Your Minima node only "
                + "reads the chain and posts what " + app() + " has already signed — so no other app on this phone can touch "
                + "these funds, and " + app() + " needs no ADMIN rights.\n\nMove funds in from your Minima wallet with "
                + "RECEIVE; send them back any time.\n\n" + seeds()
                + (tradedBefore ? "\n\n⚠ " + notice : ""), false);
        AlertDialog main = dialog(a).setTitle("Your Trading Account").setView(pad(a, body)).setCancelable(false)
                .setPositiveButton("Create new", (d, w) -> create(a, done))
                .setNeutralButton("Restore 24 words", (d, w) -> restore(a, done))
                // Words only for a NEW account (owner, 11 Nov 2026): 24 words outside the node work on every node (Minima
                // Core and the Bears have different seeds), and no app on the node can derive them — a node-seed account
                // needs ADMIN to make and any app that can run `seedrandom` with this modifier gets its keys. An account
                // already made from the node seed is still restored that way (lostVault / carriedPoolsDialog below).
                .show();
        if (tradedBefore) {
            dialog(a).setTitle("Before you continue").setCancelable(false)
                    .setMessage("Is there anything in that old wallet you still need to move out first? "
                            + "You can come back to this step any time.")
                    .setPositiveButton("No — continue", null)
                    .setNegativeButton("Yes — move it first", (d, w) -> { main.dismiss(); deferred = true; }).show();
        }
    }

    /** Another family app is on this phone: no account is created or restored here until it is gone (one holder of the
     *  account at a time — two apps signing with the same keys would reuse one-time leaves). */
    private static void blocked(Activity a, Transport node, Done done, boolean ro, String open, String other) {
        dialog(a).setTitle("AtomiX + PandaPools, or Dexicon").setCancelable(false)
                .setView(pad(a, text(a, other + " is installed on this phone. A phone runs either Dexicon, or AtomiX and "
                        + "PandaPools together — not both (and never two builds of the same app).\n\nIn " + other + ": finish or "
                        + "hand over anything open, then uninstall it fully (an app removed with its data kept still counts). "
                        + "Then come back here and " + (ro ? "restore your account." : "create or restore your account."), false)))
                .setPositiveButton("Check again", (d, w) -> show0(a, node, done, ro, open))
                .setNegativeButton("Close", (d, w) -> a.finish()).show();
    }

    private static void restoreOnlyDialog(Activity a, Transport n, Done done, String open) {
        AccountVault v = new AccountVault(a);
        if (v.lostMode().isEmpty() && v.knownFingerprints().isEmpty()) { carriedPoolsDialog(a, n, done, open); return; }
        boolean nodeMode = AccountVault.MODE_NODE.equals(v.lostMode());
        boolean nothingOpen = open == null || "funds".equals(open);
        AlertDialog.Builder b = dialog(a).setTitle("Restore your Trading Account").setCancelable(false)
                .setView(pad(a, text(a, "This phone's Trading Account can no longer be unlocked" + (nothingOpen ? "" : ", and it still has " + open)
                        + ". Restore THE SAME account: " + (nodeMode ? "it came from your node's seed — use Use node seed." : "its 24 words.")
                        + " (The locked copy is kept.)", false)))
                .setPositiveButton(nodeMode ? "Use node seed" : "Restore 24 words", (d, w) -> { if (nodeMode) fromNode(a, n, done); else restore(a, done); });
        if (nothingOpen) b.setNegativeButton("Lost the words?", (d, w) -> dialog(a).setTitle("Start a NEW account?")
                .setMessage("Anything still at the old Trading Account's addresses stays there, reachable ONLY with its own words "
                        + "(restore them on a fresh install). Start a new, empty account anyway?").setCancelable(false)
                .setPositiveButton("Start new", (x, y) -> create(a, done))
                .setNegativeButton("Back", (x, y) -> back(a, done)).show());
        b.show();
    }

    /** No account was ever on this phone, yet pools made with one are here (a device transfer copies their recipes, not
     *  the account): restore THAT account — by its words or the node seed, as it was made — or leave it for later. */
    private static void carriedPoolsDialog(Activity a, Transport n, Done done, String open) {
        boolean node = AccountVault.MODE_NODE.equals(ownersMode), phrase = !ownersMode.isEmpty() && !node;
        AlertDialog.Builder b = dialog(a).setTitle("Restore the account these pools belong to").setCancelable(false)
                .setView(pad(a, text(a, (open == null ? "These pools" : "These " + open) + " were made with a Trading Account "
                        + "that is not on this phone. Only THAT account can close them or collect from them: restore it "
                        + (node ? "from your node's seed (Use node seed)." : phrase ? "from its 24 words." : "from its 24 words, or with "
                        + "Use node seed if it came from your node.")
                        + (owners.isEmpty() ? " These pools record no account fingerprint, so the restore cannot be checked: use "
                                + "exactly the account that made them." : " A different account is refused.")
                        + "\n\nFirst finish or close these pools in the app on your old phone, or uninstall it there: two phones "
                        + "must never sign for one account.", false)))
                .setNegativeButton("Later", null);
        if (!node) b.setPositiveButton("Restore 24 words", (d, w) -> restore(a, done));
        if (!phrase) b.setNeutralButton("Use node seed", (d, w) -> fromNode(a, n, done));
        b.show();
    }

    /** A restore while recovering a LOST account, or the account of carried-over pools, must be that account. */
    private static String wrongAccount(Activity a, String fingerprint) {
        if (!restoreOnly) return null;
        return wrongAccount(new AccountVault(a).knownFingerprints(), owners, fingerprint);
    }

    /** Pure: accepted when no fingerprint is known at all, or {@code fp} is one of this phone's or the pools' accounts. */
    static String wrongAccount(java.util.Set<String> known, java.util.Set<String> pools, String fp) {
        String f = fp.toLowerCase(java.util.Locale.ROOT);
        if (known.isEmpty() && pools.isEmpty()) return null;
        if (known.contains(f)) return null;
        for (String p : pools) if (p.equalsIgnoreCase(f)) return null;
        return pools.isEmpty() ? "That is not this phone's Trading Account — its positions need the SAME account. Nothing was changed."
                : "That is not the account these pools belong to. Nothing was changed.";
    }

    private static void create(Activity a, Done done) {
        String phrase = AccountVault.newPhrase();
        LinearLayout box = new LinearLayout(a); box.setOrientation(LinearLayout.VERTICAL);
        box.addView(text(a, "Write these 24 words down, in order, and keep them offline. They are the ONLY way to recover "
                + "this Trading Account. " + app() + " never shows them to anyone and never sends them anywhere.", false));
        TextView words = text(a, numbered(phrase), true);
        box.addView(words);
        CheckBox ok = new CheckBox(a); ok.setText("I have written down all 24 words"); { Look l = Account.config().look; if (l != null) ok.setTextColor(l.text()); }
        box.addView(ok);
        AlertDialog d = dialog(a).setTitle("New Trading Account").setView(pad(a, box))
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
        // One box per word (WordEntry, ported from apks/entropy): a free-text box let a line break or a number glue two
        // words together and a correct phrase was refused. No checksum: Minima phrases have none.
        Look l = Account.config().look;
        WordEntry words = new WordEntry(a, l == null ? 0 : l.text(), l == null ? null : l.mono(), null).enableAnyPhrase(false);
        LinearLayout box = new LinearLayout(a); box.setOrientation(LinearLayout.VERTICAL);
        box.addView(text(a, "Restore a Trading Account. Only use it on ONE phone and ONE profile, in ONE app: stop using it "
                + "everywhere else first.", false));
        box.addView(text(a, "Type each word in its box. A box completes itself as soon as the word is certain; tap a "
                + "suggestion for short words. You can also paste all 24 words into box 1.", false));
        box.addView(text(a, SAME_WORDS_REMINDER, false));
        box.addView(words.view);
        AlertDialog d = dialog(a).setTitle("Restore Trading Account").setView(pad(a, box))
                .setCancelable(false).setPositiveButton("Restore", null).setNegativeButton("Back", (x, w) -> back(a, done)).create();
        secure(d);
        if (d.getWindow() != null) d.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        d.setOnShowListener(x -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                String typed = words.phrase();
                if (typed == null) { Toast.makeText(a, words.problem(), Toast.LENGTH_LONG).show(); return; }
                boolean any = words.isAnyPhrase();
                String p = any ? typed : AccountVault.cleanPhrase(typed);
                String wrong = wrongAccount(a, any ? Account.fingerprintOfAnyPhrase(p) : Account.fingerprintOfPhrase(p));
                if (wrong != null) { Toast.makeText(a, wrong, Toast.LENGTH_LONG).show(); return; }
                if (any) new AccountVault(a).storeRestoredAnyPhrase(p); else new AccountVault(a).storeRestoredPhrase(p);
                d.dismiss(); done.ready();
            } catch (Exception e) { Toast.makeText(a, e.getMessage(), Toast.LENGTH_LONG).show(); }
        }));
        d.show();
    }

    private static void fromNode(Activity a, Transport node, Done done) {
        if (node == null) { back(a, done); return; }
        dialog(a).setTitle("Derive from your node's seed")
                .setView(pad(a, text(a, app() + " will read ONE value derived from your node's seed. That needs ADMIN once: "
                        + "give " + app() + " ADMIN in Minima Core → Apps, tap Derive, then switch ADMIN off again.\n\n"
                        + "Your node's 24 words will then also restore this account. " + SEPARATE + "\n\nNote: on Minima Core any app you have "
                        + "enabled could compute the same value, so a new phrase (Create new) is the safer choice.", false)))
                .setCancelable(false)
                .setPositiveButton("Derive", (d, w) -> { node.reRegister(); new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> node.cmdDirect("seedrandom modifier:" + AccountKeys.DOMAIN, null, 0, new Transport.Reply() {
                    public void onResult(JSONObject j) {
                        JSONObject r = j.optJSONObject("response");
                        String hex = r == null ? "" : r.optString("seedrandom", "");
                        try {
                            if (!hex.matches("0x[0-9a-fA-F]{64}")) throw new IllegalArgumentException(j.optString("error", "no derived seed"));
                            String wrong = wrongAccount(a, Account.fingerprintOfBase(hex));
                            if (wrong != null) { Toast.makeText(a, wrong, Toast.LENGTH_LONG).show(); back(a, done); return; }
                            new AccountVault(a).storeNodeBase(hex); done.ready();
                        }
                        catch (Exception e) { Toast.makeText(a, app() + " needs ADMIN for this step: " + e.getMessage(), Toast.LENGTH_LONG).show(); back(a, done); }
                    }
                    public void onError(String m) { Toast.makeText(a, app() + " needs ADMIN for this step: " + m, Toast.LENGTH_LONG).show(); back(a, done); }
                }), 2500); })
                .setNegativeButton("Back", (d, w) -> back(a, done)).show();
    }


    /** Show the 24 words again (phrase mode). No copy: they are written down, never put on a clipboard. */
    public static void reveal(Activity a) {
        try {
            AccountVault vault = new AccountVault(a);
            String p = vault.revealPhrase();
            boolean any = AccountVault.MODE_ANY.equals(vault.mode());
            if (p == null) { Toast.makeText(a, NODE_SEED_NOTE, Toast.LENGTH_LONG).show(); return; }
            // No Copy: the 24 words never go on a clipboard any other app can read. Write them down.
            String[] t = revealTexts(p, any);
            LinearLayout box = new LinearLayout(a); box.setOrientation(LinearLayout.VERTICAL);
            box.addView(text(a, t[1], true));
            box.addView(text(a, t[2], false));
            AlertDialog d = dialog(a).setTitle(t[0]).setView(pad(a, box)).setPositiveButton("Done", null).create();
            secure(d);
            d.show();
        } catch (Exception e) { Toast.makeText(a, e.getMessage(), Toast.LENGTH_LONG).show(); }
    }

    /** Title, the words exactly as stored (the phrase as made, or the 24 words numbered), and the note shown under them. */
    static String[] revealTexts(String phrase, boolean any) {
        return new String[]{any ? "Trading Account phrase" : "Trading Account words", any ? phrase : numbered(phrase), REVEAL_NOTE};
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
        t.setText(s); color(t); t.setTextSize(mono ? 15 : 14);
        Look l = Account.config().look;
        t.setTypeface(l == null ? (mono ? Typeface.MONOSPACE : Typeface.DEFAULT) : (mono ? l.mono() : l.sans()), Typeface.NORMAL);
        t.setTextIsSelectable(false);   // the 24 words are never selectable / copyable — write them down
        t.setPadding(0, dp(c, 6), 0, dp(c, 6));
        return t;
    }

    private static android.view.View pad(Context c, android.view.View v) {
        android.widget.ScrollView s = new android.widget.ScrollView(c);
        int p = dp(c, 20);
        s.setPadding(p, dp(c, 8), p, 0);
        s.addView(v);
        return s;
    }
}
