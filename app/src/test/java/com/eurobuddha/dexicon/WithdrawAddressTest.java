package com.eurobuddha.dexicon;

import static org.junit.Assert.*;
import org.junit.Test;

public class WithdrawAddressTest {
    @Test public void onlyAChecksummedMxAddressIsAccepted() {
        assertTrue(WalletTools.validMx("MxG086KWK8A5SEPVYPUJEQYH926KFUSCPHVGTM9K4UKG8FGJWNSG1DQP5TG1CUP"));
        assertFalse("one character changed", WalletTools.validMx("MxG086KWK8A5SEPVYPUJEQYH926KFUSCPHVGTM9K4UKG8FGJWNSG1DQP5TG1CUQ"));
        assertFalse("a token id is not an address", WalletTools.validMx("0x7D39745FBD29049BE29850B55A18BF550E4D442F930F86266E34193D89042A90"));
        assertFalse(WalletTools.validMx(""));
    }
}
