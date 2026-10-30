package com.eurobuddha.dexicon.identity;

import com.eurobuddha.dexicon.NodeApi;

import org.json.JSONObject;

/**
 * Security review 30 Oct (HIGH): Dexicon read the node's 24-word phrase and master seed (vault action:seed) to derive
 * its identities, so anything that ever compromised this app took the whole wallet, permanently. Identity input is now
 * a one-way node derivation, seedrandom modifier:&lt;m&gt; (the ETH key's own rule): the phrase never enters this app.
 * {@link #legacy} survives only to retire what the old identity signed, once.
 */
public final class SeedSource {
    private SeedSource() {}
    public static final String DEX = "dexicon-identity-v1", DOORS = "dexicon-doors-v1";

    public interface Cb { void got(String seedHex); void failed(String why); }

    /** One node command: either NodeApi (Dexicon's, or the vendored AtomiX comms one). */
    private interface Cmd { void run(String command, java.util.function.Consumer<JSONObject> ok, java.util.function.Consumer<String> err); }
    private static Cmd of(NodeApi n) { return (c, ok, err) -> n.cmd(c, new NodeApi.Cb() { public void onResult(JSONObject j) { ok.accept(j); } public void onError(String m) { err.accept(m); } }); }
    private static Cmd of(com.eurobuddha.comms.NodeApi n) { return (c, ok, err) -> n.cmd(c, new com.eurobuddha.comms.NodeApi.Cb() { public void onResult(JSONObject j) { ok.accept(j); } public void onError(String m) { err.accept(m); } }); }

    public static void derive(NodeApi node, String modifier, Cb cb) { derive(of(node), modifier, cb); }
    public static void derive(com.eurobuddha.comms.NodeApi node, String modifier, Cb cb) { derive(of(node), modifier, cb); }
    public static void legacy(NodeApi node, Cb cb) { legacy(of(node), cb); }
    public static void legacy(com.eurobuddha.comms.NodeApi node, Cb cb) { legacy(of(node), cb); }

    /** "0x" + 64 hex from seedrandom, or failed. */
    private static void derive(Cmd node, String modifier, Cb cb) {
        node.run("seedrandom modifier:" + modifier, j -> {
                JSONObject r = j.optJSONObject("response");
                String hex = r == null ? "" : r.optString("seedrandom", "").trim();
                if (hex.startsWith("0x") || hex.startsWith("0X")) hex = hex.substring(2);
                if (hex.matches("[0-9a-fA-F]{64}")) cb.got("0x" + hex); else cb.failed("the node returned no derived seed (vault locked?)");
        }, cb::failed);
    }

    /** MIGRATION ONLY: the old identity input (vault seed; the phrase is never used). Callers retire, then never call again. */
    private static void legacy(Cmd node, Cb cb) {
        node.run("vault action:seed", j -> {
                JSONObject r = j.optJSONObject("response");
                String seed = r == null ? "" : r.optString("seed", "");
                if (seed.isEmpty()) cb.failed("no seed"); else cb.got(seed);
        }, cb::failed);
    }
}
