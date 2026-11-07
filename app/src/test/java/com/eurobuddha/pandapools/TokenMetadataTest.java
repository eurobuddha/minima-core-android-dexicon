// VENDORED VERBATIM from apks/pandapools 0.9.87 app/src/test/java/com/eurobuddha/pandapools/TokenMetadataTest.java by scripts/vendor-pandapools.sh — do not edit here; re-vendor.
package com.eurobuddha.pandapools;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;
public class TokenMetadataTest {
    private JSONObject metadata() throws Exception {
        return new JSONObject().put("name", "USDT").put("ticker", "USDT")
                .put("url", "https://mxusd.global/svg/USDT.svg").put("owner", "MINIMAXIA.GLOBAL.SA");
    }
    @Test public void balanceSerializedMetadataIsNotDisplayedAsJson() throws Exception {
        JSONObject row = new JSONObject().put("tokenid", "0x1234").put("token", metadata().toString());
        TokenBalance b = TokenBalance.from(row);
        assertEquals("USDT", b.name);
        assertEquals("USDT", b.meta.ticker);
        assertEquals("MINIMAXIA.GLOBAL.SA", b.meta.owner);
        assertEquals("https://mxusd.global/svg/USDT.svg", b.meta.iconUrl);
        assertEquals("USDT", Util.tokenName(row.get("token"), "0x1234"));
    }
    @Test public void rawCoinSerializedNameIsDecoded() throws Exception {
        JSONObject coinToken = new JSONObject().put("name", metadata().toString()).put("decimals", 8);
        assertEquals("USDT", TokenMeta.parse(coinToken, "0xab").name);
        assertEquals("8", TokenMeta.parse(coinToken, "0xab").decimals);
        assertEquals("USDT", Util.tokenName(coinToken, "0xab"));
        assertEquals("USDT", Util.tokenNameCached("0xab"));
    }
    @Test public void serializedPrecisionRetainsTokenGrain() throws Exception {
        assertEquals(0, Util.tokenDecimals(new JSONObject().put("decimals", 0).toString()));
        assertEquals(6, Util.tokenDecimals(new JSONObject().put("token", new JSONObject().put("decimals", 6).toString())));
        assertEquals(8, Util.tokenDecimals("{broken"));
    }
    @Test public void objectAndPlainNamesStillWork() throws Exception {
        assertEquals("USDT", Util.tokenName(metadata(), "0xac"));
        assertEquals("USDT", Util.tokenName(new JSONObject().put("name", metadata()), "0xad"));
        assertEquals("Plain", Util.tokenName("Plain", "0xae"));
        assertEquals("Minima", Util.tokenName(metadata().toString(), "0x00"));
        assertEquals("Token", Util.tokenName(null, "0xaf"));
        assertEquals("{broken", Util.tokenName("{broken", "0xb0"));
    }
}
