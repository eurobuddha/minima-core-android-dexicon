// VENDORED VERBATIM from apks/pandapools 0.9.80 app/src/test/java/com/eurobuddha/pandapools/PoolBookStateTest.java by scripts/vendor-pandapools.sh — do not edit here; re-vendor.
package com.eurobuddha.pandapools;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

/**
 * {@link PoolBook#state} reads a beacon coin's announce params out of the coin state. The node returns state in
 * TWO shapes depending on the command — a map ({@code simplestate:true}) or an array of {port,data} entries —
 * and discovery breaks (pools invisible / mis-parameterised) if either path drifts. This is the discovery
 * parsing the reviewers flagged as untested.
 */
public class PoolBookStateTest {

    @Test public void mapFormStateReadsPorts() throws JSONException {
        JSONObject state = new JSONObject()
                .put("2", "0xTOK").put("3", "0xOADR").put("4", "0xOPK").put("5", "716.2");
        JSONObject coin = new JSONObject().put("state", state);
        assertEquals("0xTOK", PoolBook.state(coin, 2));
        assertEquals("0xOADR", PoolBook.state(coin, 3));
        assertEquals("0xOPK", PoolBook.state(coin, 4));
        assertEquals("716.2", PoolBook.state(coin, 5));
        assertNull("missing port -> null", PoolBook.state(coin, 9));
    }

    @Test public void arrayFormStateReadsPorts() throws JSONException {
        JSONArray state = new JSONArray()
                .put(new JSONObject().put("port", 2).put("data", "0xTOK"))
                .put(new JSONObject().put("port", 3).put("data", "0xOADR"))
                .put(new JSONObject().put("port", 5).put("data", "716.2"));
        JSONObject coin = new JSONObject().put("state", state);
        assertEquals("0xTOK", PoolBook.state(coin, 2));
        assertEquals("0xOADR", PoolBook.state(coin, 3));
        assertEquals("716.2", PoolBook.state(coin, 5));
        assertNull("port not present -> null", PoolBook.state(coin, 4));
    }

    @Test public void nullAndEmptyAreNull() throws JSONException {
        assertNull(PoolBook.state(null, 2));
        assertNull("no state key -> null", PoolBook.state(new JSONObject().put("coinid", "0xAB"), 2));
        // empty string value is treated as absent
        JSONObject coin = new JSONObject().put("state", new JSONObject().put("2", ""));
        assertNull(PoolBook.state(coin, 2));
    }
}
