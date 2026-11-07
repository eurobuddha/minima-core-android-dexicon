// VENDORED VERBATIM from apks/pandapools 0.9.83 app/src/main/java/com/eurobuddha/pandapools/HistoryDb.java by scripts/vendor-pandapools.sh — do not edit here; re-vendor.
package com.eurobuddha.pandapools;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * Persistent, txpowid-keyed mirror of the node's relevant history — a verbatim port of the standalone
 * Minima History app's store. It ACCUMULATES forever (never pruned), so a transaction stays in the local
 * record even after the node drops it from its own `history`, and the Activity tab shows instantly + offline.
 */
public class HistoryDb extends SQLiteOpenHelper {

    private static final String DB = "pandapools_history.db";
    /** v2: rows stored before this held the wrong token amount in inputs/outputs (see
     *  {@link HistoryEntry} — `amount` was read where `tokenamount` was meant). The table is never
     *  dropped; instead {@link #onUpgrade} arms a one-time re-sync that rewrites the rows in place. */
    private static final int VERSION = 5;
    private static final String TX = "tx";
    private static final String PUBLIC = "public_pool_tx";
    private static final String META = "meta";
    /** Meta flag driving the v2 token-amount repair: "pending" until a full re-sync has rewritten rows. */
    public static final String META_REPAIR_V2 = "repair_v2";

    public HistoryDb(Context ctx) { super(ctx, DB, null, VERSION); }

    @Override
    public void onCreate(SQLiteDatabase db) {
        createTransactions(db, TX);
        createTransactions(db, PUBLIC);
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_block ON " + TX + "(block)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_dir ON " + TX + "(direction)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_tok ON " + TX + "(tokenid)");
        db.execSQL("CREATE TABLE IF NOT EXISTS " + META + " (k TEXT PRIMARY KEY, v TEXT)");
    }

    private static void createTransactions(SQLiteDatabase db, String table) {
        db.execSQL("CREATE TABLE IF NOT EXISTS " + table + " (" +
                "txpowid TEXT PRIMARY KEY," +
                "block INTEGER, timemilli INTEGER," +
                "direction TEXT, incoming INTEGER," +
                "tokenid TEXT, tokenname TEXT, amount TEXT," +
                "deltas TEXT, counterparty TEXT, inputs TEXT, outputs TEXT," +
                "synced_at INTEGER, transactionid TEXT DEFAULT '', verified_depth INTEGER DEFAULT -1, verified_at INTEGER DEFAULT 0)");
    }

    // Never drop the table (permanence). Future versions add columns via ALTER here.
    @Override public void onUpgrade(SQLiteDatabase db, int o, int n) {
        onCreate(db);
        if (o < 2) {
            // Arm the token-amount repair: clear the backfill flag so HistorySync pages the whole of what
            // the node still retains, rewriting each row through upsert(). Rows the node has since dropped
            // keep their old token amounts — beyond reach, and the statement's P&L does not read them.
            ContentValues cv = new ContentValues();
            cv.put("k", META_REPAIR_V2); cv.put("v", "pending");
            db.insertWithOnConflict(META, null, cv, SQLiteDatabase.CONFLICT_REPLACE);
            db.delete(META, "k=?", new String[]{"backfill_done"});
        }
        if (o < 3) {
            db.execSQL("ALTER TABLE " + TX + " ADD COLUMN transactionid TEXT DEFAULT ''");
            db.execSQL("ALTER TABLE " + TX + " ADD COLUMN verified_depth INTEGER DEFAULT -1");
            db.execSQL("ALTER TABLE " + TX + " ADD COLUMN verified_at INTEGER DEFAULT 0");
            ContentValues cv = new ContentValues();
            cv.put("k", META_REPAIR_V2); cv.put("v", "pending");
            db.insertWithOnConflict(META, null, cv, SQLiteDatabase.CONFLICT_REPLACE);
            db.delete(META, "k=?", new String[]{"backfill_done"});
        }
        if (o < 4) {
            // Replay retained headers for cryptographic recovery of legacy pre-mining receipts.
            ContentValues cv = new ContentValues();
            cv.put("k", META_REPAIR_V2); cv.put("v", "pending");
            db.insertWithOnConflict(META, null, cv, SQLiteDatabase.CONFLICT_REPLACE);
            db.delete(META, "k=?", new String[]{"backfill_done"});
        }
    }

