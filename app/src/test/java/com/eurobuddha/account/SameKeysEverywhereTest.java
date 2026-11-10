package com.eurobuddha.account;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * The same 24 words MUST give the same Trading Account in Dexicon, AtomiX and PandaPools (owner, 10 Nov: the apps are
 * mutually exclusive and moving between them is a restore). This file is vendored verbatim into each app: if a value
 * here ever changes, every existing account would open as a different, empty one.
 */
public class SameKeysEverywhereTest {
    static final String PHRASE = AccountKeysTest.PHRASE;

    @Test public void oneKnownPhraseGivesTheseExactKeysInEveryApp() {
        AccountKeys k = AccountKeys.fromPhrase(PHRASE, new AccountKeysTest.Mem(), null);
        assertEquals("domain", "dexicon-account-v1", AccountKeys.DOMAIN);
        assertEquals("fingerprint", "C3BA14CD8AF95045", k.fingerprint());
        assertEquals("address 0", "0x3821D35B75492D1935FA4158E7702ADD5BFF48062EBE6D64B01089CD1F4895AE", k.address(0));
        assertEquals("address 0 (Mx)", "MxG081Z479YMTA95KCJBUW1B3JN0AMTBFVKG1HENPMM9C0GH76HUW4YYQWTRZFH", k.miniAddress(0));
        assertEquals("ETH key seed (seedrandom ethbridge)", "0x3A24E5DA9DAA72AC1E044D346B85AB792A59511EE2A58D4ADD92732B1F469B5E", k.seedRandom("ethbridge"));
        assertEquals("AtomiX comms identity seed", "0x57ED270F37D6E15831152356865F5C78913A83379870DB848ECAB80915A9584A", k.seedRandom("dexicon-doors-v1"));
        assertEquals("DEX identity seed", "0x35CDCF7AE19F7998480586FD75C9AA9B01A2A2BAA21B0B00CAF35EDE939D4DC3", k.seedRandom("dexicon-identity-v1"));
    }
}
