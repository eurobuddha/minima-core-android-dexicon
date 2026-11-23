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
        Transport t = (cmd, guard, timeout, cb) -> {
            if (nodeDown) { cb.onError("Minima Core is not running"); return; }
            try { cb.onResult(node.call(cmd)); } catch (Exception e) { cb.onError(e.toString()); }
        };
        java.lang.reflect.Constructor<Account> c = Account.class.getDeclaredConstructor(android.content.Context.class, String.class, String.class, int.class, Transport.class);
        c.setAccessible(true);
        return c.newInstance(RuntimeEnvironment.getApplication(), AccountKeysTest.PHRASE, AccountVault.MODE_PHRASE, 0, t);
    }

    static VirtualWallet wallet(Account a) throws Exception {
        java.lang.reflect.Field f = Account.class.getDeclaredField("wallet"); f.setAccessible(true);
        return (VirtualWallet) f.get(a);
    }

    static String search(Account a, List<String> progress) throws Exception {
        AtomicReference<String> done = new AtomicReference<>();
        a.searchArchive(progress::add, done::set);
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
        a.searchArchive(null, first::set);
        assertEquals(Account.ARCHIVE_BUSY, search(a, new ArrayList<>()));
        hold.countDown();
        long end = System.currentTimeMillis() + 60_000;
        while (first.get() == null && System.currentTimeMillis() < end) { org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5); }
        assertTrue(first.get(), first.get().startsWith("Nothing new found."));
    }

    @Test public void theExplanationIsTheOwnersWords() {
        assertTrue(Account.ARCHIVE_SEARCH_ABOUT.startsWith("Looks for coins of this Trading Account that are older than your node keeps. "
                + "Use it if a balance looks short after your node was resynced or restored. It moves nothing."));
        assertTrue(Account.ARCHIVE_SEARCH_ABOUT.contains("eurobuddha.com:9001"));
    }
}
