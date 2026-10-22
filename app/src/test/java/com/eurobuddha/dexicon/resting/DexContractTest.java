package com.eurobuddha.dexicon.resting;
/* Ported from apks/pandadex app/src/test/java/com/eurobuddha/pandadex/DexContractTest.java (PandaDEX 0.4.23). */


import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** SCRIPT_V5 must be BYTE-EXACT against the frozen, on-chain-proven script (the address
 *  derives from it — one changed char = a different, unproven book). The literal below is
 *  the exact clean-form output of contract/v5script.tpl at mainnet dims. */
public class DexContractTest {

    private static final String FROZEN =
            "LET u=0x7D39745FBD29049BE29850B55A18BF550E4D442F930F86266E34193D89042A90 IF (@TOKENID NEQ 0x00 AND @TOKENID NEQ u) OR (PREVSTATE(3) NEQ 0x00 AND PREVSTATE(3) NEQ u) THEN RETURN FALSE ENDIF IF SIGNEDBY(PREVSTATE(0)) THEN IF GETOUTADDR(@INPUT) EQ @ADDRESS THEN IF SAMESTATE(0 1) AND SAMESTATE(3 5) AND SAMESTATE(7 8) AND STATE(2) GT 0 AND VERIFYOUT(@INPUT @ADDRESS @AMOUNT @TOKENID TRUE) THEN RETURN TRUE ENDIF ENDIF IF VERIFYOUT(@INPUT PREVSTATE(1) @AMOUNT @TOKENID FALSE) THEN RETURN TRUE ENDIF ENDIF IF @COINAGE GT 600 THEN RETURN VERIFYOUT(@INPUT PREVSTATE(1) @AMOUNT @TOKENID FALSE) ENDIF LET w=PREVSTATE(2) LET r=0 IF @TOTOUT GT @INPUT+1 THEN IF GETOUTADDR(@INPUT+1) EQ @ADDRESS THEN LET r=GETOUTAMT(@INPUT+1) ENDIF ENDIF IF r EQ 0 THEN RETURN VERIFYOUT(@INPUT PREVSTATE(1) w PREVSTATE(3) FALSE) ENDIF RETURN r LT @AMOUNT AND r GTE PREVSTATE(8) AND VERIFYOUT(@INPUT+1 @ADDRESS r @TOKENID TRUE) AND SAMESTATE(0 1) AND SAMESTATE(3 5) AND SAMESTATE(7 8) AND STATE(2)*@AMOUNT GTE w*r AND STATE(2) LTE w AND GETOUTADDR(@INPUT) EQ PREVSTATE(1) AND GETOUTTOK(@INPUT) EQ PREVSTATE(3) AND GETOUTAMT(@INPUT)*@AMOUNT GTE w*(@AMOUNT-r)";

    @Test public void scriptIsByteExact() {
        assertEquals(FROZEN, DexContract.SCRIPT_V5);
    }

    @Test public void scriptUnderSizeLimit() {
        // KISS scripts over ~1200 chars are silently rejected on-chain
        org.junit.Assert.assertTrue(DexContract.SCRIPT_V5.length() <= 1200);
    }
}
