package com.eurobuddha.dexicon.account;

import static org.junit.Assert.*;

import org.junit.Test;
import org.minima.objects.base.MiniData;
import org.minima.objects.keys.Signature;
import org.minima.objects.keys.TreeKey;
import org.minima.utils.BIP39;
import org.minima.utils.Crypto;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;

public class AccountKeysTest {

    static final String PHRASE = "ABANDON ABILITY ABLE ABOUT ABOVE ABSENT ABSORB ABSTRACT ABSURD ABUSE ACCESS ACCIDENT "
            + "ACCOUNT ACCUSE ACHIEVE ACID ACOUSTIC ACQUIRE ACROSS ACT ACTION ACTOR ACTRESS ACTUAL";

    static final class Mem implements KeyUses {
        final Map<Integer, Integer> u = new HashMap<>();
        public int currentUses(int k) { return u.getOrDefault(k, 0); }
        public int reserveNextUse(int k) { int n = currentUses(k); u.put(k, n + 1); return n; }
        public void recordExternalUses(int k, int n) { u.put(k, Math.max(currentUses(k), n)); }
        public Map<Integer, Integer> snapshotAllUses() { return new HashMap<>(u); }
    }

    @Test public void derivationIsDeterministicAndDomainSeparated() {
        AccountKeys a = AccountKeys.fromPhrase(PHRASE, new Mem(), null);
        AccountKeys b = AccountKeys.fromPhrase(PHRASE.toLowerCase() + "  ", new Mem(), null);
        assertEquals(a.publicKey(0), b.publicKey(0));
        assertEquals(a.address(0), b.address(0));
        assertNotEquals(a.address(0), a.address(1));
        // A Minima node (or apks/wallet) restored from the SAME words must NOT reproduce these keys: two independent
        // signers on one Winternitz tree reuse a leaf.
        MiniData nodeSeed = Crypto.getInstance().hashObjects(BIP39.convertStringToSeed(BIP39.cleanSeedPhrase(PHRASE)), new MiniData(BigInteger.ZERO));
        assertNotEquals(new TreeKey(nodeSeed, 64, 3).getPublicKey().to0xString(), a.publicKey(0));
        assertNotEquals(new TreeKey(nodeSeed, 128, 4).getPublicKey().to0xString(), a.publicKey(0));
        assertEquals("RETURN SIGNEDBY(" + a.publicKey(0) + ")", a.script(0));
    }

    static final long NOW = AccountKeys.EPOCH_MS + 10_000_000_000L;   // ~115 days after the epoch

    @Test public void signReservesAtLeastTheTimeFloorAndVerifies() {
        Mem uses = new Mem();
        AccountKeys k = AccountKeys.fromPhrase(PHRASE, uses, null);
        MiniData id = new MiniData("0x" + "AB".repeat(32));
        Signature s0 = k.sign(id, 0, NOW);
        Signature s1 = k.sign(id, 0, NOW);
        int floor = AccountKeys.timeFloor(NOW);
        assertEquals(floor + 2, uses.currentUses(0));
        TreeKey verifier = new TreeKey();
        verifier.setPublicKey(new MiniData(k.publicKey(0)));
        assertTrue(verifier.verify(id, s0));
        assertTrue(verifier.verify(id, s1));
        assertEquals(268_435_456, k.maxUses());
    }

    @Test public void restoreThenReinstallThenRestoreNeverRepeatsALeaf() {
        // Install 1 signs, then is wiped (counters gone); install 2 restores the same words LATER; then again.
        java.util.Set<Integer> leaves = new java.util.HashSet<>();
        long t = NOW;
        for (int install = 0; install < 3; install++) {
            Mem fresh = new Mem();                         // a reinstall / clear-data loses every counter
            AccountKeys k = AccountKeys.fromPhrase(PHRASE, fresh, null);
            for (int sig = 0; sig < 5; sig++) {
                k.sign(new MiniData("0x" + String.format("%064x", install * 100 + sig)), 0, t);
                int leaf = fresh.currentUses(0) - 1;
                assertTrue("leaf " + leaf + " reused on install " + install, leaves.add(leaf));
                t += 1_000;                                 // 5 signatures in 5 s (faster than the 2 s floor step)
            }
            t += 60_000;                                    // the restore happens a minute later
        }
    }

    @Test public void refusesPastCapacityInsteadOfWrappingToLeafZero() {
        Mem uses = new Mem();
        uses.recordExternalUses(0, 268_435_456);
        AccountKeys k = AccountKeys.fromPhrase(PHRASE, uses, null);
        try { k.sign(new MiniData("0x01"), 0, NOW); fail("signed past capacity"); }
        catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("exhausted")); }
    }

    @Test public void cachedPublicKeyIsUsedButAMismatchRefusesToSign() {
        Map<Integer, String> store = new HashMap<>();
        AccountKeys.PubCache cache = new AccountKeys.PubCache() { public String get(int i) { return store.get(i); } public void put(int i, String p) { store.put(i, p); } };
        AccountKeys k = AccountKeys.fromPhrase(PHRASE, new Mem(), cache);
        String pk = k.publicKey(0);
        assertEquals(pk, store.get(0));
        store.put(0, "0x" + "00".repeat(32));
        AccountKeys poisoned = AccountKeys.fromPhrase(PHRASE, new Mem(), cache);
        try { poisoned.sign(new MiniData("0x01"), 0, NOW); fail("signed with a mismatched cached key"); }
        catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("does not match")); }
    }

    @Test public void seedRandomMatchesTheNodeFormula() {
        AccountKeys k = AccountKeys.fromPhrase(PHRASE, new Mem(), null);
        String a = k.seedRandom("ethbridge"), b = k.seedRandom("ethbridge"), c = k.seedRandom("dexicon-identity-v1");
        assertEquals(a, b);
        assertNotEquals(a, c);
        assertTrue(a.matches("0x[0-9A-F]{64}"));
    }
}
