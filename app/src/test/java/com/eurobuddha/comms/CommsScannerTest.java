// VENDORED VERBATIM from apks/atomix 0.1.84 app/src/test/java/com/eurobuddha/comms/CommsScannerTest.java by scripts/vendor-atomix.sh — do not edit here; re-vendor.
package com.eurobuddha.comms;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The scanner must NEVER wedge (running stuck true) on an exception — that is a permanent DoS of the
 * message/payment scan until app restart. A completed scan always calls {@code listener.onDone}, so an
 * onDone after a deliberately-thrown router / MetaStore failure is the observable proof the scan finished.
 */
public class CommsScannerTest {

    private static final String ADDR = "0x4D494E494D45524348";   // a clean 0x-hex sentinel (constructor requires it)

    /** A NodeApi mock that answers coinnotify with an empty ok and `coins` with the given coin array. */
    private static NodeApi nodeReturning(JSONArray coins) {
        NodeApi node = mock(NodeApi.class);
        doAnswer(inv -> {
            String cmd = inv.getArgument(0);
            NodeApi.Cb cb = inv.getArgument(1);
            JSONObject j = new JSONObject().put("status", true);
            j.put("response", cmd.startsWith("coins") ? coins : new JSONArray());
            cb.onResult(j);
            return null;
        }).when(node).cmd(anyString(), any(NodeApi.Cb.class));
        return node;
    }

    /** A MetaStore that answers backfilled=true / tip=99 so the scan targets a tiny depth and finishes fast. */
    private static CommsScanner.MetaStore fastMeta(final boolean throwOnSet) {
        return new CommsScanner.MetaStore() {
            final Map<String, String> m = new HashMap<>();
            @Override public String getMeta(String k, String def) {
                if (k.contains("backfilled")) return "true";
                if (k.contains("tip")) return "99";
                return def;
            }
            @Override public void setMeta(String k, String v) {
                if (throwOnSet) throw new RuntimeException("disk full");
                m.put(k, v);
            }
        };
    }

    @Test public void aThrowingRouterFinishesTheScanInsteadOfWedgingIt() throws Exception {
        JSONArray coins = new JSONArray().put(new JSONObject().put("coinid", "0xC1"));
        NodeApi node = nodeReturning(coins);
        final List<Boolean> done = new ArrayList<>();

        // Raw scanner (decrypt=false): every coin goes straight to the router — which throws here.
        CommsScanner scanner = new CommsScanner(node, null, fastMeta(false), ADDR,
                (coinid, opened, coin) -> { throw new RuntimeException("router blew up"); },
                (ok, n) -> done.add(ok), false);

        scanner.scan(100);   // mock drives callbacks synchronously

        assertEquals("a completed scan must call onDone exactly once", 1, done.size());
        assertTrue("the throwing router must not wedge the scanner — the scan finishes (ok=false)", !done.get(0));
    }

    @Test public void aThrowingMetaStoreStillFinishesAndResetsRunning() throws Exception {
        JSONArray coins = new JSONArray().put(new JSONObject().put("coinid", "0xC1"));
        NodeApi node = nodeReturning(coins);
        final List<Boolean> done = new ArrayList<>();

        CommsScanner scanner = new CommsScanner(node, null, fastMeta(true), ADDR,
                (coinid, opened, coin) -> false, (ok, n) -> done.add(ok), false);

        scanner.scan(100);   // finish() will call setMeta, which throws

        assertEquals("onDone must still fire even though the MetaStore write threw", 1, done.size());
        assertTrue("the scan completed (ok=true) despite the MetaStore failure", done.get(0));
    }

    @Test(expected = IllegalArgumentException.class)
    public void constructorRejectsNonHexAddress() {
        // MA-10: a target address is interpolated into node commands — a non-hex one must be refused fail-fast.
        new CommsScanner(mock(NodeApi.class), null, fastMeta(false), "MxNOTHEX withspace",
                (coinid, opened, coin) -> false, (ok, n) -> {}, false);
    }
}