    /** Insert a row; returns true if it was NEW, false if this txpowid was already stored. Idempotent.
     *
     *  The boolean is load-bearing: it is how {@link HistorySync}'s incremental pass knows it has caught up
     *  with what it already holds. Do NOT switch this to CONFLICT_REPLACE — replace always succeeds, the
     *  caught-up test would never fire, and an incremental sync would page forever. Use {@link #upsert}. */
    public boolean insert(HistoryEntry e) {
        long rid = getWritableDatabase().insertWithOnConflict(TX, null, values(e), SQLiteDatabase.CONFLICT_IGNORE);
        if (rid == -1 && !e.transactionId.isEmpty()) {
            ContentValues identity = new ContentValues(); identity.put("transactionid", e.transactionId);
            getWritableDatabase().update(TX, identity, "txpowid=?", new String[]{e.txpowid});
        }
        return rid != -1;
    }

    /** Write a row, overwriting any existing one with the same txpowid. Used only by the v2 repair pass,
     *  which has to rewrite rows that are already stored. */
    public void upsert(HistoryEntry e) {
        ContentValues v = values(e);
        v.remove("verified_depth"); v.remove("verified_at");
        if (getWritableDatabase().update(TX, v, "txpowid=?", new String[]{e.txpowid}) == 0) insert(e);
    }

    private static ContentValues values(HistoryEntry e) {
        ContentValues v = new ContentValues();
        v.put("txpowid", e.txpowid);
        v.put("block", e.block);
        v.put("timemilli", e.timemilli);
        v.put("direction", e.direction);
        v.put("incoming", e.incoming ? 1 : 0);
        v.put("tokenid", e.tokenid);
        v.put("tokenname", e.tokenName);
        v.put("amount", e.amount);
        v.put("deltas", e.deltas);
        v.put("counterparty", e.counterparty);
        v.put("inputs", e.inputs);
        v.put("outputs", e.outputs);
        v.put("synced_at", e.syncedAt);
        v.put("transactionid", e.transactionId);
        v.put("verified_depth", e.verifiedDepth);
        v.put("verified_at", e.verifiedAt);
        return v;
    }

    public int count() {
        Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM " + TX, null);
        try { return c.moveToFirst() ? c.getInt(0) : 0; } finally { c.close(); }
    }

