// VENDORED VERBATIM from apks/pandapools 0.9.84 app/src/test/java/com/eurobuddha/pandapools/ExistingWalletRecoveryTest.java by scripts/vendor-pandapools.sh — do not edit here; re-vendor.
package com.eurobuddha.pandapools;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.ArrayList;

public class ExistingWalletRecoveryTest {
    void check(BlockOwnerRecoveryTest.Harness h) {
        new BlockOwnerRecovery(h::run,h,new ArrayList<>(h.pools),ignored->{},result->{h.result=result;h.completions++;}).existingOnly().start();
        int steps=0;while(!h.tasks.isEmpty()){assertTrue(++steps<100);h.tasks.remove().run();}
        assertNotNull(h.result);assertEquals(1,h.completions);assertEquals(0,h.mints());assertEquals(0,h.signs());
        for(Pool p:h.pools)assertTrue(p.signingStateUnverified);
    }
    @Test public void existingBlockKeyCanMatchWithoutRegeneration()throws Exception{
        BlockOwnerRecoveryTest.Harness h=new BlockOwnerRecoveryTest.Harness(34,33);check(h);assertTrue(h.result.matched(h.pools.get(0)));
    }
    @Test public void existingClassicKeyCanMatchOnlyOnClassicNode()throws Exception{
        BlockOwnerRecoveryTest.Harness h=new BlockOwnerRecoveryTest.Harness(34,33);h.classic=true;h.classicOwner=true;check(h);assertTrue(h.result.matched(h.pools.get(0)));
        BlockOwnerRecoveryTest.Harness wrong=new BlockOwnerRecoveryTest.Harness(34,33);wrong.classicOwner=true;check(wrong);wrong.assertPaused();
    }
    @Test public void missingKeyNeverRegeneratesEvenWithKnownIndex()throws Exception{
        BlockOwnerRecoveryTest.Harness h=new BlockOwnerRecoveryTest.Harness(32,33);check(h);h.assertPaused();assertTrue(h.result.problems.values().iterator().next().contains("OWNER_KEY_MISSING"));
    }
    @Test public void modeChangeAndUnknownModeRemainBlocked()throws Exception{
        BlockOwnerRecoveryTest.Harness h=new BlockOwnerRecoveryTest.Harness(34,33);h.switchMode=true;check(h);h.assertPaused();
        BlockOwnerRecoveryTest.Harness unknown=new BlockOwnerRecoveryTest.Harness(34,33);unknown.unknownMode=true;check(unknown);unknown.assertPaused();
    }
    @Test public void preservedWalletModeDoesNotDropCounterOrAttemptGuards()throws Exception{
        BoundedRecoveryTest h=new BoundedRecoveryTest();h.setup();try{
            assertNotNull(RestoreExit.refusal(h.f.context,h.pool,0,268435456,2398700));
            assertNull(RestoreExit.refusal(h.f.context,h.pool,0,268435456,2398700,true));
            h.pool.minimumOwnerUses=2;assertNotNull(RestoreExit.refusal(h.f.context,h.pool,0,268435456,2398700,true));
            h.pool.minimumOwnerUses=-1;assertNotNull(RestoreExit.refusal(h.f.context,h.pool,2,268435456,2398700,true));
            h.pool.minimumOwnerUses=0;assertNotNull(RestoreExit.refusal(h.f.context,h.pool,null,268435456,2398700,true));
            assertNotNull(RestoreExit.refusal(h.f.context,h.pool,268435456,268435456,2398700,true));
            assertTrue(ExitStore.beginClose(h.f.context,h.pool));assertNotNull(RestoreExit.refusal(h.f.context,h.pool,2,268435456,2398700,true));
        }finally{h.clear();}
    }
}
