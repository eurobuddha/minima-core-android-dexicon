package com.eurobuddha.dexicon.resting;

import static org.junit.Assert.*;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** Review 7 Nov: a transaction can carry a state port twice; the covenant binds the FIRST entry, so the order parser
 *  must keep the first (proven on a -test chain that KISS reads the first while simplestate shows the last). */
public class DuplicatePortOrderTest {
    @Test public void aDuplicatedPortReadsAsItsFirstEntry() throws Exception {
        String[][] v = {{"0","0x0A0A"},{"1","0xBB11223344556677889900AABBCCDDEEFF00112233445566778899AABBCCDDEE"},{"2","50"},
                {"3","0x00"},{"4","0xAB12"},{"5","0"},{"6","0.5"},{"7","1"},{"8","1"},{"2","999"}};
        JSONArray st = new JSONArray();
        for (String[] e : v) st.put(new JSONObject().put("port", Integer.parseInt(e[0])).put("data", e[1]));
        JSONObject c = new JSONObject().put("coinid", "0x01").put("tokenid", DexContract.MXUSD_ID).put("tokenamount", "25")
                .put("created", 10).put("state", st);
        Order5 o = Order5.from(c);
        assertNotNull(o);
        assertEquals(0, o.wantAmt.compareTo(new java.math.BigDecimal("50")));
    }
}
