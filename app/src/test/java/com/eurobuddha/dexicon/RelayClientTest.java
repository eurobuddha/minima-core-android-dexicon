package com.eurobuddha.dexicon;

import static org.junit.Assert.*;

import com.eurobuddha.dexicon.envelope.Envelope;
import com.eurobuddha.dexicon.envelope.Envelopes;
import com.eurobuddha.dexicon.identity.DexIdentity;
import com.eurobuddha.dexicon.relay.RelayClient;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Port of mds/dexicon/test/relay.test.js: union by (signPk, orderId, highest seq), cancel, expiry, round-robin, fan-out, inbox dedupe. */
public class RelayClientTest {
    static final String ADDR = "0x0CDCD61692F186EB0BBCFA289F438043F586FF7B3F6864193358E29166E8454A";
    static final String COIN = "0xF19FE115E66277A5785D69CA53BDA431B0D7CDB5B7DB1C4AE8C3CA01558C045F";
    final DexIdentity maker = TestIds.id("11"), maker2 = TestIds.id("44");

    String ord(DexIdentity me, String id, long seq, String side, String price, String amount, long expiry) {
        return Envelopes.order(me, "MINIMA/MxUSD", side, price, amount, Arrays.asList(COIN), new ArrayList<>(), ADDR, id, seq, expiry);
    }

    /** A cancel that arrives after its own 10-minute expiry (app was in the background) must still kill the live
     *  copy it supersedes; dropping it as "expired" left a ghost order on the book for the live copy's 23 h. */
    @Test public void lateExpiredCancelStillRemovesTheLiveOrder() {
        String live = ord(maker, "0x0000000000000009", 1, "sell", "0.02", "100", TestIds.NOW + 23 * 3600);
        String cancel = ord(maker, "0x0000000000000009", 2, "sell", "0.02", "0", TestIds.NOW + 600);
        String staleLive = ord(maker, "0x0000000000000009", 1, "sell", "0.02", "100", TestIds.NOW + 1);
        final long[] now = {TestIds.NOW};
        RelayClient c = new RelayClient(null, "MINIMA/MxUSD", () -> now[0]);
        assertTrue(c.applyOrderStrict(live));
        assertEquals(1, c.orders(null).size());
        now[0] = TestIds.NOW + 601;                       // back from the background after the cancel expired
        assertTrue("expired newer cancel supersedes", c.applyOrderStrict(cancel));
        assertEquals("ghost gone", 0, c.orders(null).size());
        assertFalse("an older expired copy changes nothing", c.applyOrderStrict(staleLive));
        int k = cancel.indexOf("\"sig\":\"0x") + 9;
        String forged = cancel.substring(0, k) + (cancel.charAt(k) == '0' ? '1' : '0') + cancel.substring(k + 1);
        try { c.applyOrderStrict(forged); fail("expired envelopes are still signature-checked"); } catch (RuntimeException expected) {}
    }

