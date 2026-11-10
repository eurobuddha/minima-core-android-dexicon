// VENDORED VERBATIM from apks/pandapools 0.9.97 app/src/main/java/com/eurobuddha/pandapools/NetFetch.java by scripts/vendor-pandapools.sh — do not edit here; re-vendor.
package com.eurobuddha.pandapools;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;

/**
 * The ONE way this app fetches anything named by token metadata (icon urls, webvalidate urls).
 * Token metadata is written by whoever minted the token, so it is hostile input:
 *  - http(s) only;
 *  - loopback / LAN / link-local hosts are refused (the node's RPC port lives on loopback);
 *  - redirects are followed MANUALLY, re-checking the host at EVERY hop (with automatic redirects a
 *    public host could 302 us straight to http://127.0.0.1:9005/<command>), max {@link #MAX_HOPS};
 *  - the body is byte-capped.
 * Residual: DNS is resolved once for the check and again by the connection, so a rebinding server
 * could still race it; all resolved addresses are checked to narrow that window.
 */
final class NetFetch {

    private static final int MAX_HOPS = 3;

    private NetFetch() {}

    /**
     * GET {@code url}. Returns the body, or null when refused / non-200 / failed. If the body exceeds
     * {@code maxBytes}: {@code truncateAtCap} true → the first maxBytes are returned; false → null.
     */
    static byte[] get(String url, int maxBytes, int connectMs, int readMs, boolean truncateAtCap) throws Exception {
        String cur = url;
        for (int hop = 0; hop <= MAX_HOPS; hop++) {
            URL u = new URL(cur);
            String proto = u.getProtocol();
            if (!"http".equals(proto) && !"https".equals(proto)) return null;
            if (isBlockedHost(u.getHost())) return null;
            HttpURLConnection con = (HttpURLConnection) u.openConnection();
            con.setInstanceFollowRedirects(false);
            con.setConnectTimeout(connectMs);
            con.setReadTimeout(readMs);
            con.setRequestProperty("User-Agent", "PandaPools");
            try {
                int code = con.getResponseCode();
                if (code / 100 == 3) {
                    String loc = con.getHeaderField("Location");
                    if (loc == null || loc.isEmpty()) return null;
                    cur = new URL(u, loc).toString();        // resolve relative Location; re-checked next hop
                    continue;
                }
                if (code != 200) return null;
                try (InputStream in = con.getInputStream(); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
                    byte[] buf = new byte[8192]; int n; int total = 0;
                    while ((n = in.read(buf)) > 0) {
                        if (total + n > maxBytes) {
                            if (!truncateAtCap) return null;
                            bos.write(buf, 0, maxBytes - total);
                            return bos.toByteArray();
                        }
                        bos.write(buf, 0, n);
                        total += n;
                    }
                    return bos.toByteArray();
                }
            } finally {
                con.disconnect();
            }
        }
        return null;   // too many redirects
    }

    /** True for loopback / any-local / link-local / private hosts, or anything unresolvable. Every resolved
     *  address is checked. Beyond the JDK predicates (isSiteLocalAddress only knows RFC1918 and the dead
     *  fec0::/10) this also blocks IPv6 unique-local fc00::/7 (today's LAN IPv6), carrier-grade NAT
     *  100.64.0.0/10 and 0.0.0.0/8. */
    static boolean isBlockedHost(String host) {
        if (host == null || host.isEmpty()) return true;
        try {
            for (InetAddress a : InetAddress.getAllByName(host)) {
                if (a.isLoopbackAddress() || a.isAnyLocalAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress()
                        || a.isMulticastAddress() || isExtraPrivate(a))
                    return true;
            }
        } catch (Exception e) { return true; }
        return false;
    }

    static boolean isExtraPrivate(InetAddress a) {
        byte[] b = a.getAddress();
        if (b.length == 16) return (b[0] & 0xFE) == 0xFC;                        // fc00::/7 unique-local
        if (b.length == 4) {
            int o1 = b[0] & 0xFF, o2 = b[1] & 0xFF;
            return o1 == 0 || (o1 == 100 && o2 >= 64 && o2 <= 127);               // 0.0.0.0/8, 100.64.0.0/10
        }
        return false;
    }
}
