package com.eurobuddha.dexicon;

import android.content.Context;

import com.eurobuddha.minimaapi.MinimaAPIListener;

/**
 * Host-node mode (owner, 14 Nov 2026): Dexicon embedded in a Minima node app - Pandamonium - trading with the HOST
 * NODE's own wallet, in-process ("the direct plumbing of that build"). The host installs its link once, before any
 * Dexicon class runs; from then on:
 * <ul><li>every command goes straight to the node ({@link NodeApi#cmd}): no Trading Account, no second seed, no deposit -
 *     the node signs everything and picks every one-time key itself, so a key cannot be reused across apps;
 * <li>identities and the AtomiX ETH key come from the node's seed (seedrandom on the node, as Dexicon 0.2.x did);
 * <li>everything that only makes sense for a separate app is off: account setup, deposit/withdraw, word reveal, the
 *     trading gate against sibling apps, pairing/ADMIN screens, uninstall / clear-storage buttons.</ul>
 * Standalone Dexicon never calls {@link #install}, so {@link #on()} is false and nothing changes.
 */
public final class HostMode {
    private HostMode() {}

    /** Opens the host's link; {@code registration} gets {@code {status, enabled, admin}} as a node register reply. */
    public interface LinkFactory { NodeLink open(Context ctx, MinimaAPIListener registration); }

    private static volatile LinkFactory factory;
    private static volatile String hostName = "";

    /** Called once by the host app (before Dexicon's first use). */
    public static void install(LinkFactory linkFactory, String host) {
        if (linkFactory == null) throw new IllegalArgumentException("no link");
        factory = linkFactory; hostName = host == null ? "" : host;
    }

    /** True inside a host node app. */
    public static boolean on() { return factory != null; }

    /** The host app's name ("Pandamonium"), for texts. */
    public static String host() { return hostName; }

    /** The node link: the host's in-process link in host mode, else the IPC transport. */
    static NodeLink open(Context ctx, MinimaAPIListener registration) {
        LinkFactory f = factory;
        return f != null ? f.open(ctx, registration) : new NodeTransport(ctx, registration);
    }

    /** The host forwards node events (NEWBLOCK / NEWBALANCE) the IPC service would have rebroadcast. */
    public static void nodeEvent(Context ctx, String event) {
        if (!"NEWBLOCK".equals(event) && !"NEWBALANCE".equals(event)) return;
        ctx.sendBroadcast(new android.content.Intent(NodeTransportService.EVENT_ACTION).setPackage(ctx.getPackageName())
                .putExtra("event", event));
    }

    /** Host mode (review of Pandamonium 2.2.34, M3): a host user who never opened Dexicon gets no maker engine, no heartbeat
     *  and no relay traffic. Background work is allowed once the tab has been opened, or when trading data from the host's
     *  old built-in AtomiX / PandaPools is on disk (live swaps and pools must keep being served). Standalone: always. */
    public static boolean backgroundAllowed(Context c) {
        if (!on()) return true;
        if (c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("opened", false)) return true;
        java.io.File prefs = new java.io.File(c.getApplicationInfo().dataDir, "shared_prefs");
        return c.getDatabasePath("atomix.db").exists() || new java.io.File(prefs, "pandapools_ownpools.xml").exists();
    }

    /** The user opened Dexicon in the host: from now on its background work runs, as in the standalone app. */
    public static void markOpened(Context c) {
        if (on()) c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("opened", true).apply();
    }

    /** Standalone wording, or the host's (no pairing, no Apps screen: the node is the host itself). */
    public static String text(String standalone, String host) { return on() ? host : standalone; }

    private static final String PREFS = "dexicon_host";

    /** Test seam. */
    public static void reset() { factory = null; hostName = ""; }
}
