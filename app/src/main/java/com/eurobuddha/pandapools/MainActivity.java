package com.eurobuddha.pandapools;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * DEXICON-OWNED SHIM (not vendored): PandaPools' host surface. The vendored pool screens (SwapView, PoolsView, MyLpView,
 * ActivityView) and helpers (CombinedBackupUi, WalletTools, ExportWriter …) take a PandaPools {@code MainActivity}; Dexicon's
 * own {@link com.eurobuddha.dexicon.MainActivity} extends this class, so they run inside Dexicon's ② Pools room unchanged.
 * Ported from apks/pandapools MainActivity: node()/history()/pools(), the file pickers, the per-block refresh and the
 * once-per-session boot (sentinel untrack, re-track my pools, foreign-tracking hygiene).
 *
 * Launched by itself (the stranding notification's "Open"), it forwards to Dexicon's ② My pools and closes.
 */
public class MainActivity extends AppCompatActivity {

    public static final int TAB_SWAP = 0, TAB_POOLS = 1, TAB_MYLP = 2, TAB_WALLET = 3, TAB_ACTIVITY = 4;
    /** Extra on the Dexicon launch intent: open this ② page (one of TAB_SWAP / TAB_POOLS / TAB_MYLP). */
    public static final String EXTRA_POOLS_PAGE = "pandapools_page";

    private NodeApi ppNode;
    private HistoryDb ppHistory;
    private PoolRepository ppRepo;
    private final Handler ppUi = new Handler(Looper.getMainLooper());
    private int ppBlock;
    private boolean retrackedOwn, untrackedSentinel, cleanedForeign;
    /** The vendored pool screens alive in this Activity (new block / stop / destroy fan out to them). */
    protected final List<BaseView> poolViews = new ArrayList<>();

    private ActivityResultLauncher<String> saveDocLauncher, saveCsvLauncher;
    private ActivityResultLauncher<String[]> openDocLauncher;
    private Consumer<Uri> pendingSave, pendingOpen, pendingCsv;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (getClass() == MainActivity.class) {   // the bare shim (a notification tap): open Dexicon on ② My pools
            startActivity(new Intent().setClassName(this, "com.eurobuddha.dexicon.MainActivity").putExtra(EXTRA_POOLS_PAGE, TAB_MYLP)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK));
            finish();
            return;
        }
        // Storage Access Framework pickers (pool backup save / restore, statement CSV) — registered before STARTED.
        saveDocLauncher = registerForActivityResult(new ActivityResultContracts.CreateDocument("application/octet-stream"), uri -> {
            Consumer<Uri> cb = pendingSave; pendingSave = null; if (cb != null) cb.accept(uri);
        });
        openDocLauncher = registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
            Consumer<Uri> cb = pendingOpen; pendingOpen = null; if (cb != null) cb.accept(uri);
        });
        saveCsvLauncher = registerForActivityResult(new ActivityResultContracts.CreateDocument("text/csv"), uri -> {
            Consumer<Uri> cb = pendingCsv; pendingCsv = null; if (cb != null) cb.accept(uri);
        });
    }

    /** Bring the pool engine up on Dexicon's ONE node connection (before any pool screen is built). */
    protected void initPools(com.eurobuddha.dexicon.NodeApi dex) {
        if (ppNode != null) return;
        ppNode = new NodeApi(dex);
        ppHistory = new HistoryDb(this);
        ppRepo = new PoolRepository(ppNode);
    }

    // ---- PandaPools host API (used by the vendored screens) ----
    public NodeApi node() { return ppNode; }
    public HistoryDb history() { return ppHistory; }
    public PoolRepository pools() { return ppRepo; }
    public Handler ui() { return ppUi; }
    public int chainBlock() { return ppBlock; }
    /** The PandaPools tab the user is looking at (Dexicon maps its ② pages); -1 when none is. */
    public int currentTab() { return -1; }
    public void markPaired(boolean enabled) { /* Dexicon's header owns pairing */ }
    /** Jump to a PandaPools tab; Dexicon maps it onto its ② pages. */
    public void goToTab(int index) { }

    public void pickSaveFile(String suggestedName, Consumer<Uri> onPicked) {
        pendingSave = onPicked;
        try { saveDocLauncher.launch(suggestedName); }
        catch (Exception e) { pendingSave = null; if (onPicked != null) onPicked.accept(null); }
    }
    public void pickSaveCsv(String suggestedName, Consumer<Uri> onPicked) {
        pendingCsv = onPicked;
        try { saveCsvLauncher.launch(suggestedName); }
        catch (Exception e) { pendingCsv = null; if (onPicked != null) onPicked.accept(null); }
    }
    public void pickOpenFile(Consumer<Uri> onPicked) {
        pendingOpen = onPicked;
        try { openDocLauncher.launch(new String[]{"application/json", "text/plain", "*/*"}); }
        catch (Exception e) { pendingOpen = null; if (onPicked != null) onPicked.accept(null); }
    }

    /** Dexicon's MakerService is always running; this keeps PandaPools' battery-exemption ask (weekly, LP nodes only). */
    public void ensureKeepAlive() {
        if (OwnPoolStore.all(this).isEmpty()) return;
        ensureNotificationPermissionNow();
        try { androidx.core.content.ContextCompat.startForegroundService(this, new Intent().setClassName(this, "com.eurobuddha.dexicon.maker.MakerService")); } catch (Exception ignored) {}
        try {
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
            if (pm == null || pm.isIgnoringBatteryOptimizations(getPackageName())) return;
            android.content.SharedPreferences sp = getSharedPreferences("pandapools", MODE_PRIVATE);
            long last = sp.getLong("batt_asked_at", 0), now = System.currentTimeMillis();
            if (last != 0 && now - last < 7L * 24 * 60 * 60_000L) return;
            sp.edit().putLong("batt_asked_at", now).apply();
            startActivity(new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName())));
        } catch (Exception ignored) {}
    }
    public boolean isBatteryExempt() {
        try {
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        } catch (Exception unknown) { return false; }
    }
    public void requestBatteryExemptionNow() {
        try { startActivity(new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName()))); }
        catch (Exception unavailable) {
            android.widget.Toast.makeText(this, "This device has no battery-optimisation screen. Exclude Dexicon from battery "
                    + "optimisation in Android Settings → Apps → Dexicon.", android.widget.Toast.LENGTH_LONG).show();
        }
    }
    public void ensureNotificationPermissionNow() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED)
                androidx.core.app.ActivityCompat.requestPermissions(this, new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 1);
        } catch (Exception ignored) {}
    }

    /** Repaint both transaction displays after the asynchronous node checks finish. */
    void confirmationsChanged() {
        if (isFinishing() || isDestroyed()) return;
        for (BaseView v : poolViews) {
            if (v instanceof ActivityView) v.refresh();
            if (v instanceof SwapView) ((SwapView) v).refreshConfirmations();
        }
    }

    // ---- driven by Dexicon's MainActivity ----

    /** Each chain-tip read: confirmations, and ONE shared pool scan per new block, multicast to every pool screen. */
    protected void poolsBlock(long block) {
        if (ppNode == null || block <= 0) return;
        ActivityLog.verify(this, ppNode, this::confirmationsChanged);
        if (block != ppBlock) {
            ppBlock = (int) block;
            ppRepo.refresh();
            for (BaseView v : poolViews) v.onNewBlock();
        }
    }

    /** Paired with the node: the PandaPools boot (once per session each). */
    protected void poolsPaired() {
        untrackSentinelOnce();
        retrackOwnPools();
        cleanForeignTracking();
        for (BaseView v : poolViews) v.refresh();
    }

    protected void poolsStop() { for (BaseView v : poolViews) v.onStop(); }

    protected void poolsDestroy() {
        for (BaseView v : poolViews) v.onDestroy();
        poolViews.clear();
    }

    protected boolean poolsBusy() {
        for (BaseView v : poolViews) if (v.isBusy()) return true;
        return false;
    }

    /** Close the pool engine with the Activity (the shared node connection belongs to Dexicon). */
    protected void poolsClose() {
        poolsDestroy();
        if (ppNode != null) ppNode.onDestroy();
        if (ppHistory != null) ppHistory.close();
    }

    // ---- ported from apks/pandapools MainActivity ----

    /** Stop tracking the unspendable PANDAPOOLS sentinel (pre-0.9.13 builds tracked it; its reply could kill the app). */
    private void untrackSentinelOnce() {
        if (untrackedSentinel || ppNode == null) return;
        untrackedSentinel = true;
        ppNode.cmd("coinnotify action:remove address:" + PoolCovenant.SENTINEL, new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {}
            @Override public void onError(String m) {}
        });
    }

    /** Layer 2: re-register my pools' covenants so a re-synced node tracks them again; forward funds stranded at owner
     *  addresses. A pool another app still keeps (AWAITING HAND-OVER) is left to that app. */
    private void retrackOwnPools() {
        if (retrackedOwn || ppNode == null) return;
        retrackedOwn = true;
        final List<Pool> own = Handover.keepable(this, OwnPoolStore.all(this));
        final List<String> oadrs = new ArrayList<>();
        for (Pool p : own) if (p.oadr != null && !p.oadr.isEmpty()) oadrs.add(p.oadr);
        new PoolManager(ppNode).sweepOwnerFunds(oadrs, (addressesForwarded, coins, error) -> {
            if (addressesForwarded > 0 && ppRepo != null) ppRepo.refresh();
        });
        for (Pool p : own) {
            if (p.covenantScript == null || p.covenantScript.isEmpty()) continue;
            PoolManager.retrack(ppNode, p, new NodeApi.Cb() {
                @Override public void onResult(JSONObject j) { if (ppRepo != null) ppRepo.refresh(); }
                @Override public void onError(String m) {}
            });
        }
    }

    /** Demote every tracked FOREIGN pool covenant to trackall:false (a stranger's reserves out of my balance). */
    private void cleanForeignTracking() {
        if (cleanedForeign || ppNode == null) return;
        cleanedForeign = true;
        ppNode.cmd("keys", new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                final Set<String> myKeys = parseKeysLower(j);
                if (myKeys.isEmpty()) return;   // can't prove ownership → zero writes
                final Set<String> ownAddrs = new HashSet<>(), ownOpks = new HashSet<>();
                for (Pool p : OwnPoolStore.all(MainActivity.this)) {
                    if (p.address != null && !p.address.isEmpty()) ownAddrs.add(p.address.toLowerCase());
                    if (p.opk != null && !p.opk.isEmpty()) ownOpks.add(p.opk.toLowerCase());
                }
                ppNode.cmd("scripts", new NodeApi.Cb() {
                    @Override public void onResult(JSONObject sj) {
                        List<TrackHygiene.Row> foreign = TrackHygiene.classifyForeign(sj.optJSONArray("response"), myKeys, ownAddrs, ownOpks);
                        if (!foreign.isEmpty()) demoteForeign(foreign, 0);
                    }
                    @Override public void onError(String m) {}
                });
            }
            @Override public void onError(String m) {}
        });
    }

    private static Set<String> parseKeysLower(JSONObject j) {
        Set<String> out = new HashSet<>();
        Object resp = j == null ? null : j.opt("response");
        JSONArray arr = null;
        if (resp instanceof JSONArray) arr = (JSONArray) resp;
        else if (resp instanceof JSONObject) arr = ((JSONObject) resp).optJSONArray("keys");
        if (arr != null) for (int i = 0; i < arr.length(); i++) {
            JSONObject k = arr.optJSONObject(i);
            if (k != null) { String pk = k.optString("publickey", ""); if (!pk.isEmpty()) out.add(pk.toLowerCase()); }
        }
        return out;
    }

    private boolean savedOwner(TrackHygiene.Row row) {
        for (Pool p : OwnPoolStore.all(this)) if (row.address.equalsIgnoreCase(p.address)
                || (p.opk != null && row.script.contains(p.opk))) return true;
        return false;
    }

    private void demoteForeign(List<TrackHygiene.Row> foreign, int i) {
        if (i >= foreign.size()) { untrackNextAddress(foreign, 0, false); return; }
        TrackHygiene.Row r = foreign.get(i);
        if (!r.track || savedOwner(r)) { demoteForeign(foreign, i + 1); return; }
        ppNode.cmd("newscript trackall:false script:" + Util.scriptArg(r.script), new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) { demoteForeign(foreign, i + 1); }
            @Override public void onError(String m) { demoteForeign(foreign, i + 1); }
        });
    }

    /** ONE bounded query per foreign address, sequential — never an unbounded `coins relevant:true`. */
    private void untrackNextAddress(List<TrackHygiene.Row> foreign, int i, boolean touched) {
        if (i >= foreign.size()) { if (touched && ppRepo != null) ppRepo.refresh(); return; }
        if (savedOwner(foreign.get(i))) { untrackNextAddress(foreign, i + 1, touched); return; }
        final String addr = foreign.get(i).address;
        final Set<String> one = new HashSet<>();
        one.add(addr.toLowerCase());
        ppNode.cmd("coins relevant:true address:" + addr, new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                List<String> ids = TrackHygiene.coinsToUntrack(j.optJSONArray("response"), one);
                untrackNextCoin(foreign.get(i), ids, 0, () -> untrackNextAddress(foreign, i + 1, touched || !ids.isEmpty()));
            }
            @Override public void onError(String m) { untrackNextAddress(foreign, i + 1, touched); }
        });
    }

    private void untrackNextCoin(TrackHygiene.Row row, List<String> coinids, int i, Runnable then) {
        if (i >= coinids.size() || savedOwner(row)) { then.run(); return; }
        ppNode.cmd("cointrack enable:false coinid:" + coinids.get(i), new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) { untrackNextCoin(row, coinids, i + 1, then); }
            @Override public void onError(String m) { untrackNextCoin(row, coinids, i + 1, then); }
        });
    }
}
