package com.eurobuddha.account;

import static org.junit.Assert.*;

import org.junit.Test;
import org.minima.objects.Coin;
import org.minima.objects.CoinProof;
import org.minima.objects.base.MiniData;
import org.minima.objects.base.MiniNumber;
import org.minima.objects.mmr.MMRProof;
import org.minima.system.network.minima.NIOMessage;

import java.io.*;
import java.util.*;

public class ArchiveProofsTest {
    static final String OURS = "0x" + "AB".repeat(32), HTLC = "0x" + "CD".repeat(32);

    static CoinProof proof(String coinid, String address) {
        return new CoinProof(new Coin(new MiniData(coinid), new MiniData(address), new MiniNumber("1"), new MiniData("0x00")),
                new MMRProof(new MiniNumber(100)));
    }

    /** A MegaMMR reply as the server writes it: no cascade, no blocks, the proofs, an empty peers field. */
    static byte[] frame(CoinProof... proofs) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(body);
        NIOMessage.MSG_MEGAMMRSYNC_RESP.writeDataStream(d);
        new MiniNumber(2).writeDataStream(d);
        d.writeByte(0);
        new MiniNumber(0).writeDataStream(d);
        new MiniNumber(proofs.length).writeDataStream(d);
        for (CoinProof p : proofs) p.writeDataStream(d);
        d.writeInt(0);
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        DataOutputStream o = new DataOutputStream(all);
        o.writeInt(body.size()); o.write(body.toByteArray());
        return all.toByteArray();
    }

    /** The server also returns coins that only name our address in their state (an HTLC lock): skipped, not fatal. */
    @Test public void lockNamingUsIsSkippedNotFatal() throws Exception {
        List<CoinProof> got = ArchiveProofs.readFrame(new ByteArrayInputStream(frame(
                proof("0x01", OURS), proof("0x02", HTLC), proof("0x01", OURS))), Collections.singletonList(OURS));
        assertEquals(1, got.size());
        assertEquals("0x01", got.get(0).getCoin().getCoinID().to0xString());
    }

    @Test(expected = IOException.class) public void trailingBytesAreRefused() throws Exception {
        byte[] f = frame(proof("0x01", OURS));
        byte[] longer = Arrays.copyOf(f, f.length + 1);
        longer[3]++;   // declared length covers one more byte than the peers field accounts for
        ArchiveProofs.readFrame(new ByteArrayInputStream(longer), Collections.singletonList(OURS));
    }
}
