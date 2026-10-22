package com.eurobuddha.dexicon.resting;
/* Ported from apks/pandadex app/src/test/java/com/eurobuddha/pandadex/PoolBookTest.java (PandaDEX 0.4.23). */


import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public class PoolBookTest {

    private static final String OPK = "0xAA";
    private static final String OADR = "0xBB11223344556677889900AABBCCDDEEFF00112233445566778899AABBCCDDEE";
    private static final String POOL = "0xCC11223344556677889900AABBCCDDEEFF00112233445566778899AABBCCDDEE";

    private static JSONObject beacon(String tok) {
        return beacon(tok, OADR);
    }

    private static JSONObject beacon(String tok, String oadr) {
        try {
            JSONObject c = new JSONObject();
            JSONObject st = new JSONObject();
            st.put("2", tok);
            st.put("3", oadr);
            st.put("4", OPK);
            st.put("5", "1");
            c.put("state", st);
            return c;
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private static JSONObject coin(String coinid, String tokenid, String amount) {
        try {
            JSONObject c = new JSONObject();
            c.put("coinid", coinid);
            c.put("address", POOL);
            c.put("tokenid", tokenid);
            c.put("amount", amount);
            c.put("tokenamount", amount);
            c.put("created", 123);
            return c;
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private static JSONObject reply(JSONArray a) {
        try { return new JSONObject().put("status", true).put("response", a); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    @Test public void scanUsesBoundedSentinelAndBoundedReserveQueryOnly() {
        List<String> cmds = new ArrayList<>();
        PoolBook book = new PoolBook((command, cb) -> {
            cmds.add(command);
            if (command.contains("address:" + PoolCovenant.SENTINEL)) {
                cb.onResult(reply(new JSONArray().put(beacon(DexContract.MXUSD_ID))));
            } else if (command.startsWith("runscript ")) {
                try {
                    cb.onResult(new JSONObject().put("status", true)
                            .put("response", new JSONObject().put("parseok", true)
                                    .put("script", new JSONObject().put("address", POOL).put("mxaddress", "MxPOOL"))));
                } catch (Exception e) { throw new RuntimeException(e); }
            } else if (command.contains("address:" + POOL)) {
                cb.onResult(reply(new JSONArray()
                        .put(coin("0xDD01", Util.MINIMA_TOKENID, "1"))
                        .put(coin("0xAA01", Util.MINIMA_TOKENID, "5"))
                        .put(coin("0xDD02", DexContract.MXUSD_ID, "0.01"))
                        .put(coin("0xBB01", DexContract.MXUSD_ID, "0.2"))));
            } else {
                throw new AssertionError(command);
            }
        });

        AtomicReference<List<Pool>> seen = new AtomicReference<>();
        book.scan(new PoolBook.Listener() {
            @Override public void onPools(List<Pool> pools) { seen.set(pools); }
            @Override public void onError(String msg) { throw new AssertionError(msg); }
        });

        assertTrue(cmds.get(0).contains("depth:" + PoolCovenant.SENTINEL_SCAN_DEPTH));
        assertTrue(cmds.get(0).contains("address:" + PoolCovenant.SENTINEL));
        for (String c : cmds) assertFalse(c.startsWith("scripts"));
        assertTrue(cmds.get(2).startsWith("coins simplestate:true depth:" + PoolCovenant.SENTINEL_SCAN_DEPTH));
        assertEquals(1, seen.get().size());
        Pool p = seen.get().get(0);
        assertEquals(POOL, p.address);
        assertEquals("0xAA01", p.coinidM);
        assertEquals("0xBB01", p.coinidT);
        assertEquals(0, p.reserveM.compareTo(new java.math.BigDecimal("5")));
        assertEquals(0, p.reserveT.compareTo(new java.math.BigDecimal("0.2")));
    }

    @Test public void nonParsingCovenantMakesTheSnapshotUnavailable() {
        PoolBook book = new PoolBook((command, cb) -> {
            if (command.contains("address:" + PoolCovenant.SENTINEL)) {
                cb.onResult(reply(new JSONArray().put(beacon(DexContract.MXUSD_ID))));
            } else if (command.startsWith("runscript ")) {
                try { cb.onResult(new JSONObject().put("status", true)
                        .put("response", new JSONObject().put("parseok", false))); }
                catch (Exception e) { throw new RuntimeException(e); }
            } else {
                throw new AssertionError(command);
            }
        });

        AtomicReference<List<Pool>> seen = new AtomicReference<>();
        AtomicReference<String> error = new AtomicReference<>();
        book.scan(new PoolBook.Listener() {
            @Override public void onPools(List<Pool> pools) { seen.set(pools); }
            @Override public void onError(String msg) { error.set(msg); }
        });
        assertEquals(null, seen.get());
        assertTrue(error.get().contains("parse"));
    }

    @Test public void distinctOwnerPayoutAddressesRemainDistinctPools() {
        String oadr2 = "0xDD11223344556677889900AABBCCDDEEFF00112233445566778899AABBCCDDEE";
        List<String> runScripts = new ArrayList<>();
        PoolBook book = new PoolBook((command, cb) -> {
            if (command.contains("address:" + PoolCovenant.SENTINEL)) {
                cb.onResult(reply(new JSONArray()
                        .put(beacon(DexContract.MXUSD_ID, OADR))
                        .put(beacon(DexContract.MXUSD_ID, oadr2))));
            } else if (command.startsWith("runscript ")) {
                runScripts.add(command);
                String address = runScripts.size() == 1 ? POOL : oadr2;
                try {
                    cb.onResult(new JSONObject().put("status", true)
                            .put("response", new JSONObject().put("parseok", true)
                                    .put("script", new JSONObject().put("address", address))));
                } catch (Exception e) { throw new RuntimeException(e); }
            } else if (command.contains("address:" + POOL) || command.contains("address:" + oadr2)) {
                JSONObject m = coin("0xAA010" + runScripts.size(), Util.MINIMA_TOKENID, "5");
                JSONObject t = coin("0xBB010" + runScripts.size(), DexContract.MXUSD_ID, "0.2");
                try { m.put("address", command.contains("address:" + POOL) ? POOL : oadr2);
                      t.put("address", command.contains("address:" + POOL) ? POOL : oadr2); }
                catch (Exception invalid) { throw new AssertionError(invalid); }
                cb.onResult(reply(new JSONArray().put(m).put(t)));
            } else {
                throw new AssertionError(command);
            }
        });

        AtomicReference<List<Pool>> seen = new AtomicReference<>();
        book.scan(new PoolBook.Listener() {
            @Override public void onPools(List<Pool> pools) { seen.set(pools); }
            @Override public void onError(String msg) { throw new AssertionError(msg); }
        });

        assertEquals(2, runScripts.size());
        assertEquals(2, seen.get().size());
    }
}
