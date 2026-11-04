// VENDORED VERBATIM from apks/atomix 0.1.91 app/src/test/java/com/eurobuddha/atomix/MarketDraftTest.java by scripts/vendor-atomix.sh — do not edit here; re-vendor.
package com.eurobuddha.atomix;
import org.junit.Test;
import static org.junit.Assert.*;

public class MarketDraftTest {
 private static final String HUGE="9".repeat(340), TINY="0."+"0".repeat(340)+"1";
 @Test public void acceptsExistingDecimalSyntaxWithoutChangingSignOrBlankMeaning(){
  assertEquals(12.34,MarketDraft.number("00012.34000",false,0),0);
  assertEquals(0.5,MarketDraft.number(".5",false,0),0);
  assertEquals(1,MarketDraft.number("1.",false,0),0);
  assertEquals(-0.25,MarketDraft.number("-0.2500",true,0),0);
  assertEquals(1,MarketDraft.number("",false,1),0);
  assertEquals(0,MarketDraft.number("000.000",false,1),0);
 }
 @Test public void malformedInfiniteAndUnderflowingNumbersStayInvalid(){
  for(String value:new String[]{null,"NaN","Infinity","-Infinity","1e3","0x1p2",".","-","1 2",HUGE,TINY})assertTrue(String.valueOf(value),Double.isNaN(MarketDraft.number(value,true,0)));
  assertTrue(Double.isNaN(MarketDraft.number("-1",false,0)));
 }
 @Test public void generationRejectsOverflowBeforeAnyRowCanBeReplaced(){
  assertFalse(MarketDraft.generation(Double.MAX_VALUE,100,1,1,1));
  assertFalse(MarketDraft.generation(2,1,Double.MAX_VALUE,0,1));
  assertFalse(MarketDraft.generation(0.1,1,Double.MAX_VALUE/2,0,3));
  assertFalse(MarketDraft.generation(1,Double.POSITIVE_INFINITY,1,1,1));
  assertFalse(MarketDraft.generation(Double.MIN_VALUE,1,Double.MIN_VALUE,0,1));
 }
 @Test public void ordinaryGenerationAndSingleSidedIdleFormsKeepTheirMeaning(){
  assertTrue(MarketDraft.generation(1,0.25,12.34,4.5,6));
  assertTrue(MarketDraft.generation(1,150,0,4.5,6)); // negative-price bids are discarded by existing sanitizer
  assertTrue(MarketDraft.generation(0,0,0,0,1));
  assertTrue(MarketDraft.generation(1,0.25,0,4.5,2));
  assertFalse(MarketDraft.generation(1,0.25,1,1,7));
 }
 @Test public void rowsRejectMalformedTermsAndAggregateOrProductOverflow(){
  for(String bad:new String[]{HUGE,TINY,"-1","NaN","Infinity","."}){
   assertFalse(MarketDraft.rows(new String[][]{{bad,"1"}}));assertFalse(MarketDraft.rows(new String[][]{{"1",bad}}));
  }
  String max=java.math.BigDecimal.valueOf(Double.MAX_VALUE).toPlainString();
  assertFalse(MarketDraft.rows(new String[][]{{"2",max}}));
  assertFalse(MarketDraft.rows(new String[][]{{"0.1",max},{"0.1",max}}));
  assertFalse(MarketDraft.rows(new String[][]{{"0."+"0".repeat(199)+"1","0."+"0".repeat(199)+"1"}}));
 }
 @Test public void unusedAndNormalRowsRemainValid(){
  assertTrue(MarketDraft.rows(new String[][]{{"",""},{"1",""},{"","1"},{"0","1"},{"0002.000","00012.34000"}}));
  assertFalse(MarketDraft.rows(null));assertFalse(MarketDraft.rows(new String[][]{null}));
 }
 @Test public void everyEditorFieldIsCheckedBeforeSaving(){
  String[] good={"-0.25","1.5","1","0.25","2","12.34","4.5","1"};assertTrue(MarketDraft.parameters(good));
  for(int i=0;i<good.length;i++)for(String bad:new String[]{HUGE,TINY,"NaN","."}){String[] fields=good.clone();fields[i]=bad;assertFalse("field "+i+": "+bad,MarketDraft.parameters(fields));}
  assertTrue(MarketDraft.parameters("","","","","","","",""));
  assertTrue(MarketDraft.parameters("-50","0","1","1","99","1","0","0")); // existing skew/level/reprice clamps retained
  assertFalse(MarketDraft.parameters("0","0","9".repeat(200),"1","1","9".repeat(200),"0","0"));
 }
 @Test public void editorRoundingCannotTurnFiniteValuesIntoInvalidSavedNumbers(){
  String max=java.math.BigDecimal.valueOf(Double.MAX_VALUE).toPlainString();
  assertFalse(MarketDraft.generation(Double.MAX_VALUE/1.01,1,1,0,1));
  assertFalse(MarketDraft.generation(0.1,1,Double.MAX_VALUE,0,1));
  for(int field:new int[]{1,3,5,6}){String[] values={"","","","","1","","",""};values[field]=max;assertFalse("rounded preference "+field,MarketDraft.parameters(values));}
  assertTrue(MarketDraft.generation(1e300,1,1,1,1));
  assertTrue(MarketDraft.rows(new String[][]{{"0.1",max}})); // manual rows use the existing JSON codec, not trimSig
 }
}
