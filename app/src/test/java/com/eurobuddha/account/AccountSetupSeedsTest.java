package com.eurobuddha.account;

import static org.junit.Assert.*;

import android.app.Dialog;
import android.content.DialogInterface;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;

/** 23 Nov 2026 (owner): the Trading Account is a separate wallet even with the node's words — said before the first
 *  choice, beside the same-words paths, under "Show 24 words", and in About; setup still goes on with one tap. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class AccountSetupSeedsTest {
    static final String SEPARATE = "Your Trading Account is a separate wallet from your Minima node's wallet. Even with the same 24 words "
            + "it has its own addresses and its own balance: coins in your node wallet do not appear here until you deposit them.";
    static final String NEW_WORDS = "A new set of words for the Trading Account is the better choice: then one leaked phrase cannot cost you both wallets.";
    static final String SAME_WORDS = "If you do use the same words: write them down once and keep them as safe as you would for both wallets.";
    static final String ONE_DEVICE = "Use a Trading Account on ONE device at a time. Restoring it on a second phone is fine once the first has "
            + "stopped using it for good. Two devices trading with the same Trading Account can expose its keys; if the app sees "
            + "another device signing, it stops signing here.";

    private static String texts(Dialog d) { StringBuilder b = new StringBuilder(); collect(d.getWindow().getDecorView(), b); return b.toString(); }
    private static void collect(View v, StringBuilder b) {
        if (v instanceof TextView) b.append(((TextView) v).getText()).append('\n');
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) collect(((ViewGroup) v).getChildAt(i), b);
    }
    private static void idle() { org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle(); }

    /** The four points sit on the first setup screen, before the choice; one tap on "Create new" goes straight on to the
     *  new words (no extra acknowledgement), and the restore screen carries the same-words reminder. */
    @Test public void explainedBeforeTheChoiceAndSetupStillGoesOn() {
        AppCompatActivity a = Robolectric.buildActivity(AppCompatActivity.class).setup().get();
        AccountSetup.show(a, null, () -> { });
        idle();
        AlertDialog first = (AlertDialog) ShadowDialog.getLatestDialog();
        String shown = texts(first);
        for (String s : new String[]{SEPARATE, NEW_WORDS, SAME_WORDS, ONE_DEVICE}) assertTrue(s, shown.contains(s));
        first.getButton(DialogInterface.BUTTON_POSITIVE).performClick();   // "Create new"
        idle();
        String next = texts(ShadowDialog.getLatestDialog());
        assertTrue(next, next.contains("New Trading Account"));
        assertTrue(next.contains("I have written down all 24 words"));

        AccountSetup.show(a, null, () -> { });
        idle();
        ((AlertDialog) ShadowDialog.getLatestDialog()).getButton(DialogInterface.BUTTON_NEUTRAL).performClick();   // "Restore 24 words"
        idle();
        String restore = texts(ShadowDialog.getLatestDialog());
        assertTrue(restore, restore.contains("Restore Trading Account"));
        assertTrue(restore, restore.contains("Using your node's 24 words? This is still a separate wallet with its own balance"));
    }

    @Test public void theOwnersWordsExactly() {
        assertEquals(SEPARATE, AccountSetup.SEPARATE);
        assertEquals(NEW_WORDS, AccountSetup.NEW_WORDS);
        assertEquals(SAME_WORDS, AccountSetup.SAME_WORDS);
        assertEquals(ONE_DEVICE, AccountSetup.ONE_DEVICE);
        assertEquals("These words restore your Trading Account only. Your node's wallet is separate, even if you used the same words.", AccountSetup.REVEAL_NOTE);
        assertEquals("This account comes from your node's seed: your node's 24 words restore it. It is still a separate wallet with its own balance.",
                AccountSetup.NODE_SEED_NOTE);
    }

    /** "Show 24 words": the line sits UNDER the words; the words are exactly as stored, nothing appended to them. */
    @Test public void revealAddsTheLineAndLeavesThePhraseUnchanged() {
        String[] w = AccountSetup.revealTexts(AccountKeysTest.PHRASE, false);
        assertEquals("Trading Account words", w[0]);
        assertEquals(AccountSetup.REVEAL_NOTE, w[2]);
        assertEquals("the numbered words, in order, are the phrase", AccountKeysTest.PHRASE.trim().replaceAll("\\s+", " "),
                w[1].replaceAll("\\s*\\d+\\. ", " ").trim().replaceAll("\\s+", " "));
        assertFalse(w[1].contains("restore"));
        String any = "  My Own Phrase: exactly as made!  ";
        String[] p = AccountSetup.revealTexts(any, true);
        assertEquals("Trading Account phrase", p[0]);
        assertEquals("the free-form phrase, byte for byte", any, p[1]);
        assertEquals(AccountSetup.REVEAL_NOTE, p[2]);
    }

    /** Existing accounts are not prompted again; the same text is one tap away in the account's About. */
    @Test public void aboutCarriesTheSameText() {
        AppCompatActivity a = Robolectric.buildActivity(AppCompatActivity.class).setup().get();
        AccountSetup.about(a);
        idle();
        String shown = texts(ShadowDialog.getLatestDialog());
        for (String s : new String[]{SEPARATE, NEW_WORDS, SAME_WORDS, ONE_DEVICE}) assertTrue(s, shown.contains(s));
    }
}
