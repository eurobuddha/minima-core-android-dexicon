package com.eurobuddha.comms;

import org.json.JSONObject;

/**
 * DEXICON-OWNED SHIM (not vendored): the vendored AtomiX engine (com.eurobuddha.atomix.*, com.eurobuddha.comms.*) talks
 * to the node through AtomiX's {@code comms.NodeApi}; Dexicon already owns ONE transport — {@link
 * com.eurobuddha.dexicon.NodeApi}: the single process-wide IPC queue, the :nodeipc binder transport and one pairing — so
 * this class presents AtomiX's call surface on top of it instead of bringing a second SDK instance.
 *
 * Kept from AtomiX: the {@code authorized} guard is evaluated before queueing AND again at dispatch time (Dexicon's
 * NodeApi.cmd(command, guard, cb)), so a market switch between the two never sends a stale command. Not kept: AtomiX's
 * interrupted-write latch (WriteSafety.begin/uncertain on prefs). A fund write that times out surfaces here as an error,
 * and the engine's own per-hash retry windows (ETH_RETRY_SECS markers, txncheck before post) decide what happens next.
 */
public class NodeApi {

    public interface Cb {
        void onResult(JSONObject json);
        void onError(String message);
    }

    public static final String ERR_NOT_ENABLED = com.eurobuddha.dexicon.NodeApi.ERR_NOT_ENABLED;
    public static final String ERR_COMMAND_CHANGED = "Market changed; refresh and try again.";
    public static final String ERR_WRITE_UNCERTAIN = "A node write lost its reply and may still complete.";

    private final com.eurobuddha.dexicon.NodeApi node;

    public NodeApi(com.eurobuddha.dexicon.NodeApi node) { this.node = node; }

    static boolean authorized(java.util.function.BooleanSupplier guard) {
        try { return guard != null && guard.getAsBoolean(); }
        catch (RuntimeException failure) { return false; }
    }

    public void cmd(String command, Cb cb) { cmd(command, () -> true, cb); }

    public void cmd(String command, java.util.function.BooleanSupplier authorized, Cb cb) {
        if (!authorized(authorized)) { if (cb != null) cb.onError(ERR_COMMAND_CHANGED); return; }
        node.cmd(command, () -> authorized(authorized), new com.eurobuddha.dexicon.NodeApi.Cb() {
            @Override public void onResult(JSONObject json) { if (cb != null) cb.onResult(json); }
            @Override public void onError(String message) { if (cb != null) cb.onError(message); }
        });
    }
}