    @Test public void unionBySeqCancelExpiryAndFanOut() {
        String A1 = ord(maker, "0x0000000000000001", 1, "sell", "0.02", "100", TestIds.NOW + 3600);
        String A2 = ord(maker, "0x0000000000000001", 2, "sell", "0.019", "90", TestIds.NOW + 3600);   // edit
        String B1 = ord(maker2, "0x0000000000000002", 1, "buy", "0.018", "50", TestIds.NOW + 3600);
        String C1 = ord(maker, "0x0000000000000003", 1, "buy", "0.017", "10", TestIds.NOW + 1);      // expires soon
        String A3cancel = ord(maker, "0x0000000000000001", 3, "sell", "0.019", "0", TestIds.NOW + 3600);

        Map<String, JSONObject> served = new HashMap<>();
        served.put("http://r1", TestIds.obj("cursor", 5, "reset", true, "orders", TestIds.arr(A1, B1)));
        served.put("http://r2", TestIds.obj("cursor", 9, "reset", true, "orders", TestIds.arr(A2, C1)));
        List<String> requests = new ArrayList<>(), posts = new ArrayList<>();
        RelayClient.Transport t = new RelayClient.Transport() {
            public String get(String url) throws IOException {
                requests.add(url);
                String base = url.split("/dex/")[0];
                if (url.contains("/dex/inbox")) return TestIds.obj("cursor", 1, "messages", TestIds.arr()).toString();
                JSONObject s = served.get(base); if (s == null) throw new IOException("down");
                return s.toString();
            }
            public String post(String url, String body) { posts.add(url); return TestIds.obj("ok", true, "cursor", 1).toString(); }
        };
        final long[] now = {TestIds.NOW};
        RelayClient c = new RelayClient(t, "MINIMA/MxUSD", () -> now[0]);
        c.setRelays(Arrays.asList("http://r1/", "http://r1", " http://r2 ", "ftp://bad", "http://r3"));
        assertEquals(Arrays.asList("http://r1", "http://r2", "http://r3"), c.relays());

        assertEquals("r1 changed 2", 2, c.pollBook(8));
        assertTrue("hold + since params", requests.get(0).contains("&hold=8") && requests.get(0).contains("since=0"));
        assertEquals("r2: A2 replaces A1, C1 added", 2, c.pollBook(8));
        assertEquals("r3 down", -1, c.pollBook(8));
        assertFalse(c.status().get("http://r3").ok);
        assertTrue(c.status().get("http://r1").ok);

        List<Envelope> live = c.orders(null);
        assertEquals("union = 3 live orders", 3, live.size());
        List<String> order = new ArrayList<>(); for (Envelope e : live) order.add(e.side + ":" + e.price);
        assertEquals(Arrays.asList("buy:0.018", "buy:0.017", "sell:0.019"), order);
        assertEquals("90", c.orders("sell").get(0).amount);

        assertFalse("stale A1 ignored", c.applyOrder(A1));
        assertTrue("cancel applies", c.applyOrder(A3cancel));
        assertEquals(2, c.orders(null).size());
        now[0] = TestIds.NOW + 2;
        assertEquals("after expiry of C1", 1, c.orders(null).size());
        now[0] = TestIds.NOW;
        assertFalse("forged rejected", c.applyOrder(B1.replace("\"amount\":\"50\"", "\"amount\":\"5000\"")));

        List<RelayClient.PublishResult> results = c.publish(A2);
        assertEquals(3, results.size());
        for (String u : posts.subList(posts.size() - 3, posts.size())) assertTrue(u.endsWith("/dex/publish"));
        String blob = maker2.seal(maker.publicId(), "hi".getBytes(StandardCharsets.UTF_8));
        c.publish(Envelopes.msg(maker2, maker.publicId(), blob, TestIds.NOW + 60));
        for (String u : posts.subList(posts.size() - 3, posts.size())) assertTrue(u.endsWith("/dex/msg"));
    }

    @Test public void inboxDedupesBySigAndFiltersRecipient() {
        String blob = maker2.seal(maker.publicId(), "hi".getBytes(StandardCharsets.UTF_8));
        String m1 = Envelopes.msg(maker2, maker.publicId(), blob, TestIds.NOW + 60);
        String mOther = Envelopes.msg(maker2, maker2.publicId(), blob, TestIds.NOW + 60);
        RelayClient.Transport t = new RelayClient.Transport() {
            public String get(String url) { return TestIds.obj("cursor", 3, "messages", TestIds.arr(m1, m1, mOther)).toString(); }
            public String post(String url, String body) { return "{}"; }
        };
        RelayClient ic = new RelayClient(t, "MINIMA/MxUSD", () -> TestIds.NOW);
        ic.setRelays(Arrays.asList("http://r1"));
        assertEquals("one fresh message for me", 1, ic.pollInbox(maker.publicId(), 8).size());
        assertEquals("replay yields none", 0, ic.pollInbox(maker.publicId(), 8).size());
    }

    @Test public void discoverToleratesMissingEndpoint() {
        RelayClient.Transport t = new RelayClient.Transport() {
            public String get(String url) throws IOException {
                if (url.startsWith("http://r1")) throw new IOException("HTTP 404");
                return TestIds.obj("relays", TestIds.arr("http://r1", "http://r9/", "junk")).toString();
            }
            public String post(String url, String body) { return "{}"; }
        };
        RelayClient c = new RelayClient(t, "MINIMA/MxUSD", () -> TestIds.NOW);
        c.setRelays(Arrays.asList("http://r1", "http://r2"));
        assertEquals(Arrays.asList("http://r9"), c.discover());
    }
}
