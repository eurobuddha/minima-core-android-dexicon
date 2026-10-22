// VENDORED VERBATIM from apks/atomix 0.1.83 app/src/test/java/com/eurobuddha/comms/HkdfTest.java by scripts/vendor-atomix.sh — do not edit here; re-vendor.
package com.eurobuddha.comms;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

import java.util.Arrays;

/**
 * HKDF-SHA256 — NI-3: the RFC 5869 length cap (255*HashLen) must be enforced, since past it the single-byte
 * block counter wraps and silently produces repeating/weak key material. The two 32-byte sub-seeds the app
 * actually derives (one block each) must stay deterministic and distinct.
 */
public class HkdfTest {

    @Test public void derivesDeterministicDistinctSubSeeds() {
        byte[] ikm = "minima-seed".getBytes();
        byte[] box = Hkdf.derive(ikm, "ctx-box-v1", 32);
        byte[] sign = Hkdf.derive(ikm, "ctx-sign-v1", 32);
        assertEquals(32, box.length);
        assertEquals(32, sign.length);
        assertFalse("domain separation → the two sub-seeds must differ", Arrays.equals(box, sign));
        assertEquals("deterministic", Hex.to(box), Hex.to(Hkdf.derive(ikm, "ctx-box-v1", 32)));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsLengthAboveTheRfcCap() {
        Hkdf.derive("k".getBytes(), "info", 255 * 32 + 1);
    }
}
