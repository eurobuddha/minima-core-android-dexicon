package com.eurobuddha.account;

import static org.junit.Assert.*;

import org.junit.Test;

/** Review 23 Nov 2026 (MINOR): stock Minima Core runs every ';'-separated part of a command; AtomiX and PandaPools sent
 *  commands unchecked. One shared rule now, vendored with the account into all three apps. */
public class CommandSafetyTest {
    @Test public void oneCommandOnly() {
        assertNull(CommandSafety.failure("coins address:0x" + "AB".repeat(32)));
        assertNotNull(CommandSafety.failure("txncreate id:x;vault"));
        assertNotNull(CommandSafety.failure("balance\nvault"));
        assertNotNull(CommandSafety.failure(" "));
        assertNotNull(CommandSafety.failure("x".repeat(100_001)));
    }
}
