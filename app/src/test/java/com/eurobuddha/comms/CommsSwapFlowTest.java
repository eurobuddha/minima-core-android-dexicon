// VENDORED VERBATIM from apks/atomix 0.1.82 app/src/test/java/com/eurobuddha/comms/CommsSwapFlowTest.java by scripts/vendor-atomix.sh — do not edit here; re-vendor.
package com.eurobuddha.comms;

import static org.junit.Assert.*;

import com.goterl.lazysodium.LazySodium;
import com.goterl.lazysodium.LazySodiumJava;
import com.goterl.lazysodium.SodiumJava;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;

/**
 * REAL end-to-end comms crypto (desktop libsodium) exercising the EXACT SwapTake buy-handshake path a maker
 * relies on: taker seals {to,from,hash} to the maker's published id → maker opens it. If the combine broke the
 * handshake (identity context, seal/open), this fails; if it passes, the responder no-show is NOT the comms layer.
 */
public class CommsSwapFlowTest {

    private LazySodium ls;

    @Before public void setUp() { ls = new LazySodiumJava(new SodiumJava()); }

    /** AtomiX-mxUSDT derives its comms identity under the LEGACY "usdtswap" context (must match usdtSwap). */
    private CommsIdentity id(String seed) { return id(seed, "usdtswap"); }
    private CommsIdentity id(String seed, String context) {
        return CommsIdentity.fromSeed(ls, seed.getBytes(StandardCharsets.UTF_8), context);
    }

    /**
     * THE combine regression: the comms identity is per-app-CONTEXT. A handshake sealed to an order published
     * under "usdtswap" (the entire existing mxUSDT book, incl. the user's own orders) can ONLY be opened by an
     * identity derived under "usdtswap". A single "atomix" context (the bug) can't open it → the maker sits idle.
     * AtomiX-mxUSDT MUST therefore derive under "usdtswap" (== usdtSwap's identity), not a custom context.
     */
    @Test public void atomixMustUseLegacyContextToServiceTheExistingBook() throws Exception {
        byte[] seed = "shared-node-seed".getBytes(StandardCharsets.UTF_8);
        CommsIdentity legacy  = CommsIdentity.fromSeed(ls, seed, "usdtswap");   // what the live book was published under
        CommsIdentity atomix1 = CommsIdentity.fromSeed(ls, seed, "atomix");     // the broken single-context identity
        CommsIdentity fixed   = CommsIdentity.fromSeed(ls, seed, "usdtswap");   // AtomiX-mxUSDT after the fix

        assertNotEquals("a custom 'atomix' context orphans the legacy identity", legacy.publicId(), atomix1.publicId());
        assertEquals("AtomiX-mxUSDT under 'usdtswap' == the legacy usdtSwap identity (rejoins the book)",
                legacy.publicId(), fixed.publicId());

        // A taker seals a buy handshake to a maker whose order was published under the legacy identity.
        String blob = new LocalEcCryptoProvider(ls, id("taker"))
                .seal(legacy.publicId(), "{\"hash\":\"0xBEEF\"}".getBytes(StandardCharsets.UTF_8));
        assertNull("the BUG: an 'atomix'-identity maker cannot open it → idle",
                new LocalEcCryptoProvider(ls, atomix1).open(blob));
        assertNotNull("the FIX: a 'usdtswap'-identity maker opens it → responds",
                new LocalEcCryptoProvider(ls, fixed).open(blob));
    }

    @Test public void takeHandshakeOpensOnTheMaker() throws Exception {
        CommsIdentity taker = id("seed-taker-S23-0xD56F48");
        CommsIdentity maker = id("seed-maker-ZFold-0xC5828B");
        CryptoProvider takerCrypto = new LocalEcCryptoProvider(ls, taker);
        CryptoProvider makerCrypto = new LocalEcCryptoProvider(ls, maker);

        // taker → maker, exactly as SwapTake.send builds it
        String hash = "0x1234ABCDEF5678";
        JSONObject j = new JSONObject().put("to", maker.publicId()).put("from", taker.publicId()).put("hash", hash);
        String blob = takerCrypto.seal(maker.publicId(), j.toString().getBytes(StandardCharsets.UTF_8));

        Opened o = makerCrypto.open(blob);
        assertNotNull("maker MUST open the taker's handshake", o);
        assertTrue("sender signature must verify", o.valid);
        assertEquals("sender id preserved", taker.publicId(), o.fromPublicId);
        JSONObject got = new JSONObject(new String(o.plaintext, StandardCharsets.UTF_8));
        assertEquals("hashlock delivered intact", hash, got.getString("hash"));
        assertEquals(maker.publicId(), got.getString("to"));
    }

    @Test public void onlyTheAddressedMakerCanOpen() throws Exception {
        CommsIdentity taker = id("t"), maker = id("m"), eavesdropper = id("e");
        String blob = new LocalEcCryptoProvider(ls, taker)
                .seal(maker.publicId(), "{\"hash\":\"0xAA\"}".getBytes(StandardCharsets.UTF_8));
        assertNull("a third party must NOT open a seal addressed to the maker",
                new LocalEcCryptoProvider(ls, eavesdropper).open(blob));
        assertNotNull("the maker opens it", new LocalEcCryptoProvider(ls, maker).open(blob));
    }

    @Test public void identityIsDeterministicPerSeed() {
        assertEquals("same seed → same published id (stable across restarts)", id("same").publicId(), id("same").publicId());
        assertNotEquals("different seeds → different ids (two phones are distinct parties)", id("a").publicId(), id("b").publicId());
        assertTrue(CommsIdentity.isValidPublicId(id("x").publicId()));
    }
}
