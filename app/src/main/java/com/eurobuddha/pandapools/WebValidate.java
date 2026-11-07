// VENDORED VERBATIM from apks/pandapools 0.9.88 app/src/main/java/com/eurobuddha/pandapools/WebValidate.java by scripts/vendor-pandapools.sh — do not edit here; re-vendor.
package com.eurobuddha.pandapools;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Web-validation check (matches the official Minima wallet): a token is web-validated when the file at its
 * {@code token.webvalidate} URL contains the token's id — proving whoever set it controls that domain. Result
 * is cached per tokenid; fetched once on a worker thread.
 */
public final class WebValidate {

    private static final ConcurrentHashMap<String, Boolean> CACHE = new ConcurrentHashMap<>();
    private static final Set<String> INFLIGHT = Collections.synchronizedSet(new HashSet<>());
    private static final java.util.concurrent.ExecutorService EXEC =
            java.util.concurrent.Executors.newFixedThreadPool(2);

    private WebValidate() {}

    /** TRUE validated, FALSE not / failed, null not checked yet. */
    public static Boolean status(String tokenid) { return CACHE.get(norm(tokenid)); }

    /** Kick off a check if we haven't already; calls onDone on the UI thread when a fresh result lands. */
    public static void ensure(final MainActivity act, final String tokenid, final String webvalidateUrl, final Runnable onDone) {
        final String k = norm(tokenid);
        if (CACHE.containsKey(k)) return;
        if (webvalidateUrl == null || webvalidateUrl.trim().isEmpty()) { CACHE.put(k, Boolean.FALSE); return; }
        if (!INFLIGHT.add(k)) return;
        EXEC.execute(() -> {
            boolean ok = fetchContains(webvalidateUrl.trim(), tokenid);
            CACHE.put(k, ok);
            INFLIGHT.remove(k);
            if (onDone != null) act.runOnUiThread(() -> { if (!act.isDestroyed()) onDone.run(); });
        });
    }

    private static boolean fetchContains(String url, String tokenid) {
        try {
            // NetFetch: http(s) only, no loopback/LAN targets, redirects re-checked per hop, body capped at 256KB.
            byte[] bytes = NetFetch.get(url, 262144, 8000, 10000, true);
            if (bytes == null) return false;
            String body = new String(bytes, StandardCharsets.UTF_8).toLowerCase();
            String tid = tokenid.toLowerCase();
            String bare = tid.startsWith("0x") ? tid.substring(2) : tid;
            return body.contains(tid) || body.contains(bare);
        } catch (Throwable t) {
            return false;
        }
    }

    private static String norm(String t) {
        if (t == null) return "";
        String s = t.trim().toLowerCase();
        return s.startsWith("0x") ? s.substring(2) : s;
    }
}
