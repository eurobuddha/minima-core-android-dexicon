package com.eurobuddha.account;

import static org.junit.Assert.*;

import org.junit.Test;
import org.minima.utils.BIP39;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** 13 Nov 2026: a correct phrase typed into one multi-line box was refused ("Unknown BIP39 word found!") because a line
 *  break or a number glued two words together. WordEntry takes one word per box; these pin that it can never change WHICH
 *  account a phrase restores, and that no BIP39 checksum is demanded (Minima phrases have none). */
public class WordEntryTest {
    private static final String PLAIN = "ABANDON ABILITY ABLE ABOUT ABOVE ABSENT ABSORB ABSTRACT ABSURD ABUSE ACCESS ACCIDENT "
            + "ACCOUNT ACCUSE ACHIEVE ACID ACOUSTIC ACQUIRE ACROSS ACT ACTION ACTOR ACTRESS ACTUAL";

    @Test public void pastedWordsSplitOnAnythingButLetters() {
        assertEquals(Arrays.asList("abandon", "ability", "able", "about"),
                WordEntry.splitPasted(" 1. abandon\n2) ABILITY,  able\t3 about. "));
        assertTrue(WordEntry.splitPasted("12 34 .,").isEmpty());
        assertEquals("abandon", WordEntry.letters("Aban-don 1"));
    }

    @Test public void fourLettersAlwaysPickOneWordAndShortWordsStayTypeable() {
        for (String w : BIP39.WORD_LIST) {
            String p = w.length() >= 4 ? w.substring(0, 4) : w;
            if (w.length() >= 4) assertEquals("4-letter prefix of " + w, Collections.singletonList(w), WordEntry.matches(p, 2));
            assertTrue(WordEntry.isWord(w));
        }
        assertTrue("act is a word although action/actor also match", WordEntry.isWord("act"));
        assertTrue(WordEntry.matches("act", 8).size() > 1);
        assertTrue(WordEntry.matches("", 8).isEmpty());
        assertTrue(WordEntry.matches("zzz", 8).isEmpty());
        assertFalse(WordEntry.isWord("abandonn"));
    }

    @Test public void joinNeedsAllTwentyFourRealWords() {
        List<String> w = WordEntry.splitPasted(PLAIN);
        assertEquals(24, w.size());
        assertEquals(PLAIN.toLowerCase(), WordEntry.join(w));
        assertNull("23 words", WordEntry.join(w.subList(0, 23)));
        List<String> bad = new java.util.ArrayList<>(w); bad.set(5, "absen");
        assertNull("a prefix is not a word", WordEntry.join(bad));
    }

    @Test public void messyEntrySavesExactlyThePlainPhraseSoTheSameAccount() {
        String messy = String.join("\n", WordEntry.splitPasted(PLAIN)).replaceFirst("abandon", "1. Abandon,");
        String fromBoxes = WordEntry.join(WordEntry.splitPasted(messy));
        String plain = AccountVault.cleanPhrase(PLAIN);
        assertEquals(plain, AccountVault.cleanPhrase(fromBoxes));
        assertEquals("cleanPhrase alone also survives line breaks and numbers", plain, AccountVault.cleanPhrase(messy));
        assertEquals(Account.fingerprintOfPhrase(plain), Account.fingerprintOfPhrase(AccountVault.cleanPhrase(fromBoxes)));
    }

    @Test public void noBip39ChecksumIsDemanded() {
        // 24 x "abandon" fails the BIP39 checksum (the valid one ends in "art"); a Minima phrase never has one.
        String p = String.join(" ", Collections.nCopies(24, "abandon"));
        assertEquals(p, WordEntry.join(Arrays.asList(p.split(" "))));
        assertEquals(p.toUpperCase(), AccountVault.cleanPhrase(p));
    }

    @Test public void anyPhraseIsForRealCustomPhrasesOnly() {
        assertEquals("too short", "A phrase needs at least 16 characters.", WordEntry.anyPhraseProblem("short phrase", false));
        assertNull(WordEntry.anyPhraseProblem("  My very own Phrase, 2019!  ", false));
        assertNotNull("24 list words belong in the boxes", WordEntry.anyPhraseProblem(PLAIN, false));
        assertNotNull("...even typed messy", WordEntry.anyPhraseProblem("1. " + PLAIN.toLowerCase().replace(" ", "\n"), false));
        assertNull("23 list words is a custom phrase", WordEntry.anyPhraseProblem(PLAIN.substring(0, PLAIN.lastIndexOf(' ')), false));
        assertNull("a colon is fine in the app", WordEntry.anyPhraseProblem("my secret: the long one", false));
        for (String bad : new String[]{"my secret: the long one", "my \"quoted\" long phrase", "two; commands here please"})
            assertNotNull("node commands refuse it: " + bad, WordEntry.anyPhraseProblem(bad, true));
    }
}
