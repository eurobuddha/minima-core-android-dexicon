package com.eurobuddha.pandapools;

import static org.junit.Assert.*;

import com.eurobuddha.account.AccountKeys;
import com.eurobuddha.account.AccountTestAccess;
import com.eurobuddha.account.VirtualWallet;

import org.json.JSONObject;
import org.junit.Test;

/**
 * Stock Minima Core, Trading Account: a pool created on phone A (owner key = account key 2) is recovered on phone B
 * restored from the same 24 words, through PandaPools' own BlockOwnerRecovery against the account wallet — then the
 * owner key's counter must not read as regressed against the count the backup recorded (opkuses).
 */
public class AccountRestoreRecoveryTest {
    @Test public void aPoolOwnedByTheAccountRecoversOnARestoredPhone() throws Exception {
        long now = System.currentTimeMillis();
        // phone A: two newaddress calls -> the pool's owner key is account key 2; it signed a few times
        AccountKeys keysA = AccountTestAccess.keys();
        VirtualWallet a = AccountTestAccess.wallet(keysA);
        a.handle("newaddress"); a.handle("newaddress");
        String opk = keysA.publicKey(2);
        int recorded = keysA.reportedUses(2, now) + 5;           // the backup's opkuses: a few signatures past the floor

        Pool p = new Pool(); p.opk = opk; p.kidx = 2; p.address = "0x" + "AB".repeat(32);
        p.minimumOwnerUses = recorded; p.signingStateUnverified = true;

        // phone B: same words, restored (restore floor), fresh key count
        AccountKeys keysB = AccountTestAccess.keys();
        keysB.setRestoreFloor(AccountKeys.restoreFloor(now));
        VirtualWallet b = AccountTestAccess.wallet(keysB);
        FundingCoins.Command node = (cmd, cb) -> {
            try {
                if (cmd.equals("status")) { cb.onResult(new JSONObject().put("status", true).put("response", new JSONObject().put("locked", false))); return; }
                JSONObject r = b.handle(cmd);
                if (r == null) cb.onError("not a wallet command: " + cmd); else cb.onResult(r);
            } catch (Exception e) { cb.onError(e.toString()); }
        };
        BlockOwnerRecovery.Result[] out = {null};
        new BlockOwnerRecovery(node, new BlockOwnerRecovery.State() {
            public int searchLimit(String fp, String owner, int total) { return total + BlockOwnerRecovery.UNKNOWN_INDEX_BUDGET; }
            public boolean recordMatch(String owner, int index) { return true; }
        }, java.util.Collections.singletonList(p), m -> { }, r -> out[0] = r).start();

        assertNotNull("recovery finished", out[0]);
        assertEquals(BlockOwnerRecovery.Mode.BLOCK, out[0].mode);
        assertTrue("owner key found on the restored phone: " + out[0].problems, out[0].matched(p));

        JSONObject row = b.handle("keys action:list publickey:" + opk);
        Integer uses = KeyUses.extractUses(row, opk);
        assertNotEquals(OwnerKeyRecovery.Reason.COUNTER_REGRESSED, OwnerKeyRecovery.classify(true, true, uses, keysB.maxUses(), p, false));
        assertTrue("the restored counter is at or past what the backup recorded", uses >= recorded);
    }
}
