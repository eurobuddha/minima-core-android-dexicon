// VENDORED VERBATIM from apks/pandapools 0.9.82 app/src/main/java/com/eurobuddha/pandapools/MegaArchiveProofs.java by scripts/vendor-pandapools.sh — do not edit here; re-vendor.
package com.eurobuddha.pandapools;

import org.minima.database.cascade.CascadeNode;
import org.minima.objects.CoinProof;
import org.minima.objects.TxBlock;
import org.minima.objects.base.MiniByte;
import org.minima.objects.base.MiniData;
import org.minima.objects.base.MiniNumber;
import org.minima.system.commands.backup.mmrsync.MegaMMRSyncData;
import org.minima.system.network.minima.NIOManager;
import org.minima.system.network.minima.NIOMessage;

import java.io.*;
import java.net.*;
import java.util.*;

/** Read-only native MegaMMR request. Never installs the supplied IBD, peers, keys or chain.
 * Wire format and object codecs are those shipped in Minima, not a second protocol.
 * Returned proofs are UNTRUSTED until ReserveRecovery runs local coincheck/coinimport. */
final class MegaArchiveProofs {
    static final String HOST = "eurobuddha.com";
    static final int PORT = 9001;
    static final int MAX_FRAME = 64 * 1024 * 1024, MAX_PROOFS = 512;

    static byte[] request(String address) throws IOException {
        if (address == null || !address.matches("0x[0-9a-fA-F]{64}")) throw new IOException("ARCHIVE_ADDRESS_INVALID");
        ArrayList<MiniData> addresses = new ArrayList<>();
        addresses.add(new MiniData(address));
        MegaMMRSyncData query = new MegaMMRSyncData(addresses, new ArrayList<>());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        NIOManager.createNIOMessage(NIOMessage.MSG_MEGAMMRSYNC_REQ, query)
                .writeDataStream(new DataOutputStream(bytes));
        return bytes.toByteArray();
    }

    static List<CoinProof> fetch(String address) throws IOException {
        byte[] query = request(address);
        // One address only. No seed, public-key list, backup contents or signing state leave the node.
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(HOST, PORT), 10000);
            socket.setSoTimeout(45000);
            socket.getOutputStream().write(query);
            socket.getOutputStream().flush();
            return readFrame(socket.getInputStream(), address);
        }
    }

    static List<CoinProof> readFrame(InputStream input, String address) throws IOException {
        DataInputStream wire = new DataInputStream(input);
        int length = wire.readInt();
        if (length < 1 || length > MAX_FRAME) throw new IOException("ARCHIVE_REPLY_LIMIT: archive reply exceeds the mobile recovery limit.");
        // Stream and discard chain data instead of retaining a second node-sized IBD in memory.
        LimitedInput limited = new LimitedInput(wire, length);
        DataInputStream in = new DataInputStream(limited);
        try {
            if (!MiniByte.ReadFromStream(in).isEqual(NIOMessage.MSG_MEGAMMRSYNC_RESP))
                throw new IOException("ARCHIVE_PROTOCOL: expected a MegaMMR reply.");
            if (count(in, 2) != 2) throw new IOException("ARCHIVE_PROTOCOL: unsupported MegaMMR version.");
            int cascade = in.readUnsignedByte();
            if (cascade > 1) throw new IOException("ARCHIVE_PROTOCOL: invalid cascade marker.");
            if (cascade == 1) {
                int nodes = count(in, 65536);
                for (int i = 0; i < nodes; i++) CascadeNode.ReadFromStream(in);
            }
            int blocks = count(in, 16384);
            for (int i = 0; i < blocks; i++) TxBlock.ReadFromStream(in);
            int total = count(in, MAX_PROOFS);
            List<CoinProof> proofs = new ArrayList<>();
            Set<String> ids = new HashSet<>();
            for (int i = 0; i < total; i++) {
                CoinProof proof = CoinProof.ReadFromStream(in);
                if (!address.equalsIgnoreCase(proof.getCoin().getAddress().to0xString())
                        || proof.getCoin().getSpent()
                        || !ids.add(proof.getCoin().getCoinID().to0xString().toLowerCase(Locale.ROOT)))
                    throw new IOException("ARCHIVE_PROOF_IDENTITY: unexpected, spent or duplicate coin.");
                if (MiniData.getMiniDataVersion(proof).getLength() > 15999)
                    throw new IOException("ARCHIVE_PROOF_LIMIT: reserve proof is too large.");
                proofs.add(proof);
            }
            // The peers field is unnecessary for proof recovery and is never installed.
            int peersLength = in.readInt();
            if (peersLength < 0 || peersLength > 65536 || peersLength != limited.remaining)
                throw new IOException("ARCHIVE_PROTOCOL: invalid peers field or trailing data.");
            byte[] discard = new byte[Math.min(4096, peersLength)];
            while (limited.remaining > 0) in.readFully(discard, 0, Math.min(discard.length, limited.remaining));
            return proofs;
        } catch (RuntimeException malformed) {
            throw new IOException("ARCHIVE_PROTOCOL: malformed archive reply.", malformed);
        }
    }

    private static int count(DataInputStream in, int maximum) throws IOException {
        try {
            int n = new java.math.BigDecimal(MiniNumber.ReadFromStream(in).toString()).intValueExact();
            if (n < 0 || n > maximum) throw new IOException("ARCHIVE_REPLY_LIMIT: excessive archive object count.");
            return n;
        } catch (ArithmeticException invalid) { throw new IOException("ARCHIVE_PROTOCOL: invalid object count.", invalid); }
    }

    private static final class LimitedInput extends InputStream {
        final InputStream source;
        int remaining;
        final long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(90);
        LimitedInput(InputStream source, int size) { this.source = source; remaining = size; }
        private void check() throws IOException {
            if (System.nanoTime() > deadline) throw new SocketTimeoutException("ARCHIVE_TIMEOUT: recovery time limit reached.");
        }
        public int read() throws IOException {
            check(); if (remaining == 0) return -1;
            int value = source.read(); if (value < 0) throw new EOFException("ARCHIVE_TRUNCATED");
            remaining--; return value;
        }
        public int read(byte[] b, int off, int len) throws IOException {
            check(); if (len == 0) return 0; if (remaining == 0) return -1;
            int n = source.read(b, off, Math.min(len, remaining));
            if (n < 0) throw new EOFException("ARCHIVE_TRUNCATED");
            remaining -= n; return n;
        }
    }
}
