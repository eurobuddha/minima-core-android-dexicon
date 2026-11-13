package com.eurobuddha.account;

import static org.junit.Assert.*;

import org.junit.Test;

/** 13 Nov 2026 (owner: any-phrase for the Trading Account, restore only): a free-form phrase is hashed exactly as made
 *  (trimmed), never cleaned to list words; the 24-word recipe and every existing account are untouched. */
public class AnyPhraseAccountTest {
    private static final String ANY = "My very own Phrase, made in 2019!";

    @Test public void anyPhraseKeysAreStableAndTrimOnly() {
        String fp = Account.fingerprintOfAnyPhrase(ANY);
        assertEquals(fp, Account.fingerprintOfAnyPhrase("  " + ANY + "\n"));
        assertNotEquals("case counts, as in the node's anyphrase", fp, Account.fingerprintOfAnyPhrase(ANY.toLowerCase()));
        assertNotEquals("spacing counts", fp, Account.fingerprintOfAnyPhrase(ANY.replace(" ", "  ")));
    }

    @Test public void listWordsHashedRawAreADifferentAccountSoTheyAreRefused() {
        String words = AccountKeysTest.PHRASE, typed = words.toLowerCase();
        String boxes = Account.fingerprintOfPhrase(AccountVault.cleanPhrase(typed));
        assertNotEquals("lower-case words hashed raw: another account", boxes, Account.fingerprintOfAnyPhrase(typed));
        assertEquals("only the exact canonical form would match", boxes, Account.fingerprintOfAnyPhrase(words));
        assertNotNull(WordEntry.anyPhraseProblem(typed, false));
        assertNotNull(WordEntry.anyPhraseProblem(words, false));
    }

    @Test public void theTwentyFourWordRecipeIsUnchanged() {
        // fromPhrase still cleans: messy and plain give the same account (the any-phrase path never touches it)
        String words = AccountKeysTest.PHRASE;
        assertEquals(Account.fingerprintOfPhrase(AccountVault.cleanPhrase(words)),
                Account.fingerprintOfPhrase(AccountVault.cleanPhrase(" 1. " + words.toLowerCase().replace(" ", "\n"))));
    }

    /** Review L2: an older app reads a MODE_ANY secret as a 24-word phrase; the stored form must make it throw, never
     *  derive another account (a bare 12-word phrase would have cleaned silently). */
    @Test public void storedAnySecretCannotBeMisreadByAnOlderApp() {
        String twelve = "abandon ability able about above absent absorb abstract absurd abuse access accident";
        String stored = AccountVault.ANY_PREFIX + twelve;
        assertEquals(twelve, AccountVault.anyPhraseOf(stored));
        try { org.minima.utils.BIP39.cleanSeedPhrase(stored); fail("an older app must fail loudly"); }
        catch (IllegalArgumentException expected) {}
        org.minima.utils.BIP39.cleanSeedPhrase(twelve);   // what the bare form would have done: no error
    }
}
