import com.eurobuddha.dexicon.account.AccountKeys;
import com.eurobuddha.dexicon.account.KeyUses;
import com.eurobuddha.dexicon.account.VirtualWallet;

import org.json.JSONArray;
import org.json.JSONObject;
import org.minima.Minima;
import org.minima.utils.BIP39;

import java.io.File;
import java.util.*;

/**
 * Testnet harness: unmodified stock 1.1.2.31 jar, private -test chain, no network. Two Trading Accounts (A = owner /
 * maker, B = counterparty) drive the node as NON-admin clients (0xFF) through VirtualWallet, replaying AtomiX's exact
 * HTLC command sequences (MinimaHtlc lock / claim / refund).
 */
public class H1 {
    static Minima m = new Minima();
    static int pass = 0, failn = 0;

    static JSONObject raw(String cmd, String who) {
        String r = m.runMinimaCMD(cmd, false, who);
        try { return new JSONObject(r); } catch (Exception e) { return new JSONObject().put("status", false).put("error", r); }
    }
    static JSONObject admin(String cmd) { return raw(cmd, "0x00"); }

    static class Mem implements KeyUses {
        Map<Integer, Integer> u = new HashMap<>();
        public synchronized int currentUses(int k) { return u.getOrDefault(k, 0); }
        public synchronized int reserveNextUse(int k) { int n = currentUses(k); u.put(k, n + 1); return n; }
        public synchronized void recordExternalUses(int k, int n) { u.put(k, Math.max(currentUses(k), n)); }
        public synchronized Map<Integer, Integer> snapshotAllUses() { return new HashMap<>(u); }
    }
    static class MemStore implements VirtualWallet.Store {
        int n; String posted = "{}";
        public int keyCount() { return n; } public void setKeyCount(int x) { n = x; }
        public String posted() { return posted; } public boolean savePosted(String j) { posted = j; return true; }
    }
    static class MemCache implements AccountKeys.PubCache { Map<Integer,String> c = new HashMap<>(); public String get(int i) { return c.get(i); } public void put(int i, String p) { c.put(i, p); } }

    static VirtualWallet wallet(String phrase) {
        AccountKeys k = AccountKeys.fromPhrase(phrase, new Mem(), new MemCache());
        VirtualWallet.Node n = cmd -> {
            if (cmd.contains(";")) throw new IllegalStateException("command split char in " + cmd);
            return raw(cmd, "0xFF");                     // EXACTLY what a non-admin IPC companion is
        };
        return new VirtualWallet(n, k, new MemStore(), new HashSet<>(Arrays.asList(
                "MxG080CRJB1D4NHGRYGNF7Q52FK7023UM3FUUPVD1W1WCQZSA8MDQ25982N842G", "MxG0832NN9BNMM4FG9JQWHANJ47R1Y723VK1KKA3R6WTT63Y6JSERYYEMT9PTA7")));
    }

    static JSONObject v(VirtualWallet w, String cmd) {
        JSONObject r = w.handle(cmd);
        if (r == null) r = raw(cmd, "0xFF");
        return r;
    }
    static JSONObject must(VirtualWallet w, String cmd) {
        JSONObject r = v(w, cmd);
        if (!r.optBoolean("status")) throw new RuntimeException("FAILED: " + cmd + "\n  -> " + r);
        return r;
    }
    static void check(String what, boolean ok, Object detail) {
        if (ok) pass++; else failn++;
        System.out.println((ok ? "  PASS " : "  FAIL ") + what + (ok ? "" : "  :: " + detail));
    }
    static long block() { return Long.parseLong(admin("block").getJSONObject("response").getString("block")); }
    static void waitBlocks(int n) throws Exception { long t = block() + n; for (int i = 0; i < 600 && block() < t; i++) Thread.sleep(500); }
    static JSONArray coinsAt(String q) { JSONObject r = admin(q); return r.optJSONArray("response") == null ? new JSONArray() : r.getJSONArray("response"); }
    static String tokbal(VirtualWallet w, String tok) {
        JSONArray a = must(w, "balance tokenid:" + tok).getJSONArray("response");
        return a.length() == 0 ? "0" : a.getJSONObject(0).getString("sendable");
    }

