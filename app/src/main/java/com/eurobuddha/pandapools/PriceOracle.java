// VENDORED VERBATIM from apks/pandapools 0.9.87 app/src/main/java/com/eurobuddha/pandapools/PriceOracle.java by scripts/vendor-pandapools.sh — do not edit here; re-vendor.
package com.eurobuddha.pandapools;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Minimal MEXC MINIMA/USDT price oracle — a one-shot async fetch of the live mid (USDT per MINIMA), used
 * to force a market-correct opening price when creating a MINIMA/USDT pool. Ported from minimaSwap's
 * PriceOracle (the {@code httpGet} pattern) but stripped to just the fetch — no peg/ladder/tombstone
 * machinery. The last-good price is cached with a timestamp so a dialog can show it immediately and treat
 * it as usable only while {@link #fresh()}.
 */
public final class PriceOracle {

    public static final String SOURCE = "MEXC";
    // PRIMARY: order-book depth — the effective bid/ask is the level where cumulative notional reaches
    // DEPTH_MIN_USDT, so dust at the top-of-book (trivially movable on a thin market) can't set the opening
    // price of a pool. FALLBACK: top-of-book bookTicker (weaker, still spread-checked).
    private static final String DEPTH_URL = "https://api.mexc.com/api/v3/depth?symbol=MINIMAUSDT&limit=20";
    private static final String BOOK_URL = "https://api.mexc.com/api/v3/ticker/bookTicker?symbol=MINIMAUSDT";
    private static final double DEPTH_MIN_USDT = 25;   // effective bid/ask = level where cumulative notional ≥ this
    private static final double MAX_SPREAD     = 0.20; // reject a book wider than this (too thin to quote)
    /** A price older than this is treated as stale — a create must not use a stale MEXC price (it falls back
     *  to the live-pool anchor instead). */
    public static final long FRESH_MS = 5 * 60_000L;
    private static final MathContext MC = new MathContext(20, RoundingMode.HALF_UP);

    public interface Cb { void onPrice(BigDecimal mid); void onError(String message); }

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "pandapools-price"); t.setDaemon(true); return t;
    });
    private static final Handler UI = new Handler(Looper.getMainLooper());

    private static volatile BigDecimal lastMid = null;
    private static volatile long lastMs = 0;

    private PriceOracle() {}

    /** Last-good cached mid (may be null if never fetched; may be stale — check {@link #fresh()}). */
    public static BigDecimal cachedMid() { return lastMid; }

    public static boolean fresh() {
        long age = System.currentTimeMillis() - lastMs;
        return lastMid != null && lastMid.signum() > 0 && age >= 0 && age <= FRESH_MS;
    }

    /** One-shot async fetch of the MINIMA/USDT mid (robust: depth effective-level + spread check, bookTicker
     *  fallback); the callback fires on the UI thread. */
    public static void fetch(Cb cb) {
        EXEC.execute(() -> {
            try {
                double b, a;
                try {
                    JSONObject depth = httpGet(DEPTH_URL);
                    b = effectiveLevel(depth.optJSONArray("bids"));
                    a = effectiveLevel(depth.optJSONArray("asks"));
                } catch (Exception depthDown) {
                    // depth endpoint unreachable → weaker top-of-book, still spread-checked below
                    JSONObject book = httpGet(BOOK_URL);
                    b = book.optDouble("bidPrice", 0);
                    a = book.optDouble("askPrice", 0);
                }
                if (!(b > 0) || !(a > 0) || b > a) throw new Exception("thin/empty book");
                if ((a - b) / a >= MAX_SPREAD) throw new Exception("market too thin to quote");
                BigDecimal mid = new BigDecimal(Double.toString((a + b) / 2.0), MC);
                if (mid.signum() <= 0) throw new Exception("bad price");
                lastMid = mid; lastMs = System.currentTimeMillis();
                UI.post(() -> cb.onPrice(mid));
            } catch (Exception e) {
                String msg = e.getMessage() == null ? "price unavailable" : e.getMessage();
                UI.post(() -> cb.onError(msg));
            }
        });
    }

    /** Price at which cumulative notional (price × qty) reaches DEPTH_MIN_USDT, or 0 if the side is too thin.
     *  Rows are ["price","qty"] pairs, best level first. */
    private static double effectiveLevel(JSONArray side) {
        try {
            if (side == null) return 0;
            double cum = 0;
            for (int i = 0; i < side.length(); i++) {
                JSONArray row = side.getJSONArray(i);
                double px = Double.parseDouble(row.getString(0));
                double qty = Double.parseDouble(row.getString(1));
                if (!(px > 0) || !(qty > 0)) return 0;
                cum += px * qty;
                if (cum >= DEPTH_MIN_USDT) return px;
            }
            return 0;
        } catch (Exception e) { return 0; }
    }

    private static JSONObject httpGet(String u) throws Exception {
        if (ImageLoader.isBlockedHost(new URL(u).getHost())) throw new Exception("blocked host");
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(u).openConnection();
            c.setConnectTimeout(10_000);
            c.setReadTimeout(10_000);
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("User-Agent", "PandaPools");
            int code = c.getResponseCode();
            InputStream in = (code >= 200 && code < 300) ? c.getInputStream() : c.getErrorStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            if (in != null) {
                byte[] buf = new byte[4096];
                int n, total = 0;
                while ((n = in.read(buf)) > 0) { bos.write(buf, 0, n); total += n; if (total > 16384) break; }
            }
            String body = bos.toString("UTF-8");
            if (code < 200 || code >= 300) throw new Exception("HTTP " + code);
            return new JSONObject(body);
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
