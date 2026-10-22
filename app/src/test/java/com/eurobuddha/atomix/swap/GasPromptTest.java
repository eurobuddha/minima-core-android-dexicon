// VENDORED VERBATIM from apks/atomix 0.1.82 app/src/test/java/com/eurobuddha/atomix/swap/GasPromptTest.java by scripts/vendor-atomix.sh — do not edit here; re-vendor.
package com.eurobuddha.atomix.swap;
import org.junit.Test;
import static org.junit.Assert.*;

/** The "Add ETH for gas" prompt must name the wallet to fund IN FULL (RULE 1) — the shade is the only surface
 *  the user reads while a swap is blocked, and a truncated address cannot be pasted into a wallet. */
public class GasPromptTest {
    private static final String ADDR = "0xf95ddec507956bd35e991cffbb4d550940b4a561";

    @Test public void promptCarriesTheWholeAddress() {
        String m = SwapEngine.gasShortfallMessage("0.0000985", ADDR);
        assertTrue(m.contains(ADDR));
        assertTrue(m.contains("0.0000985"));
        assertFalse("never abbreviate an identifier", m.contains("…") || m.contains("..."));
    }

    @Test public void missingWalletPointsAtTheWalletTabNotNull() {
        for (String empty : new String[]{null, ""}) {
            String m = SwapEngine.gasShortfallMessage("0.0001", empty);
            assertFalse(m.contains("null"));
            assertTrue(m.contains("Wallet tab"));
        }
    }
}
