// VENDORED VERBATIM from apks/pandapools 0.9.80 app/src/test/java/com/eurobuddha/pandapools/ReceiptRecoveryTest.java by scripts/vendor-pandapools.sh — do not edit here; re-vendor.
package com.eurobuddha.pandapools;

import org.json.JSONObject;
import org.junit.Test;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public class ReceiptRecoveryTest {
    private JSONObject fixture() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/receipt-consolidation-header.json")) {
            assertNotNull(in);
            return new JSONObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }
    @Test public void recoversActualLegacyConsolidationAfterVerifyingMinedHash() throws Exception {
        assertEquals("0x8C6B7401E1343318C7EC80563408C9978C40B7CA59EF76CE443EB0D2B68D27EB",
                ReceiptRecovery.submittedId(fixture()));
    }
    @Test public void alteredHeaderCannotMatch() throws Exception {
        JSONObject tx = fixture();
        tx.getJSONObject("header").put("block", "2304537");
        assertEquals("", ReceiptRecovery.submittedId(tx));
    }
    @Test public void wrongMinedIdCannotMatch() throws Exception {
        JSONObject tx = fixture();
        tx.put("txpowid", "0x" + "1".repeat(64));
        assertEquals("", ReceiptRecovery.submittedId(tx));
    }
    @Test public void malformedParentsCannotMatch() throws Exception {
        JSONObject tx = fixture();
        tx.getJSONObject("header").getJSONArray("superparents").getJSONObject(0).put("count", 33);
        assertEquals("", ReceiptRecovery.submittedId(tx));
        assertEquals("", ReceiptRecovery.submittedId(new JSONObject()));
    }
}
