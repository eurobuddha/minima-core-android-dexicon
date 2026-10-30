package com.eurobuddha.dexicon.relay;

import com.eurobuddha.dexicon.envelope.Envelope;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * Multi-homed client for the Dexicon relay cape (SPEC §4) — port of mds/dexicon/lib/relay.js. SYNCHRONOUS and
 * transport-injected (callers run it on a worker thread; tests pass a fake transport).
 *
 * Union semantics: every order envelope from any relay is verified locally ({@link Envelope#parse}) and kept only if
 * its seq beats what we hold for (signPk|orderId). Cancels (amount 0) and expiry (local clock) hide an order.
 * Polling is ONE relay per call, round-robin; a sender publishes to EVERY relay it uses.
 */
public final class RelayClient {

    public interface Transport {
        String get(String url) throws IOException;
        String post(String url, String body) throws IOException;
    }

    public static final class Status {
        public boolean ok; public long lastMs; public String error = ""; public int orders; public boolean reset;
        /** Envelopes this relay served that we refused (shape, signature, expiry) and the last reason — visible in RELAYS. */
        public int rejected; public String lastReject = "";
    }
    public static final class PublishResult {
        public final String url; public final boolean ok; public final String error;
        PublishResult(String url, boolean ok, String error) { this.url = url; this.ok = ok; this.error = error; }
    }

    private final Transport t;
    private final String market;
    private final LongSupplier now;
    private final List<String> relays = new ArrayList<>();
    private final Map<String, Long> cursors = new HashMap<>(), inboxCursors = new HashMap<>();
    private final Map<String, Status> status = new LinkedHashMap<>();
    private final Map<String, Envelope> book = new LinkedHashMap<>();
    private static final java.util.concurrent.ExecutorService POOL = java.util.concurrent.Executors.newCachedThreadPool(r -> { Thread th = new Thread(r, "dexicon-relay-post"); th.setDaemon(true); return th; });
    /** Security review 30 Oct: a relay is untrusted — bounded work per reply (each envelope costs a full Ed25519 verify),
     *  a reply abandoned after MAX_BAD bad envelopes, a bounded book and a bounded inbox dedupe set. */
    static final int MAX_PER_REPLY = 2000, MAX_BAD = 50, MAX_BOOK = 5000, MAX_SEEN = 10_000;
    private final Set<String> seenMsgs = new java.util.LinkedHashSet<>();
    private int rr;

    public RelayClient(Transport t, String market, LongSupplier nowSeconds) {
        this.t = t; this.market = market; this.now = nowSeconds;
    }
    public RelayClient(Transport t) { this(t, "MINIMA/MxUSD", () -> System.currentTimeMillis() / 1000); }

    public static String clean(String u) {
        if (u == null) return null;
        u = u.trim().replaceAll("/+$", "");
        // https only (security review 30 Oct): plain http lets anyone on the path read and drop the book and the takes.
        // A local relay (dev box, emulator host) may stay http.
        if (u.matches("^https://[^\\s/]+.*")) return u;
        return u.matches("^http://(localhost|127\\.0\\.0\\.1|10\\.0\\.2\\.2)(:[0-9]+)?(/.*)?$") ? u : null;
    }

    public synchronized void setRelays(List<String> urls) {
        relays.clear();
        for (String u : urls) { String c = clean(u); if (c != null && !relays.contains(c)) relays.add(c); }
    }
    public synchronized List<String> relays() { return new ArrayList<>(relays); }
    public synchronized Map<String, Status> status() { return new LinkedHashMap<>(status); }

    /** Apply one raw order envelope; true if it changed the book. */
    public synchronized boolean applyOrder(String raw) {
        try { return applyOrderStrict(raw); } catch (RuntimeException e) { return false; }
    }
    /** Like applyOrder but an invalid envelope THROWS with the reason (shape / signature / expiry). */
    public synchronized boolean applyOrderStrict(String raw) {
        Envelope v;
        try { v = Envelope.parse(raw, now.getAsLong()); }
        catch (Envelope.Invalid e) {
            // A cancel received after its 10-minute expiry (app was backgrounded) must still supersede the live copy,
            // or that copy haunts the book for its 23 h. Re-check it as of just before it expired: signature included.
            if (!"expired".equals(e.getMessage())) throw e;
            Object exp = com.eurobuddha.dexicon.envelope.Json.parseObject(raw).get("expiry");
            v = Envelope.parse(raw, (Long) exp - 1);
        }
        if (v.kind != Envelope.Kind.ORDER) throw new IllegalArgumentException("not an order");
        Envelope have = book.get(v.key());
        if (have != null && have.seq >= v.seq) return false;
        if (have == null && book.size() >= MAX_BOOK) {
            long n = now.getAsLong();
            book.values().removeIf(e -> e.expiry <= n);
            if (book.size() >= MAX_BOOK) throw new IllegalArgumentException("book full");
        }
        book.put(v.key(), v);
        return true;
    }

    /** Live orders for the market: not cancelled, not expired; side optional. Bids first (desc), then asks (asc). */
    public synchronized List<Envelope> orders(String side) {
        long n = now.getAsLong();
        List<Envelope> out = new ArrayList<>();
        for (Envelope e : book.values()) {
            if (!e.market.equals(market) || e.expiry <= n || e.isCancel()) continue;
            if (side != null && !side.equals(e.side)) continue;
            out.add(e);
        }
        out.sort((a, b) -> {
            if (!a.side.equals(b.side)) return a.side.equals("sell") ? 1 : -1;
            int c = new java.math.BigDecimal(a.price).compareTo(new java.math.BigDecimal(b.price));
            return a.side.equals("sell") ? c : -c;
        });
        return out;
    }

    private synchronized String next() {
        if (relays.isEmpty()) return null;
        return relays.get((rr++) % relays.size());
    }

    private synchronized void mark(String url, boolean ok, String err) {
        Status s = status.get(url);
        if (s == null) { s = new Status(); status.put(url, s); }
        s.ok = ok; s.lastMs = System.currentTimeMillis(); s.error = ok ? "" : String.valueOf(err == null ? "error" : err);
    }

    /** Long-poll ONE relay's book (round-robin). Returns the number of changed orders; -1 on error (status records why). */

    /** The top-level JSON objects inside `"<key>":[ ... ]` of a relay body, as the EXACT substrings the relay sent.
     *  Envelopes are signed over their canonical bytes and the subset parser accepts no escapes, so they must never
     *  be re-serialised by a platform JSON library (Android's org.json turns "MINIMA/MxUSD" into "MINIMA\/MxUSD"). */
    public static List<String> rawObjects(String body, String key) {
        List<String> out = new ArrayList<>();
        int k = body.indexOf("\"" + key + "\"");
        if (k < 0) return out;
        int a = body.indexOf('[', k);
        if (a < 0) return out;
        int depth = 0, start = -1; boolean inStr = false;
        for (int i = a + 1; i < body.length(); i++) {
            char c = body.charAt(i);
            if (inStr) { if (c == '\\') i++; else if (c == '"') inStr = false; continue; }
            if (c == '"') inStr = true;
            else if (c == '{') { if (depth++ == 0) start = i; }
            else if (c == '}') { if (--depth == 0 && start >= 0) { out.add(body.substring(start, i + 1)); start = -1; if (out.size() >= MAX_PER_REPLY) break; } }
            else if (c == ']' && depth == 0) break;
        }
        return out;
    }

    /** One relay's live orders right now (a since=0 snapshot), signature-checked; null when it could not be read. */
    public List<Envelope> snapshot(String url) {
        String text;
        try { text = t.get(url + "/dex/book?market=" + enc(market) + "&since=0&hold=0"); } catch (Exception e) { return null; }
        List<Envelope> out = new ArrayList<>();
        for (String raw : rawObjects(text, "orders")) {
            try { Envelope v = Envelope.parse(raw, now.getAsLong()); if (v.kind == Envelope.Kind.ORDER) out.add(v); } catch (RuntimeException ignored) {}
        }
        return out;
    }

    /** Publish one envelope to ONE relay. */
    public PublishResult publishTo(String url, String raw) {
        boolean ok = false; String err = "";
        try {
            JSONObject b = new JSONObject(t.post(url + "/dex/publish", raw));
            ok = b.optBoolean("ok", false); err = b.optString("error", "");
        } catch (Exception e) { err = String.valueOf(e.getMessage()); }
        mark(url, ok || "stale seq".equals(err), err);
        return new PublishResult(url, ok, err);
    }

    public int pollBook(int hold) {
        String url = next();
        if (url == null) return -1;
        long since; synchronized (this) { Long c = cursors.get(url); since = c == null ? 0 : c; }
        String text;
        try { text = t.get(url + "/dex/book?market=" + enc(market) + "&since=" + since + "&hold=" + hold); }
        catch (Exception e) { mark(url, false, e.getMessage()); return -1; }
        JSONObject body;
        try { body = new JSONObject(text); } catch (Exception e) { mark(url, false, "bad json"); return -1; }
        JSONArray orders = body.optJSONArray("orders");
        if (!body.has("cursor") || orders == null) { mark(url, false, "bad body"); return -1; }
        int changed = 0, rejected = 0; String why = "";
        List<String> raws = rawObjects(text, "orders");
        for (int i = 0; i < orders.length() && i < MAX_PER_REPLY && rejected < MAX_BAD; i++) {
            Object o = orders.opt(i);
            String raw = o instanceof String ? (String) o : (i < raws.size() ? raws.get(i) : String.valueOf(o));
            try { if (applyOrderStrict(raw)) changed++; }
            catch (RuntimeException e) { rejected++; why = String.valueOf(e.getMessage()); }
        }
        synchronized (this) { cursors.put(url, body.optLong("cursor", 0)); }
        mark(url, true, null);
        synchronized (this) { Status s = status.get(url); s.orders = orders.length(); s.reset = body.optBoolean("reset", false); s.rejected = rejected; if (rejected > 0) s.lastReject = why; }
        return changed;
    }

    /** Publish one envelope (raw JSON) to EVERY relay (orders → /dex/publish, msgs → /dex/msg). */
    public List<PublishResult> publish(String raw) {
        List<PublishResult> results = new ArrayList<>();
        String path = "/dex/publish";
        try { if ("msg".equals(new JSONObject(raw).optString("t"))) path = "/dex/msg"; }
        catch (Exception e) { results.add(new PublishResult(null, false, "bad json")); return results; }
        // Every relay at once (the slowest relay sets the pace, not the sum of all of them).
        List<String> urls = relays();
        List<java.util.concurrent.Future<PublishResult>> futures = new ArrayList<>();
        final String fPath = path;
        for (String url : urls) futures.add(POOL.submit(() -> {
            boolean ok = false; String err = "";
            try {
                JSONObject b = new JSONObject(t.post(url + fPath, raw));
                ok = b.optBoolean("ok", false); err = b.optString("error", "");
            } catch (Exception e) { err = String.valueOf(e.getMessage()); }
            mark(url, ok || "stale seq".equals(err), err);
            return new PublishResult(url, ok, err);
        }));
        for (int i = 0; i < futures.size(); i++) {
            try { results.add(futures.get(i).get()); }
            catch (Exception e) { results.add(new PublishResult(urls.get(i), false, String.valueOf(e.getMessage()))); }
        }
        return results;
    }

    /** Long-poll ONE relay's inbox for my publicId (round-robin). Verified msg envelopes addressed to me, deduped by sig. */
    public List<Envelope> pollInbox(String publicId, int hold) {
        List<Envelope> fresh = new ArrayList<>();
        String url = next();
        if (url == null) return fresh;
        long since; synchronized (this) { Long c = inboxCursors.get(url); since = c == null ? 0 : c; }
        String text;
        try { text = t.get(url + "/dex/inbox?pk=" + publicId + "&since=" + since + "&hold=" + hold); }
        catch (Exception e) { mark(url, false, e.getMessage()); return fresh; }
        JSONObject body;
        try { body = new JSONObject(text); } catch (Exception e) { mark(url, false, "bad json"); return fresh; }
        JSONArray msgs = body.optJSONArray("messages");
        if (!body.has("cursor") || msgs == null) { mark(url, false, "bad body"); return fresh; }
        long n = now.getAsLong();
        List<String> raws = rawObjects(text, "messages");
        int bad = 0;
        for (int i = 0; i < msgs.length() && i < MAX_PER_REPLY && bad < MAX_BAD; i++) {
            Object m = msgs.opt(i);
            String raw = m instanceof String ? (String) m : (i < raws.size() ? raws.get(i) : String.valueOf(m));
            Envelope v;
            try { v = Envelope.parse(raw, n); } catch (RuntimeException e) { bad++; continue; }
            if (v.kind != Envelope.Kind.MSG || !v.to.equalsIgnoreCase(publicId)) continue;
            String sig = sigOf(raw);
            synchronized (this) {
                if (!seenMsgs.add(sig.toLowerCase(Locale.ROOT))) continue;
                if (seenMsgs.size() > MAX_SEEN) { java.util.Iterator<String> it = seenMsgs.iterator(); it.next(); it.remove(); }   // oldest first
            }
            fresh.add(v);
        }
        synchronized (this) { inboxCursors.put(url, body.optLong("cursor", 0)); }
        mark(url, true, null);
        return fresh;
    }

    /** Bootstrap: ask every relay for `GET /dex/relays` (another agent is adding it — 404 tolerated). Returns new URLs. */
    public List<String> discover() {
        List<String> found = new ArrayList<>();
        for (String url : relays()) {
            try {
                String text = t.get(url + "/dex/relays");
                JSONArray a = null;
                try { a = new JSONArray(text); } catch (Exception e) { JSONObject o = new JSONObject(text); a = o.optJSONArray("relays"); }
                if (a == null) continue;
                for (int i = 0; i < a.length(); i++) {
                    Object x = a.opt(i);
                    String u = x instanceof JSONObject ? ((JSONObject) x).optString("url", "") : String.valueOf(x);
                    String c = clean(u);
                    synchronized (this) { if (c != null && !relays.contains(c) && !found.contains(c)) found.add(c); }
                }
            } catch (Exception ignored) { /* 404 / down / not json: no discovery from this relay */ }
        }
        return found;
    }

    private static String sigOf(String raw) { try { return new JSONObject(raw).optString("sig", ""); } catch (Exception e) { return ""; } }
    private static String enc(String s) { try { return URLEncoder.encode(s, "UTF-8"); } catch (Exception e) { return s; } }
}
