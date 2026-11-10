package com.eurobuddha.minimaapi;

import static org.junit.Assert.*;

import org.json.JSONObject;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

/** 10 Nov 2026, on the Fold: with BlackBear AND the Play Store Minima Core running, both answered REGISTER and every
 *  companion refused every command ("Multiple Minima Core builds are running") — the ETH Wallet showed "Node returned
 *  an invalid Ethereum key". The library now picks: the only node; the user's preferred node; the ONE node that has this
 *  app enabled. Only a real tie stays ambiguous. */
public class NodeChooserTest {
    private static final String BLACKBEAR = "com.eurobuddha.minimablock", OFFICIAL = "org.minima.core";

    private static Map<String, JSONObject> replies(boolean blackbearEnabled, boolean officialEnabled) throws Exception {
        Map<String, JSONObject> m = new LinkedHashMap<>();
        m.put(BLACKBEAR, new JSONObject().put("status", true).put("enabled", blackbearEnabled));
        m.put(OFFICIAL, new JSONObject().put("status", true).put("enabled", officialEnabled));
        return m;
    }

    @Test public void theOneNodeThatHasThisAppEnabledIsChosen() throws Exception {
        assertEquals(BLACKBEAR, MinimaAPI.choose(replies(true, false), null));
        assertEquals(OFFICIAL, MinimaAPI.choose(replies(false, true), null));
    }

    @Test public void thePreferredNodeWinsWhenItAnswered() throws Exception {
        assertEquals(OFFICIAL, MinimaAPI.choose(replies(true, false), OFFICIAL));
        assertEquals("a preferred node that did not answer is ignored", BLACKBEAR,
                MinimaAPI.choose(replies(true, false), "com.eurobuddha.pandamonium"));
    }

    @Test public void aRealTieStaysAmbiguous() throws Exception {
        assertNull(MinimaAPI.choose(replies(true, true), null));
        assertNull(MinimaAPI.choose(replies(false, false), null));
    }

    @Test public void aSingleNodeIsAlwaysChosen() throws Exception {
        Map<String, JSONObject> one = new LinkedHashMap<>();
        one.put(BLACKBEAR, new JSONObject().put("status", true).put("enabled", false));
        assertEquals(BLACKBEAR, MinimaAPI.choose(one, null));
    }
}
