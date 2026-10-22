package com.eurobuddha.dexicon;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import com.eurobuddha.dexicon.maker.MyOrder;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Local-first storage (PandaDEX DexDb pattern, small): my orders, my fills, pending takes, key/value.
 *   myorder — my resting orders (JSON per row; maker.js 'myorders')
 *   fill    — my fills, maker or taker side; status settling | done | lost (service.js 'fill_<txpowid>')
 *   pending — takes I sent and await a reply for (app.js 'pending_takes')
 *   meta    — key/value
 *   activity — ACTIVITY tab receipts, one per trade (see ActivityLog); added in v2
 */
public final class DexDb extends SQLiteOpenHelper {
    private static final String DB = "dexicon.db";
    private static final int V = 2;

    public DexDb(Context ctx) {
        super(ctx.getApplicationContext(), DB, null, V);
        setOpenParams(new SQLiteDatabase.OpenParams.Builder()
                .addOpenFlags(SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING).setSynchronousMode(SQLiteDatabase.SYNC_MODE_FULL).build());
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE myorder (orderid TEXT PRIMARY KEY, json TEXT, created INTEGER)");
        db.execSQL("CREATE TABLE fill (txpowid TEXT PRIMARY KEY, json TEXT, ts INTEGER)");
        db.execSQL("CREATE TABLE pending (orderid TEXT PRIMARY KEY, json TEXT, ts INTEGER)");
        db.execSQL("CREATE TABLE meta (k TEXT PRIMARY KEY, v TEXT)");
        onUpgrade(db, 1, V);
    }
    /** Additive migrations only; sideloading an older build must not brick the app. */
    @Override public void onUpgrade(SQLiteDatabase db, int o, int n) {
        db.execSQL("CREATE TABLE IF NOT EXISTS activity (id TEXT PRIMARY KEY, json TEXT, ts INTEGER)");
    }
    @Override public void onDowngrade(SQLiteDatabase db, int o, int n) { }

    // ---- my orders ----
    public synchronized List<MyOrder> myOrders() {
        List<MyOrder> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT json FROM myorder ORDER BY created ASC", null)) {
            while (c.moveToNext()) try { out.add(MyOrder.fromJson(new JSONObject(c.getString(0)))); } catch (Exception ignored) {}
        }
        return out;
    }
    public synchronized MyOrder myOrder(String orderId) {
        for (MyOrder o : myOrders()) if (o.orderId.equalsIgnoreCase(orderId)) return o;
        return null;
    }
    public synchronized void saveOrder(MyOrder o) {
        ContentValues v = new ContentValues();
        v.put("orderid", o.orderId); v.put("json", o.toJson().toString()); v.put("created", o.created);
        getWritableDatabase().insertWithOnConflict("myorder", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    // ---- fills ----
    /** Insert or replace; returns true when the txpowid was NEW (notification de-dup). */
    public synchronized boolean saveFill(JSONObject f) {
        String id = f.optString("txpowid", "");
        if (id.isEmpty()) return false;
        boolean isNew;
        try (Cursor c = getReadableDatabase().rawQuery("SELECT 1 FROM fill WHERE txpowid=?", new String[]{id})) { isNew = !c.moveToFirst(); }
        ContentValues v = new ContentValues();
        v.put("txpowid", id); v.put("json", f.toString()); v.put("ts", f.optLong("ts", System.currentTimeMillis() / 1000));
        getWritableDatabase().insertWithOnConflict("fill", null, v, SQLiteDatabase.CONFLICT_REPLACE);
        return isNew;
    }
    public synchronized List<JSONObject> fills(int limit) {
        List<JSONObject> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT json FROM fill ORDER BY ts DESC LIMIT " + limit, null)) {
            while (c.moveToNext()) try { out.add(new JSONObject(c.getString(0))); } catch (Exception ignored) {}
        }
        return out;
    }

    // ---- pending takes ----
    public synchronized void savePending(String orderId, JSONObject p) {
        ContentValues v = new ContentValues();
        v.put("orderid", orderId); v.put("json", p.toString()); v.put("ts", System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict("pending", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }
    public synchronized JSONObject pending(String orderId) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT json FROM pending WHERE orderid=?", new String[]{orderId})) {
            if (c.moveToFirst()) return new JSONObject(c.getString(0));
        } catch (Exception ignored) {}
        return null;
    }
    public synchronized List<JSONObject> pendings() {
        List<JSONObject> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT json FROM pending ORDER BY ts DESC", null)) {
            while (c.moveToNext()) try { out.add(new JSONObject(c.getString(0))); } catch (Exception ignored) {}
        }
        return out;
    }
    public synchronized void removePending(String orderId) { getWritableDatabase().delete("pending", "orderid=?", new String[]{orderId}); }

    // ---- meta ----
    public synchronized String meta(String k, String def) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT v FROM meta WHERE k=?", new String[]{k})) { return c.moveToFirst() ? c.getString(0) : def; }
    }
    public synchronized void setMeta(String k, String v) {
        ContentValues cv = new ContentValues(); cv.put("k", k); cv.put("v", v);
        getWritableDatabase().insertWithOnConflict("meta", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    // ---- activity (ActivityLog owns the read-modify-write) ----
    synchronized void saveActivity(JSONObject a) {
        ContentValues v = new ContentValues();
        v.put("id", a.optString("id")); v.put("json", a.toString()); v.put("ts", a.optLong("ts", System.currentTimeMillis()));
        getWritableDatabase().insertWithOnConflict("activity", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }
    synchronized JSONObject activity(String id) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT json FROM activity WHERE id=?", new String[]{id})) {
            if (c.moveToFirst()) return new JSONObject(c.getString(0));
        } catch (Exception ignored) {}
        return null;
    }
    synchronized List<JSONObject> activities(int limit) {
        List<JSONObject> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT json FROM activity ORDER BY ts DESC LIMIT " + limit, null)) {
            while (c.moveToNext()) try { out.add(new JSONObject(c.getString(0))); } catch (Exception ignored) {}
        }
        return out;
    }
    synchronized void trimActivity(int keep) {
        getWritableDatabase().execSQL("DELETE FROM activity WHERE id NOT IN (SELECT id FROM activity ORDER BY ts DESC LIMIT " + keep + ")");
    }
}
