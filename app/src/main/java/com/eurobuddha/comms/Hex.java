// VENDORED VERBATIM from apks/atomix 0.1.83 app/src/main/java/com/eurobuddha/comms/Hex.java by scripts/vendor-atomix.sh — do not edit here; re-vendor.
package com.eurobuddha.comms;

/** Minimal, dependency-free hex codec. Accepts an optional 0x/0X prefix on decode. */
public final class Hex {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    public static String to(byte[] b) {
        if (b == null) return "";   // MI-9: guard null (from() already tolerates null)
        char[] out = new char[b.length * 2];
        for (int i = 0; i < b.length; i++) {
            int v = b[i] & 0xFF;
            out[i * 2] = HEX[v >>> 4];
            out[i * 2 + 1] = HEX[v & 0x0F];
        }
        return new String(out);
    }

    public static byte[] from(String h) {
        if (h == null) return new byte[0];
        if (h.length() >= 2 && (h.charAt(0) == '0') && (h.charAt(1) == 'x' || h.charAt(1) == 'X')) h = h.substring(2);
        // MI-9: reject odd length rather than silently dropping the final nibble (h.length()/2 truncated it).
        if ((h.length() & 1) != 0) throw new IllegalArgumentException("odd-length hex");
        // Review NIT: reject any non-hex char up front — Integer.parseInt(…,16) would otherwise accept a
        // leading sign (e.g. "0x-1" → 0xFF), which a hex codec must not.
        for (int i = 0; i < h.length(); i++)
            if (Character.digit(h.charAt(i), 16) < 0) throw new IllegalArgumentException("non-hex char");
        int n = h.length() / 2;
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) {
            b[i] = (byte) Integer.parseInt(h.substring(2 * i, 2 * i + 2), 16);
        }
        return b;
    }

    private Hex() {}
}
