// VENDORED VERBATIM from apks/pandapools 0.9.97 app/src/test/java/com/eurobuddha/pandapools/BackupCheckTest.java by scripts/vendor-pandapools.sh — do not edit here; re-vendor.
package com.eurobuddha.pandapools;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The backup dry-run must never change anything.
 *
 * This is the "look at my file without committing to a restore" path, and it only earns that description if
 * read-only-ness is enforced in code rather than promised in a comment. The allowlist is the enforcement point,
 * so it is pinned here — including the write commands that would be the actual accidents.
 */
public class BackupCheckTest {

    private static final String HEX64 = "0x" + String.join("", java.util.Collections.nCopies(64, "a"));

    @Test public void theChecksOwnCommandsAreAllowed() {
        assertTrue(BackupCheck.readOnly("runscript script:\"RETURN TRUE\""));
        assertTrue(BackupCheck.readOnly("coins address:" + HEX64));
        assertTrue(BackupCheck.readOnly("coins coinid:" + HEX64));
        assertTrue(BackupCheck.readOnly("balance address:" + HEX64));
        assertTrue(BackupCheck.readOnly("keys action:list publickey:" + HEX64));
    }

    @Test public void everyWritingCommandIsRefused() {
        // Each of these would make a "check" change state. None may ever be reachable from this path.
        for (String cmd : new String[]{
                "newscript trackall:true script:\"RETURN TRUE\"",
                "coinimport track:true data:0xdead",
                "coincheck data:0xdead",
                "coinnotify action:add address:" + HEX64,
                "txncreate id:ppclose_1",
                "txninput id:ppclose_1 coinid:" + HEX64,
                "txnoutput id:ppclose_1 amount:1 address:" + HEX64,
                "txnsign id:ppclose_1 publickey:auto",
                "txnpost id:ppclose_1",
                "txndelete id:ppclose_1",
                "sign publickey:" + HEX64 + " data:0x00",
                "send amount:1 address:" + HEX64,
                "consolidate tokenid:0x00",
                "newaddress",
                "keys action:new",
                "keys action:genkey",
                "keys action:createallkeys",
                "backup password:x",
                "restore file:x",
                "vault",
        }) assertFalse(cmd, BackupCheck.readOnly(cmd));
    }

    @Test public void bareKeysIsRefusedBecauseItHasCreatingSubcommands() {
        // `keys` on its own is a read, but the prefix must not open the door to `keys action:new`.
        assertFalse(BackupCheck.readOnly("keys"));
        assertFalse(BackupCheck.readOnly("keys action:new"));
        assertTrue(BackupCheck.readOnly("keys action:list"));
    }

    @Test public void aChainedCommandIsRefused() {
        // A permitted prefix must not smuggle a second command through.
        assertFalse(BackupCheck.readOnly("coins address:" + HEX64 + ";send amount:1 address:" + HEX64));
        assertFalse(BackupCheck.readOnly("coins address:" + HEX64 + "\nnewaddress"));
        assertFalse(BackupCheck.readOnly("runscript script:\"RETURN TRUE\";txnpost id:x"));
    }

    @Test public void anUnboundedOrWideningReadIsRefused() {
        // `coins` with no address/coinid filter can return a huge reply; the check never needs one.
        assertFalse(BackupCheck.readOnly("coins"));
        assertFalse(BackupCheck.readOnly("coins relevant:true"));
        assertFalse(BackupCheck.readOnly("coins address:" + HEX64 + " megammr:true"));
    }

    @Test public void nullAndEmptyAreRefused() {
        assertFalse(BackupCheck.readOnly(null));
        assertFalse(BackupCheck.readOnly(""));
        assertFalse(BackupCheck.readOnly("   "));
    }
}
