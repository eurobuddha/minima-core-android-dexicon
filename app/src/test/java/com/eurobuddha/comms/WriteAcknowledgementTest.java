// VENDORED VERBATIM from apks/atomix 0.1.84 app/src/test/java/com/eurobuddha/comms/WriteAcknowledgementTest.java by scripts/vendor-atomix.sh — do not edit here; re-vendor.
package com.eurobuddha.comms;
import android.content.SharedPreferences;
import java.util.*;
import org.junit.*;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
/** Reuses WriteSafetyTest's preferences fixture and PandaPools ReserveRecoveryTest's failed-clear contract. */
public class WriteAcknowledgementTest {
 private SharedPreferences prefs;private SharedPreferences.Editor edit;private final Map<String,Object>persisted=new HashMap<>();
 @Before public void setup(){
  prefs=mock(SharedPreferences.class);edit=mock(SharedPreferences.Editor.class);when(prefs.edit()).thenReturn(edit);
  when(prefs.getString(anyString(),anyString())).thenAnswer(i->persisted.getOrDefault(i.getArgument(0),i.getArgument(1)));
  when(prefs.getLong(anyString(),anyLong())).thenAnswer(i->persisted.getOrDefault(i.getArgument(0),i.getArgument(1)));
  when(edit.putString(anyString(),anyString())).thenAnswer(i->{persisted.put(i.getArgument(0),i.getArgument(1));return edit;});
  when(edit.putLong(anyString(),anyLong())).thenAnswer(i->{persisted.put(i.getArgument(0),i.getArgument(1));return edit;});
  when(edit.remove(anyString())).thenAnswer(i->{persisted.remove(i.getArgument(0));return edit;});when(edit.commit()).thenReturn(true);WriteSafety.resetForTest();
 }
 @After public void reset(){WriteSafety.resetForTest();}
 private void interrupted(){assertTrue(WriteSafety.begin(prefs,"original","txnpost id:public-fixture"));WriteSafety.uncertain(prefs,"original");}
 @Test public void failedClearRestoresAllDetailsAndPreventsNextWrite(){
  interrupted();Map<String,Object>before=new HashMap<>(persisted);when(edit.commit()).thenReturn(false);assertFalse(WriteSafety.acknowledge(prefs));assertEquals(before,persisted);assertTrue(WriteSafety.interrupted(prefs));assertFalse(WriteSafety.begin(prefs,"new","send amount:1"));
  when(edit.commit()).thenReturn(true);assertTrue(WriteSafety.acknowledge(prefs));assertTrue(persisted.isEmpty());assertFalse(WriteSafety.interrupted(prefs));assertTrue(WriteSafety.begin(prefs,"new","send amount:1"));WriteSafety.returned(prefs,"new",true);
 }
 @Test public void throwingClearAndRollbackStillKeepProcessLatch(){
  interrupted();assertFalse(WriteSafety.commitAcknowledgement(()->{persisted.clear();throw new IllegalStateException("disk");},()->{throw new IllegalStateException("disk");}));assertTrue(WriteSafety.interrupted(prefs));assertNotNull(WriteSafety.describe(prefs));assertFalse(WriteSafety.begin(prefs,"new","sign"));assertTrue(WriteSafety.acknowledge(prefs));assertNull(WriteSafety.describe(prefs));
 }
 @Test public void lateReplyCannotSilentlyClearFailedAcknowledgement(){
  interrupted();when(edit.commit()).thenReturn(false);assertFalse(WriteSafety.acknowledge(prefs));when(edit.commit()).thenReturn(true);WriteSafety.returned(prefs,"original",true);assertTrue(persisted.isEmpty());assertTrue(WriteSafety.interrupted(prefs));assertFalse(WriteSafety.begin(prefs,"new","txnpost"));assertTrue(WriteSafety.acknowledge(prefs));assertFalse(WriteSafety.interrupted(prefs));
 }
 @Test public void activeWriteRejectsStaleConsentWithoutTouchingItsMarker(){
  assertTrue(WriteSafety.begin(prefs,"active","consolidate"));Map<String,Object>before=new HashMap<>(persisted);clearInvocations(edit);assertFalse(WriteSafety.acknowledge(prefs));assertEquals(before,persisted);verify(edit,never()).commit();WriteSafety.returned(prefs,"active",true);
 }
 @Test public void throwingCommitsRetainEvidenceInMemoryAndKeepPause(){
  interrupted();Map<String,Object>before=new HashMap<>(persisted);when(edit.commit()).thenThrow(new IllegalStateException("disk"));assertFalse(WriteSafety.acknowledge(prefs));assertEquals(before,persisted);assertTrue(WriteSafety.interrupted(prefs));assertFalse(WriteSafety.begin(prefs,"new","send amount:1"));
 }
 @Test public void reviewOfOldWriteCannotClearNewInterruptedWrite(){
  interrupted();String reviewed="original";WriteSafety.returned(prefs,"original",true);assertTrue(WriteSafety.begin(prefs,"new","send amount:1"));WriteSafety.uncertain(prefs,"new");Map<String,Object>before=new HashMap<>(persisted);assertFalse(WriteSafety.acknowledge(prefs,reviewed));assertFalse(WriteSafety.acknowledge(prefs,null));assertEquals(before,persisted);assertTrue(WriteSafety.interrupted(prefs));assertTrue(WriteSafety.acknowledge(prefs,"new"));assertFalse(WriteSafety.interrupted(prefs));
 }
}
