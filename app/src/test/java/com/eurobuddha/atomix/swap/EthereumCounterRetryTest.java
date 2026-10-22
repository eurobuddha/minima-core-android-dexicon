// VENDORED VERBATIM from apks/atomix 0.1.83 app/src/test/java/com/eurobuddha/atomix/swap/EthereumCounterRetryTest.java by scripts/vendor-atomix.sh — do not edit here; re-vendor.
package com.eurobuddha.atomix.swap;
import android.os.Handler;
import com.eurobuddha.atomix.TradingContext;
import com.eurobuddha.atomix.eth.*;
import com.eurobuddha.comms.NodeApi;
import org.json.JSONObject;
import org.junit.*;
import org.mockito.MockedConstruction;
import org.web3j.crypto.Credentials;
import java.math.BigInteger;
import java.lang.reflect.Method;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Real responder method, mocked transport and public synthetic identity only. */
public class EthereumCounterRetryTest {
    static final String HASH="0x"+"44".repeat(32),ADDRESS="0x"+"12".repeat(20);
    SwapEngine engine;SwapDb db;EthRpc rpc;EthWallet wallet;long now;
    @Before public void setup()throws Exception {
        now=System.currentTimeMillis()/1000;db=mock(SwapDb.class);rpc=mock(EthRpc.class);wallet=mock(EthWallet.class);
        when(wallet.creds()).thenReturn(Credentials.create("01"));when(wallet.ethBalanceWei(rpc)).thenReturn(BigInteger.TEN.pow(18));
        when(rpc.latestBlockTimestamp()).thenReturn(now);when(rpc.callStr(eq("eth_gasPrice"),any())).thenReturn("0x3b9aca00");when(rpc.baseFeePerGasOrZero()).thenReturn(BigInteger.ZERO);
        engine=new SwapEngine(mock(NodeApi.class),mock(MinimaHtlc.class),db,wallet,mock(Handler.class),mock(SwapEngine.Notifier.class));engine.setNetwork(rpc,EthNet.MAINNET);engine.setMyMinimaPk("0xaaaa");
    }
    @After public void cleanup(){engine.shutdown();}
    void attempt()throws Exception {
        JSONObject coin=new JSONObject().put("amount","2").put("state",new JSONObject().put("1","3").put("6",ADDRESS));
        Method m=SwapEngine.class.getDeclaredMethod("lockEthCounterLeg",JSONObject.class,String.class,String.class,TradingContext.class,java.util.function.BooleanSupplier.class);m.setAccessible(true);
        m.invoke(engine,coin,HASH,EthNet.MAINNET.tokens[0].address,TradingContext.MINIMA,(java.util.function.BooleanSupplier)()->true);
    }
    @Test public void retryKeepsPersistedTimelockBeforeSendingAgain()throws Exception {
        SwapDb.Swap saved=new SwapDb.Swap();saved.myTimelock=now+600;saved.myLegIsMinima=false;when(db.getSwap(HASH)).thenReturn(saved);
        try(MockedConstruction<EthHtlc> h=mockConstruction(EthHtlc.class,(eth,context)->when(eth.allowance(anyString())).thenReturn(BigInteger.TEN.pow(18)))){
            attempt();EthHtlc eth=h.constructed().get(0);
            verify(eth).newContract(eq("0xaaaa"),eq(ADDRESS),eq(HASH),eq(BigInteger.valueOf(saved.myTimelock)),eq(EthNet.MAINNET.tokens[0].address),any(),any(),eq(false));
            verify(db).upsertSwap(argThat(row->row.myTimelock==saved.myTimelock));
        }
    }
    @Test public void expiredSavedCounterLegCannotKeepSpendingGasOnReverts()throws Exception {
        SwapDb.Swap saved=new SwapDb.Swap();saved.myTimelock=now-1;saved.myLegIsMinima=false;when(db.getSwap(HASH)).thenReturn(saved);
        try(MockedConstruction<EthHtlc> h=mockConstruction(EthHtlc.class,(eth,context)->when(eth.allowance(anyString())).thenReturn(BigInteger.TEN.pow(18)))){
            attempt();verify(h.constructed().get(0),never()).newContract(any(),any(),any(),any(),any(),any(),any(),anyBoolean());verify(db,never()).upsertSwap(any());
        }
    }
}
