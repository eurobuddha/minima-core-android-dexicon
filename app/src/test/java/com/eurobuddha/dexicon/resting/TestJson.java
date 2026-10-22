package com.eurobuddha.dexicon.resting;
/* Ported from apks/pandadex app/src/test/java/com/eurobuddha/pandadex/TestJson.java (PandaDEX 0.4.23). */

import org.json.JSONObject;
/** Fixture builder: Android compiles JSONObject with checked JSONException. */
final class TestJson extends JSONObject {
    @Override public TestJson put(String key, Object value) {
        try { super.put(key, value); return this; } catch (Exception e) { throw new AssertionError(e); }
    }
    @Override public TestJson put(String key, boolean value) { return put(key, (Object)value); }
    @Override public TestJson put(String key, int value) { return put(key, (Object)value); }
    @Override public TestJson put(String key, long value) { return put(key, (Object)value); }
    @Override public TestJson put(String key, double value) { return put(key, (Object)value); }
}
