package com.eurobuddha.minimaapi;

import org.json.JSONObject;
import org.json.JSONTokener;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** PandaDEX adaptation of NodeTransport.read: cap bytes before JSON allocation.
 * A lost or malformed reply says nothing about whether the command executed. */
public final class MinimaAPIResponse {
    public static final int MAX_BYTES = 4 * 1024 * 1024;
    private MinimaAPIResponse() { }
    public static String read(InputStream in) throws IOException {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int n, count = 0;
            while ((n = in.read(buffer)) != -1) {
                count += n;
                if (count > MAX_BYTES) throw new IOException("Payload too large");
                out.write(buffer, 0, n);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }
    public static JSONObject parse(String text) {
        if (text == null || text.indexOf(0) >= 0 || text.length() > MAX_BYTES || text.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES)
            return failure("Node response exceeds the supported size.");
        try {
            JSONTokener input = new JSONTokener(text);
            Object value = input.nextValue();
            if (!(value instanceof JSONObject) || !hasOnlyTrailingWhitespace(input))
                return failure("Node returned an invalid JSON response.");
            return (JSONObject) value;
        } catch (Exception malformed) {
            return failure("Node returned an invalid JSON response.");
        }
    }
    /** Consume only JSON whitespace after a parsed value. Android nextClean() also
     * skips comments, which would silently discard trailing receipt/reply data. */
    public static boolean hasOnlyTrailingWhitespace(JSONTokener input) throws org.json.JSONException {
        while (input.more()) {
            char c = input.next();
            if (c != ' ' && c != '\t' && c != '\r' && c != '\n') return false;
        }
        return true;
    }
    public static JSONObject failure(String message) {
        JSONObject result = new JSONObject();
        try { result.put("transporterror", message); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
        return result; // Deliberately no status/ enabled: never clear the funds-write quarantine.
    }
}
