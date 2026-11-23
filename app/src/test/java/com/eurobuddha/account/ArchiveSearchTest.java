package com.eurobuddha.account;

import static org.junit.Assert.*;

import android.os.Looper;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** "Search the archive again" (Account.searchArchive) end to end, on the real worker threads — the archive download is
 *  replaced (Account.archive), so no test contacts the archive or any node. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class ArchiveSearchTest {
    private final Account.Fetch real = Account.archive;
    private final Account.Config saved = Account.config();

    @After public void restore() { Account.archive = real; Account.configure(saved); }

    /** The node answers through the account's own Transport, as on a phone (replies on the main thread). */
    static Account account(VirtualWalletTest.FakeNode node, boolean nodeDown) throws Exception {
        Account.configure(new Account.Config("Dexicon", null, null, null, null, AccountKeys.SLOT_DEXICON));
        Transport t = transport(node, nodeDown);
        java.lang.reflect.Constructor<Account> c = Account.class.getDeclaredConstructor(android.content.Context.class, String.class, String.class, int.class, boolean.class, Transport.class);
        c.setAccessible(true);
        return c.newInstance(RuntimeEnvironment.getApplication(), AccountKeysTest.PHRASE, AccountVault.MODE_PHRASE, 0, false, t);
    }

    static Transport transport(VirtualWalletTest.FakeNode node, boolean nodeDown) {
        return (cmd, guard, timeout, cb) -> {
            if (nodeDown) { cb.onError("Minima Core is not running"); return; }
            try { cb.onResult(node.call(cmd)); } catch (Exception e) { cb.onError(e.toString()); }
        };
    }

    /** The connection the account was opened with (as if the same screen is still open). */
    static Transport transportOf(Account a) throws Exception {
        java.lang.reflect.Field f = Account.class.getDeclaredField("current"); f.setAccessible(true);
        return (Transport) f.get(a);
    }

    static VirtualWallet wallet(Account a) throws Exception {
        java.lang.reflect.Field f = Account.class.getDeclaredField("wallet"); f.setAccessible(true);
        return (VirtualWallet) f.get(a);
    }

    static String search(Account a, List<String> progress) throws Exception { return search(a, transportOf(a), progress); }

    static String search(Account a, Transport screen, List<String> progress) throws Exception {
        AtomicReference<String> done = new AtomicReference<>();
        a.searchArchive(screen, progress::add, done::set);
        long end = System.currentTimeMillis() + 60_000;
        while (done.get() == null && System.currentTimeMillis() < end) { org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5); }
        assertNotNull("the search never finished", done.get());
        return done.get();
    }

    /** A wallet call off the main thread (as the worker makes it), the main looper running its node replies. */
    static <T> T off(java.util.concurrent.Callable<T> c) throws Exception {
        java.util.concurrent.FutureTask<T> f = new java.util.concurrent.FutureTask<>(c);
        new Thread(f).start();
        while (!f.isDone()) { org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5); }
        return f.get();
    }

    static String address0() { return AccountKeys.fromPhrase(AccountKeysTest.PHRASE, new AccountKeysTest.Mem(), null).address(0); }

    /** The automatic mark says "done" on this node — the manual search runs anyway, finds the coin, and a second press
     *  imports nothing. Nothing is signed or sent. */
    @Test public void runsWithTheMarkPresentAndIsSafeToRepeat() throws Exception {
        VirtualWalletTest.ArchiveNode node = new VirtualWalletTest.ArchiveNode();
        Account a = account(node, false);
        VirtualWallet w = wallet(a);
        w.markArchived(VirtualWalletTest.nodeFolder(), 1);
        assertNull("the automatic pass would not run", off(w::archiveWanted));
        Account.archive = addrs -> Collections.singletonList(VirtualWalletTest.archived("0x44", address0(), 100 - 256));
        List<String> progress = new ArrayList<>();
        String r = search(a, progress);
        assertEquals("Found 1 coin your node did not have: 5 MINIMA.\n\nAnswered by the MegaMMR archive at eurobuddha.com:9001 "
                + "(1 coin at this account's addresses). It reports only the coins that archive holds.\nNothing was moved and no key was used.", r);
        assertTrue(progress.toString(), progress.contains("Asking the archive at eurobuddha.com:9001 about 1 address (a download of a few MB)…"));
        assertEquals(1, node.imports());
        assertTrue(search(a, new ArrayList<>()).startsWith("Nothing new found."));
        assertEquals("pressed again: nothing imported twice", 1, node.imports());
        assertFalse(node.calls.stream().anyMatch(c -> c.startsWith("txn") || c.startsWith("postfrom") || c.startsWith("send")));
        assertNull("a clean search records the mark", off(w::archiveWanted));
    }

    @Test public void anUnreachableArchiveIsNamed() throws Exception {
        Account a = account(new VirtualWalletTest.ArchiveNode(), false);
        Account.archive = addrs -> { throw new java.net.ConnectException("Connection refused"); };
        assertEquals("No answer from the archive at eurobuddha.com:9001: Connection refused.\n\nNothing was moved and no key was used.",
                search(a, new ArrayList<>()));
    }

    @Test public void aNodeThatDoesNotAnswerIsNamed() throws Exception {
        Account a = account(new VirtualWalletTest.ArchiveNode(), true);
        Account.archive = addrs -> { fail("the archive is not asked without the node"); return null; };
        assertEquals("Your node did not answer: Minima Core is not running.\n\nNothing was moved and no key was used.", search(a, new ArrayList<>()));
    }

    @Test public void aRefusedImportIsNamedAndLeavesTheMarkOpen() throws Exception {
        VirtualWalletTest.ArchiveNode node = new VirtualWalletTest.ArchiveNode(); node.synced = false;
        Account a = account(node, false);
        Account.archive = addrs -> Collections.singletonList(VirtualWalletTest.archived("0x44", address0(), 100 - 256));
        String r = search(a, new ArrayList<>());
        assertTrue(r, r.startsWith("Nothing new found.\n\nYour node did not take 1 coin the archive listed — search again later:\n0x44 — "));
        assertNotNull("not clean: the automatic pass still wants it", off(wallet(a)::archiveWanted));
    }

    @Test public void oneSearchAtATime() throws Exception {
        Account a = account(new VirtualWalletTest.ArchiveNode(), false);
        java.util.concurrent.CountDownLatch hold = new java.util.concurrent.CountDownLatch(1);
        Account.archive = addrs -> { hold.await(); return Collections.emptyList(); };
        AtomicReference<String> first = new AtomicReference<>();
        a.searchArchive(transportOf(a), null, first::set);
        assertEquals(Account.ARCHIVE_BUSY, search(a, new ArrayList<>()));
        hold.countDown();
        long end = System.currentTimeMillis() + 60_000;
        while (first.get() == null && System.currentTimeMillis() < end) { org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5); }
        assertTrue(first.get(), first.get().startsWith("Nothing new found."));
    }

    /** Review NIT: a pass that started before the node lost the account's script rows must not record the mark after the
     *  loss reset it — what it imported may have gone with the wallet table. */
    @Test public void aPassThatStartedBeforeALossDoesNotMarkAfterIt() throws Exception {
        VirtualWalletTest.ScriptTableNode node = new VirtualWalletTest.ScriptTableNode();
        Account a = account(node, false);
        VirtualWallet w = wallet(a);
        java.util.concurrent.CountDownLatch fetching = new java.util.concurrent.CountDownLatch(1), hold = new java.util.concurrent.CountDownLatch(1);
        Account.archive = addrs -> { fetching.countDown(); hold.await(); return Collections.emptyList(); };
        AtomicReference<String> done = new AtomicReference<>();
        a.searchArchive(transportOf(a), null, done::set);
        while (fetching.getCount() > 0) { org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5); }
        node.table.clear();                 // the node is reseeded while the archive answers
        off(() -> { w.ensureTracked(); return null; });
        hold.countDown();
        long end = System.currentTimeMillis() + 60_000;
        while (done.get() == null && System.currentTimeMillis() < end) { org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5); }
        assertNotNull(done.get());
        assertEquals("the older pass did not record the mark", VirtualWalletTest.nodeFolder(), off(w::archiveWanted));
    }

    /** Review NIT: the pending reset is kept in the account's prefs, so a process death before the node's status answers
     *  cannot lose it. */
    @Test public void aPendingResetIsKeptInThePrefs() throws Exception {
        VirtualWalletTest.ScriptTableNode node = new VirtualWalletTest.ScriptTableNode() {
            boolean statusDown = true;
            @Override public JSONObject call(String cmd) throws Exception {
                if (cmd.equals("status") && statusDown && !table.isEmpty()) { statusDown = false; return new JSONObject().put("status", false).put("error", "busy"); }
                return super.call(cmd);
            }
        };
        VirtualWallet first = wallet(account(node, false));
        first.markArchived(VirtualWalletTest.nodeFolder(), 1);
        off(() -> { first.ensureTracked(); return null; });   // loss seen, the status fails once — then the process dies
        VirtualWallet restarted = wallet(account(node, false));
        assertEquals("the reset survived the restart", VirtualWalletTest.nodeFolder(), off(restarted::archiveWanted));
    }

    /** Review NIT: an archive that answered, but with something unusable (limits, protocol, out of memory), is not "no answer". */
    @Test public void anUnusableAnswerIsNamed() throws Exception {
        Account a = account(new VirtualWalletTest.ArchiveNode(), false);
        Account.archive = addrs -> { throw new java.io.IOException("ARCHIVE_PROTOCOL: expected a MegaMMR reply."); };
        assertEquals("The archive's answer could not be used: ARCHIVE_PROTOCOL: expected a MegaMMR reply.\n\nNothing was moved and no key was used.",
                search(a, new ArrayList<>()));
        Account.archive = addrs -> { throw new java.io.IOException("ARCHIVE_REPLY_LIMIT: archive reply exceeds the mobile recovery limit."); };
        assertEquals("The archive's answer could not be used: ARCHIVE_REPLY_LIMIT: archive reply exceeds the mobile recovery limit.\n\n"
                + "Nothing was moved and no key was used.", search(a, new ArrayList<>()));
        Account.archive = addrs -> { throw new OutOfMemoryError("Failed to allocate a 268435468 byte allocation"); };
        assertEquals("The archive's answer could not be used: Failed to allocate a 268435468 byte allocation.\n\nNothing was moved and no key was used.",
                search(a, new ArrayList<>()));
        Account.archive = addrs -> { throw new java.net.SocketTimeoutException("ARCHIVE_TIMEOUT: recovery time limit reached."); };
        assertEquals("No answer from the archive at eurobuddha.com:9001: ARCHIVE_TIMEOUT: recovery time limit reached.\n\n"
                + "Nothing was moved and no key was used.", search(a, new ArrayList<>()));
    }

    /** Review MINOR-4: the account outlives the screen (MakerService keeps the process); the connection it was opened
     *  with was closed with that screen. The search uses the calling screen's connection. */
    @Test public void aRecreatedScreensNodeIsUsed() throws Exception {
        VirtualWalletTest.ArchiveNode node = new VirtualWalletTest.ArchiveNode();
        Account a = account(node, true);   // the first screen's node, closed since
        Account.archive = addrs -> Collections.emptyList();
        String r = search(a, transport(node, false), new ArrayList<>());
        assertTrue(r, r.startsWith("Nothing new found."));
    }

    /** Review NIT: closing the dialog while the search runs drops nothing — opened again, it shows the result. */
    @Test public void aClosedDialogKeepsTheResult() throws Exception {
        VirtualWalletTest.ArchiveNode node = new VirtualWalletTest.ArchiveNode();
        Account a = account(node, false);
        java.lang.reflect.Field inst = Account.class.getDeclaredField("instance"); inst.setAccessible(true);
        Object before = inst.get(null);
        inst.set(null, a);
        try {
            java.util.concurrent.CountDownLatch hold = new java.util.concurrent.CountDownLatch(1);
            Account.archive = addrs -> { hold.await(); return Collections.singletonList(VirtualWalletTest.archived("0x44", address0(), 100 - 256)); };
            androidx.appcompat.app.AppCompatActivity act = org.robolectric.Robolectric.buildActivity(androidx.appcompat.app.AppCompatActivity.class).setup().get();
            AccountSetup.searchArchive(act, transportOf(a));
            org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle();
            androidx.appcompat.app.AlertDialog d = (androidx.appcompat.app.AlertDialog) org.robolectric.shadows.ShadowDialog.getLatestDialog();
            assertEquals(Account.ARCHIVE_SEARCH_ABOUT, ((android.widget.TextView) d.findViewById(android.R.id.message)).getText().toString());
            d.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick();
            d.dismiss();                                    // closed while the archive answers
            hold.countDown();
            long end = System.currentTimeMillis() + 60_000;
            while (a.archiveSearching() && System.currentTimeMillis() < end) { org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5); }
            assertFalse(a.archiveSearching());
            AccountSetup.searchArchive(act, transportOf(a));   // opened again
            org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle();
            androidx.appcompat.app.AlertDialog again = (androidx.appcompat.app.AlertDialog) org.robolectric.shadows.ShadowDialog.getLatestDialog();
            String shown = ((android.widget.TextView) again.findViewById(android.R.id.message)).getText().toString();
            assertTrue(shown, shown.startsWith("Found 1 coin your node did not have: 5 MINIMA."));
            assertEquals("Search again", again.getButton(android.content.DialogInterface.BUTTON_POSITIVE).getText().toString());
        } finally { inst.set(null, before); }
    }

    @Test public void theExplanationIsTheOwnersWords() {
        assertTrue(Account.ARCHIVE_SEARCH_ABOUT.startsWith("Looks for coins of this Trading Account that are older than your node keeps. "
                + "Use it if a balance looks short after your node was resynced or restored. It moves nothing."));
        assertTrue(Account.ARCHIVE_SEARCH_ABOUT.contains("eurobuddha.com:9001"));
    }
}
