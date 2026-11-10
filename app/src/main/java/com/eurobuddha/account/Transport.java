package com.eurobuddha.account;

import org.json.JSONObject;

import java.util.function.BooleanSupplier;

/**
 * How the Trading Account reaches the node: the app's own IPC client, straight through (no account interception).
 * Dexicon, AtomiX and PandaPools each implement it on their node layer. Replies arrive on the main thread.
 */
public interface Transport {
    interface Reply {
        void onResult(JSONObject json);
        void onError(String message);
    }

    /** Run {@code command} on the node as-is; {@code guard} (may be null) is evaluated right before it is sent. */
    void cmdDirect(String command, BooleanSupplier guard, long timeoutMs, Reply cb);

    /** Re-announce this app to the node (after the user changed its rights there). */
    default void reRegister() {}
}
