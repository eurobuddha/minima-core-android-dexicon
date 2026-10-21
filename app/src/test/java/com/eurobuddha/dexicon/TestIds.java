package com.eurobuddha.dexicon;

import com.eurobuddha.dexicon.identity.DexIdentity;
import com.goterl.lazysodium.LazySodium;
import com.goterl.lazysodium.LazySodiumJava;
import com.goterl.lazysodium.SodiumJava;

/** Test fixtures: the reference identities of mds/dexicon/test (seed = one byte repeated 32×, context "decks"). */
final class TestIds {
    static final LazySodium LS = new LazySodiumJava(new SodiumJava());
    static final long NOW = 1800000000L - 3600;

    static String rep(String b) { StringBuilder s = new StringBuilder("0x"); for (int i = 0; i < 32; i++) s.append(b); return s.toString(); }
    static DexIdentity id(String b) { return DexIdentity.fromSeed(LS, rep(b)); }
    /** android.jar's org.json declares put() as throwing a checked JSONException; keep the fixtures readable. */
    static org.json.JSONObject obj(Object... kv) {
        try { org.json.JSONObject o = new org.json.JSONObject(); for (int i = 0; i < kv.length; i += 2) o.put((String) kv[i], kv[i + 1]); return o; }
        catch (org.json.JSONException e) { throw new RuntimeException(e); }
    }
    static org.json.JSONArray arr(Object... xs) { org.json.JSONArray a = new org.json.JSONArray(); for (Object x : xs) a.put(x); return a; }
    static String hex(String b, int bytes) { StringBuilder s = new StringBuilder("0x"); for (int i = 0; i < bytes; i++) s.append(b); return s.toString(); }
}
