// VENDORED VERBATIM from apks/pandapools 0.9.84 app/src/main/java/com/eurobuddha/pandapools/ExitAuthority.java by scripts/vendor-pandapools.sh — do not edit here; re-vendor.
package com.eurobuddha.pandapools;

import java.util.Locale;

/**
 * Holds the ONE outstanding exit exemption, in memory, for as long as a single signature needs it.
 *
 * Three properties, each chosen against a specific way this could go wrong.
 *
 * NEVER PERSISTED. A crash, a kill, a relaunch — none of them can leave a standing exemption behind. If the
 * process dies mid-exit there is nothing on disk that would let the next launch sign without re-deciding from
 * scratch. {@link ExitStore} does persist a record of what was attempted, for idempotency and audit, but it
 * grants no authority: the guards never read it.
 *
 * AT MOST ONE AT A TIME. Not a map. {@link RestoreExit} posts strictly serially, so a second concurrent
 * exemption would mean a bug, and making that structurally impossible is cheaper than detecting it. "There is
 * never more than one outstanding exemption" is then a property you can read off this file.
 *
 * SINGLE USE. {@link #consume} clears the grant, so one grant admits one signature. An exit needs two, and
 * {@link RestoreExit} grants the second explicitly for the forward stage — so even a bug that re-ran a signing
 * chain could not spend a third leaf on the back of one decision.
 *
 * The guards call {@link #permits} and nothing else. They do not receive a ticket, cannot be handed one, and
 * cannot be told to skip the check: authority is looked up, never passed in.
 */
final class ExitAuthority {

    private static ExitTicket granted;
    private static long grantedAt;

    /** An exemption expires on its own, so a wedged chain cannot leave one open indefinitely. */
    private static final long TTL_MS = 4 * 60 * 1000L;

    private ExitAuthority() {}

    /** Grant the single outstanding exemption. Replaces any previous grant, which by design cannot exist. */
    static synchronized void grant(ExitTicket t) {
        granted = t;
        grantedAt = System.currentTimeMillis();
    }

    /** Do not replace another in-flight recovery's authority. */
    static synchronized boolean tryGrant(ExitTicket t) {
        if (t == null || current() != null) return false;
        grant(t);
        return true;
    }

    /** A late callback cannot revoke a newer operation's ticket. */
    static synchronized void revoke(ExitTicket expected) {
        if (granted == expected) revoke();
    }

    /** Drop the grant. Safe to call when there is none — every exit path calls it. */
    static synchronized void revoke() {
        granted = null;
        grantedAt = 0;
    }

    /** The live grant, or null when there is none or it has aged out. */
    static synchronized ExitTicket current() {
        if (granted != null && System.currentTimeMillis() - grantedAt > TTL_MS) revoke();
        return granted;
    }

    /**
     * Whether the outstanding exemption authorises this exact transaction for this exact pool. Consumes the
     * grant on a match, so it cannot admit a second signature.
     */
    static synchronized boolean permits(java.util.List<String> cmds, Pool p) {
        ExitTicket t = current();
        if (t == null || p == null) return false;
        if (!t.permits(cmds, p)) return false;
        revoke();
        return true;
    }

    /** True when an exemption is outstanding for this covenant address — for status text only, never authority. */
    static synchronized boolean pendingFor(String address) {
        ExitTicket t = current();
        return t != null && address != null && t.address.equals(address.toLowerCase(Locale.ROOT));
    }
}
