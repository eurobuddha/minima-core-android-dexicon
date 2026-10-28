package com.eurobuddha.dexicon;

import static org.junit.Assert.*;

import com.eurobuddha.dexicon.envelope.Envelope;
import com.eurobuddha.dexicon.envelope.Envelopes;
import com.eurobuddha.dexicon.identity.DexIdentity;
import com.eurobuddha.dexicon.maker.MakerEngine;
import com.eurobuddha.dexicon.maker.MyOrder;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 26 Oct: two relays kept 9 of my CANCELLED orders live because their cancels never arrived. The sweep must find them. */
public class RelaySyncTest {
    static final String ADDR = "0x0CDCD61692F186EB0BBCFA289F438043F586FF7B3F6864193358E29166E8454A";
    static final String COIN = "0xF19FE115E66277A5785D69CA53BDA431B0D7CDB5B7DB1C4AE8C3CA01558C045F";
    final DexIdentity me = TestIds.id("11"), other = TestIds.id("44");

    Envelope env(DexIdentity who, String id, long seq) {
        return Envelope.parse(Envelopes.order(who, "MINIMA/MxUSD", "sell", "0.0035", "1200", Arrays.asList(COIN), new ArrayList<>(), ADDR, id, seq, TestIds.NOW + 3600), TestIds.NOW);
    }
    static MyOrder mine(String id, String status, long seq) { MyOrder o = new MyOrder(); o.orderId = id; o.status = status; o.seq = seq; return o; }

    @Test public void findsCancelledFilledAndOutdatedOnlyForMe() {
        Map<String, MyOrder> local = new HashMap<>();
        local.put("0x0000000000000001", mine("0x0000000000000001", "cancelled", 5));   // relay still has it live → fix
        local.put("0x0000000000000002", mine("0x0000000000000002", "filled", 5));      // relay still has it live → fix
        local.put("0x0000000000000003", mine("0x0000000000000003", "live", 9));        // relay has seq 4 → fix
        local.put("0x0000000000000004", mine("0x0000000000000004", "live", 4));        // relay up to date → leave
        List<Envelope> relay = Arrays.asList(env(me, "0x0000000000000001", 4), env(me, "0x0000000000000002", 4),
                env(me, "0x0000000000000003", 4), env(me, "0x0000000000000004", 4),
                env(me, "0x0000000000000005", 4),                                               // unknown here → leave
                env(other, "0x0000000000000001", 4));                                          // someone else's → leave
        List<MyOrder> fix = MakerEngine.stale(relay, me.signPkHex(), local);
        List<String> ids = new ArrayList<>(); for (MyOrder o : fix) ids.add(o.orderId);
        assertEquals(Arrays.asList("0x0000000000000001", "0x0000000000000002", "0x0000000000000003"), ids);
    }

    /** 28 Oct: the relays restarted (their book is in memory) and every ladder order was gone; the phone still had
     *  them live and never re-sent them. A live, unexpired order the relay lacks must be re-sent; an expired one not. */
    @Test public void resendsLiveOrdersTheRelayLost() {
        Map<String, MyOrder> local = new HashMap<>();
        MyOrder lost = mine("0x0000000000000007", "live", 5); lost.expiry = MakerEngine.now() + 3600; local.put(lost.orderId, lost);
        MyOrder old = mine("0x0000000000000008", "live", 5); old.expiry = MakerEngine.now() - 10; local.put(old.orderId, old);
        MyOrder gone = mine("0x0000000000000009", "cancelled", 5); gone.expiry = MakerEngine.now() + 3600; local.put(gone.orderId, gone);
        List<MyOrder> fix = MakerEngine.stale(new ArrayList<>(), me.signPkHex(), local);
        assertEquals(1, fix.size());
        assertEquals("0x0000000000000007", fix.get(0).orderId);
    }
}