    /** Newest-first page, optionally free-text filtered across address / id / token / amount / direction. */
    public List<HistoryEntry> list(int limit, int offset, String search) {
        List<HistoryEntry> out = new ArrayList<>();
        String where = "";
        String[] args = null;
        if (search != null && !search.trim().isEmpty()) {
            String s = "%" + search.trim() + "%";
            where = " WHERE counterparty LIKE ? OR txpowid LIKE ? OR tokenname LIKE ? OR amount LIKE ? OR direction LIKE ?";
            args = new String[]{s, s, s, s, s};
        }
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT txpowid,block,timemilli,direction,incoming,tokenid,tokenname,amount,deltas,counterparty,inputs,outputs,synced_at,transactionid,verified_depth,verified_at FROM "
                        + TX + where + " ORDER BY block DESC, timemilli DESC LIMIT " + limit + " OFFSET " + offset, args);
        try {
            while (c.moveToNext()) out.add(read(c));
        } finally { c.close(); }
        return out;
    }

    /**
     * Every stored transaction in a time window, OLDEST FIRST — the order the accounting export needs, since
     * a running balance only means anything accumulated forwards. Unbounded by design: a ledger with a
     * LIMIT on it does not reconcile.
     *
     * The window is on {@code timemilli} (wall-clock, what a tax year is defined in) rather than block
     * height. Pass {@code fromMs = 0} and {@code toMs = Long.MAX_VALUE} for all time.
     */
    public List<HistoryEntry> listChronological(long fromMs, long toMs) {
        List<HistoryEntry> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT txpowid,block,timemilli,direction,incoming,tokenid,tokenname,amount,deltas,counterparty,inputs,outputs,synced_at,transactionid,verified_depth,verified_at FROM "
                        + TX + " WHERE timemilli BETWEEN ? AND ? ORDER BY block ASC, timemilli ASC",
                new String[]{String.valueOf(fromMs), String.valueOf(toMs)});
        try {
            while (c.moveToNext()) out.add(read(c));
        } finally { c.close(); }
        return out;
    }

    /** Lowest and highest block present, as {@code {min, max}}; {@code {0, 0}} when the table is empty.
     *  Reported in the export's provenance block so the coverage of the ledger is explicit. */
    public long[] blockRange() {
        Cursor c = getReadableDatabase().rawQuery("SELECT MIN(block), MAX(block) FROM " + TX, null);
        try {
            if (c.moveToFirst()) return new long[]{c.getLong(0), c.getLong(1)};
        } finally { c.close(); }
        return new long[]{0, 0};
    }

    private static HistoryEntry read(Cursor c) {
        HistoryEntry e = new HistoryEntry();
        e.txpowid = c.getString(0); e.block = c.getLong(1); e.timemilli = c.getLong(2);
        e.direction = c.getString(3); e.incoming = c.getInt(4) == 1;
        e.tokenid = c.getString(5); e.tokenName = c.getString(6); e.amount = c.getString(7);
        e.deltas = c.getString(8); e.counterparty = c.getString(9);
        e.inputs = c.getString(10); e.outputs = c.getString(11); e.syncedAt = c.getLong(12);
        e.transactionId = c.getString(13); e.verifiedDepth = c.getInt(14); e.verifiedAt = c.getLong(15);
        return e;
    }

    /** Exact transaction identity resolves a receipt even when its mined row was already cached. */
    public String minedIdFor(String transactionId) {
        if (!FundingCoins.hex(transactionId)) return "";
        Cursor c = getReadableDatabase().rawQuery("SELECT txpowid FROM " + TX
                + " WHERE transactionid=? COLLATE NOCASE ORDER BY verified_depth DESC, block DESC LIMIT 1",
                new String[]{transactionId});
        try { return c.moveToFirst() ? c.getString(0) : ""; } finally { c.close(); }
    }

    public void setConfirmation(String id, int depth, long at) {
        ContentValues v = new ContentValues(); v.put("verified_depth", depth); v.put("verified_at", at);
        getWritableDatabase().update(TX, v, "txpowid=?", new String[]{id});
        getWritableDatabase().update(PUBLIC, v, "txpowid=?", new String[]{id});
    }

    /** Public address-search results are deliberately separate from the wallet accounting table. */
    public void upsertPublic(HistoryEntry entry) {
        ContentValues v = values(entry); v.remove("verified_depth"); v.remove("verified_at");
        if (getWritableDatabase().update(PUBLIC, v, "txpowid=?", new String[]{entry.txpowid}) == 0)
            getWritableDatabase().insertWithOnConflict(PUBLIC, null, values(entry), SQLiteDatabase.CONFLICT_IGNORE);
    }

    public List<HistoryEntry> publicList(int limit, int offset) {
        List<HistoryEntry> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery("SELECT txpowid,block,timemilli,direction,incoming,tokenid,tokenname,amount,deltas,counterparty,inputs,outputs,synced_at,transactionid,verified_depth,verified_at FROM "
                + PUBLIC + " ORDER BY block DESC, timemilli DESC LIMIT " + limit + " OFFSET " + offset, null);
        try { while (c.moveToNext()) out.add(read(c)); } finally { c.close(); }
        return out;
    }
    public int publicCount() {
        Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM " + PUBLIC, null);
        try { return c.moveToFirst() ? c.getInt(0) : 0; } finally { c.close(); }
    }

    public void setMeta(String k, String v) {
        ContentValues cv = new ContentValues(); cv.put("k", k); cv.put("v", v);
        getWritableDatabase().insertWithOnConflict(META, null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public String getMeta(String k, String def) {
        Cursor c = getReadableDatabase().rawQuery("SELECT v FROM " + META + " WHERE k=?", new String[]{k});
        try { return c.moveToFirst() ? c.getString(0) : def; } finally { c.close(); }
    }
}
