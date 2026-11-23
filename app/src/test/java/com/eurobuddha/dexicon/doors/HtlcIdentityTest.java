package com.eurobuddha.dexicon.doors;

import static org.junit.Assert.*;

import com.eurobuddha.atomix.swap.MinimaHtlc;

import org.junit.Test;

/** The host-mode "start fresh" step forgets MinimaHtlc's adopted identity from OUTSIDE the vendored file (it stays
 *  byte-identical to AtomiX). Fails if AtomiX renames the two fields — re-check HtlcIdentity on every re-vendor. */
public class HtlcIdentityTest {
    @Test public void forgetsTheAdoptedIdentity() throws Exception {
        MinimaHtlc m = new MinimaHtlc(null);
        for (String f : new String[]{"myAddress", "myPubkey"}) {
            java.lang.reflect.Field x = MinimaHtlc.class.getDeclaredField(f); x.setAccessible(true); x.set(m, "0xAB");
        }
        assertTrue(m.ready());
        HtlcIdentity.forget(m);
        assertFalse("the next setup() adopts a node key afresh", m.ready());
        assertNull(m.myAddress());
        assertNull(m.myPubkey());
    }
}
