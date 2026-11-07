// VENDORED VERBATIM from apks/pandapools 0.9.83 app/src/test/java/com/eurobuddha/pandapools/NetFetchTest.java by scripts/vendor-pandapools.sh — do not edit here; re-vendor.
package com.eurobuddha.pandapools;
import org.junit.Test;
import static org.junit.Assert.*;
public class NetFetchTest {
    @Test public void refusesLocalAndPrivateNetworkTargets() {
        for (String host : new String[]{"127.0.0.1", "::1", "10.1.2.3", "192.168.1.1", "169.254.169.254", "fc00::1", "100.64.0.1", "0.1.2.3", "224.0.0.1"})
            assertTrue(host, NetFetch.isBlockedHost(host));
        assertFalse(NetFetch.isBlockedHost("8.8.8.8"));
    }
    @Test public void refusesNonHttpSchemesBeforeOpeningConnection() throws Exception {
        assertNull(NetFetch.get("file:///etc/passwd", 10, 100, 100, false));
        assertNull(NetFetch.get("http://127.0.0.1:9005/status", 10, 100, 100, false));
    }
}
