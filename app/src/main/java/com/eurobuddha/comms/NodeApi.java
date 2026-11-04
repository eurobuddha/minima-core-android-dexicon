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

    /**
     * Dexicon's pool owner-coin seam (0.1.78): the vendored engine never spends a ② pool owner-address coin (it signs with
     * the pool's one-time owner key). A wallet coin read ({@code coins … relevant:true}) comes back without owner-address
     * rows — so the engine's explicit inputs (myFreeCoins → lockFromCoins) can never name one; a NODE-PICKED spend
     * ({@code send}: the HTLC lock, the split, comms posts, the door forward; {@code consolidate}) first passes PandaPools'
     * owner-key guard for that token. Both fail closed when the pool recipes cannot be read.
     */
    public void cmd(String command, java.util.function.BooleanSupplier authorized, Cb cb) {
        if (!authorized(authorized)) { if (cb != null) cb.onError(ERR_COMMAND_CHANGED); return; }
        String verb = verb(command);
        if (verb.equals("send") || verb.equals("consolidate")) {
            com.eurobuddha.pandapools.DexiconPools.guardNodePickedSpend(node, tokenOf(command), why -> {
                if (why != null) { if (cb != null) cb.onError(why); return; }
                dispatch(command, authorized, cb, false);
            });
            return;
        }
        dispatch(command, authorized, cb, verb.equals("coins") && command.contains("relevant:true"));
    }

    private void dispatch(String command, java.util.function.BooleanSupplier authorized, Cb cb, boolean walletCoins) {
        if (walletCoins && com.eurobuddha.pandapools.DexiconPools.ownerAddresses(node.context()) == null) {
            if (cb != null) cb.onError(com.eurobuddha.pandapools.DexiconPools.OWNERS_UNREADABLE); return;
        }
        node.cmd(command, () -> authorized(authorized), new com.eurobuddha.dexicon.NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                if (cb == null) return;
                if (!walletCoins) { cb.onResult(json); return; }
                JSONObject kept = com.eurobuddha.pandapools.DexiconPools.withoutOwnerCoins(node.context(), json);
                if (kept == null) cb.onError(com.eurobuddha.pandapools.DexiconPools.OWNERS_UNREADABLE); else cb.onResult(kept);
            }
            @Override public void onError(String message) { if (cb != null) cb.onError(message); }
        });
    }

    static String verb(String command) { return command == null ? "" : command.trim().split("\\s+", 2)[0]; }
    /** The token a node-picked spend draws on: its {@code tokenid:} (Minima's default for both verbs is 0x00). */
    static String tokenOf(String command) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?:^|\\s)tokenid:(\\S+)").matcher(command);
        return m.find() ? m.group(1) : "0x00";
    }
}
