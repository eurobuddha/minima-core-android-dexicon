// VENDORED VERBATIM from apks/pandapools 0.9.88 app/src/test/java/com/eurobuddha/pandapools/CmdTest.java by scripts/vendor-pandapools.sh — do not edit here; re-vendor.
package com.eurobuddha.pandapools;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The shared command parser. Two guard layers read the same command with this, and they do different jobs with
 * the answer: TxPost locks the coins and authorises the signature, CmdChain verifies them at the signature
 * boundary. Before this class they parsed independently — CmdChain took the first matching token, TxPost the
 * last — so a malformed command would have had one layer authorise a coin the other never checked.
 */
public class CmdTest {

    private static final String A = "0x" + String.join("", Collections.nCopies(64, "a"));
    private static final String B = "0x" + String.join("", Collections.nCopies(64, "b"));

    @Test public void readsASingleParameter() {
        assertEquals(A, Cmd.param("txninput id:pp_1 coinid:" + A, "coinid"));
        assertEquals("pp_1", Cmd.param("txninput id:pp_1 coinid:" + A, "id"));
        assertEquals("auto", Cmd.param("txnsign id:pp_1 publickey:auto", "publickey"));
    }

    @Test public void aDuplicatedParameterIsRefusedNotResolved() {
        // The whole reason this class exists. There is no correct answer to "which coinid did they mean", and
        // guessing decides which coins get spent.
        assertNull(Cmd.param("txninput id:pp_1 coinid:" + A + " coinid:" + B, "coinid"));
        assertNull(Cmd.param("txnsign id:pp_1 publickey:auto publickey:" + A, "publickey"));
        assertNull(Cmd.param("txninput id:pp_1 id:pp_2 coinid:" + A, "id"));
    }

    @Test public void anAbsentOrEmptyParameterIsNull() {
        assertNull(Cmd.param("txnbasics id:pp_1", "coinid"));
        assertNull(Cmd.param("txninput id:pp_1 coinid:", "coinid"));
        assertNull(Cmd.param(null, "coinid"));
        assertNull(Cmd.param("txninput id:pp_1", null));
    }

    @Test public void extraWhitespaceDoesNotHideAParameter() {
        assertEquals(A, Cmd.param("  txninput   id:pp_1    coinid:" + A + "  ", "coinid"));
    }

    @Test public void aPrefixIsNotAParameterName() {
        // "coinid:" must not be found by asking for "coin", nor "publickey:" by asking for "public".
        assertNull(Cmd.param("txninput id:pp_1 coinid:" + A, "coin"));
        assertNull(Cmd.param("txnsign id:pp_1 publickey:auto", "public"));
    }

    @Test public void verbsAreMatchedAsWholeTokens() {
        assertTrue(Cmd.is("txninput id:pp_1 coinid:" + A, "txninput"));
        assertFalse("txninputs must not read as txninput", Cmd.is("txninputs id:pp_1", "txninput"));
        assertFalse(Cmd.is("txninput id:pp_1", "txnoutput"));
        assertEquals("txncreate", Cmd.verb("txncreate id:pp_1"));
        assertEquals("vault", Cmd.verb("vault"));
        assertNull(Cmd.verb(null));
        assertNull(Cmd.verb("   "));
    }

    @Test public void inputCoinIdsCollectsEveryInputInOrder() {
        assertEquals(Arrays.asList(A, B), Cmd.inputCoinIds(Arrays.asList(
                "txncreate id:pp_1",
                "txninput id:pp_1 coinid:" + A,
                "txninput id:pp_1 coinid:" + B,
                "txnbasics id:pp_1")));
    }

    @Test public void inputCoinIdsRefusesTheWholeListIfAnyInputIsMalformed() {
        // Returning a partial list would let a caller proceed with an incomplete view of what is being spent.
        assertNull(Cmd.inputCoinIds(Arrays.asList(
                "txninput id:pp_1 coinid:" + A,
                "txninput id:pp_1 coinid:" + A + " coinid:" + B)));
        assertNull(Cmd.inputCoinIds(Collections.singletonList("txninput id:pp_1")));
    }

    @Test public void inputCoinIdsIsEmptyRatherThanNullWhenThereAreNoInputs() {
        assertTrue(Cmd.inputCoinIds(Collections.singletonList("txncreate id:pp_1")).isEmpty());
        assertTrue(Cmd.inputCoinIds(null).isEmpty());
    }
}
