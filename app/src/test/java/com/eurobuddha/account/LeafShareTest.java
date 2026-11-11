package com.eurobuddha.account;

import static org.junit.Assert.*;

import org.junit.Test;
import org.minima.objects.base.MiniData;

import java.util.HashSet;
import java.util.Set;

/**
 * Owner, 11 Nov 2026: AtomiX and PandaPools hold the SAME Trading Account side by side (Dexicon the same words on its
 * own phones). Each app has its own counter, so each must sign only bottom leaves in its own share (index mod 4).
 */
public class LeafShareTest {
    static final long NOW = AccountKeys.EPOCH_MS + 3_600_000L;   // one hour in: time floor = 1800

    /** Records every leaf handed out, as the durable store would. */
    static final class Recording implements KeyUses {
        final AccountKeysTest.Mem m = new AccountKeysTest.Mem();
        final Set<Integer> leaves = new HashSet<>();
        public int currentUses(int k) { return m.currentUses(k); }
        public int reserveNextUse(int k) { int n = m.reserveNextUse(k); leaves.add(n); return n; }
        public void recordExternalUses(int k, int n) { m.recordExternalUses(k, n); }
        public java.util.Map<Integer, Integer> snapshotAllUses() { return m.snapshotAllUses(); }
    }

    static MiniData txn(int i) { return new MiniData("0x" + String.format("%064x", i)); }

    @Test public void twoAppsWithTheSameWordsNeverSignWithTheSameLeaf() {
        Recording atomixStore = new Recording(), poolsStore = new Recording();
        AccountKeys atomix = AccountKeys.fromPhrase(AccountKeysTest.PHRASE, atomixStore, null);
        AccountKeys pools = AccountKeys.fromPhrase(AccountKeysTest.PHRASE, poolsStore, null);
        atomix.setLeafSlot(AccountKeys.SLOT_ATOMIX);
        pools.setLeafSlot(AccountKeys.SLOT_PANDAPOOLS);
        assertEquals("same words, same key", atomix.publicKey(0), pools.publicKey(0));
        for (int i = 0; i < 20; i++) {   // interleaved, same key, same instant: the case two counters would collide on
            atomix.sign(txn(2 * i), 0, NOW);
            pools.sign(txn(2 * i + 1), 0, NOW);
        }
        assertEquals(20, atomixStore.leaves.size());
        assertEquals(20, poolsStore.leaves.size());
        for (int l : atomixStore.leaves) assertEquals("AtomiX leaf " + l, AccountKeys.SLOT_ATOMIX, Math.floorMod(l, AccountKeys.LEAF_SLOTS));
        for (int l : poolsStore.leaves) assertEquals("PandaPools leaf " + l, AccountKeys.SLOT_PANDAPOOLS, Math.floorMod(l, AccountKeys.LEAF_SLOTS));
        Set<Integer> both = new HashSet<>(atomixStore.leaves);
        both.retainAll(poolsStore.leaves);
        assertTrue("no leaf signed by both apps: " + both, both.isEmpty());
    }

    /** An existing Dexicon account (counter anywhere) moves UP to its next share leaf — never down, never a used leaf. */
    @Test public void anExistingCounterOnlyMovesUpIntoItsShare() {
        Recording store = new Recording();
        store.recordExternalUses(0, 1_000_003);   // next unused leaf 1,000,003 (share 3)
        AccountKeys dexicon = AccountKeys.fromPhrase(AccountKeysTest.PHRASE, store, null);
        dexicon.setLeafSlot(AccountKeys.SLOT_DEXICON);
        dexicon.sign(txn(1), 0, NOW);
        assertEquals(java.util.Collections.singleton(1_000_004), store.leaves);
        dexicon.sign(txn(2), 0, NOW);
        assertTrue("the next one is the share's next leaf", store.leaves.contains(1_000_008));
    }

    @Test public void alignmentAndBounds() {
        assertEquals(1801, AccountKeys.alignToSlot(1800, 1));
        assertEquals(1800, AccountKeys.alignToSlot(1800, 0));
        assertEquals(1803, AccountKeys.alignToSlot(1801, 3));
        AccountKeys k = AccountKeys.fromPhrase(AccountKeysTest.PHRASE, new AccountKeysTest.Mem(), null);
        try { k.setLeafSlot(4); fail("slot 4 accepted"); } catch (IllegalArgumentException expected) { }
        try { k.setLeafSlot(-1); fail("slot -1 accepted"); } catch (IllegalArgumentException expected) { }
        try { new Account.Config("x", null, null, null, null, 7); fail("config slot 7 accepted"); } catch (IllegalArgumentException expected) { }
    }

    /** A store that hands out a leaf outside the share (a corrupted or foreign counter) is refused, not signed. */
    @Test public void aLeafOutsideTheShareIsNeverSigned() {
        KeyUses liar = new KeyUses() {
            int n = 5000;
            public int currentUses(int k) { return n; }
            public int reserveNextUse(int k) { return n++; }   // ignores the raise: returns 5000 (share 0)
            public void recordExternalUses(int k, int v) { }
            public java.util.Map<Integer, Integer> snapshotAllUses() { return new java.util.HashMap<>(); }
        };
        AccountKeys k = AccountKeys.fromPhrase(AccountKeysTest.PHRASE, liar, null);
        k.setLeafSlot(AccountKeys.SLOT_ATOMIX);
        try { k.sign(txn(9), 0, NOW); fail("signed outside its share"); }
        catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("outside this app's share")); }
    }
}
