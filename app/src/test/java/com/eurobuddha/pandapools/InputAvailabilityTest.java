// VENDORED VERBATIM from apks/pandapools 0.9.97 app/src/test/java/com/eurobuddha/pandapools/InputAvailabilityTest.java by scripts/vendor-pandapools.sh — do not edit here; re-vendor.
package com.eurobuddha.pandapools;
import org.json.*;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;
public class InputAvailabilityTest {
    private boolean check(JSONObject reply) {
        boolean[] result={false};CoinLock.checkAvailable((command,cb)->{assertEquals("coins checkmempool:true coinid:0xabc",command);cb.onResult(reply);},Arrays.asList("0xabc"),0,ok->result[0]=ok);return result[0];
    }
    @Test public void rejectsMissingPendingSpentAndMalformedReplies() throws Exception {
        assertFalse(check(null));assertFalse(check(new JSONObject().put("status",true).put("response",new JSONArray())));
        JSONObject coin=new JSONObject().put("coinid","0xabc").put("spent",false);
        JSONObject reply=new JSONObject().put("status",true).put("response",new JSONArray().put(coin));assertTrue(check(reply));
        coin.put("spent",true);assertFalse(check(reply));coin.put("spent",false).put("coinid","0xdef");assertFalse(check(reply));
        coin.put("coinid","0xabc");reply.put("pending",true);assertFalse(check(reply));
    }
}
