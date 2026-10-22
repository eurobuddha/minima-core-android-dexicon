// VENDORED VERBATIM from apks/atomix 0.1.83 app/src/main/java/com/eurobuddha/comms/Opened.java by scripts/vendor-atomix.sh — do not edit here; re-vendor.
package com.eurobuddha.comms;

/** Result of {@link CryptoProvider#open}: the decrypted message plus whether the sender signature verified. */
public final class Opened {
    public final boolean valid;        // sender signature checked out
    public final String fromPublicId;  // sender's published id (boxPk||signPk hex), as embedded + verified
    public final byte[] plaintext;

    public Opened(boolean valid, String fromPublicId, byte[] plaintext) {
        this.valid = valid;
        this.fromPublicId = fromPublicId;
        this.plaintext = plaintext;
    }
}
