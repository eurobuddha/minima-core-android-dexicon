// VENDORED VERBATIM from apks/pandapools 0.9.89 app/src/main/java/com/eurobuddha/pandapools/PandaAddrBook.java by scripts/vendor-pandapools.sh — do not edit here; re-vendor.
package com.eurobuddha.pandapools;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Every pool covenant address this device has ever seen, lowercased, in BOTH hex (0x) and Mx forms.
 *
 * Lifted out of {@link ActivityView} (where it was a private field) because the accounting export needs
 * exactly the same set to tell a PandaPools transaction from a plain wallet one. Behaviour is unchanged:
 * grows on discovery + own pools, PERSISTED across sessions (a past swap on a pool that has since closed
 * must still match after a restart), and NEVER shrinks (it must remember a closed pool's address to keep
 * matching past swaps on it). Grows monotonically, but bounded by the pools this device ever touched —
 * trivial storage at realistic counts.
 *
 * Deliberately excludes the SENTINEL: creates already match via the covenant address, and matching the
 * sentinel would surface background re-announce dust beacons as noise. (The export detects those beacons
 * separately — see {@link TxClassifier}.)
 */
public final class PandaAddrBook implements TxClassifier.Addrs {

    private static final String PREFS = "pandapools_known_addrs";
    private static final String KEY = "addrs";

    private final Context ctx;
    private final Set<String> addrs = new HashSet<>();

    public PandaAddrBook(Context c) { this.ctx = c; }

    /** Load persisted addresses from prior sessions + our own pools' covenant addresses (both hex + Mx),
     *  so our own creates/closes match even before discovery runs this session. */
    public void seed() {
        Set<String> saved = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY, null);
        if (saved != null) addrs.addAll(saved);
        boolean added = false;
        for (Pool p : OwnPoolStore.all(ctx)) { added |= add(p.address); added |= add(p.mxaddress); }
        if (added) persist();
    }

    /** Add a lowercased address. Returns true if it was newly added. */
    public boolean add(String a) {
        if (a == null || a.isEmpty()) return false;
        return addrs.add(a.toLowerCase());
    }

    /** Add every address of every pool; returns true if any were new. Does NOT persist — call {@link #persist()}. */
    public boolean addAll(List<Pool> pools) {
        boolean added = false;
        for (Pool p : pools) { added |= add(p.address); added |= add(p.mxaddress); }
        return added;
    }

    public void persist() {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
           .putStringSet(KEY, new HashSet<>(addrs)).apply();
    }

    public boolean contains(String a) {
        return a != null && !a.isEmpty() && addrs.contains(a.toLowerCase());
    }

    public int size() { return addrs.size(); }

    /** True if any coin in the JSON array sits at a known pool address. */
    public boolean hits(String coinsJson) { return hitAddress(coinsJson) != null; }

    /**
     * WHICH known pool address this coin array touches (original case as stored on the coin), or null.
     * The export groups rows by pool with this; {@link ActivityView} only needs the boolean.
     */
    @Override public String hitAddress(String coinsJson) {
        if (coinsJson == null || coinsJson.isEmpty()) return null;
        try {
            JSONArray a = new JSONArray(coinsJson);
            for (int i = 0; i < a.length(); i++) {
                JSONObject c = a.optJSONObject(i);
                if (c == null) continue;
                String addr = c.optString("addr", "");
                if (!addr.isEmpty() && addrs.contains(addr.toLowerCase())) return addr;
            }
        } catch (Exception ignore) {}
        return null;
    }
}
