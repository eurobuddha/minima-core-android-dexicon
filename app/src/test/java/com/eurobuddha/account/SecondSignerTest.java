package com.eurobuddha.account;

import static org.junit.Assert.*;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.minima.objects.base.MiniData;
import org.minima.objects.keys.Signature;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Review 23 Nov 2026 (MAJOR-A): two devices with the same words in the same app WILL reuse a one-time leaf. Every spend
 * of an account coin carries its signature on chain; a signature by an account key over a transaction this install never
 * signed means another install signs too. The node's own JSON (history / txpow) is rebuilt here from real signatures.
 */
public class SecondSignerTest {

    /** Persists like the app's prefs: one instance outlives a "restart" (a new VirtualWallet over it). */
    static final class Store extends VirtualWalletTest.Store {
        final Set<String> signed = new HashSet<>();
        String state = "{}";
        int floor = -1;
        public boolean recordSigned(String id) { signed.add(id); return true; }
        public boolean signedHere(String id) { return signed.contains(id); }
        public String signerState() { return state; }
        public boolean saveSignerState(String j) { state = j; return true; }
        public void saveRestoreFloor(int f) { floor = Math.max(floor, f); }
        final Set<String> own = new HashSet<>();
        public void ownOutputs(java.util.Collection<String> ids) { own.addAll(ids); }
        public boolean isOwnOutput(String id) { return own.contains(id); }
    }

    /** A node whose relevant history is {@code items}, newest first (the order of `history action:list`). */
    static final class Node extends VirtualWalletTest.FakeNode {
        final List<JSONObject> items = new ArrayList<>();
        boolean dieOnRead;
        @Override public JSONObject call(String cmd) throws Exception {
            if (cmd.equals("history action:size")) { calls.add(cmd); return new JSONObject().put("status", true).put("response", new JSONObject().put("size", items.size())); }
            if (cmd.startsWith("history action:list max:1 offset:")) {
                calls.add(cmd);
                if (dieOnRead) throw new VirtualWalletTest.Died();
                int off = Integer.parseInt(cmd.substring(cmd.lastIndexOf(':') + 1));
                return new JSONObject().put("status", true).put("response", new JSONObject().put("txpows",
                        off < items.size() ? new JSONArray().put(items.get(off)) : new JSONArray()));
            }
            return super.call(cmd);
        }
        void add(JSONObject txpow) { items.add(0, txpow); }
    }

    static int n;
    static String txnId() { return "0x" + String.format("%064X", 0xABC000L + (++n)); }

    /** The TxPoW JSON the node prints for a transaction {@code id} carrying {@code sigs}. */
    static JSONObject txpow(String id, Signature... sigs) throws Exception {
        JSONArray w = new JSONArray();
        for (Signature s : sigs) w.put(new JSONObject(s.toJSON().toString()));
        return new JSONObject().put("txpowid", "0x" + String.format("%064X", 0xF00000L + (++n))).put("hasbody", true)
                .put("body", new JSONObject().put("txn", new JSONObject().put("transactionid", id).put("inputs", new JSONArray()).put("outputs", new JSONArray()))
                        .put("witness", new JSONObject().put("signatures", w)));
    }

    /** Another install of the same words (its own counter), signing in {@code slot} at leaf {@code >= at}. */
    static Signature foreign(int slot, int at, String id) {
        AccountKeysTest.Mem m = new AccountKeysTest.Mem();
        m.recordExternalUses(0, at);
        AccountKeys other = AccountKeys.fromPhrase(AccountKeysTest.PHRASE, m, null);
        other.setLeafSlot(slot);
        return other.sign(new MiniData(id), 0, System.currentTimeMillis());
    }

    static VirtualWallet wallet(Node node, Store store, AccountKeysTest.Mem uses, int slot, int restoreFloor) {
        AccountKeys k = AccountKeys.fromPhrase(AccountKeysTest.PHRASE, uses, null);
        k.setLeafSlot(slot);
        k.setRestoreFloor(restoreFloor);
        return new VirtualWallet(node, k, store, Collections.emptySet());
    }

    static int leafOf(Signature s) throws Exception {
        return SignerWatch.leafOf(new JSONObject(s.toJSON().toString()).getJSONArray("signatures"));
    }

    @Test public void theLeafIsReadBackFromTheSignatureProofs() throws Exception {
        for (int leaf : new int[]{0, 1, 127, 128, 129, 16_384, 16_384 * 3 + 128 * 5 + 7, 2_097_152 + 1, 28_000_123, 268_435_455}) {
            AccountKeysTest.Mem m = new AccountKeysTest.Mem();
            m.recordExternalUses(0, leaf);
            AccountKeys k = AccountKeys.fromPhrase(AccountKeysTest.PHRASE, m, null);
            Signature s = k.sign(new MiniData("0x" + "AB".repeat(32)), 0, AccountKeys.EPOCH_MS);
            assertEquals(leaf, leafOf(s));
        }
    }

