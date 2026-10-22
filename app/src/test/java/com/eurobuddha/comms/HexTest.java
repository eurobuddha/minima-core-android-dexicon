// VENDORED VERBATIM from apks/atomix 0.1.83 app/src/test/java/com/eurobuddha/comms/HexTest.java by scripts/vendor-atomix.sh — do not edit here; re-vendor.
package com.eurobuddha.comms;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Hex codec — MI-9: odd-length input must be rejected, not silently truncated to drop the final nibble. */
public class HexTest {

    @Test public void roundTripsAndToleratesThePrefix() {
        byte[] b = {0x00, (byte) 0xAB, (byte) 0xFF, 0x10};
        assertEquals("00abff10", Hex.to(b));
        assertArrayEquals(b, Hex.from("00abff10"));
        assertArrayEquals(b, Hex.from("0x00ABFF10"));   // optional 0x, case-insensitive
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsOddLength() {
        Hex.from("abc");   // 3 nibbles — the old code dropped the 'c'
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNonHexChars() {
        Hex.from("0x-1");   // Integer.parseInt(…,16) would decode a sign to 0xFF — a hex codec must reject it
    }

    @Test public void nullsAreHandled() {
        assertEquals("", Hex.to(null));
        assertArrayEquals(new byte[0], Hex.from(null));
    }
}
