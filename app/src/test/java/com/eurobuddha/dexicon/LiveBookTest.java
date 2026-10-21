package com.eurobuddha.dexicon;

import static org.junit.Assert.*;

import com.eurobuddha.dexicon.envelope.Envelope;
import com.eurobuddha.dexicon.relay.RelayClient;

import org.junit.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/** A /dex/book snapshot captured from the relay on 2026-10-21, holding an order the MiniDapp (Dexicon 0.0.16)
 *  published and the relay (dexrelay 0.1.2) verified. The APK must accept exactly what they accept. */
public class LiveBookTest {
    static String resource(String name) throws Exception {
        try (InputStream in = LiveBookTest.class.getClassLoader().getResourceAsStream(name)) {
            assertNotNull("missing test resource " + name, in);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test public void miniDappOrderIsAcceptedByTheApk() throws Exception {
        final String book = resource("live-book-2026-10-21.json");
        org.json.JSONObject raw = new org.json.JSONObject(book);
        String env = raw.getJSONArray("orders").getJSONObject(0).toString();
        long expiry = raw.getJSONArray("orders").getJSONObject(0).getLong("expiry");
        Envelope e = Envelope.parse(env, expiry - 3600);   // shape + signature, same rules as the relay
        assertEquals("MINIMA/MxUSD", e.market);
        RelayClient.Transport t = new RelayClient.Transport() {
            public String get(String url) { return url.contains("/dex/inbox") ? "{\"cursor\":1,\"messages\":[]}" : book; }
            public String post(String url, String body) { return "{\"ok\":true,\"cursor\":1}"; }
        };
        RelayClient c = new RelayClient(t, "MINIMA/MxUSD", () -> e.expiry - 3600);
        c.setRelays(Arrays.asList("http://relay"));
        assertEquals(1, c.pollBook(1));
        List<Envelope> live = c.orders(null);
        assertEquals("the live order is in the book", 1, live.size());
        assertEquals("sell", live.get(0).side);
    }

    /** Android's org.json escapes '/' on toString(); the client must hand Envelope.parse the relay's exact bytes. */
    @Test public void rawObjectsKeepTheRelayBytes() {
        String body = "{\"cursor\":7,\"reset\":true,\"orders\":[{\"a\":\"x{y\",\"market\":\"MINIMA/MxUSD\",\"q\":\"s\\\"t\"},{\"b\":2}],\"x\":[{}]}";
        List<String> raws = RelayClient.rawObjects(body, "orders");
        assertEquals(2, raws.size());
        assertEquals("{\"a\":\"x{y\",\"market\":\"MINIMA/MxUSD\",\"q\":\"s\\\"t\"}", raws.get(0));
        assertEquals("{\"b\":2}", raws.get(1));
        assertEquals(0, RelayClient.rawObjects("{\"cursor\":1}", "orders").size());
        // and the real book, served with the market slash already escaped the way Android would re-serialise it,
        // still yields the order because the client never re-serialises
        final String book = "{\"cursor\":42,\"reset\":true,\"orders\":[" + rawOrder() + "]}";
        RelayClient.Transport tr = new RelayClient.Transport() {
            public String get(String url) { return book; }
            public String post(String url, String b) { return "{}"; }
        };
        RelayClient c = new RelayClient(tr, "MINIMA/MxUSD", () -> 1792607000L);
        c.setRelays(Arrays.asList("http://relay"));
        assertEquals(1, c.pollBook(1));
        assertEquals(0, c.status().get("http://relay").rejected);
    }
    static String rawOrder() {
        try { return new org.json.JSONObject(resource("live-book-2026-10-21.json")).getJSONArray("orders").getJSONObject(0).toString(); }
        catch (Exception e) { throw new RuntimeException(e); }
    }
}
