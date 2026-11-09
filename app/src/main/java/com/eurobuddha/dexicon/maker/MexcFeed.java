package com.eurobuddha.dexicon.maker;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.MathContext;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * MEXC MINIMA/USDT price feed for the fast market maker — a port of AtomiX's PriceOracle MINIMA path
 * (apks/atomix swap/PriceOracle.java), with the SAME fund-safety rules and constants:
 *   - effective bid/ask = the book level where cumulative notional reaches 100 USDT (dust can't move it);
 *   - a spread ≥ 20 % or an empty/crossed book is "no price", never a guess;
 *   - a > 50 % move needs a second agreeing read before it prices anything;
 *   - fresh ≤ 5 min → tight; stale → the spread widens linearly to ×10 over 90 min; no price at all, or
 *     stale past 12 h → withdraw.
 * Why not AtomiX's class itself: its state follows the process-global TradingContext.active(), which
 * Dexicon's AtomiX tab flips between the MxUSD (parity 1.0) and MINIMA markets — sharing it could price a
 * MINIMA ladder at 1.0. This feed is MINIMA-only, so that cannot happen. MxUSD is priced as USDT (parity).
 */
public final class MexcFeed {
    private MexcFeed() {}

    static final String DEPTH_URL = "https://api.mexc.com/api/v3/depth?symbol=MINIMAUSDT&limit=50";
    static final String BOOK_URL = "https://api.mexc.com/api/v3/ticker/bookTicker?symbol=MINIMAUSDT";
    /** 9 Nov: the S10+ could not get ANY reply from MEXC's CDN (read timed out on every MEXC host) while the Fold on the same
     *  Wi-Fi got one in 18 ms, and the pegged ladder was withdrawn for hours. When MEXC is UNREACHABLE (a network error, not
     *  a thin book) the feed falls back, in order, to XT.COM's MINIMA/USDT book (same 100-USDT depth rule, so its thin book
     *  quotes wide, never a guess) and then CoinGecko's MINIMA price (aged by its own timestamp). Every read still passes
     *  accept(): spread, crossed-book and the 3 %/60 s jump rules apply to fallback prices exactly as to MEXC's. */
    static final String XT_URL = "https://sapi.xt.com/v4/public/depth?symbol=minima_usdt&limit=50";
    static final String GECKO_URL = "https://api.coingecko.com/api/v3/simple/price?ids=minima&vs_currencies=usd&include_last_updated_at=true";
    public static final long FRESH_MS = 5 * 60_000;
    static final long FETCH_GAP_MS = 30_000;
    static final long STALE_WIDEN_FULL_MS = 90 * 60_000;
    static final double MAX_WIDEN = 10.0;
    public static final long HARD_STALE_MS = 12 * 60 * 60_000L;
    /** Security review 30 Oct: the ladder followed a thin feed (25 USDT deep, ±50 % in one read), so a cheap push on MEXC
     *  repriced it for a taker to pick off. Now 100 USDT deep (≈1 % from the touch on 30 Oct's book), and any move over
     *  3 % is taken only after it HOLDS — a second read within 1 % of it, at least 60 s later. */
    static final double JUMP_FRACTION = 0.03, CONFIRM_BAND = 0.01;
    static final long CONFIRM_MS = 60_000;
    static final double DEPTH_MIN_USDT = 100;

    private static final Object LOCK = new Object();
    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "dexicon-mexc"); t.setDaemon(true); return t; });
    private static double price, bid, ask, suspect;
    private static long suspectAtMs;
    private static long goodAtMs, lastTryMs;
    private static boolean fetching;
    private static String lastError;
    private static String source = "MEXC";

    public static double mid() { synchronized (LOCK) { return price; } }
    public static long ageMs() {
        synchronized (LOCK) {
            if (goodAtMs <= 0) return Long.MAX_VALUE;
            long a = System.currentTimeMillis() - goodAtMs;
            return a < 0 ? Long.MAX_VALUE : a;   // a backward clock jump reads as STALE, never fresh
        }
    }
    public static boolean fresh() { return mid() > 0 && ageMs() <= FRESH_MS && !jumpPending(); }
    /** Security review 7 Nov (R2-1): a move over JUMP_FRACTION is being confirmed — the held mid may be badly out of date. */
    public static boolean jumpPending() { synchronized (LOCK) { return suspect > 0; } }
    /** True when a pegged ladder must not be live: no price at all, or the feed has been down past 12 h. */
    public static boolean unusable() { return !(mid() > 0) || ageMs() > HARD_STALE_MS || jumpPending(); }
    /** No price and no failure yet since the app started: the first fetch is still on its way. A pegged ladder keeps its
     *  live orders meanwhile instead of withdrawing them (28 Oct: every app update cancelled the whole ladder). */
    public static boolean firstFetchPending() { synchronized (LOCK) { return price <= 0 && lastError == null; } }
    /** Spread multiplier for the current feed age (1 while fresh). */
    public static double widen() { return wideningFactor(ageMs()); }

    static double wideningFactor(long ageMs) {
        if (ageMs <= FRESH_MS) return 1.0;
        double t = (double) (ageMs - FRESH_MS) / (STALE_WIDEN_FULL_MS - FRESH_MS);
        if (t < 0) t = 0; else if (t > 1) t = 1;
        return 1.0 + (MAX_WIDEN - 1.0) * t;
    }

    public static String describe() {
        synchronized (LOCK) {
            if (price <= 0) return fetching ? "MEXC: fetching…" + (lastError != null ? " (last try: " + lastError + ")" : "")
                    : lastError != null ? "MEXC: unavailable (" + lastError + ")" : "MEXC: no price yet";
            long age = System.currentTimeMillis() - goodAtMs;
            String s = ("MEXC".equals(source) ? "MEXC mid " : source + " mid (fallback: MEXC unreachable) ") + fmt(price) + (bid > 0 ? "  (bid " + fmt(bid) + " / ask " + fmt(ask) + ")" : "") + "  ·  " + (age / 1000) + "s ago";
            return age > FRESH_MS ? s + "  — STALE · defensive spread ×" + new BigDecimal(wideningFactor(age), new MathContext(2)).stripTrailingZeros().toPlainString() : s;
        }
    }
    private static String fmt(double v) { return new BigDecimal(v, new MathContext(6)).stripTrailingZeros().toPlainString(); }

    /** Rate-limited background refresh — safe to call from any thread, every tick. */
    public static void refreshAsync() {
        synchronized (LOCK) {
            long now = System.currentTimeMillis();
            if (fetching || now - lastTryMs < FETCH_GAP_MS) return;
            fetching = true; lastTryMs = now;
        }
        EXEC.execute(() -> { try { fetchOnce(); } finally { synchronized (LOCK) { fetching = false; } } });
    }

    private static void fetchOnce() {
        StringBuilder errs = new StringBuilder();
        // MEXC first. Its answer is authoritative even when it says "too thin": only a NETWORK failure moves on.
        try {
            double b, a;
            try {
                JSONObject depth = httpGet(DEPTH_URL);
                b = effectiveLevel(depth.optJSONArray("bids"));
                a = effectiveLevel(depth.optJSONArray("asks"));
            } catch (IOException ignore) {
                JSONObject book = httpGet(BOOK_URL);
                b = book.optDouble("bidPrice", 0); a = book.optDouble("askPrice", 0);
            }
            long now = System.currentTimeMillis();
            accept(b, a, now, now, "MEXC");
            return;
        } catch (Exception e) { errs.append("MEXC ").append(why(e)); }
        try {
            double[] r = parseXt(httpGet(XT_URL));
            if (r == null) throw new IOException("no XT.COM book");
            long now = System.currentTimeMillis();
            accept(r[0], r[1], now, now, "XT.COM");
            return;
        } catch (Exception e) { errs.append("; XT.COM ").append(why(e)); }
        try {
            long now = System.currentTimeMillis();
            double[] r = parseGecko(httpGet(GECKO_URL), now);
            if (r == null) throw new IOException("no CoinGecko price");
            accept(r[0], r[1], now, (long) r[2], "CoinGecko");
            return;
        } catch (Exception e) { errs.append("; CoinGecko ").append(why(e)); }
        synchronized (LOCK) { lastError = errs.toString(); }
    }
    private static String why(Exception e) { return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(); }

    /** XT.COM v4 depth → {bid, ask} at the 100-USDT depth level, or null (bad answer). Pure, for tests. */
    static double[] parseXt(JSONObject d) {
        if (d == null || d.optInt("rc", -1) != 0) return null;
        JSONObject r = d.optJSONObject("result");
        if (r == null) return null;
        return new double[]{effectiveLevel(r.optJSONArray("bids")), effectiveLevel(r.optJSONArray("asks"))};
    }
    /** CoinGecko simple price → {mid, mid, dataAtMs}, or null. A future timestamp counts as now. Pure, for tests. */
    static double[] parseGecko(JSONObject d, long nowMs) {
        JSONObject m = d == null ? null : d.optJSONObject("minima");
        if (m == null) return null;
        double p = m.optDouble("usd", 0); long at = m.optLong("last_updated_at", 0) * 1000;
        if (!(p > 0) || at <= 0) return null;
        return new double[]{p, p, Math.min(at, nowMs)};
    }

    /** Apply one read (bid, ask) with AtomiX's sanity rules. Returns the error, or null when the price was taken. */
    static String accept(double b, double a, long nowMs) { return accept(b, a, nowMs, nowMs, "MEXC"); }
    /** As {@link #accept(double, double, long)}, from {@code src}, whose data dates from {@code dataAtMs} (its age widens). */
    static String accept(double b, double a, long nowMs, long dataAtMs, String src) {
        synchronized (LOCK) {
            String err = null;
            if (!(b > 0) || !(a > 0) || b > a) err = "thin/empty book";
            else if ((a - b) / a >= 0.2) err = "spread too wide — book too thin to quote";
            double m = (a + b) / 2;
            if (err == null && (!(m > 0) || Double.isInfinite(m))) err = "bad price";
            if (err == null && price > 0 && Math.abs(m - price) / price > JUMP_FRACTION) {
                // Security review 7 Nov (R2-1): a move is confirmed once reads stay on the SAME side of the held price, beyond
                // JUMP_FRACTION, for CONFIRM_MS — a trending market (each read a little further) no longer resets the timer
                // forever and freezes the ladder at an old price. While it is pending, unusable()/fresh() keep a pegged ladder
                // and auto-cross OFF, so nothing is quoted or refilled at the old price meanwhile.
                boolean sameSide = suspect > 0 && Math.signum(m - price) == Math.signum(suspect - price);
                if (!sameSide) { suspect = m; suspectAtMs = nowMs; err = "suspect jump " + fmt(price) + "→" + fmt(m) + " — held until it holds for 60 s"; }
                else { suspect = m; if (nowMs - suspectAtMs < CONFIRM_MS) err = "confirming jump " + fmt(price) + "→" + fmt(m); }
            }
            if (err != null) { lastError = err; return err; }
            suspect = 0; price = m; bid = b; ask = a; goodAtMs = Math.min(nowMs, dataAtMs); lastError = null; source = src;
            return null;
        }
    }

    /** Price where cumulative notional (price × qty) reaches DEPTH_MIN_USDT, or 0 if the side can't absorb it. */
    static double effectiveLevel(JSONArray side) {
        try {
            if (side == null) return 0;
            double cum = 0;
            for (int i = 0; i < side.length(); i++) {
                JSONArray row = side.getJSONArray(i);
                double px = Double.parseDouble(row.getString(0)), qty = Double.parseDouble(row.getString(1));
                if (!(px > 0) || !(qty > 0)) return 0;
                cum += px * qty;
                if (cum >= DEPTH_MIN_USDT) return px;
            }
            return 0;
        } catch (Exception e) { return 0; }
    }

    /** Test hook: forget everything. */
    static void reset() { synchronized (LOCK) { price = bid = ask = suspect = 0; goodAtMs = lastTryMs = suspectAtMs = 0; lastError = null; source = "MEXC"; } }
    static String source() { synchronized (LOCK) { return source; } }

    private static JSONObject httpGet(String u) throws IOException {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(u).openConnection();
            c.setConnectTimeout(10_000); c.setReadTimeout(10_000);
            c.setRequestProperty("Accept", "application/json");
            int code = c.getResponseCode();
            InputStream in = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            if (in != null) {
                byte[] buf = new byte[4096]; int n, total = 0;
                while ((n = in.read(buf)) > 0) { bos.write(buf, 0, n); total += n; if (total > 16384) break; }
            }
            String body = bos.toString("UTF-8");
            if (code < 200 || code >= 300) throw new IOException("HTTP " + code);
            return new JSONObject(body);
        } catch (org.json.JSONException e) {
            throw new IOException("bad JSON");
        } finally { if (c != null) c.disconnect(); }
    }
}