    @Test public void onlyVerifiedAccountSignaturesCount() throws Exception {
        String id = txnId();
        Signature s = foreign(0, 40_000_000, id);
        VirtualWallet w = wallet(new Node(), new Store(), new AccountKeysTest.Mem(), 0, 0);
        SignerWatch.KeyIndex k0 = pk -> pk.equalsIgnoreCase(w.handle("getaddress").optJSONObject("response").optString("publickey")) ? 0 : -1;
        assertEquals(1, SignerWatch.signatures(txpow(id, s), k0).size());
        assertEquals(40_000_000, SignerWatch.signatures(txpow(id, s), k0).get(0).leaf);
        assertTrue("a signature over another transaction id is not ours", SignerWatch.signatures(txpow(txnId(), s), k0).isEmpty());
        assertTrue("not an account key", SignerWatch.signatures(txpow(id, s), pk -> -1).isEmpty());
    }

    @Test public void ownSpendIsNeverASecondSigner() throws Exception {
        Node node = new Node(); Store store = new Store(); AccountKeysTest.Mem uses = new AccountKeysTest.Mem();
        VirtualWallet w = wallet(node, store, uses, AccountKeys.SLOT_DEXICON, 0);
        w.watchSigners();                                           // first pass: a baseline
        String pub = w.handle("getaddress").getJSONObject("response").getString("publickey");
        assertTrue(w.handle("txncreate id:own").getBoolean("status"));
        assertTrue(w.handle("txnoutput id:own amount:1 address:0xFFEEDD9999").getBoolean("status"));
        JSONObject signed = w.handle("txnsign id:own publickey:" + pub);
        assertTrue(signed.toString(), signed.getBoolean("status"));
        org.minima.database.userprefs.txndb.TxnRow row = org.minima.database.userprefs.txndb.TxnRow.convertMiniDataVersion(
                new MiniData(w.handle("txnexport id:own").getJSONObject("response").getString("data")));
        String id = row.getTransaction().getTransactionID().to0xString();
        assertTrue("recorded before posting", store.signedHere(VirtualWallet.norm(id)));
        node.add(txpow(id, row.getWitness().getAllSignatures().get(0)));
        w.watchSigners();
        assertNull(w.secondSigner());
    }

    @Test public void aForeignSignatureAtOrAboveTheFloorHaltsAndRaises() throws Exception {
        Node node = new Node(); Store store = new Store(); AccountKeysTest.Mem uses = new AccountKeysTest.Mem();
        VirtualWallet w = wallet(node, store, uses, AccountKeys.SLOT_DEXICON, 0);
        w.watchSigners();
        String id = txnId();
        Signature s = foreign(AccountKeys.SLOT_DEXICON, 60_000_000, id);
        int leaf = leafOf(s);
        node.add(txpow(id, s));
        w.watchSigners();
        assertEquals(VirtualWallet.ANOTHER_DEVICE, w.secondSigner());
        assertTrue(w.secondSignerDetail(), w.secondSignerDetail().contains(id));
        assertTrue("raised past the foreign leaf", uses.currentUses(0) > leaf);
        assertEquals("in this app's share", AccountKeys.SLOT_DEXICON, Math.floorMod(uses.currentUses(0), AccountKeys.LEAF_SLOTS));
        String pub = w.handle("getaddress").getJSONObject("response").getString("publickey");
        w.handle("txncreate id:x");
        int before = uses.currentUses(0);
        JSONObject r = w.handle("txnsign id:x publickey:" + pub);
        assertFalse(r.getBoolean("status"));
        assertEquals(VirtualWallet.ANOTHER_DEVICE, r.getString("error"));
        assertEquals("no leaf spent while halted", before, uses.currentUses(0));

        VirtualWallet restarted = wallet(node, store, uses, AccountKeys.SLOT_DEXICON, 0);   // the latch survives a restart
        assertEquals(VirtualWallet.ANOTHER_DEVICE, restarted.secondSigner());
        restarted.handle("txncreate id:y");
        assertFalse(restarted.handle("txnsign id:y publickey:" + pub).getBoolean("status"));

        int reads = (int) node.calls.stream().filter(c -> c.startsWith("history action:size")).count();
        restarted.onlyDeviceNow();                                  // the confirmed un-latch re-reads the chain first
        assertTrue(node.calls.stream().filter(c -> c.startsWith("history action:size")).count() > reads);
        assertNull(restarted.secondSigner());
        long now = System.currentTimeMillis();
        int margin = AccountKeys.restoreFloor(now) - AccountKeys.timeFloor(now);
        assertTrue("past everything seen plus the restore margin", uses.currentUses(0) >= leaf + margin);
        assertTrue(store.floor >= AccountKeys.restoreFloor(now) - 5);
        assertTrue(restarted.handle("txnsign id:y publickey:" + pub).getBoolean("status"));
    }

