// VENDORED VERBATIM from apks/atomix 0.1.84 app/src/main/java/com/eurobuddha/atomix/swap/SwapDb.java by scripts/vendor-atomix.sh — do not edit here; re-vendor.
package com.eurobuddha.atomix.swap;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Durable swap state — the native equivalent of the bridge MiniDapp's sql.js tables.
 *
 * Two layers:
 *  - The TRUSTLESS idempotency core (verbatim from upstream): {@code secrets} (every known preimage),
 *    an append-only {@code events} log keyed on the hashlock (HTLC_STARTED / CPTXN_SENT / CPTXN_COLLECT /
 *    CPTXN_EXPIRED), and {@code myhtlc} (what I requested when I initiated, so on collect I re-verify the
 *    counterparty locked the right amount/token before revealing the secret). The engine's guards
 *    (haveSent / haveCollect / haveSecret) read these so a restart never double-locks or double-claims.
 *  - A UX projection: {@code swaps}, one row per swap the engine keeps current for resumable cards. Not
 *    trusted for safety — purely what the home screen shows.
 *
 * State on restart is reconstructed from the chains plus this DB exactly like service.js does.
 */
public final class SwapDb {

    // ---- event names (verbatim from sql.js) ----
    public static final String EV_STARTED  = "HTLC_STARTED";   // I initiated + locked leg 1
    public static final String EV_CPSENT   = "CPTXN_SENT";     // I (responder) locked leg 2
    public static final String EV_COLLECT  = "CPTXN_COLLECT";  // I claimed/withdrew a leg (or a terminal error)
    public static final String EV_EXPIRED  = "CPTXN_EXPIRED";  // I refunded an expired leg / saw a secret revealed
    public static final String EV_MINIMA_CLAIM_SUBMITTED = "MINIMA_CLAIM_SUBMITTED";
    public static final String EV_MINIMA_REFUND_SUBMITTED = "MINIMA_REFUND_SUBMITTED";
    // Counterparty amount/token MISMATCH — its OWN event (backport from atomix-mds 0.1.3). It must NEVER be
    // EV_COLLECT: the claim gates on haveCollect, so a mismatch logged as COLLECT let ANY third party poison a
    // victim's real claim with one hostile dust coin carrying the victim's active hash (mutual-refund grief).
    public static final String EV_MISMATCH = "CPTXN_MISMATCH";
    // Swap-status visibility (0.1.84; same names in atomix-mds lib/swapdb.js):
    public static final String EV_TAKE_SENT    = "TAKE_SENT";      // taker: buy handshake sent (note = maker comms id)
    public static final String EV_MAKER_NOTICE = "MAKER_NOTICE";   // taker: sealed notice from that maker (note = state|reason)
    public static final String EV_DECLINED     = "BUY_DECLINED";   // maker: why I did not lock (note = reason)
    public static final String EV_TIMEOUT      = "MAKER_TIMEOUT";  // taker: the maker's respond-by deadline passed
    public static final String EV_REFUND_SENT  = "REFUND_SENT";    // taker: my ETH refund broadcast (note = tx hash)

    // ---- UX swap status ----
    public static final String ST_STARTED   = "STARTED";    // leg 1 locked, waiting for counterparty
    public static final String ST_LOCKED    = "LOCKED";     // both legs locked
    public static final String ST_CLAIMING  = "CLAIMING";   // claiming a leg
    public static final String ST_COMPLETE  = "COMPLETE";   // I have my funds
    public static final String ST_REFUNDED  = "REFUNDED";   // I refunded after timeout
    public static final String ST_ERROR     = "ERROR";

    private final Helper helper;

    public SwapDb(Context ctx) { helper = new Helper(ctx.getApplicationContext()); }

    // ================= secrets =================

    /** Store a preimage for its hashlock. Returns true if newly added (false if we already had it). */
    public synchronized boolean insertSecret(String hash, String secret) {
        if (hash == null || secret == null || hash.isEmpty() || secret.isEmpty()) return false;
        if (getSecret(hash) != null) return false;
        ContentValues v = new ContentValues();
        v.put("hash", norm(hash));
        v.put("secret", secret);
        v.put("added", System.currentTimeMillis());
        return helper.getWritableDatabase().insert("secrets", null, v) >= 0;
    }

