// VENDORED VERBATIM from apks/atomix 0.1.82 app/src/test/java/com/eurobuddha/atomix/AtomixKeyBoundaryTest.java by scripts/vendor-atomix.sh — do not edit here; re-vendor.
package com.eurobuddha.atomix;
import com.eurobuddha.atomix.eth.EthWallet;
import com.eurobuddha.comms.NodeApi;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
/** Public scalar fixtures; no node, owner keys, signing or network. */
public class AtomixKeyBoundaryTest {
 static final String ONE="0".repeat(63)+"1", TWO="0".repeat(63)+"2";
 static class Result implements EthWallet.Cb {String address,error; public void ok(String a){address=a;} public void err(String e){error=e;}}
 static JSONObject reply(Object status,Object key)throws Exception{return new JSONObject().put("status",status).put("response",new JSONObject().put("seedrandom",key));}
 static NodeApi node(JSONObject result){NodeApi n=mock(NodeApi.class);doAnswer(i->{((NodeApi.Cb)i.getArgument(1)).onResult(result);return null;}).when(n).cmd(eq("seedrandom modifier:ethbridge"),any());return n;}
 static void derive(EthWallet w,JSONObject reply,Result r){w.deriveFromNode(node(reply),null,r);}
 @Test public void validKeyFormsShareOneAddress()throws Exception{
  for(String raw:new String[]{ONE,"0x"+ONE,"0X"+ONE," 0x"+ONE+" "}){EthWallet w=new EthWallet();Result r=new Result();derive(w,reply(true,raw),r);assertNull(r.error);assertEquals("0x7e5f4552091a69125d5dfcb7b8c2659029395bdf",r.address);assertEquals(r.address,w.address());}
 }
 @Test public void malformedKeysNeverBecomeLiveOrLeakIntoErrors()throws Exception{
  String order=org.web3j.crypto.Sign.CURVE_PARAMS.getN().toString(16);
  for(Object raw:new Object[]{"", "0x01",ONE.substring(1),ONE+"0","g".repeat(64),"0".repeat(64),order,"f".repeat(64),new JSONObject().put("secret",ONE),JSONObject.NULL,123}){
   EthWallet w=new EthWallet();Result result=new Result();derive(w,reply(true,raw),result);assertNull(result.address);assertFalse(w.ready());assertEquals("Node returned an invalid Ethereum key. Not using it.",result.error);
  }
 }
 @Test public void invalidEnvelopeCannotReplaceExistingCredentials()throws Exception{
  EthWallet w=new EthWallet();derive(w,reply(true,ONE),new Result());Object kept=w.creds();
  for(JSONObject response:new JSONObject[]{null,new JSONObject(),reply(false,TWO),reply("true",TWO),reply(JSONObject.NULL,TWO),new JSONObject().put("status",true).put("response","secret "+TWO)}){Result result=new Result();derive(w,response,result);assertNotNull(result.error);assertSame(kept,w.creds());}
 }
 @Test public void probeNeverChangesWalletAndUsesSameValidation()throws Exception{
  EthWallet w=new EthWallet();derive(w,reply(true,ONE),new Result());Object kept=w.creds();Result r=new Result();w.probeNodeAddress(node(reply(true,TWO)),null,r);assertNull(r.error);assertNotEquals(w.address(),r.address);assertSame(kept,w.creds());
  for(JSONObject bad:new JSONObject[]{reply(true,"0x02"),reply(false,TWO),reply(true,"f".repeat(64))}){r=new Result();w.probeNodeAddress(node(bad),null,r);assertNull(r.address);assertEquals("probe failed",r.error);assertSame(kept,w.creds());}
 }
 @Test public void transportDiagnosticsCannotExposeKeyMaterial()throws Exception{
  NodeApi n=mock(NodeApi.class);doAnswer(i->{((NodeApi.Cb)i.getArgument(1)).onError("seedrandom secret="+ONE);return null;}).when(n).cmd(anyString(),any());EthWallet w=new EthWallet();Result r=new Result();w.deriveFromNode(n,null,r);assertEquals("Ethereum key derivation failed. Retry when the node is ready.",r.error);r=new Result();w.probeNodeAddress(n,null,r);assertEquals("probe failed",r.error);assertFalse(w.ready());
 }
}
