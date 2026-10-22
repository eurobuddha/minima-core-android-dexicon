// VENDORED VERBATIM from apks/atomix 0.1.83 app/src/test/java/com/eurobuddha/atomix/swap/MismatchPoisonTest.java by scripts/vendor-atomix.sh — do not edit here; re-vendor.
package com.eurobuddha.atomix.swap;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.os.Handler;

import com.eurobuddha.comms.NodeApi;
import com.eurobuddha.atomix.eth.EthNet;
import com.eurobuddha.atomix.eth.EthRpc;
import com.eurobuddha.atomix.eth.EthWallet;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;

/**
 * STEP: the EV_COLLECT mismatch-POISON fix (backport from atomix-mds 0.1.3) + the taker-claim token gate.
 *
 * The old code logged a counterparty amount/token mismatch as EV_COLLECT — and the claim gates on
 * haveCollect — so ANY third party could lock one hostile dust coin carrying a victim's ACTIVE hash +
 * receiver key (both public on-chain) with a wrong amount/token, and the victim's REAL claim was blocked
 * FOREVER (guaranteed mutual-refund grief for pennies). A mismatch must now:
 *   • log its OWN once-guarded event (EV_MISMATCH) and never EV_COLLECT,
 *   • never stop a later well-formed coin for the same hash from claiming.
 * Plus the new claim token gate: a coin of the WRONG token (worthless coloured token of the right amount —
 * amountTokenOk's currency-agnostic 'minima' literal can't catch it) must be declined before the secret is
 * revealed.
 */
public class MismatchPoisonTest {

    private static final String HASH = "0x" + "22".repeat(32);
    private static final String MY_MPK = "0xAAAA";

    private SwapEngine engine;
    private SwapDb db;
    private MinimaHtlc minima;

    @Before public void setUp() {
        EthWallet wallet = mock(EthWallet.class);
        when(wallet.address()).thenReturn("0x1111111111111111111111111111111111111111");
        minima = mock(MinimaHtlc.class);
        db = mock(SwapDb.class);
        engine = new SwapEngine(mock(NodeApi.class), minima, db,
                wallet, mock(Handler.class), mock(SwapEngine.Notifier.class));
        engine.setNetwork(new EthRpc(EthNet.MAINNET.defaultRpc), EthNet.MAINNET);
        engine.setMyMinimaPk(MY_MPK);
        // The victim's swap: buying mxUSDT, secret known (I'm the claimer), request 4.95 minima-leg.
        when(db.getSecret(HASH)).thenReturn("0x" + "33".repeat(32));
        SwapDb.Swap sw = new SwapDb.Swap();
        sw.hash = HASH; sw.buyToken = "mxUSDT"; sw.status = SwapDb.ST_LOCKED;
        when(db.getSwap(HASH)).thenReturn(sw);
        when(db.getRequest(HASH)).thenReturn(new String[]{"4.95", "minima"});
    }

    private static JSONObject coin(String tokenid, String amount) throws Exception {
        return new JSONObject()
                .put("tokenid", tokenid)
                .put("tokenamount", amount)
                .put("state", new JSONObject()
                        .put("0", "0xMAKER").put("2", "[minima]").put("3", "999999")
                        .put("4", MY_MPK).put("5", HASH));
    }

    // ---- expectedTokenId: label → tokenid mapping for the claim token gate ----

    private static SwapDb.Swap swapBuying(String label) {
        SwapDb.Swap s = new SwapDb.Swap(); s.buyToken = label; return s;
    }

    @Test public void expectedTokenIdMapsBothCurrencyLabels() {
        assertEquals(MinimaHtlc.USDT_TOKENID, SwapEngine.expectedTokenId(swapBuying("mxUSDT")));
        assertEquals(MinimaHtlc.MINIMA_TOKENID, SwapEngine.expectedTokenId(swapBuying("MINIMA")));
        // unknown label / missing row → the active currency (whatever another test may have set it to)
        String activeTok = com.eurobuddha.atomix.TradingContext.active().tokenId;
        assertEquals(activeTok, SwapEngine.expectedTokenId(swapBuying("USDT")));
        assertEquals(activeTok, SwapEngine.expectedTokenId(null));
    }

    // ---- the poison paths: mismatches log EV_MISMATCH, never EV_COLLECT, and never claim ----

    @Test public void wrongTokenCoinIsDeclinedAsMismatchNeverCollect() throws Exception {
        JSONObject worthless = coin("0x00000000000000000000000000000000000000000000000000000000000000DD", "4.95");
        engine.checkCanSwapCoin(worthless, 100);
        verify(db).logEvent(eq(HASH), eq(SwapDb.EV_MISMATCH), anyString(), anyString(), anyString());
        verify(db, never()).logEvent(eq(HASH), eq(SwapDb.EV_COLLECT), anyString(), anyString(), anyString());
        verify(db, never()).setSwapStatus(anyString(), eq(SwapDb.ST_CLAIMING));
    }

    @Test public void undersizedCoinIsDeclinedAsMismatchAndLoggedOnce() throws Exception {
        JSONObject dust = coin(MinimaHtlc.USDT_TOKENID, "0.1");   // right token, locked less than the 4.95 asked
        engine.checkCanSwapCoin(dust, 100);
        verify(db).logEvent(eq(HASH), eq(SwapDb.EV_MISMATCH), anyString(), anyString(), anyString());
        // once-guard: after the first log the event exists → no per-poll spam
        when(db.haveMismatch(HASH)).thenReturn(true);
        engine.checkCanSwapCoin(dust, 100);
        verify(db, times(1)).logEvent(eq(HASH), eq(SwapDb.EV_MISMATCH), anyString(), anyString(), anyString());
        verify(db, never()).logEvent(eq(HASH), eq(SwapDb.EV_COLLECT), anyString(), anyString(), anyString());
    }

    @Test public void poisonDoesNotBlockTheRealClaim() throws Exception {
        // The hash is already "poisoned" (a hostile mismatch coin was seen earlier)…
        when(db.haveMismatch(HASH)).thenReturn(true);
        when(db.haveCollect(HASH)).thenReturn(false);
        // …but the REAL well-formed coin must still engage the claim. (The claim path logs via android.util.Log,
        // which the JVM android stub throws on — reaching ST_CLAIMING proves it passed every gate before that.)
        JSONObject real = coin(MinimaHtlc.USDT_TOKENID, "4.95");
        try { engine.checkCanSwapCoin(real, 100); } catch (RuntimeException androidLogStub) { /* expected in JVM tests */ }
        verify(db).setSwapStatus(HASH, SwapDb.ST_CLAIMING);
        verify(db, never()).logEvent(eq(HASH), eq(SwapDb.EV_MISMATCH), anyString(), anyString(), anyString());
    }
}