    public synchronized String getSecret(String hash) {
        try (Cursor c = helper.getReadableDatabase().rawQuery(
                "SELECT secret FROM secrets WHERE hash=? LIMIT 1", new String[]{norm(hash)})) {
            return c.moveToFirst() ? c.getString(0) : null;
        }
    }

    // ================= event log =================

    public synchronized void logEvent(String hash, String event, String token, String amount, String txnhash) {
        ContentValues v = new ContentValues();
        v.put("hash", norm(hash));
        v.put("event", event);
        v.put("token", token == null ? "" : token);
        v.put("amount", amount == null ? "" : amount);
        v.put("txnhash", txnhash == null ? "" : txnhash);
        v.put("eventdate", System.currentTimeMillis());
        helper.getWritableDatabase().insert("events", null, v);
    }

    public synchronized boolean hasEvent(String hash, String event) {
        try (Cursor c = helper.getReadableDatabase().rawQuery(
                "SELECT 1 FROM events WHERE hash=? AND event=? LIMIT 1", new String[]{norm(hash), event})) {
            return c.moveToFirst();
        }
    }

    public static final class Event {
        public String event, token, amount, note;
        public long date;
    }

    /** All logged events for a swap, newest first — surfaces actions + reject/error notes (in {@code note}). */
    public synchronized java.util.List<Event> getEvents(String hash) {
        java.util.List<Event> out = new ArrayList<>();
        try (Cursor c = helper.getReadableDatabase().rawQuery(
                "SELECT event, token, amount, txnhash, eventdate FROM events WHERE hash=? ORDER BY eventdate DESC",
                new String[]{norm(hash)})) {
            while (c.moveToNext()) {
                Event e = new Event();
                e.event = c.getString(0); e.token = c.getString(1); e.amount = c.getString(2);
                e.note = c.getString(3); e.date = c.getLong(4);
                out.add(e);
            }
        }
        return out;
    }

    public boolean haveSentCounterParty(String hash) { return hasEvent(hash, EV_CPSENT); }
    public boolean haveCollect(String hash)          { return hasEvent(hash, EV_COLLECT); }
    public boolean haveMismatch(String hash)         { return hasEvent(hash, EV_MISMATCH); }
    public boolean haveCollectExpired(String hash)   { return hasEvent(hash, EV_EXPIRED); }

    // ================= myhtlc (what I requested when initiating) =================