    @Test public void belowTheRestoreFloorItOnlyRaises() throws Exception {
        Node node = new Node(); Store store = new Store(); AccountKeysTest.Mem uses = new AccountKeysTest.Mem();
        String id = txnId();
        Signature s = foreign(AccountKeys.SLOT_DEXICON, 50_000_000, id);
        int leaf = leafOf(s);
        VirtualWallet w = wallet(node, store, uses, AccountKeys.SLOT_DEXICON, leaf + 1_000);   // restored after that install
        w.watchSigners();
        node.add(txpow(id, s));
        w.watchSigners();
        assertNull("an earlier install of the words", w.secondSigner());
        assertTrue(uses.currentUses(0) > leaf);
    }

    @Test public void theSiblingAppsShareIsItsOwnToJudge() throws Exception {
        Node node = new Node(); Store store = new Store(); AccountKeysTest.Mem uses = new AccountKeysTest.Mem();
        VirtualWallet atomix = wallet(node, store, uses, AccountKeys.SLOT_ATOMIX, 0);
        atomix.watchSigners();
        String id = txnId();
        node.add(txpow(id, foreign(AccountKeys.SLOT_PANDAPOOLS, 70_000_000, id)));   // PandaPools beside AtomiX
        atomix.watchSigners();
        assertNull(atomix.secondSigner());
        assertEquals("not raised by the sibling's leaves", 0, uses.currentUses(0));
        String id2 = txnId();
        node.add(txpow(id2, foreign(AccountKeys.SLOT_DEXICON, 70_000_100, id2)));    // Dexicon can never share this phone
        atomix.watchSigners();
        assertEquals(VirtualWallet.ANOTHER_DEVICE, atomix.secondSigner());
    }

    @Test public void anItemThatKilledTheProcessIsSkippedForGood() throws Exception {
        Node node = new Node(); Store store = new Store(); AccountKeysTest.Mem uses = new AccountKeysTest.Mem();
        VirtualWallet w = wallet(node, store, uses, AccountKeys.SLOT_DEXICON, 0);
        w.watchSigners();
        String id = txnId();
        node.add(txpow(id, foreign(AccountKeys.SLOT_DEXICON, 60_000_000, id)));
        node.dieOnRead = true;
        try { w.watchSigners(); fail("the read died"); } catch (VirtualWalletTest.Died expected) { }
        assertTrue("the mark was on disk before the read", new JSONObject(store.state).has("reading"));
        node.dieOnRead = false;
        VirtualWallet restarted = wallet(node, store, uses, AccountKeys.SLOT_DEXICON, 0);
        restarted.watchSigners();
        restarted.watchSigners();
        assertFalse(new JSONObject(store.state).has("reading"));
        assertEquals("never read again", 1, node.calls.stream().filter(c -> c.startsWith("history action:list")).count());
    }

    /** MINOR (review 23 Nov): AtomiX and PandaPools share one account with separate records, so each filed the other's
     *  change as "Received". A transaction spending only account coins makes internal outputs, whoever signed it. */
    @Test public void outputsOfATransactionFundedOnlyByTheAccountAreNeverReceived() throws Exception {
        Node node = new Node(); Store store = new Store();
        VirtualWallet w = wallet(node, store, new AccountKeysTest.Mem(), AccountKeys.SLOT_ATOMIX, 0);
        String mine = w.handle("getaddress").getJSONObject("response").getString("address");
        String change = "0x" + "C1".repeat(32), deposit = "0x" + "D2".repeat(32);
        JSONObject sibling = txpow(txnId());
        sibling.getJSONObject("body").getJSONObject("txn").put("inputs", new JSONArray().put(new JSONObject().put("address", mine)))
                .put("outputs", new JSONArray().put(new JSONObject().put("coinid", change).put("address", mine)));
        JSONObject fromOutside = txpow(txnId());
        fromOutside.getJSONObject("body").getJSONObject("txn").put("inputs", new JSONArray()
                        .put(new JSONObject().put("address", mine)).put(new JSONObject().put("address", "0x" + "99".repeat(32))))
                .put("outputs", new JSONArray().put(new JSONObject().put("coinid", deposit).put("address", mine)));
        node.add(sibling); node.add(fromOutside);
        w.watchSigners();
        assertTrue("the sibling's change is internal", w.isOwnOutput(change));
        assertFalse("coins from outside stay Received", w.isOwnOutput(deposit));
    }
}
