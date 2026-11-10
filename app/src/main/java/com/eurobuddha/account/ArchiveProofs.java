package com.eurobuddha.account;

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

/**
 * Unspent coins at the account's addresses, with proofs, from the unpruned MegaMMR archive (eurobuddha.com:9001) — the
 * coins a node that started tracking the account late can no longer see (they are older than its chain window).
 * Same wire request and parser as PandaPools' MegaArchiveProofs (pool recovery), for several addresses in ONE request
 * (a reply carries ~5.6 MB of chain data, measured 10 Nov 2026; capped at 16 MB). Plain TCP, so a reply is hostile until
 * proven: a declared object length can still force a large allocation (OutOfMemoryError) — callers catch Throwable. Only the addresses leave the phone. Never installs the
 * supplied chain, peers or keys. The proofs are UNTRUSTED until the local node verifies them (VirtualWallet.importArchived).
 */
public final class ArchiveProofs {
    public static final String HOST = "eurobuddha.com";
    public static final int PORT = 9001;
    // ponytail: the 512 cap counts coins that only NAME our addresses too (HTLC locks); a very active AtomiX trader
    // could pass it and the whole reply is refused — query per address if that is ever seen.
    static final int MAX_FRAME = 16 * 1024 * 1024, MAX_PROOFS = 512;

    private ArchiveProofs() {}

    static byte[] request(Collection<String> addresses) throws IOException {
        ArrayList<MiniData> list = new ArrayList<>();
        for (String a : addresses) {
            if (a == null || !a.matches("0x[0-9a-fA-F]{64}")) throw new IOException("ARCHIVE_ADDRESS_INVALID");
            list.add(new MiniData(a));
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        NIOManager.createNIOMessage(NIOMessage.MSG_MEGAMMRSYNC_REQ, new MegaMMRSyncData(list, new ArrayList<>()))
                .writeDataStream(new DataOutputStream(bytes));
        return bytes.toByteArray();
    }

    public static List<CoinProof> fetch(Collection<String> addresses) throws IOException {
        byte[] query = request(addresses);
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(HOST, PORT), 10000);
            socket.setSoTimeout(45000);
            socket.getOutputStream().write(query);
            socket.getOutputStream().flush();
            return readFrame(socket.getInputStream(), addresses);
        }
    }

    static List<CoinProof> readFrame(InputStream input, Collection<String> addresses) throws IOException {
        Set<String> wanted = new HashSet<>();
        for (String a : addresses) wanted.add(a.toLowerCase(Locale.ROOT));
        DataInputStream wire = new DataInputStream(input);
        int length = wire.readInt();
        if (length < 1 || length > MAX_FRAME) throw new IOException("ARCHIVE_REPLY_LIMIT: archive reply exceeds the mobile recovery limit.");
        LimitedInput limited = new LimitedInput(wire, length);   // chain data is streamed and discarded, never kept
        DataInputStream in = new DataInputStream(limited);
        try {
            if (!MiniByte.ReadFromStream(in).isEqual(NIOMessage.MSG_MEGAMMRSYNC_RESP))
                throw new IOException("ARCHIVE_PROTOCOL: expected a MegaMMR reply.");
            if (count(in, 2) != 2) throw new IOException("ARCHIVE_PROTOCOL: unsupported MegaMMR version.");
            int cascade = in.readUnsignedByte();
            if (cascade > 1) throw new IOException("ARCHIVE_PROTOCOL: invalid cascade marker.");
            if (cascade == 1) { int nodes = count(in, 65536); for (int i = 0; i < nodes; i++) CascadeNode.ReadFromStream(in); }
            int blocks = count(in, 16384);
            for (int i = 0; i < blocks; i++) TxBlock.ReadFromStream(in);
            int total = count(in, MAX_PROOFS);
            List<CoinProof> proofs = new ArrayList<>();
            Set<String> ids = new HashSet<>();
            for (int i = 0; i < total; i++) {
                CoinProof proof = CoinProof.ReadFromStream(in);
                // The server also returns coins that only NAME an address in their state (megammrsync.searchMegaCoins),
                // e.g. HTLC locks with the account as owner or counterparty: they sit at another address — skipped, as
                // are spent and repeated rows (10 Nov: a lock naming address 1 made the whole reply throw).
                if (!wanted.contains(proof.getCoin().getAddress().to0xString().toLowerCase(Locale.ROOT))
                        || proof.getCoin().getSpent()
                        || !ids.add(proof.getCoin().getCoinID().to0xString().toLowerCase(Locale.ROOT))) continue;
                if (MiniData.getMiniDataVersion(proof).getLength() > 15999)
                    throw new IOException("ARCHIVE_PROOF_LIMIT: coin proof is too large.");
                proofs.add(proof);
            }
            int peersLength = in.readInt();   // never installed
            if (peersLength < 0 || peersLength > 65536 || peersLength != limited.remaining)
                throw new IOException("ARCHIVE_PROTOCOL: invalid peers field or trailing data.");
            byte[] discard = new byte[Math.min(4096, Math.max(1, peersLength))];
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