    static final String HTLC_SCRIPT =
            "LET version=1.2 LET owner=PREVSTATE(0) LET requestamount=PREVSTATE(1) LET requesttoken=PREVSTATE(2) "
          + "LET timelock=PREVSTATE(3) LET counterparty=PREVSTATE(4) LET hash=PREVSTATE(5) LET ownerethkey=PREVSTATE(6) "
          + "IF SIGNEDBY(owner) AND (@BLOCK GT timelock) THEN RETURN TRUE ENDIF LET secret=STATE(100) "
          + "ASSERT SIGNEDBY(counterparty) AND (SHA2(secret) EQ hash) ASSERT STATE(101) EQ hash "
          + "ASSERT STATE(102) EQ STRING(owner) ASSERT STATE(103) EQ STRING(counterparty) "
          + "RETURN VERIFYOUT(@INPUT 0xFFEEDD9999 0.0001 @TOKENID TRUE)";
    static final String HTLC = "MxG080CRJB1D4NHGRYGNF7Q52FK7023UM3FUUPVD1W1WCQZSA8MDQ25982N842G";

    /** AtomiX MinimaHtlc lock sequence (lines 341-358), token 0x00. Returns the lock coin id once mined. */
    static String lock(VirtualWallet w, String owner, String receiver, String hash, long timelock, String amount) throws Exception {
        JSONArray free = must(w, "coins relevant:true sendable:true tokenid:0x00 coinage:1 checkmempool:true").getJSONArray("response");
        String cid = null; java.math.BigDecimal have = null;
        for (int i = 0; i < free.length(); i++) { java.math.BigDecimal a = new java.math.BigDecimal(free.getJSONObject(i).getString("amount")); if (a.compareTo(new java.math.BigDecimal(amount)) > 0 && (have == null || a.compareTo(have) < 0)) { have = a; cid = free.getJSONObject(i).getString("coinid"); } }
        if (cid == null) throw new RuntimeException("no coin for lock " + free);
        String change = have.subtract(new java.math.BigDecimal(amount)).toPlainString();
        String my = must(w, "getaddress").getJSONObject("response").getString("miniaddress");
        String id = "lk" + System.nanoTime();
        List<String> seq = new ArrayList<>(Arrays.asList(
            "txncreate id:" + id, "txninput id:" + id + " coinid:" + cid,
            "txnstate id:" + id + " port:0 value:" + owner, "txnstate id:" + id + " port:1 value:10",
            "txnstate id:" + id + " port:2 value:[0x00]", "txnstate id:" + id + " port:3 value:" + timelock,
            "txnstate id:" + id + " port:4 value:" + receiver, "txnstate id:" + id + " port:5 value:" + hash,
            "txnstate id:" + id + " port:6 value:0x1234", "txnstate id:" + id + " port:7 value:0",
            "txnoutput id:" + id + " amount:" + amount + " address:" + HTLC + " tokenid:0x00 storestate:true",
            "txnoutput id:" + id + " amount:" + change + " address:" + my + " tokenid:0x00 storestate:false",
            "txnsign id:" + id + " publickey:auto"));
        for (String c : seq) must(w, c);
        JSONObject post = must(w, "txnpost id:" + id + " mine:true txndelete:true");
        return post.getJSONObject("response").optString("txpowid");
    }

    static JSONObject findLock(String hash) {
        JSONArray a = coinsAt("coins address:" + HTLC + " state:" + hash);
        return a.length() == 0 ? null : a.getJSONObject(0);
    }

