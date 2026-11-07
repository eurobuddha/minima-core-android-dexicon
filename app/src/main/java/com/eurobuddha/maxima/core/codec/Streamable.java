// VENDORED VERBATIM from apks/pandapools 0.9.90 app/src/main/java/com/eurobuddha/maxima/core/codec/Streamable.java by scripts/vendor-pandapools.sh — do not edit here; re-vendor.
package com.eurobuddha.maxima.core.codec;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Anything that can be written to / read from the Maxima wire.
 *
 * Mirrors org.minima.utils.Streamable. Byte-for-byte compatibility with the
 * reference implementation is mandatory - see fixtures/golden-vectors.json.
 */
public interface Streamable {

    void writeDataStream(DataOutputStream zOut) throws IOException;

    void readDataStream(DataInputStream zIn) throws IOException;
}