    public synchronized void insertMyHtlc(String hash, String reqAmount, String reqToken) {
        ContentValues v = new ContentValues();
        v.put("hash", norm(hash));
        v.put("reqamount", reqAmount);
        v.put("token", reqToken);
        v.put("eventdate", System.currentTimeMillis());
        helper.getWritableDatabase().insertWithOnConflict("myhtlc", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    /** {reqAmount, reqToken} for an initiated swap, or null if I did not start this HTLC. */
    public synchronized String[] getRequest(String hash) {
        try (Cursor c = helper.getReadableDatabase().rawQuery(
                "SELECT reqamount, token FROM myhtlc WHERE hash=? LIMIT 1", new String[]{norm(hash)})) {
            return c.moveToFirst() ? new String[]{c.getString(0), c.getString(1)} : null;
        }
    }

    // ================= swaps (UX projection) =================

    public static final class Swap {
        public String hash;
        public String role;        // INITIATOR / RESPONDER
        public String direction;   // MINIMA_TO_ERC20 / ERC20_TO_MINIMA
        public String sellToken, sellAmount, buyToken, buyAmount;
        public String counterparty;  // a short label (eth or minima key)
        public String status;
        public String contractId;    // ETH leg, if known
        public long myTimelock;      // absolute: minima block OR unix secs for my locked leg
        public boolean myLegIsMinima;// true if my locked leg is the Minima coin (governs timelock units)
        public long created;
        public long updated;
    }

    /**
     * Remember the coin WE locked for this swap, the first time the engine sees it on-chain.
     *
     * Refunding our own lock must never depend on rediscovering it: `coins depth:` is a fixed walk back
     * from the tip, so a lock ages out of view (256 blocks on the hot path, 1024 on the expired sweep) and
     * the engine then cannot refund it because it cannot find it. Stored while the coin is still in range,
     * these four values are everything MinimaHtlc.refund() needs to build the spend later.
     *
     * Write-once: the first sighting wins, so a later scan cannot overwrite a good record with a worse one.
     */
    private final java.util.Set<String> recordedLockCoins = java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    public synchronized void rememberLockCoin(String hash, String coinid, String amount, String tokenid, String owner) {
        if (hash == null || coinid == null || coinid.isEmpty()) return;
        // Cache only hashes we have actually WRITTEN (see the end of this method). Marking on entry would
        // make a call that returns early — no swap row yet — permanently skip the retry, which is worse than
        // the SELECT it saves.
        if (recordedLockCoins.contains(norm(hash))) return;
        try (android.database.Cursor c = helper.getReadableDatabase().rawQuery(
                "SELECT mycoinid FROM swaps WHERE hash=?", new String[]{norm(hash)})) {
            if (c.moveToFirst()) {
                String existing = c.getString(0);
                if (existing != null && !existing.isEmpty()) return;   // already recorded
            } else return;                                             // no such swap row
        }
        ContentValues v = new ContentValues();
        v.put("mycoinid", coinid);
        v.put("mycoinamount", amount);
        v.put("mycointoken", tokenid);
        v.put("mycoinowner", owner);
        helper.getWritableDatabase().update("swaps", v, "hash=?", new String[]{norm(hash)});
        recordedLockCoins.add(norm(hash));   // written — later cycles can skip the read
    }

    /** The remembered lock coin as the JSON shape MinimaHtlc.refund() consumes, or null if we never saw it. */
    public synchronized org.json.JSONObject rememberedLockCoin(String hash) {
        if (hash == null) return null;
        try (android.database.Cursor c = helper.getReadableDatabase().rawQuery(
                "SELECT mycoinid, mycoinamount, mycointoken, mycoinowner, mytimelock FROM swaps WHERE hash=?",
                new String[]{norm(hash)})) {
            if (!c.moveToFirst()) return null;
            String coinid = c.getString(0);
            if (coinid == null || coinid.isEmpty()) return null;
            org.json.JSONObject coin = new org.json.JSONObject();
            org.json.JSONArray state = new org.json.JSONArray();
            try {
                coin.put("coinid", coinid);
                // Both fields, matching a REAL coin's shape. A token coin carries amount (raw, scaled by the
                // token's decimals) AND tokenamount (the human value); MinimaHtlc.coinAmount prefers
                // tokenamount, which is what we stored. Writing it to only one field would leave a
                // reconstructed coin whose `amount` silently means something different from a live one's -
                // a ~10^36 discrepancy on a fund path for whoever reads it next.
                coin.put("tokenamount", c.getString(1));
                coin.put("amount", c.getString(1));
                coin.put("tokenid", c.getString(2));
                // Port 0 is the refund signer, which is all MinimaHtlc.refund() needs. Port 3 (the timelock)
                // is carried too so this coin is valid for EITHER path: checkExpiredMinima opens with
                // `parseBlock(stateAt(coin,3)) < 0 -> return`, so a coin without it would be dropped there in
                // silence - the exact shape of failure this whole record exists to prevent.
                state.put(new org.json.JSONObject().put("port", 0).put("data", c.getString(3)));
                state.put(new org.json.JSONObject().put("port", 3).put("data", String.valueOf(c.getLong(4))));
                state.put(new org.json.JSONObject().put("port", 5).put("data", norm(hash)));
                coin.put("state", state);
            } catch (org.json.JSONException impossible) { return null; }
            return coin;
        }
    }

    /** Save every recovery record together before the first leg can leave the app. */
    public synchronized void prepareInitiator(Swap swap, String secret, String requestAmount, String requestToken) {
        if (swap == null || swap.hash == null || secret == null || secret.isEmpty())
            throw new IllegalArgumentException("Missing swap recovery data");
        SQLiteDatabase db = helper.getWritableDatabase();
        db.beginTransaction();
        try {
            // An existing hash may only retain its original preimage.
            if (!insertSecret(swap.hash, secret) && !secret.equals(getSecret(swap.hash)))
                throw new android.database.sqlite.SQLiteException("Swap secret could not be saved");
            insertMyHtlc(swap.hash, requestAmount, requestToken);
            String[] request = getRequest(swap.hash);
            if (request == null || !java.util.Objects.equals(requestAmount, request[0])
                    || !java.util.Objects.equals(requestToken, request[1]))
                throw new android.database.sqlite.SQLiteException("Swap request could not be saved");
            upsertSwap(swap);
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    public synchronized void upsertSwap(Swap s) {
        ContentValues v = new ContentValues();
        v.put("hash", norm(s.hash));
        v.put("role", s.role);
        v.put("direction", s.direction);
        v.put("selltoken", s.sellToken);
        v.put("sellamount", s.sellAmount);
        v.put("buytoken", s.buyToken);
        v.put("buyamount", s.buyAmount);
        v.put("counterparty", s.counterparty);
        v.put("status", s.status);
        v.put("contractid", s.contractId == null ? "" : s.contractId);
        v.put("mytimelock", s.myTimelock);
        v.put("mylegminima", s.myLegIsMinima ? 1 : 0);
        if (s.created == 0) s.created = System.currentTimeMillis();
        v.put("created", s.created);
        v.put("updated", System.currentTimeMillis());
        if (helper.getWritableDatabase().insertWithOnConflict("swaps", null, v, SQLiteDatabase.CONFLICT_REPLACE) < 0)
            throw new android.database.sqlite.SQLiteException("Swap could not be saved");
    }

    public synchronized void setSwapStatus(String hash, String status) {
        ContentValues v = new ContentValues();
        v.put("status", status);
        v.put("updated", System.currentTimeMillis());
        helper.getWritableDatabase().update("swaps", v, "hash=?", new String[]{norm(hash)});
    }

    public synchronized void setSwapContractId(String hash, String contractId) {
        ContentValues v = new ContentValues();
        v.put("contractid", contractId);
        v.put("updated", System.currentTimeMillis());
        helper.getWritableDatabase().update("swaps", v, "hash=?", new String[]{norm(hash)});
    }

    public synchronized Swap getSwap(String hash) {
        try (Cursor c = helper.getReadableDatabase().rawQuery(
                "SELECT * FROM swaps WHERE hash=? LIMIT 1", new String[]{norm(hash)})) {
            return c.moveToFirst() ? readSwap(c) : null;
        }
    }

    /** Remove a swap row — used to release a record-before-broadcast reservation when the responder's lock
     *  PROVABLY never broadcast, so the leg can be retried. Never call this for a lock that may have landed. */
    public synchronized void deleteSwap(String hash) {
        helper.getWritableDatabase().delete("swaps", "hash=?", new String[]{norm(hash)});
    }

    /** All swaps, newest first. */
    public synchronized List<Swap> allSwaps() {
        List<Swap> out = new ArrayList<>();
        try (Cursor c = helper.getReadableDatabase().rawQuery(
                "SELECT * FROM swaps ORDER BY created DESC", null)) {
            while (c.moveToNext()) out.add(readSwap(c));
        }
        return out;
    }

    /** Hashes of swaps not yet finished — the set the watcher is still interested in (for secret matching). */
    public synchronized Set<String> activeHashes() {
        Set<String> out = new HashSet<>();
        try (Cursor c = helper.getReadableDatabase().rawQuery(
                "SELECT hash FROM swaps WHERE status NOT IN (?,?,?)",
                new String[]{ST_COMPLETE, ST_REFUNDED, ST_ERROR})) {
            while (c.moveToNext()) out.add(c.getString(0));
        }
        return out;
    }

    private static Swap readSwap(Cursor c) {
        Swap s = new Swap();
        s.hash = c.getString(c.getColumnIndexOrThrow("hash"));
        s.role = c.getString(c.getColumnIndexOrThrow("role"));
        s.direction = c.getString(c.getColumnIndexOrThrow("direction"));
        s.sellToken = c.getString(c.getColumnIndexOrThrow("selltoken"));
        s.sellAmount = c.getString(c.getColumnIndexOrThrow("sellamount"));
        s.buyToken = c.getString(c.getColumnIndexOrThrow("buytoken"));
        s.buyAmount = c.getString(c.getColumnIndexOrThrow("buyamount"));
        s.counterparty = c.getString(c.getColumnIndexOrThrow("counterparty"));
        s.status = c.getString(c.getColumnIndexOrThrow("status"));
        s.contractId = c.getString(c.getColumnIndexOrThrow("contractid"));
        s.myTimelock = c.getLong(c.getColumnIndexOrThrow("mytimelock"));
        s.myLegIsMinima = c.getInt(c.getColumnIndexOrThrow("mylegminima")) != 0;
        s.created = c.getLong(c.getColumnIndexOrThrow("created"));
        s.updated = c.getLong(c.getColumnIndexOrThrow("updated"));
        return s;
    }

    /** Normalise a hashlock for keys: lower-case, no 0x prefix, so 0x-prefixed and bare forms match. */
    private static String norm(String h) {
        if (h == null) return "";
        String s = h.trim().toLowerCase();
        return s.startsWith("0x") ? s.substring(2) : s;
    }

    // ================= market_trades (network-wide trade history, from on-chain HTLC locks) =================

    public static final class MarketTrade {
        public String coinid, hash, sizeMinima, reqAmount, reqToken, owner, receiver, status, secret;
        /** The MARKET this print belongs to — the tokenid of the locked Minima leg. Without it both
         *  currencies' prints share one series, and the ~200x price gap (mxUSD parity ~1.00 vs MINIMA
         *  ~0.004) makes the chart meaningless after a currency switch. */
        public String tokenId;
        public double price;          // the counter-asset per locked coin = reqAmount / sizeMinima
        public long createdBlock, observedAt, timelock;
    }

    public static final String MT_OPEN = "OPEN", MT_EXECUTED = "EXECUTED", MT_REFUNDED = "REFUNDED";

    /** Record/refresh an observed open HTLC lock (network-wide). Keyed by coinid; never downgrades a
     *  terminal row back to OPEN. */
    public synchronized void upsertOpenTrade(MarketTrade t) {
        if (t.coinid == null || t.coinid.isEmpty()) return;
        // keep the first-seen observed_at; only set on insert
        helper.getWritableDatabase().execSQL(
                "INSERT OR IGNORE INTO market_trades(coinid,hash,price,size_minima,req_amount,req_token,owner,"
                        + "receiver,created_block,timelock,observed_at,status,tokenid) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                new Object[]{t.coinid, norm(t.hash), t.price, t.sizeMinima, t.reqAmount, t.reqToken, t.owner,
                        t.receiver, t.createdBlock, t.timelock, System.currentTimeMillis(), MT_OPEN,
                        t.tokenId == null ? "" : t.tokenId});
    }

    /** Trades still marked OPEN in ONE market, so the collector can detect which were spent since its last
     *  scan. The token scope is MANDATORY, not a nicety: the scan that feeds the reconcile is itself
     *  token-filtered, so an unscoped read handed the collector the OTHER currency's open locks — absent from
     *  the scan through no fault of their own — and it duly marked every one of them EXECUTED or REFUNDED. */
    public synchronized List<MarketTrade> openTrades(String tokenId) {
        List<MarketTrade> out = new ArrayList<>();
        try (Cursor c = helper.getReadableDatabase().rawQuery(
                "SELECT * FROM market_trades WHERE status=? AND tokenid=?",
                new String[]{MT_OPEN, tokenId == null ? "" : tokenId})) {
            while (c.moveToNext()) out.add(readTrade(c));
        }
        return out;
    }

    public synchronized void markTradeExecuted(String coinid, String secret) {
        ContentValues v = new ContentValues();
        v.put("status", MT_EXECUTED);
        if (secret != null) v.put("secret", secret);
        helper.getWritableDatabase().update("market_trades", v, "coinid=?", new String[]{coinid});
    }

    public synchronized void markTradeRefunded(String coinid) {
        ContentValues v = new ContentValues();
        v.put("status", MT_REFUNDED);
        helper.getWritableDatabase().update("market_trades", v, "coinid=?", new String[]{coinid});
    }

    /** Recent trades in ONE market (any status), newest first by created block then observed time. */
    public synchronized List<MarketTrade> recentTrades(int limit, String tokenId) {
        List<MarketTrade> out = new ArrayList<>();
        try (Cursor c = helper.getReadableDatabase().rawQuery(
                "SELECT * FROM market_trades WHERE tokenid=? ORDER BY created_block DESC, observed_at DESC LIMIT ?",
                new String[]{tokenId == null ? "" : tokenId, String.valueOf(limit)})) {
            while (c.moveToNext()) out.add(readTrade(c));
        }
        return out;
    }

    /** Executed prints for ONE market's chart, oldest→newest so a line plots left-to-right. */
    public synchronized List<MarketTrade> executedTrades(int limit, String tokenId) {
        List<MarketTrade> out = new ArrayList<>();
        try (Cursor c = helper.getReadableDatabase().rawQuery(
                "SELECT * FROM (SELECT * FROM market_trades WHERE status=? AND tokenid=? "
                        + "ORDER BY created_block DESC LIMIT ?) ORDER BY created_block ASC",
                new String[]{MT_EXECUTED, tokenId == null ? "" : tokenId, String.valueOf(limit)})) {
            while (c.moveToNext()) out.add(readTrade(c));
        }
        return out;
    }

    private static MarketTrade readTrade(Cursor c) {
        MarketTrade t = new MarketTrade();
        t.coinid = c.getString(c.getColumnIndexOrThrow("coinid"));
        t.hash = c.getString(c.getColumnIndexOrThrow("hash"));
        t.price = c.getDouble(c.getColumnIndexOrThrow("price"));
        t.sizeMinima = c.getString(c.getColumnIndexOrThrow("size_minima"));
        t.reqAmount = c.getString(c.getColumnIndexOrThrow("req_amount"));
        t.reqToken = c.getString(c.getColumnIndexOrThrow("req_token"));
        t.owner = c.getString(c.getColumnIndexOrThrow("owner"));
        t.receiver = c.getString(c.getColumnIndexOrThrow("receiver"));
        t.createdBlock = c.getLong(c.getColumnIndexOrThrow("created_block"));
        t.timelock = c.getLong(c.getColumnIndexOrThrow("timelock"));
        t.observedAt = c.getLong(c.getColumnIndexOrThrow("observed_at"));
        t.status = c.getString(c.getColumnIndexOrThrow("status"));
        t.secret = c.getString(c.getColumnIndexOrThrow("secret"));
        t.tokenId = c.getString(c.getColumnIndexOrThrow("tokenid"));
        return t;
    }

    private static final class Helper extends SQLiteOpenHelper {
        /** v2 added market_trades; v3 tagged its rows with the market they were observed in. */
        Helper(Context ctx) { super(ctx, "atomix.db", null, 4); }

        @Override public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE secrets (hash TEXT PRIMARY KEY, secret TEXT, added INTEGER)");
            db.execSQL("CREATE TABLE events (id INTEGER PRIMARY KEY AUTOINCREMENT, hash TEXT, event TEXT, "
                    + "token TEXT, amount TEXT, txnhash TEXT, eventdate INTEGER)");
            db.execSQL("CREATE INDEX idx_events_hash ON events(hash)");
            db.execSQL("CREATE TABLE myhtlc (hash TEXT PRIMARY KEY, reqamount TEXT, token TEXT, eventdate INTEGER)");
            db.execSQL("CREATE TABLE swaps (hash TEXT PRIMARY KEY, role TEXT, direction TEXT, "
                    + "selltoken TEXT, sellamount TEXT, buytoken TEXT, buyamount TEXT, counterparty TEXT, "
                    + "status TEXT, contractid TEXT, mytimelock INTEGER, mylegminima INTEGER, "
                    + "created INTEGER, updated INTEGER, "
                    // the coin WE locked — a refund is built from these, never from a depth-bounded scan
                    + "mycoinid TEXT, mycoinamount TEXT, mycointoken TEXT, mycoinowner TEXT)");
            createMarket(db);
        }

        @Override public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {
            createMarket(db);   // v1→v2: add the market_trades table (idempotent)
            tagMarketTokens(db);// v2→v3: tag each print with its market (idempotent)
            addLockCoin(db);    // v3→v4: remember the coin we locked, so a refund never needs to FIND it
        }

        /**
         * v3→v4. Remember our own Minima lock's coin, so refunding it never depends on a chain SCAN.
         *
         * `coins depth:` walks back a fixed number of blocks from the tip, so a lock becomes invisible to
         * the app once it ages past that window - 256 blocks on the hot path, 1024 on the expired sweep -
         * and from then on the engine can never refund it, because it can no longer find it. Observed live
         * 2026-10-01 on a 26.99025 MxUSD lock that was 1,304 blocks old: provably unspent in the archive,
         * invisible to the app, and reported as "spent/claimed" from the empty scan result.
         *
         * We CREATED this coin; needing to rediscover it was the mistake. These four columns are everything
         * MinimaHtlc.refund() consumes, so the refund can be built straight from the row.
         */
        static void addLockCoin(SQLiteDatabase db) {
            for (String col : new String[]{"mycoinid TEXT", "mycoinamount TEXT", "mycointoken TEXT", "mycoinowner TEXT"}) {
                try { db.execSQL("ALTER TABLE swaps ADD COLUMN " + col); }
                catch (android.database.SQLException alreadyThere) { /* idempotent: re-run is a no-op */ }
            }
        }

        @Override public void onDowngrade(SQLiteDatabase db, int oldV, int newV) {
            // MA-16: installing an older APK over a newer one would otherwise throw the default
            // SQLiteException and crash on startup until app data is cleared. This DB holds the `secrets`
            // table (HTLC claim preimages) and swap identity — dropping it is FUND-DESTROYING — so accept the
            // downgrade as a no-op (the schema is additive; older code simply ignores newer tables/columns).
            db.setVersion(oldV);
        }

        private void createMarket(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS market_trades (coinid TEXT PRIMARY KEY, hash TEXT, "
                    + "price REAL, size_minima TEXT, req_amount TEXT, req_token TEXT, owner TEXT, receiver TEXT, "
                    + "created_block INTEGER, timelock INTEGER, observed_at INTEGER, status TEXT, secret TEXT, "
                    + "tokenid TEXT)");
        }

        /**
         * v2→v3: give every market print the market it belongs to.
         *
         * <p>Rows written before this have no token at all, so they are back-attributed ONCE by price band.
         * That is a heuristic, but a wide one: the dollar market is pegged at parity (~1.00) while MINIMA
         * trades around 0.004, ~200x apart, so 0.5 sits in empty space between them. It only affects which
         * chart a historical print is drawn on — no fund decision reads this column.
         *
         * <p>Idempotent by necessity: SQLite has no ADD COLUMN IF NOT EXISTS, and onDowngrade deliberately
         * lets an older APK run against this schema (see below), so the upgrade can be re-entered on the next
         * install. A duplicate-column error therefore means the work is already done, not that it failed.
         */
        private void tagMarketTokens(SQLiteDatabase db) {
            try { db.execSQL("ALTER TABLE market_trades ADD COLUMN tokenid TEXT"); }
            catch (Exception alreadyThere) { /* column exists — re-entered upgrade */ }
            db.execSQL("UPDATE market_trades SET tokenid = CASE WHEN price >= 0.5 THEN ? ELSE ? END "
                            + "WHERE tokenid IS NULL OR tokenid = ''",
                    new Object[]{MinimaHtlc.USDT_TOKENID, MinimaHtlc.MINIMA_TOKENID});
        }
    }
}