    public static void main(String[] a) throws Exception {
        String dir = a[0];
        new File(dir).mkdirs();
        m.mainStarter(new String[]{"-data", dir + "/node", "-test", "-genesis", "-nop2p", "-noconnect", "-noshutdownhook",
                "-port", "19601", "-lowram", "-blockaskeyuses"});
        for (int i = 0; i < 120; i++) { Thread.sleep(1000); try { if (admin("block").optBoolean("status")) break; } catch (Throwable t) {} }
        // keys must exist before the node wallet can send
        for (int i = 0; i < 400; i++) { JSONObject b = admin("balance"); try { if (new java.math.BigDecimal(b.getJSONArray("response").getJSONObject(0).getString("sendable")).signum() > 0) break; } catch (Exception e) {} Thread.sleep(1000); }
        System.out.println("node balance: " + admin("balance").getJSONArray("response").getJSONObject(0));
        System.out.println("node version: " + admin("status").getJSONObject("response").optString("version"));

        VirtualWallet A = wallet(BIP39.convertWordListToString(BIP39.getNewWordList()));
        VirtualWallet B = wallet(BIP39.convertWordListToString(BIP39.getNewWordList()));
        A.ensureTracked(); B.ensureTracked();
        String aAddr = must(A, "getaddress").getJSONObject("response").getString("address");
        String bAddr = must(B, "getaddress").getJSONObject("response").getString("address");
        String aPk = must(A, "getaddress").getJSONObject("response").getString("publickey");
        String bPk = must(B, "getaddress").getJSONObject("response").getString("publickey");
        System.out.println("A " + aAddr + "\nB " + bAddr);

        System.out.println("\n== 1. deposit (node wallet -> accounts, admin = the user in Minima Core)");
        System.out.println("  deposit A: " + admin("send address:" + aAddr + " amount:200 split:4").optBoolean("status"));
        waitBlocks(4);   // the node wallet's change must confirm before it can send again
        System.out.println("  deposit B: " + admin("send address:" + bAddr + " amount:100 split:2").optBoolean("status"));
        waitBlocks(5);
        check("A sees its deposit as sendable", new java.math.BigDecimal(tokbal(A, "0x00")).compareTo(new java.math.BigDecimal("200")) == 0, tokbal(A, "0x00"));
        check("B sees its deposit as sendable", new java.math.BigDecimal(tokbal(B, "0x00")).compareTo(new java.math.BigDecimal("100")) == 0, tokbal(B, "0x00"));
        JSONObject nb = admin("balance").getJSONArray("response").getJSONObject(0);
        System.out.println("  node wallet after deposit: " + nb);

        System.out.println("\n== 2. node wallet can NOT spend account coins (non-simple)");
        JSONArray nodeSendable = coinsAt("coins relevant:true sendable:true address:" + aAddr);
        check("node 'sendable' excludes account coins", nodeSendable.length() == 0, nodeSendable);

        System.out.println("\n== 3. plain send A -> B (local build, postfrom)");
        JSONObject s = must(A, "send address:" + bAddr + " amount:12.5");
        System.out.println("  posted txpowid " + s.getJSONObject("response").optString("txpowid"));
        waitBlocks(5);
        check("B received 12.5", new java.math.BigDecimal(tokbal(B, "0x00")).compareTo(new java.math.BigDecimal("112.5")) == 0, tokbal(B, "0x00"));
        check("A has 187.5", new java.math.BigDecimal(tokbal(A, "0x00")).compareTo(new java.math.BigDecimal("187.5")) == 0, tokbal(A, "0x00"));

        System.out.println("\n== 4. HTLC lock by A (AtomiX sequence) -> CLAIM by B");
        must(A, "newscript script:\"" + HTLC_SCRIPT + "\" trackall:false");
        String secret = "0x" + "11".repeat(32);
        String hash = admin("hash type:sha2 data:" + secret).getJSONObject("response").getString("hash");
        long tl = block() + 1000;
        lock(A, aPk, bPk, hash, tl, "20");
        waitBlocks(4);
        JSONObject lk = findLock(hash);
        check("lock coin on chain at HTLC address", lk != null, "none");
        boolean portOk = false;
        if (lk != null) { JSONArray st = lk.getJSONArray("state"); for (int i = 0; i < st.length(); i++) if (st.getJSONObject(i).getInt("port") == 254 && st.getJSONObject(i).getString("data").equalsIgnoreCase(aAddr)) portOk = true; }
        check("lock carries owner port 254 = A's account address", portOk, lk);
        JSONArray rel = coinsAt("coins relevant:true coinid:" + (lk == null ? "0x00" : lk.getString("coinid")));
        check("node keeps the lock RELEVANT (owner port works)", rel.length() == 1, rel);
        // claim (MinimaHtlc 542-557) by B
        String id = "cl" + System.nanoTime(), cid = lk.getString("coinid");
        for (String c : new String[]{"txncreate id:" + id, "txninput id:" + id + " coinid:" + cid,
                "txnoutput id:" + id + " tokenid:0x00 amount:0.0001 address:0xFFEEDD9999",
                "txnoutput id:" + id + " tokenid:0x00 amount:19.9999 address:" + bAddr + " storestate:false",
                "txnstate id:" + id + " port:100 value:" + secret, "txnstate id:" + id + " port:101 value:" + hash,
                "txnstate id:" + id + " port:102 value:[" + aPk + "]", "txnstate id:" + id + " port:103 value:[" + bPk + "]",
                "txnsign id:" + id + " publickey:" + bPk}) must(B, c);
        JSONObject chk = must(B, "txnbasics id:" + id);
        JSONObject cc = v(B, "txncheck id:" + id);
        System.out.println("  claim txncheck valid=" + (cc.optJSONObject("response") == null ? cc : cc.getJSONObject("response").opt("valid")));
        must(B, "txnpost id:" + id + " mine:true txndelete:true");
        waitBlocks(5);
        check("lock spent by claim", findLock(hash) == null || coinsAt("coins coinid:" + cid).length() == 0, findLock(hash));
        check("B got 19.9999 (now 132.4999)", new java.math.BigDecimal(tokbal(B, "0x00")).compareTo(new java.math.BigDecimal("132.4999")) == 0, tokbal(B, "0x00"));

        System.out.println("\n== 5. HTLC lock by A -> REFUND by A after the timelock, across the 32-block prune");
        String secret2 = "0x" + "22".repeat(32);
        String hash2 = admin("hash type:sha2 data:" + secret2).getJSONObject("response").getString("hash");
        long tl2 = block() + 6;
        lock(A, aPk, bPk, hash2, tl2, "30");
        waitBlocks(4);
        JSONObject lk2 = findLock(hash2);
        check("second lock on chain", lk2 != null, "none");
        String cid2 = lk2.getString("coinid");
        System.out.println("  waiting 45 blocks so the -test chain (32-block window) prunes it ...");
        waitBlocks(45);
        JSONArray bare = coinsAt("coins coinid:" + cid2);
        JSONArray relv = coinsAt("coins relevant:true coinid:" + cid2);
        System.out.println("  bare coins coinid -> " + bare.length() + " ; relevant -> " + relv.length());
        check("old lock still found via relevance (owner port)", relv.length() == 1, relv);
        String rid = "rf" + System.nanoTime();
        for (String c : new String[]{"txncreate id:" + rid, "txninput id:" + rid + " coinid:" + cid2,
                "txnoutput id:" + rid + " tokenid:0x00 amount:30 address:" + aAddr + " storestate:false",
                "txnsign id:" + rid + " publickey:" + aPk}) must(A, c);
        must(A, "txnpost id:" + rid + " mine:true txndelete:true");
        waitBlocks(5);
        check("refund settled: lock gone", coinsAt("coins relevant:true coinid:" + cid2).length() == 0, "still there");

        System.out.println("\n== 6. two-party: B builds, exports; A imports, signs its input, posts");
        JSONArray bc = must(B, "coins relevant:true sendable:true tokenid:0x00").getJSONArray("response");
        JSONArray ac = must(A, "coins relevant:true sendable:true tokenid:0x00").getJSONArray("response");
        String bIn = bc.getJSONObject(0).getString("coinid"), aIn = ac.getJSONObject(0).getString("coinid");
        String bAmt = bc.getJSONObject(0).getString("amount"), aAmt = ac.getJSONObject(0).getString("amount");
        String t2 = "tp" + System.nanoTime();
        for (String c : new String[]{"txncreate id:" + t2, "txninput id:" + t2 + " coinid:" + bIn + " scriptmmr:true",
                "txninput id:" + t2 + " coinid:" + aIn + " scriptmmr:true",
                "txnoutput id:" + t2 + " amount:" + bAmt + " address:" + aAddr + " storestate:false",
                "txnoutput id:" + t2 + " amount:" + aAmt + " address:" + bAddr + " storestate:false",
                "txnsign id:" + t2 + " publickey:auto"}) must(B, c);
        String data = must(B, "txnexport id:" + t2).getJSONObject("response").getString("data");
        must(A, "txnimport id:imp data:" + data);
        JSONObject sg = must(A, "txnsign id:imp publickey:auto");
        System.out.println("  A signed keys " + sg.getJSONObject("response").opt("keys") + " nonsimple " + sg.getJSONObject("response").opt("nonsimple"));
        must(A, "txnpost id:imp mine:true txndelete:true");
        waitBlocks(5);
        check("swap of coins settled (A's input spent)", coinsAt("coins relevant:true coinid:" + aIn).length() == 0, "unspent");

        System.out.println("\n== 7. a hostile companion swaps the node's table: nothing to steal");
        JSONObject list = raw("txnlist", "0xFF");
        check("node txn table holds none of our drafts", list.optJSONArray("response") == null || list.getJSONArray("response").length() == 0, list);

        System.out.println("\n== 8. consolidate the Trading Account's MINIMA (one address, one signature)");
        int before = must(A, "coins relevant:true sendable:true tokenid:0x00").getJSONArray("response").length();
        JSONObject cons = v(A, "consolidate tokenid:0x00");
        System.out.println("  " + before + " coins before; consolidate -> " + (cons.optBoolean("status") ? "posted" : cons.optString("error")));
        waitBlocks(5);
        int after = must(A, "coins relevant:true sendable:true tokenid:0x00").getJSONArray("response").length();
        check("consolidate reduced the coin count (" + before + " -> " + after + ")", before < 2 || after < before, before + " -> " + after);
        JSONObject dry = v(A, "send address:" + bAddr + " amount:1 dryrun:true");
        check("dryrun send signs and posts nothing", dry.optBoolean("status") && dry.getJSONObject("response").optBoolean("dryrun"), dry);

        System.out.println("\n== 9. mine:false posts: txpow onchain: of the PRE-mining id is proven by the account (0.3.15)");
        JSONObject sent = must(A, "send address:" + bAddr + " amount:1 mine:true");
        String pre = sent.getJSONObject("response").optString("txpowid");
        String txnid = sent.getJSONObject("response").getJSONObject("body").getJSONObject("txn").getString("transactionid");
        System.out.println("  posted pre-mining txpowid " + pre + " txn " + txnid);
        waitBlocks(2);
        JSONObject nodeSays = raw("txpow onchain:" + pre, "0xFF");
        check("the node itself never finds the pre-mining id (the bug)", !nodeSays.optJSONObject("response").optBoolean("found"), nodeSays);
        waitBlocks(5);
        JSONObject acct = A.handle("txpow onchain:" + pre);
        JSONObject ar = acct == null ? new JSONObject() : acct.optJSONObject("response");
        check("the account proves it: found at depth >= 3", ar != null && ar.optBoolean("found") && Long.parseLong(ar.optString("confirmations", "0")) >= 3, acct);
        if (ar != null && ar.optBoolean("found")) {
            long b = Long.parseLong(ar.getString("block"));
            JSONObject blk = admin("txpow block:" + b).getJSONObject("response");
            check("its blockid is the real block " + b, blk.getString("txpowid").equalsIgnoreCase(ar.getString("blockid")), blk.optString("txpowid"));
            boolean inIt = txnid.equalsIgnoreCase(blk.getJSONObject("body").getJSONObject("txn").optString("transactionid"));
            JSONArray inList = blk.getJSONObject("body").optJSONArray("txnlist");
            for (int i = 0; !inIt && inList != null && i < inList.length(); i++) {
                JSONObject t = admin("txpow txpowid:" + inList.getString(i)).optJSONObject("response");
                if (t != null && txnid.equalsIgnoreCase(t.getJSONObject("body").getJSONObject("txn").optString("transactionid"))) inIt = true;
            }
            check("block " + b + " really carries our transaction " + txnid, inIt, blk.getJSONObject("body").opt("txnlist"));
            check("engines' check: tip - block == confirmations", Long.parseLong(ar.getString("tip")) - b == Long.parseLong(ar.getString("confirmations")), ar);
        }
        check("a foreign id is not answered by the account", A.handle("txpow onchain:0x" + "99".repeat(32)) == null, "answered");

        System.out.println("\n===== H1: " + pass + " passed, " + failn + " failed =====");
        System.exit(failn == 0 ? 0 : 1);
    }
}
