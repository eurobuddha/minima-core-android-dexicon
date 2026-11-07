// VENDORED VERBATIM from apks/pandapools 0.9.88 app/src/test/java/com/eurobuddha/pandapools/ArchiveHardeningTest.java by scripts/vendor-pandapools.sh — do not edit here; re-vendor.
package com.eurobuddha.pandapools;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.util.*;
import org.minima.objects.CoinProof;
import org.minima.objects.IBD;
import org.minima.objects.base.*;
import org.minima.objects.mmr.MMRProof;
import org.minima.system.commands.backup.mmrsync.*;
import org.minima.system.network.minima.*;

public class ArchiveHardeningTest {
    static final String ADDRESS = "0x" + "12".repeat(32), ID = "0x" + "34".repeat(32);
    static CoinProof proof(String address) {
        return new CoinProof(new org.minima.objects.Coin(new MiniData(ID), new MiniData(address), new MiniNumber(3), new MiniData("0x00")), new MMRProof(new MiniNumber(42)));
    }
    static byte[] frame(List<CoinProof> proofs) throws Exception {
        MegaMMRIBD response = new MegaMMRIBD(new IBD(), new ArrayList<>(proofs));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        NIOManager.createNIOMessage(NIOMessage.MSG_MEGAMMRSYNC_RESP, response).writeDataStream(new DataOutputStream(bytes));
        return bytes.toByteArray();
    }
    static void rejected(byte[] bytes) throws Exception {
        try { MegaArchiveProofs.readFrame(new ByteArrayInputStream(bytes), ADDRESS); fail("accepted invalid archive response"); }
        catch(IOException expected) { }
    }
    @Test public void nativeRequestContainsOnlyThePoolAndNoWalletPublicKeys() throws Exception {
        DataInputStream wire = new DataInputStream(new ByteArrayInputStream(MegaArchiveProofs.request(ADDRESS)));
        byte[] body = MiniData.ReadFromStream(wire).getBytes();
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(body));
        assertTrue(MiniByte.ReadFromStream(in).isEqual(NIOMessage.MSG_MEGAMMRSYNC_REQ));
        MegaMMRSyncData query = new MegaMMRSyncData(); query.readDataStream(in);
        assertEquals(1, query.getAllAddresses().size());
        assertEquals(ADDRESS.toUpperCase(Locale.ROOT),query.getAllAddresses().get(0).to0xString().toUpperCase(Locale.ROOT));
        assertTrue(query.getAllPublicKeys().isEmpty());assertEquals(0,in.available());
        assertEquals("eurobuddha.com",MegaArchiveProofs.HOST); assertEquals(9001,MegaArchiveProofs.PORT);
    }
    @Test public void nativeResponseRoundTripsExactProofWithoutInstallingChainData() throws Exception {
        CoinProof original=proof(ADDRESS);
        List<CoinProof> found=MegaArchiveProofs.readFrame(new ByteArrayInputStream(frame(Collections.singletonList(original))),ADDRESS);
        assertEquals(1,found.size());assertEquals(MiniData.getMiniDataVersion(original).to0xString(),MiniData.getMiniDataVersion(found.get(0)).to0xString());
    }
    @Test public void emptyArchiveIsAnEmptyResultNotProofOfSpentFunds() throws Exception {
        assertTrue(MegaArchiveProofs.readFrame(new ByteArrayInputStream(frame(Collections.emptyList())),ADDRESS).isEmpty());
    }
    @Test public void wrongPoolDuplicateAndSpentCoinsAreRejected() throws Exception {
        rejected(frame(Collections.singletonList(proof(ID))));
        rejected(frame(Arrays.asList(proof(ADDRESS),proof(ADDRESS))));
        CoinProof spent=proof(ADDRESS);spent.getCoin().setSpent(true);rejected(frame(Collections.singletonList(spent)));
    }
    @Test public void truncatedWrongTypeOversizedAndTrailingFramesAreRejected() throws Exception {
        byte[] valid=frame(Collections.emptyList());rejected(Arrays.copyOf(valid,valid.length-1));
        byte[] type=valid.clone();type[4]=0;rejected(type);
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();new DataOutputStream(bytes).writeInt(MegaArchiveProofs.MAX_FRAME+1);rejected(bytes.toByteArray());
        byte[] trailing=Arrays.copyOf(valid,valid.length+1);java.nio.ByteBuffer.wrap(trailing).putInt(valid.length-3);rejected(trailing);
    }
    @Test public void unsupportedVersionsAndExcessiveObjectCountsAreRejected() throws Exception {
        for(int count:new int[]{-1,513,Integer.MAX_VALUE}) {
            ByteArrayOutputStream body=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(body);
            NIOMessage.MSG_MEGAMMRSYNC_RESP.writeDataStream(out);MiniNumber.WriteToStream(out,2);
            new IBD().writeDataStream(out);MiniNumber.WriteToStream(out,count);
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();MiniData.WriteToStream(new DataOutputStream(bytes),body.toByteArray());rejected(bytes.toByteArray());
        }
    }
    @Test public void sessionExportsTheFetchedProofAndRefusesOtherAddressesOrCommands() throws Exception {
        int[] calls={0};MegaArchive.Session s=new MegaArchive.Session(address->{calls[0]++;return Collections.singletonList(proof(address));});
        assertTrue(s.query("status").getBoolean("status"));assertEquals(0,calls[0]);
        assertEquals(1,s.query("balance address:"+ADDRESS+" megammr:true").getJSONArray("response").getJSONObject(0).getInt("coins"));
        assertEquals(1,s.query("coins address:"+ADDRESS+" megammr:true").getJSONArray("response").length());
        assertEquals(MiniData.getMiniDataVersion(proof(ADDRESS)).to0xString(),s.query("coinexport coinid:"+ID).getJSONObject("response").getString("data"));
        assertEquals(1,calls[0]);
        for(String command:new String[]{"coins address:"+ID+" megammr:true","send amount:1","coinexport coinid:"+ADDRESS}) {
            try{s.query(command);fail(command);}catch(IOException expected){}
        }
    }
    @Test public void failedRefreshCannotExportStaleCachedProofs() throws Exception {
        int[] calls={0};MegaArchive.Session s=new MegaArchive.Session(address->{if(calls[0]++>0)throw new IOException("offline");return Collections.singletonList(proof(address));});
        s.query("balance address:"+ADDRESS+" megammr:true");
        try{s.query("balance address:"+ADDRESS+" megammr:true");fail();}catch(IOException expected){}
        try{s.query("coinexport coinid:"+ID);fail();}catch(IOException expected){}
    }
}
