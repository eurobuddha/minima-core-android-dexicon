// VENDORED VERBATIM from apks/pandapools 0.9.87 app/src/main/java/com/eurobuddha/pandapools/MyLpView.java by scripts/vendor-pandapools.sh — do not edit here; re-vendor.
package com.eurobuddha.pandapools;

import android.app.AlertDialog;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.List;
import java.util.Set;

/**
 * The "My LP" tab: the liquidity-provider cockpit. Discovers every pool, keeps the ones this node OWNS
 * (its owner key is in the node keystore), and for each shows live reserves, holdings value, accrued-fee
 * growth and a KMIN-health meter — with one-tap Add / Migrate / Close and a Create-pool flow.
 */
public class MyLpView extends BaseView {

    private static final MathContext MC = new MathContext(30, RoundingMode.DOWN);
    private static final BigDecimal TWO = new BigDecimal("2");
    /** mxUSDT — the one pair with a live MEXC market feed, so its create price is forced to the market. */
    private static final String USDT_TOKENID = "0x7D39745FBD29049BE29850B55A18BF550E4D442F930F86266E34193D89042A90";

    private final PoolManager mgr;
    private final Recovery recovery;
    private final ReAnnouncer reannouncer;
    private final PoolRefresher refresher;
    private final Set<String> myKeys = new HashSet<>();
    private final List<Pool> myPools = new ArrayList<>();   // currently-owned funded pools (for fresh coin export)
    private boolean busy = false;
    private boolean reannounceChecked = false;              // Layer 5 faded-beacon check runs once per session
    private boolean refreshChecked = false;
    private boolean prooflessDismissed = false;             // "add coin proofs" offer dismissed for this session                 // keep-fresh reserve check runs once per session
    private BigDecimal usdtAnchor;                          // tier-2 create anchor: aggregate live USDT-pool spot (Σt/Σm), null if none

    // Receives the shared PoolRepository's scan result (one scan for the whole app). render() filters to the
    // pools this node owns and drives the pending-create lifecycle.
    private final PoolBook.Listener poolListener = new PoolBook.Listener() {
        // Skip the per-block repaint WHILE a create/deposit/migrate/close is in flight — otherwise a block
        // landing mid-transaction wipes the "Creating…" status (and can flash "No pools yet" before
        // pendingCreate is armed), the worry-and-resubmit loop the pending-create lifecycle prevents. Each
        // transaction completion re-scans with busy=false, so no update is lost.
        @Override public void onPools(List<Pool> pools) { if (busy) return; render(pools); }
        @Override public void onError(String msg) { status("Scan error: " + msg); }
    };
    // A create that's been posted but not yet discovered on-chain — drives the persistent "confirming…"
    // card (so we never flash "No pools yet"), the duplicate-create guard, and a fast self-poll so the
    // pool paints itself the moment it's live (no tab-switch needed).
    private Pool pendingCreate;
    private int pendingBlock;
    private String pendingLabel = "";
    private boolean pendingPolling = false;
    private boolean stopped = false;   // true while backgrounded — blocks the fast pending-poll from re-arming

    public MyLpView(MainActivity a) {
        super(a, R.layout.view_mylp);
        a.pools().subscribe(poolListener);
        mgr = new PoolManager(a.node());
        recovery = new Recovery(a.node());
        reannouncer = new ReAnnouncer(a.node());
        refresher = new PoolRefresher(a.node());

        Ui.card(find(R.id.lpSummaryCard));
        TextView create = find(R.id.lpCreateBtn);
        Ui.primaryButton(create);
        create.setOnClickListener(v -> showCreateDialog());
        TextView recoveryBtn = find(R.id.lpRecoveryBtn);
        Ui.outlineButton(recoveryBtn);
        recoveryBtn.setTextColor(Design.accent());
        recoveryBtn.setOnClickListener(v -> showRecoveryDialog());
        TextView statementBtn = find(R.id.lpStatementBtn);
        Ui.outlineButton(statementBtn);
        statementBtn.setTextColor(Design.accent());
        statementBtn.setOnClickListener(v -> showStatementDialog());
        TextView calcBtn = find(R.id.lpCalcBtn);
        Ui.outlineButton(calcBtn);
        calcBtn.setTextColor(Design.accent());
        calcBtn.setOnClickListener(v -> PoolCalcDialog.show(act, null));

        ((TextView) find(R.id.lpSummaryLabel)).setTextColor(Design.dim());
        ((TextView) find(R.id.lpSummaryValue)).setTextColor(Design.heading());
        ((TextView) find(R.id.lpSummarySub)).setTextColor(Design.dim());
        ((TextView) find(R.id.lpStatus)).setTextColor(Design.dim());

        loadKeys();
    }

    @Override public void refresh() { act.pools().requestNow(poolListener); }
    @Override public void onShown() { stopped = false; loadKeys(); act.pools().requestNow(poolListener); if (pendingCreate != null) startPendingPoll(); }
    @Override public void onStop() { stopped = true; stopPendingPoll(); }
    @Override public boolean isBusy() { return busy; }
    @Override public void onDestroy() { stopPendingPoll(); act.pools().unsubscribe(poolListener); }

    // ---- ownership ----

    private void loadKeys() {
        if (act.node() == null) return;
        act.node().cmd("keys", new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                myKeys.clear();
                Object resp = j.opt("response");
                JSONArray arr = null;
                if (resp instanceof JSONArray) arr = (JSONArray) resp;
                else if (resp instanceof JSONObject) arr = ((JSONObject) resp).optJSONArray("keys");
                if (arr != null) for (int i = 0; i < arr.length(); i++) {
                    JSONObject k = arr.optJSONObject(i);
                    if (k != null) {
                        String pk = k.optString("publickey", k.optString("miniaddress", ""));
                        if (!pk.isEmpty()) myKeys.add(pk.toLowerCase());
                    }
                }
                act.pools().requestNow(poolListener);
            }
            @Override public void onError(String m) { act.pools().requestNow(poolListener); }
        });
    }

    private boolean mine(Pool p) { return p.opk != null && myKeys.contains(p.opk.toLowerCase()); }

    // ---- discovery (shared scan — see poolListener) ----

    private void render(List<Pool> all) {
        List<Pool> mine = new ArrayList<>();
        for (Pool p : all) if (mine(p)) {
            if (ReserveRecovery.completeReserves(p)) mine.add(p);
            // backfill a recovery recipe for an owned pool the first time we see it (e.g. one created
            // before this feature). Only when missing, so the exact create/migrate script is never
            // clobbered by a reconstructed one.
            // Record the recipe — that is the valuable half — but do NOT claim provenance for it.
            //
            // 0.9.58 asserted newlyCreatedWithCurrentOwnerState here, reasoning that mine(p) proves the pool
            // is ours. It proves the node's wallet HOLDS the key. It does not prove this install holds the
            // NEWEST COUNTER, and those two are indistinguishable from here. A wallet restored from seed with
            // `keys:<n>` and no `keyuses:` re-creates $OPK at uses = 0, mine(p) goes true, and clearing the
            // hold let PoolRefresher's keep-fresh sign UNATTENDED at a leaf the dead device already spent —
            // reuse, which leaks the key. A scanner-built Pool also carries minimumOwnerUses = -1, so
            // `uses >= p.minimumOwnerUses` is trivially true and the floor catches nothing either.
            // Erring high costs one amber card. Erring low costs the pool. Let the hold stand.
            if (OwnPoolStore.script(act, p.address) == null) OwnPoolStore.record(act, p);
            // A pool we can see live is not closed, whatever a posted-but-unlanded close once assumed.
            if (p.address != null) OwnPoolStore.setRetired(act, p.address, false);
        }
        myPools.clear(); myPools.addAll(mine);   // for a backup's fresh coinexport (these carry live coinids)
        List<Pool> unavailable = new ArrayList<>();
        Set<String> visible = new HashSet<>();
        for (Pool p : mine) if (p.address != null) visible.add(p.address.toLowerCase());
        // Only once ownership is actually known. `myKeys` loads asynchronously and `requestNow` repaints
        // from cache first, so an early render has mine(p) false for EVERYTHING — which would put every
        // recipe in `unavailable` and raise a screenful of alarms about perfectly healthy pools. Same
        // fail-closed rule as TrackHygiene and MainActivity: no keys, no conclusions.
        if (!myKeys.isEmpty()) {
            for (Pool p : OwnPoolStore.active(act)) if (!visible.contains(p.address.toLowerCase())) unavailable.add(p);
        }
        maybeReannounce(all);                     // Layer 5 gossip: refresh faded beacons for ALL funded pools (once/session)
        maybeRefresh(mine);                       // keep-fresh: recreate MY aging reserves before they leave the cascade

        // tier-2 create anchor: the aggregate spot of ALL live USDT pools (Σ reserveT / Σ reserveM). Arbitrage
        // keeps this near true market, so a new pool opened at it sits in line with the on-chain market. Used
        // to price a create when MEXC is unreachable.
        BigDecimal aggM = BigDecimal.ZERO, aggT = BigDecimal.ZERO;
        for (Pool p : all) if (p != null && p.funded() && USDT_TOKENID.equalsIgnoreCase(p.tok)) {
            aggM = aggM.add(p.reserveM); aggT = aggT.add(p.reserveT);
        }
        usdtAnchor = aggM.signum() > 0 ? aggT.divide(aggM, MC) : null;

        // Has a pending create just gone live (discovered with funded reserves)? Announce it + stop tracking.
        boolean pendingLive = false;
        if (pendingCreate != null) {
            for (Pool p : mine) if (p.address != null && p.address.equalsIgnoreCase(pendingCreate.address)) { pendingLive = true; break; }
            if (pendingLive) {
                status("Pool live ✓   MINIMA / " + pendingLabel + " is now earning fees.");
                pendingCreate = null; stopPendingPoll();
            } else if (act.chainBlock() > 0 && act.chainBlock() - pendingBlock > 20) {
                // Safety release: never let a stuck pending-create block re-creates forever (funds are safe —
                // the covenant parse-guard means nothing moved unless it compiled; a real pool shows via normal
                // discovery). Drop the tracking so the create-guard frees up.
                pendingCreate = null; stopPendingPoll();
                status("Your pool hasn't appeared after ~20 blocks — it may just be slow. Switch tabs to refresh, "
                        + "or check the Activity tab. You can create again if needed.");
            }
        }

        LinearLayout list = find(R.id.lpList);
        list.removeAllViews();
        TextView value = find(R.id.lpSummaryValue), sub = find(R.id.lpSummarySub);

        // A create still confirming → a persistent amber card at the top (never "No pools yet" while pending).
        if (pendingCreate != null) list.addView(confirmingCard());
        for (PendingCollect.Entry pc : PendingCollect.all(act)) list.addView(pendingCollectCard(pc));
        for (ExitStore.Record r : ExitStore.interrupted(act)) list.addView(interruptedExitCard(r));
        if (!BackupState.upToDate(act)) list.addView(backupCard());
        else { View proofs = prooflessCard(mine); if (proofs != null) list.addView(proofs); }
        // ONE card per pool. These used to be two separate loops — one keyed by covenant address, one by
        // owner key — so a single pool produced two cards showing two different bare 0x… values with no
        // label, and a user with two pools counted four problems. Both states now live on one card.
        Set<String> carded = new HashSet<>();
        for (Pool p : unavailable) if (p.address != null && carded.add(p.address.toLowerCase())) list.addView(recoveryCard(p));
        if (!myKeys.isEmpty()) {
            Set<String> heldKeys = new HashSet<>();
            for (Pool p : OwnPoolStore.active(act)) {
                if (p.address == null || p.opk == null) continue;
                if (!p.signingStateUnverified && !OwnPoolStore.confirmationFailed(p.opk)) continue;
                if (carded.contains(p.address.toLowerCase())) continue;          // already said on its recovery card
                if (!heldKeys.add(p.opk.toLowerCase(java.util.Locale.ROOT))) continue;  // one card per key, case-insensitively
                list.addView(signingPausedCard(p));
            }
        }
        if (!OwnPoolStore.retired(act).isEmpty()) list.addView(retiredCard());

        if (mine.isEmpty()) {
            if (pendingCreate != null) {
                value.setText("Confirming…");
                sub.setText("Your new pool is being written on-chain.");
                // keep the "Submitted ✓ … confirming" status — do NOT wipe it
            } else if (!unavailable.isEmpty()) {
                value.setText("Reserves unavailable");
                sub.setText(unavailable.size() + " saved pool(s) need a live reserve check.");
                // This branch never cleared the status, so the layout's initial "Loading your pools…"
                // stayed on screen for good — above cards that had already finished loading and were
                // telling the user something was wrong. Saying "loading" after loading finished is how a
                // transient state gets read as a permanent fault.
                status("");
            } else {
                value.setText("No pools yet");
                sub.setText(myKeys.isEmpty() ? "Pair the node to see your liquidity."
                        : "Create a pool to start earning the swap fee.");
                if (!pendingLive) status("");
            }
            return;
        }
        BigDecimal totalMinima = BigDecimal.ZERO;
        for (Pool p : mine) totalMinima = totalMinima.add(p.reserveM);
        value.setText(trim(totalMinima) + " MINIMA reserves");
        sub.setText(mine.size() + " visible pool(s) · token reserves shown below"
                + (unavailable.isEmpty() ? "" : " · " + unavailable.size() + " unavailable"));
        if (pendingCreate == null && !pendingLive) status("");
        for (Pool p : mine) list.addView(lpCard(p));
    }

    /** Both identifiers, always labelled. Two bare 0x… values on two cards is what made one pool look
     *  like two problems — an address and an owner key are indistinguishable as raw hex. Full, selectable,
     *  never abbreviated: a truncated identifier cannot be pasted into an explorer. */
    private void identifiers(LinearLayout card, Pool p) {
        TextView ids = new TextView(act);
        ids.setText("Pool: " + p.address + (p.opk == null || p.opk.isEmpty() ? "" : "\nOwner key: " + p.opk));
        ids.setTextColor(Design.dim());
        ids.setTextIsSelectable(true);
        card.addView(ids);
    }

    private View signingPausedCard(Pool p) {
        LinearLayout card = dialogBox(); Ui.card(card);
        card.addView(text("Owner signing paused", Design.amber(), 16, true));
        identifiers(card, p);
        TextView detail = new TextView(act);
        detail.setText("PandaPools cannot tell how many of this owner key's one-time signatures have already "
                + "been used, so it has stopped signing for this pool. Automatic refresh is paused too.");
        card.addView(detail);
        // Deliberately NOT styled like the read-only actions. Confirming enables unlimited owner signing
        // for this key: if this device is not the one holding the newest counter, that is signature reuse.
        Button confirm = new Button(act);
        confirm.setText("Confirm wallet signing state");
        confirm.setTextColor(Design.red());
        confirm.setOnClickListener(v -> confirmSigningState(p.opk));
        card.addView(confirm);
        return card;
    }

    /** Closed and migrated-away pools are hidden, never deleted — this is how the user gets them back. */
    private View retiredCard() {
        LinearLayout card = dialogBox(); Ui.card(card);
        List<Pool> gone = OwnPoolStore.retired(act);
        card.addView(text(gone.size() + " closed pool(s) kept for recovery", Design.dim(), 14, false));
        TextView note = new TextView(act);
        note.setText("Their recipes are still saved and still go into your backups — they are just out of the way. "
                + "If a close never actually landed, the pool reappears here on its own.");
        note.setTextColor(Design.dim());
        card.addView(note);
        Button show = new Button(act); show.setText("Show closed pools");
        show.setOnClickListener(v -> {
            StringBuilder b = new StringBuilder();
            for (Pool p : gone) b.append("Pool: ").append(p.address)
                    .append(p.opk == null || p.opk.isEmpty() ? "" : "\nOwner key: " + p.opk).append("\n\n");
            new AlertDialog.Builder(act).setTitle("Closed pools").setMessage(b.toString().trim())
                    .setNeutralButton("Bring back", (d, w) -> {
                        for (Pool p : gone) OwnPoolStore.setRetired(act, p.address, false);
                        status("Closed pools are showing again."); act.pools().refresh();
                    })
                    .setPositiveButton("Close", null).show();
        });
        card.addView(show);
        return card;
    }

    private View recoveryCard(Pool p) {
        LinearLayout card = dialogBox();
        Ui.card(card);
        boolean held = p.signingStateUnverified || OwnPoolStore.confirmationFailed(p.opk);
        card.addView(text(mine(p) ? "Saved pool · reserves not found" : "Saved pool · owner key missing", Design.dim(), 16, true));
        identifiers(card, p);
        TextView note = new TextView(act);
        note.setText((mine(p)
                ? "This node cannot currently see both reserve coins. It may still be syncing, or the pool may have closed.\n\n"
                : "This pool is saved, but its owner key has not been recovered. Its reserves may still be visible in Pools.\n\n")
                + (mine(p) ? "The owner key for it is on this device."
                           : "The owner key is not loaded on this device. Restore your pool file on the original wallet to recover it.")
                + (held ? " Owner signing is paused for it — see below." : ""));
        card.addView(note);
        Button recover = new Button(act); recover.setText("Check for reserves"); card.addView(recover);
        Button close = new Button(act); close.setText("It's closed — put it away");
        close.setOnClickListener(v -> {
            OwnPoolStore.setRetired(act, p.address, true);
            status("Put away. The recipe is still saved and still goes into your backups.");
            act.pools().refresh();
        });
        card.addView(close);
        if (held) {
            Button confirm = new Button(act);
            confirm.setText("Confirm wallet signing state");
            confirm.setTextColor(Design.red());
            confirm.setOnClickListener(v -> confirmSigningState(p.opk));
            card.addView(confirm);
        }
        recover.setOnClickListener(v -> {
            if (busy) return;
            try {
                JSONObject root = new JSONObject().put("pandapools_backup", 3)
                        .put("pools", new JSONArray().put(Recovery.baseEntry(p)));
                restoreJson(root.toString());
            } catch (Exception e) { status("Could not read the saved pool recipe."); }
        });
        return card;
    }

    /** A persistent card for a create that's posted but not yet discovered — reassures during the ~3-block
     *  wait so the user never assumes it failed and re-submits (the loop that stranded funds before). */
    private View confirmingCard() {
        LinearLayout card = new LinearLayout(act);
        card.setOrientation(LinearLayout.VERTICAL);
        Ui.card(card);
        int pad = Ui.dp(act, 15);
        card.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dp(act, 12);
        card.setLayoutParams(lp);

        int el = Math.max(0, act.chainBlock() - pendingBlock);
        card.addView(text("MINIMA / " + pendingLabel + "   ·   confirming…", Design.amber(), 16, true));
        String st = el <= 3 ? ("Writing on-chain — block " + el + " of ~3")
                            : ("Writing on-chain — " + el + " blocks (taking a little longer than usual)");
        card.addView(kv("Status", st));
        if (pendingCreate.reserveM != null && pendingCreate.reserveT != null)
            card.addView(kv("Seeding", trim(pendingCreate.reserveM) + " MINIMA  +  " + trim(pendingCreate.reserveT) + " " + pendingLabel));
        card.addView(kv("", "It will appear here automatically — no need to resubmit.  (Tap to dismiss.)"));
        // Escape hatch: dismissing clears the create-guard so a stalled node can never wedge new creates.
        card.setOnClickListener(v -> { pendingCreate = null; stopPendingPoll(); status(""); act.pools().requestNow(poolListener); });
        return card;
    }

    private void startPendingPoll() {
        if (pendingPolling || stopped) return;   // never arm while backgrounded (chainBlock is frozen → no escape)
        pendingPolling = true;
        act.ui().postDelayed(pendingPollTask, 9000);
    }
    private void stopPendingPoll() {
        pendingPolling = false;
        act.ui().removeCallbacks(pendingPollTask);
    }
    private final Runnable pendingPollTask = new Runnable() {
        @Override public void run() {
            pendingPolling = false;
            if (stopped || pendingCreate == null) return;       // backgrounded → stop (onShown re-arms)
            if (act.chainBlock() - pendingBlock > 12) return;   // give up the FAST poll; the block-poll keeps trying
            if (!busy) act.pools().refresh();                   // shared scan → render resolves + repaints the pool
            if (pendingCreate != null && !stopped) { pendingPolling = true; act.ui().postDelayed(this, 9000); }
        }
    };

    // ---- a single owned-pool card ----

    private View lpCard(Pool p) {
        LinearLayout card = new LinearLayout(act);
        card.setOrientation(LinearLayout.VERTICAL);
        Ui.card(card);
        int pad = Ui.dp(act, 15);
        card.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dp(act, 12);
        card.setLayoutParams(lp);

        card.addView(text("MINIMA / " + p.tokenLabel(), Design.heading(), 16, true));
        card.addView(kv("Your liquidity", trim(p.reserveM) + " MINIMA  +  " + trim(p.reserveT) + " " + p.tokenLabel()));
        int oldest = p.reserveBlockM > 0 && p.reserveBlockT > 0 ? Math.min(p.reserveBlockM, p.reserveBlockT) : 0;
        // Unknown age (oldest == 0) is NOT stale — same rule StrandingWatch follows. Warning about a pool
        // whose reserve blocks simply have not been read yet is how a warning gets trained out of a user.
        if (oldest > 0 && act.chainBlock() - oldest > PoolRefresher.REFRESH_BLOCKS)
            card.addView(text("Reserve proofs need attention. Older reserves may require fresh archive proofs after a node reset; keep a current wallet backup and pool recipe.", Design.amber(), 13, false));

        BigDecimal value = p.reserveM.multiply(TWO);
        card.addView(kv("Value now", "≈ " + trim(value) + " MINIMA"));
        card.addView(kv("Pool price", trim8(p.spotPrice()) + " " + p.tokenLabel() + " / MINIMA"));

        // fees earned: LP value grows ∝ √(K) at constant price, and K grows only from fees, so the fee share
        // of the position is (1 − 1/√(K/Kbase)). Kbase is the product at the LAST liquidity change (from the
        // snapshot, reset on each deposit) so a deposit isn't mistaken for earnings; falls back to KMIN when
        // no snapshot exists (a pool this install didn't open).
        LpStore.Snapshot snap = LpStore.get(act, p.address);
        BigDecimal feeBase = (snap != null && snap.feeBaseK.signum() > 0) ? snap.feeBaseK : Util.decOr(p.kmin, BigDecimal.ZERO);
        double growth = 0;
        if (feeBase.signum() > 0) growth = Math.max(0, p.k().divide(feeBase, MC).subtract(BigDecimal.ONE).doubleValue());
        double feeMult = Math.sqrt(1.0 + growth);
        double feesMinima = value.doubleValue() * (1.0 - 1.0 / feeMult);
        card.addView(kvColored("Fees earned", "≈ " + fmt(feesMinima) + " MINIMA  (+" + fmt(growth * 100.0) + "%)", Design.success()));

        // impermanent loss + price move vs the opening snapshot (if this app opened the pool)
        if (snap != null && snap.initPrice.signum() > 0 && p.spotPrice().signum() > 0) {
            double p0 = snap.initPrice.doubleValue(), pnow = p.spotPrice().doubleValue();
            double r = pnow / p0;
            double priceMove = (r - 1.0) * 100.0;
            double il = (2.0 * Math.sqrt(r) / (1.0 + r) - 1.0) * 100.0;   // ≤ 0, price-divergence loss vs holding
            card.addView(kv("Price since open", (priceMove >= 0 ? "+" : "") + fmt(priceMove) + "%"));
            card.addView(kvColored("Impermanent loss", fmt(il) + "%  vs holding", il < -0.01 ? Design.amber() : Design.dim()));
            int ageBlocks = act.chainBlock() - snap.block;
            if (ageBlocks > 0) card.addView(kv("Age", ageBlocks + " blocks  (~" + fmt(ageBlocks * 50.0 / 3600.0) + " h)"));
        }

        // KMIN health: ratio K / KMIN, 1.0 fresh → 2.0 = must migrate
        BigDecimal kmin = Util.decOr(p.kmin, BigDecimal.ZERO);
        BigDecimal ratio = kmin.signum() > 0 ? p.k().divide(kmin, MC) : BigDecimal.ONE;
        card.addView(healthBar(ratio));

        card.addView(discoverabilityRow(p));
        card.addView(actionRow(p, ratio));
        // what-if: open the calculator seeded with THIS pool's live reserves (display only, nothing posted)
        TextView calc = text("What if the price moves?  Pool calculator ›", Design.accent(), 12, true);
        calc.setGravity(Gravity.END);
        calc.setPadding(0, Ui.dp(act, 10), 0, 0);
        calc.setOnClickListener(v -> PoolCalcDialog.show(act, p));
        card.addView(calc);
        return card;
    }

    private View healthBar(BigDecimal ratio) {
        // map ratio 1.0..2.0 -> 0..1 fill
        double frac = Math.max(0, Math.min(1, (ratio.doubleValue() - 1.0)));
        boolean warn = frac >= 0.85;
        int fillColor = warn ? Design.amber() : Design.accent();

        LinearLayout wrap = new LinearLayout(act);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(0, Ui.dp(act, 10), 0, 0);

        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        TextView label = text("Floor headroom", Design.dim(), 12, false);
        label.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView pct = text((warn ? "migrate soon" : "healthy"), warn ? Design.amber() : Design.dim(), 12, false);
        row.addView(label); row.addView(pct);
        wrap.addView(row);

        // track
        LinearLayout track = new LinearLayout(act);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(act, 8));
        tlp.topMargin = Ui.dp(act, 5);
        track.setLayoutParams(tlp);
        Ui.banner(track, Ui.withAlpha(Design.dim2(), 60), Ui.withAlpha(Design.dim2(), 60));
        View fill = new View(act);
        fill.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, (float) Math.max(0.02, frac)));
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(fillColor); g.setCornerRadius(Ui.dp(act, 8));
        fill.setBackground(g);
        View spacer = new View(act);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, (float) (1.0 - Math.max(0.02, frac))));
        track.addView(fill); track.addView(spacer);
        wrap.addView(track);
        return wrap;
    }

    private View actionRow(Pool p, BigDecimal ratio) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, Ui.dp(act, 14), 0, 0);
        boolean mustMigrate = ratio.doubleValue() >= 1.85;
        row.addView(actionBtn(mustMigrate ? "Add (full)" : "Add", () -> showAddDialog(p), false));
        row.addView(gap());
        row.addView(actionBtn("Migrate", () -> showMigrateDialog(p), mustMigrate));
        row.addView(gap());
        row.addView(actionBtn("Close", () -> confirmClose(p), false));
        return row;
    }

    /**
     * Persistent amber card while the exported backup does not cover every pool this node owns.
     *
     * Not a toast and not dismissible: the on-device recipe store is excluded from cloud backup by design, so
     * for a pool missing from the exported file there is no recovery path at all if this phone is lost. It stays
     * until an export is read back and verified.
     */
    private View backupCard() {
        int owned = BackupState.ownedCount(act);
        boolean never = !BackupState.everExported(act);
        LinearLayout card = dialogBox(); Ui.card(card);
        card.addView(text(never ? "Your pools are not backed up" : "Your backup is out of date",
                Design.amber(), 16, true));
        TextView detail = new TextView(act);
        detail.setText(never
                ? (owned == 1 ? "Your pool exists only on this phone." : "Your " + owned + " pools exist only on this phone.")
                        + " PandaPools keeps their contracts here, and that copy is deliberately never sent to any "
                        + "cloud — so if this phone is lost or wiped, recovery depends on having either a "
                        + "PandaPools pool file or a current MinimaCore wallet backup. Right now you have "
                        + "neither. Save one."
                : "Your saved backup file no longer covers every pool you own, so a pool created since then "
                        + "could not be recovered from it. Save a fresh one.");
        detail.setTextIsSelectable(true); card.addView(detail);
        Button back = new Button(act); back.setText("Back up now");
        back.setOnClickListener(v -> doBackup()); card.addView(back);
        return card;
    }

    /**
     * Offer to re-export once a pool created earlier has confirmed, so its file can carry coin proofs.
     *
     * Only an offer. Proofs are a shortcut that expires anyway, and recovery works from the recipe plus an
     * archive lookup, so a file without them is already sufficient — which is exactly why this must not look
     * like the "not backed up" warning. Suppressed entirely while that warning is showing, so the user is never
     * asked to do two backup things at once.
     */
    private View prooflessCard(List<Pool> mine) {
        android.content.SharedPreferences sp = act.getSharedPreferences("pandapools_recovery", android.content.Context.MODE_PRIVATE);
        java.util.Set<String> pending = sp.getStringSet("backup_proofless", new java.util.HashSet<>());
        if (pending.isEmpty() || prooflessDismissed) return null;
        Pool ready = null;
        for (Pool p : mine)
            if (p.address != null && pending.contains(p.address.toLowerCase())
                    && ReserveRecovery.completeReserves(p)) { ready = p; break; }
        if (ready == null) return null;
        final String addr = ready.address;

        LinearLayout card = dialogBox(); Ui.card(card);
        card.addView(text("Add coin proofs to your pool file", Design.dim(), 15, true));
        TextView detail = new TextView(act);
        detail.setText("Your pool has confirmed, so a fresh backup can now include its reserve coin proofs. Those "
                + "make recovery quicker; they are not required, and your saved file already recovers this pool "
                + "through an archive lookup. Worth doing when convenient.\n\n" + addr);
        detail.setTextIsSelectable(true); card.addView(detail);
        LinearLayout row = new LinearLayout(act); row.setOrientation(LinearLayout.HORIZONTAL);
        Button again = new Button(act); again.setText("Back up again");
        again.setOnClickListener(v -> { clearProofless(addr); doBackup(); });
        Button later = new Button(act); later.setText("Not now");
        later.setOnClickListener(v -> { prooflessDismissed = true; act.pools().refresh(); });
        row.addView(again); row.addView(later); card.addView(row);
        return card;
    }

    /** Drop the "needs coin proofs" marker for every pool whose entry in this export actually carries both. */
    private void clearProoflessWithProofs(String json) {
        try {
            org.json.JSONArray pools = new JSONObject(json).optJSONArray("pools");
            if (pools == null) return;
            for (int i = 0; i < pools.length(); i++) {
                JSONObject e = pools.optJSONObject(i);
                if (e == null) continue;
                if (!e.optString("cm", "").isEmpty() && !e.optString("ct", "").isEmpty())
                    clearProofless(e.optString("addr", ""));
            }
        } catch (Exception ignore) { /* the marker is only an offer; failing to clear it is harmless */ }
    }

    private void clearProofless(String address) {
        if (address == null || address.isEmpty()) return;
        android.content.SharedPreferences sp = act.getSharedPreferences("pandapools_recovery", android.content.Context.MODE_PRIVATE);
        java.util.Set<String> set = new java.util.HashSet<>(sp.getStringSet("backup_proofless", new java.util.HashSet<>()));
        if (set.remove(address.toLowerCase())) sp.edit().putStringSet("backup_proofless", set).commit();
    }

    /**
     * A withdrawal that was in flight when PandaPools stopped.
     *
     * Deliberately NOT retried automatically. The one thing worse than a stranded pool is two closes against the
     * same coins, both signing, each burning an owner leaf — and from here the app genuinely cannot tell whether
     * the first one landed. So it states what it knows and leaves the decision to a person, with the full
     * identifiers they need to check the chain themselves.
     */
    private View interruptedExitCard(ExitStore.Record r) {
        LinearLayout card = dialogBox(); Ui.card(card);
        card.addView(text("A withdrawal was interrupted", Design.amber(), 16, true));
        TextView detail = new TextView(act);
        boolean unknown = r.stateOf() == ExitStore.State.UNCERTAIN;
        detail.setText((unknown
                ? "PandaPools sent a withdrawal for this pool but never got confirmation of the outcome, so it "
                        + "does not know whether it posted."
                : "PandaPools was withdrawing this pool when it stopped, so it does not know whether the "
                        + "withdrawal posted.")
                + " It will NOT retry on its own: if the first one did land, a second would spend the same coins "
                + "again and use another of the owner key's one-time signatures.\n\n"
                + "Check Activity, or look this pool up on a block explorer. If the reserves are gone, the "
                + "withdrawal worked and \"Collect withdrawn funds to my wallet\" finishes the job. If they are "
                + "still there, use Withdraw on this pool's card.\n\n"
                + "Pool: " + r.address
                + (r.closeTxpowid == null || r.closeTxpowid.isEmpty() ? "" : "\nTransaction: " + r.closeTxpowid)
                + (r.reason == null || r.reason.isEmpty() ? "" : "\n\n" + r.reason));
        detail.setTextIsSelectable(true); card.addView(detail);
        Ui.copyable(detail, "pool address", r.address);
        return card;
    }

    /**
     * Funds still sitting at a pool's owner payout address.
     *
     * Visible on purpose. A close can only pay to $OADR, which is a {@code newaddress} key that a
     * seed-only restore does NOT reproduce — so anything left there is one lost phone away from being
     * unreachable. Previously this state was completely silent: the retry loop expired and nothing ever
     * said the money had not arrived.
     */
    private View pendingCollectCard(PendingCollect.Entry e) {
        boolean stranded = e.statusOf() == PendingCollect.Status.STRANDED;
        LinearLayout card = dialogBox(); Ui.card(card);
        card.addView(text(stranded ? "Withdrawn funds cannot be moved from this address"
                                   : "Withdrawn funds still being moved to your wallet",
                stranded ? Design.amber() : Design.dim(), 15, true));
        TextView detail = new TextView(act);
        detail.setText((stranded
                ? "This is the address your pool was closed to. PandaPools cannot move the funds because "
                        + "this wallet cannot sign for it — that address is not one of the 64 addresses a seed "
                        + "phrase rebuilds. Restore the matching complete MinimaCore wallet backup (the one from "
                        + "the device that owned the pool), then use Collect. A seed on its own will not do it."
                : "Your pool's withdrawal landed at its owner payout address and PandaPools is moving it into "
                        + "your wallet. It keeps trying in the background, including after the app is closed, "
                        + "and stops only once the address is empty.")
                + "\n\n" + e.oadr
                + (e.lastError == null || e.lastError.isEmpty() ? "" : "\n\n" + e.lastError));
        detail.setTextIsSelectable(true); card.addView(detail);
        Ui.copyable(detail, "payout address", e.oadr);
        Button now = new Button(act); now.setText(stranded ? "Try again" : "Collect now");
        now.setOnClickListener(v -> doCollect()); card.addView(now);
        return card;
    }

    /** Best-effort discoverability hint + manual Re-publish. Other nodes find a pool only while a fresh registry
     *  beacon + young reserves exist (both maintained by keep-fresh every ~900 blocks). We proxy that from the
     *  reserve coin's age: young ⇒ discoverable; aged ⇒ the owner's node hasn't kept it fresh, so it may have gone
     *  dark to others. Re-publish forces an immediate keep-fresh (re-youths the reserves AND posts a fresh beacon
     *  in one owner-signed tx). */
    private View discoverabilityRow(Pool p) {
        int age = p.reserveAge(act.chainBlock());
        boolean known = age > 0;
        boolean fresh = known && age <= PoolRefresher.REFRESH_BLOCKS + 300;   // keep-fresh runs at ~900 blocks
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, Ui.dp(act, 10), 0, 0);
        row.setGravity(Gravity.CENTER_VERTICAL);
        String msg = !known ? "Discoverability: checking…"
                : fresh ? "Others can find this pool ✓"
                : "Others may not see this pool now";
        TextView hint = text(msg, (fresh || !known) ? Design.dim() : Design.amber(), 12, false);
        hint.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView btn = text("Re-publish", Design.accent(), 12, true);
        btn.setPadding(Ui.dp(act, 12), Ui.dp(act, 6), 0, Ui.dp(act, 6));
        btn.setOnClickListener(v -> { if (!busy) republish(p); });
        row.addView(hint); row.addView(btn);
        return row;
    }

    /** Force an immediate keep-fresh for this pool: recreate its reserves young + post a fresh registry beacon
     *  (owner-signed, no fund movement) so other nodes rediscover it. Re-reads the live coin first — refresh
     *  spends the covenant coin, so a stale snapshot would fail with "already spent (the pool moved)". */
    private void republish(Pool p) {
        busy = true; status("Re-publishing your pool so others can find it…");
        ensureOwner(java.util.Collections.singletonList(p.opk), blocked -> {
            OwnerKeyRecovery.Blocked why = blockedKey(p.opk, blocked);
            if (why != null) {
                act.runOnUiThread(() -> { busy = false; status(why.message()); });
                return;
            }
            withFreshCoins(p, () -> mgr.refresh(p, new PoolManager.Result() {
                @Override public void onPosted(String txpowid) {
                    act.runOnUiThread(() -> { busy = false; status("Pool re-published ✓ " + txpowid
                            + " — fresh reserves + registry beacon; other nodes will rediscover it shortly.", txpowid); act.pools().refresh(); });
                }
                @Override public void onFailed(String message) {
                    act.runOnUiThread(() -> { busy = false; status("Re-publish: " + message); });
                }
            }));
        });
    }

    private TextView actionBtn(String label, Runnable onClick, boolean highlight) {
        TextView t = new TextView(act);
        t.setText(label); t.setTextSize(13); t.setGravity(Gravity.CENTER);
        t.setPadding(Ui.dp(act, 10), Ui.dp(act, 11), Ui.dp(act, 10), Ui.dp(act, 11));
        t.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (highlight) Ui.primaryButton(t); else Ui.outlineButton(t);
        t.setOnClickListener(v -> { if (!busy) onClick.run(); });
        return t;
    }

    private View gap() {
        View v = new View(act);
        v.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(act, 8), 1));
        return v;
    }

    // ---- create pool ----

    /** Tokens with a live external market feed (currently only mxUSDT). Only these can be pooled, so a pool
     *  can never be opened at a mispriceable ratio while any reference price exists. */
    private static boolean isMarketFed(String tokenid) {
        return tokenid != null && tokenid.equalsIgnoreCase(USDT_TOKENID);
    }

    private static final class Anchor { final BigDecimal price; final String source; Anchor(BigDecimal p, String s) { price = p; source = s; } }

    /** The opening-price anchor, best-first: (1) a fresh MEXC mid; (2) the aggregate spot of live USDT pools;
     *  else null (no reference → tier-3 manual bootstrap). */
    private Anchor resolveAnchor() {
        BigDecimal m = PriceOracle.cachedMid();
        if (PriceOracle.fresh() && m != null && m.signum() > 0) return new Anchor(m, "MEXC market");
        if (usdtAnchor != null && usdtAnchor.signum() > 0) return new Anchor(usdtAnchor, "live pools");
        return null;
    }

    /** Route a chosen (market-fed) token to the priced form when an anchor exists, else try a fresh MEXC fetch,
     *  and only fall to the manual bootstrap form when there is genuinely no reference price at all. */
    private void dispatchCreate(String tokenid, int decimals, String tokenName, BigDecimal tokAvail, BigDecimal minimaAvail) {
        Anchor a = resolveAnchor();
        if (a != null) { createFormPriced(tokenid, decimals, tokenName, tokAvail, minimaAvail, a.price, a.source); return; }
        // no cached MEXC and no live pool → try a one-shot MEXC fetch before deciding priced-vs-manual
        status("Checking market price…");
        PriceOracle.fetch(new PriceOracle.Cb() {
            @Override public void onPrice(BigDecimal mid) {
                if (act.isFinishing() || act.isDestroyed()) return;
                status("");
                createFormPriced(tokenid, decimals, tokenName, tokAvail, minimaAvail, mid, "MEXC market");
            }
            @Override public void onError(String msg) {
                if (act.isFinishing() || act.isDestroyed()) return;
                status("");
                createFormManual(tokenid, decimals, tokenName);   // tier-3: no MEXC AND no live pool — user sets the first price
            }
        });
    }

    private void showCreateDialog() {
        // Guard: don't let a second create start while one is still confirming — that's the worry-and-resubmit
        // loop that stranded funds before.
        if (pendingCreate != null) {
            int el = Math.max(0, act.chainBlock() - pendingBlock);
            status("Your MINIMA / " + pendingLabel + " pool is still confirming (block " + Math.min(el, 3)
                    + " of ~3) — wait for it to go live before creating another.");
            return;
        }
        // Before anything else: explain what owning a pool obliges the user to do, and get it acknowledged.
        // Both obligations are things they cannot discover afterwards -- a pool that is not kept fresh goes dark
        // silently, and a backup that was never taken cannot be taken retrospectively.
        showCreateObligations(this::gatherTokensAndCreate);
    }

    /**
     * The create gate. Two obligations, one battery check, one acknowledgement.
     *
     * Shown on EVERY create with the box unticked. That is deliberate: these are real funds, the pool cannot be
     * recovered without both artefacts, and one extra tap is a small price beside a stranded pool.
     */
    private void showCreateObligations(Runnable proceed) {
        LinearLayout box = dialogBox();

        box.addView(text("Two things this pool will need from you", Design.heading(), 16, true));

        TextView one = new TextView(act);
        one.setText("1.  This app must run on this phone regularly.\n\n"
                + "Your pool's reserve coins have to be recreated about every 900 blocks — roughly twice a day. "
                + "PandaPools does that automatically while it can run. If it cannot, other wallets stop finding "
                + "your pool, and after about 1700 blocks its coins drop out of the chain's recent window and can "
                + "only be recovered through a MegaMMR archive.");
        one.setPadding(0, Ui.dp(act, 10), 0, 0);
        one.setTextColor(Design.text()); box.addView(one);

        TextView two = new TextView(act);
        two.setText("2. Save the pool file and keep your wallet seed safe.\n\n"
                + "This diagnostic build can find matching block-based owner keys and check reserves. "
                + "It does not restore signing controls. Restored pools remain paused. "
                + "Keep a current complete node backup for signing state.");
        two.setPadding(0, Ui.dp(act, 14), 0, 0);
        two.setTextColor(Design.text()); box.addView(two);

        // Live battery-optimisation state. Doze is what actually kills keep-fresh, so this belongs here rather
        // than in a settings screen nobody opens.
        final TextView batt = new TextView(act);
        batt.setPadding(0, Ui.dp(act, 14), 0, 0);
        final Button fix = new Button(act);
        fix.setText("Fix this");
        final Runnable refreshBatt = () -> {
            boolean ok = act.isBatteryExempt();
            batt.setText(ok ? "Battery optimisation: exempt ✓  Android will let PandaPools refresh this pool "
                            + "while the app is closed."
                    : "⚠  Android's battery optimisation will stop PandaPools refreshing this pool while the app "
                            + "is closed. This is the single biggest cause of a pool going dark.");
            batt.setTextColor(ok ? Design.dim() : Design.amber());
            fix.setVisibility(ok ? View.GONE : View.VISIBLE);
        };
        refreshBatt.run();
        box.addView(batt);
        fix.setOnClickListener(v -> { act.requestBatteryExemptionNow(); });
        box.addView(fix);

        final android.widget.CheckBox ack = new android.widget.CheckBox(act);
        ack.setText("I understand: I must keep this app running, save the pool file and keep my wallet seed safe.");
        ack.setPadding(0, Ui.dp(act, 16), 0, 0);
        box.addView(ack);

        // A second, separate acknowledgement only when Android is going to fight us. Some ROMs have no
        // exemption screen at all, so blocking creation outright would be worse than a pool the user can still
        // close -- but they must say out loud that they are taking the manual burden on.
        final android.widget.CheckBox manual = new android.widget.CheckBox(act);
        manual.setText("I understand Android may stop PandaPools refreshing this pool, and I will open the app "
                + "at least once a day.");
        manual.setPadding(0, Ui.dp(act, 8), 0, 0);
        box.addView(manual);

        final AlertDialog dlg = new AlertDialog.Builder(act)
                .setTitle("Before you create a pool")
                .setView(wrapScroll(box))
                .setPositiveButton("Continue", null)
                .setNegativeButton("Cancel", null)
                .create();
        dlg.setOnShowListener(d -> {
            refreshBatt.run();
            manual.setVisibility(act.isBatteryExempt() ? View.GONE : View.VISIBLE);
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                refreshBatt.run();
                boolean exempt = act.isBatteryExempt();
                manual.setVisibility(exempt ? View.GONE : View.VISIBLE);
                if (!ack.isChecked()) { toast("Tick the box to confirm you understand."); return; }
                if (!exempt && !manual.isChecked()) {
                    toast("Either fix battery optimisation, or tick the second box.");
                    return;
                }
                act.ensureNotificationPermissionNow();   // so THIS pool's keep-alive notice is not suppressed
                dlg.dismiss();
                proceed.run();
            });
        });
        dlg.show();
    }

    private View wrapScroll(View inner) {
        android.widget.ScrollView sv = new android.widget.ScrollView(act);
        sv.addView(inner);
        return sv;
    }

    private void gatherTokensAndCreate() {
        // gather the wallet's tokens (name + tokenid + decimals) to pick from
        act.node().cmd("balance", new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                List<String[]> toks = new ArrayList<>();   // {tokenid, label, decimals, name, tokenSendable}
                BigDecimal[] minimaAvail = { BigDecimal.ZERO };
                JSONArray arr = j.optJSONArray("response");
                if (arr != null) for (int i = 0; i < arr.length(); i++) {
                    JSONObject t = arr.optJSONObject(i);
                    if (t == null) continue;
                    String tid = t.optString("tokenid", "");
                    BigDecimal sendable = Util.decOr(t.optString("sendable", "0"), BigDecimal.ZERO);
                    if (Util.isMinima(tid)) { minimaAvail[0] = sendable; continue; }   // MINIMA is the other leg
                    if (tid.isEmpty() || sendable.signum() <= 0) continue;
                    if (!isMarketFed(tid)) continue;   // ONLY market-fed pairs (mxUSDT) — no mispriceable pools
                    String name = Util.tokenName(t.opt("token"), tid);
                    int dec = Util.tokenDecimals(t.opt("token"));
                    toks.add(new String[]{tid, name + "  ·  " + trim(sendable) + " avail", String.valueOf(dec),
                            name, sendable.toPlainString()});
                }
                if (toks.isEmpty()) { info("Get mxUSDT first", "PandaPools creates MINIMA / USDT pools — the pair with a live market price, so a pool always opens at the true rate. Your wallet holds no mxUSDT; receive some first, then create a pool."); return; }
                String[] labels = new String[toks.size()];
                for (int i = 0; i < toks.size(); i++) labels[i] = toks.get(i)[1];
                final BigDecimal mAvail = minimaAvail[0];
                // One market-fed token → skip the picker and go straight in; otherwise let the user choose.
                if (toks.size() == 1) {
                    String[] t = toks.get(0);
                    dispatchCreate(t[0], Integer.parseInt(t[2]), t[3], Util.decOr(t[4], BigDecimal.ZERO), mAvail);
                    return;
                }
                new AlertDialog.Builder(act)
                        .setTitle("Pool MINIMA with…")
                        .setItems(labels, (d, w) -> {
                            String[] t = toks.get(w);
                            dispatchCreate(t[0], Integer.parseInt(t[2]), t[3], Util.decOr(t[4], BigDecimal.ZERO), mAvail);
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            }
            @Override public void onError(String m) { info("Error", "Could not read your balance: " + m); }
        });
    }

    /**
     * Price-ANCHORED create for the MINIMA/USDT pair: the user enters only the USDT (dollar) amount, and the
     * required MINIMA is DERIVED from the anchor price ({@code initPrice}/{@code initSource} = tier-1 MEXC or
     * tier-2 live-pool aggregate) so the pool always opens at the true market — no lopsided, no mispriced pool.
     * The ↻ button upgrades the anchor to a fresh MEXC mid; a MEXC hiccup never blocks (the pool-price anchor
     * carries it). Confirms before posting.
     */
    private void createFormPriced(String tokenid, int decimals, String tokenName, BigDecimal tokAvail,
                                  BigDecimal minimaAvail, BigDecimal initPrice, String initSource) {
        LinearLayout box = dialogBox();
        EditText usdtAmt = field(box, tokenName + " to provide  (≈ US$)", "e.g. 1.90");
        TextView info = new TextView(act);
        info.setTextColor(Design.dim()); info.setTextSize(13);
        info.setPadding(0, Ui.dp(act, 12), 0, 0);
        box.addView(info);

        final BigDecimal[] price = { initPrice };
        final String[] source = { initSource };

        final Runnable update = () -> {
            BigDecimal p = price[0];
            if (p == null || p.signum() <= 0) { info.setText("Fetching MINIMA/USDT market price…"); return; }
            // most USDT the MINIMA balance can balance at the anchor price, and the smaller of that vs USDT held
            BigDecimal usdtCap = minimaAvail.multiply(p).min(tokAvail).setScale(decimals, RoundingMode.DOWN);
            StringBuilder sb = new StringBuilder();
            sb.append("Price: ").append(trim8(p)).append(" USDT/MINIMA (").append(source[0]).append(")\n");
            sb.append("You have: ").append(trim(minimaAvail)).append(" MINIMA · ").append(trim(tokAvail)).append(" ").append(tokenName).append("\n");
            BigDecimal u = parse(usdtAmt.getText().toString());
            if (u == null || u.signum() <= 0) {
                sb.append("Enter the USDT amount to provide (max ≈ ").append(trim(usdtCap)).append(").");
            } else {
                BigDecimal minima = u.divide(p, 6, RoundingMode.UP);
                sb.append("MINIMA required:  ").append(trim(minima)).append("\n");
                sb.append("Total pool value:  ≈ US$ ").append(trim2(u.multiply(TWO)));
                if (minima.compareTo(minimaAvail) > 0)
                    sb.append("\n⚠ Not enough MINIMA — reduce USDT to ≤ ").append(trim(usdtCap)).append(".");
                if (u.compareTo(tokAvail) > 0)
                    sb.append("\n⚠ You only have ").append(trim(tokAvail)).append(" ").append(tokenName).append(".");
            }
            info.setText(sb.toString());
        };
        watch(usdtAmt, update);

        final AlertDialog dlg = new AlertDialog.Builder(act)
                .setTitle("Create MINIMA / " + tokenName + " pool")
                .setView(box)
                .setPositiveButton("Create", null)     // wired in onShow so invalid input doesn't dismiss
                .setNeutralButton("↻ Price", null)
                .setNegativeButton("Cancel", null)
                .create();

        final PriceOracle.Cb fetchCb = new PriceOracle.Cb() {
            @Override public void onPrice(BigDecimal mid) {
                if (act.isFinishing() || act.isDestroyed()) return;   // dialog/activity gone — don't touch views
                price[0] = mid; source[0] = "MEXC market"; update.run();
            }
            @Override public void onError(String msg) {
                if (act.isFinishing() || act.isDestroyed()) return;
                update.run();   // keep the current anchor (pool/prior) — a MEXC hiccup must not block a create
            }
        };

        dlg.setOnShowListener(d -> {
            Button create = dlg.getButton(AlertDialog.BUTTON_POSITIVE);
            Button refresh = dlg.getButton(AlertDialog.BUTTON_NEUTRAL);
            create.setOnClickListener(v -> {
                BigDecimal p = price[0];
                if (p == null || p.signum() <= 0) { toast("No price yet — tap ↻ and retry."); return; }
                BigDecimal u = parse(usdtAmt.getText().toString());
                if (u == null || u.signum() <= 0) { toast("Enter the USDT amount."); return; }
                BigDecimal minima = u.divide(p, 6, RoundingMode.UP);
                if (minima.compareTo(minimaAvail) > 0 || u.compareTo(tokAvail) > 0) { toast("Not enough balance for that amount."); return; }
                dlg.dismiss();
                confirmCreate(tokenid, decimals, tokenName, minima, u, p, source[0], false);
            });
            refresh.setOnClickListener(v -> { info.setText("Refreshing price…"); PriceOracle.fetch(fetchCb); });
            update.run();
        });
        dlg.show();
        PriceOracle.fetch(fetchCb);   // try to upgrade to a live MEXC mid
    }

    /** Final confirmation before posting — states the amounts, the opening price, and which anchor set it
     *  (so a fat-fingered USD amount or a bootstrap price can't slip through un-noticed). */
    private void confirmCreate(String tokenid, int decimals, String tokenName, BigDecimal minima, BigDecimal usdt,
                               BigDecimal price, String source, boolean bootstrap) {
        String priceLine = bootstrap
                ? "⚠ No market price available — YOU are setting the opening price."
                : "Opens at " + trim8(price) + " USDT/MINIMA  (matches " + source + " ✓)";
        String msg = "MINIMA / " + tokenName + " pool\n\n"
                + trim(minima) + " MINIMA  +  " + trim(usdt) + " " + tokenName + "\n"
                + priceLine + "\n\n"
                + "The 0.5% swap fee goes to liquidity providers.";
        new AlertDialog.Builder(act)
                .setTitle("Create this pool?")
                .setMessage(msg)
                .setPositiveButton("Create", (d, w) -> doCreate(tokenid, decimals, tokenName, minima, usdt))
                .setNegativeButton("Back", null)
                .show();
    }

    /** Tier-3 BOOTSTRAP create — reached ONLY when there is no MEXC price AND no live MINIMA/USDT pool to anchor
     *  to (the very first pool). The creator sets the opening price; gated behind an explicit acknowledgement +
     *  the confirm step, because nothing can check the ratio. This is the only free-ratio path in the app. */
    private void createFormManual(String tokenid, int decimals, String tokenName) {
        LinearLayout box = dialogBox();
        TextView intro = text("No market price is available (MEXC is unreachable and there are no live MINIMA / "
                + tokenName + " pools to match). You are setting the OPENING PRICE yourself — set it to the true "
                + "market rate, or arbitrageurs will correct it at your expense.", Design.dim(), 12, false);
        intro.setPadding(0, 0, 0, Ui.dp(act, 4));
        box.addView(intro);
        EditText mAmt = field(box, "MINIMA to deposit", "e.g. 100");
        EditText tAmt = field(box, tokenName + " to deposit", "e.g. 0.56");
        TextView preview = new TextView(act);
        preview.setTextColor(Design.dim()); preview.setTextSize(13);
        preview.setPadding(0, Ui.dp(act, 10), 0, 0);
        box.addView(preview);
        final android.widget.CheckBox ack = new android.widget.CheckBox(act);
        ack.setText("I understand I'm setting the opening price with no market to check it against.");
        ack.setTextColor(Design.dim()); ack.setTextSize(12);
        box.addView(ack);

        Runnable update = () -> {
            BigDecimal x = parse(mAmt.getText().toString());
            BigDecimal y = parse(tAmt.getText().toString());
            if (x == null || y == null || x.signum() <= 0 || y.signum() <= 0) { preview.setText("Enter both amounts."); return; }
            if (!PoolCovenant.sizeOk(x, y)) { preview.setText("Amounts too large (x × y must be < 2^64)."); return; }
            BigDecimal yc = y.setScale(decimals, RoundingMode.DOWN);   // the actual on-grain reserve
            BigDecimal price = yc.divide(x, MC);
            preview.setText("Opening price:  " + trim8(price) + " " + tokenName + " / MINIMA\n"
                    + "Product floor (KMIN):  " + PoolCovenant.kmin(x, yc));
        };
        watch(mAmt, update); watch(tAmt, update); update.run();

        final AlertDialog dlg = new AlertDialog.Builder(act)
                .setTitle("Create MINIMA / " + tokenName + " pool")
                .setView(box)
                .setPositiveButton("Create", null)
                .setNegativeButton("Cancel", null)
                .create();
        dlg.setOnShowListener(d -> dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            BigDecimal x = parse(mAmt.getText().toString());
            BigDecimal y = parse(tAmt.getText().toString());
            if (x == null || y == null || x.signum() <= 0 || y.signum() <= 0) { toast("Enter both amounts."); return; }
            if (!PoolCovenant.sizeOk(x, y)) { toast("Amounts too large (x × y must be < 2^64)."); return; }
            if (!ack.isChecked()) { toast("Tick the box to confirm you're setting the price."); return; }
            BigDecimal yc = y.setScale(decimals, RoundingMode.DOWN);
            BigDecimal price = yc.divide(x, MC);
            dlg.dismiss();
            confirmCreate(tokenid, decimals, tokenName, x, y, price, "manual", true);
        }));
        dlg.show();
    }

    private void doCreate(String tokenid, int decimals, String tokenName, BigDecimal x, BigDecimal y) {
        busy = true; status("Creating your MINIMA / " + tokenName + " pool…");
        mgr.createPool(tokenid, decimals, x, y, new PoolManager.CreateResult() {
            @Override public void onCreated(Pool pool, String txpowid) {
                pool.tokName = tokenName;   // nice label for the confirming card
                LpStore.record(act, pool.address, pool.reserveM, pool.reserveT, act.chainBlock());
                OwnPoolStore.record(act, pool);   // durable recovery recipe (exact covenant script)
                act.ensureKeepAlive();            // first pool → bring up the Doze-proof keep-alive (idempotent)
                ActivityLog.record(act, ActivityLog.CREATE, "Create MINIMA / " + tokenName + " pool  ·  "
                        + trim(pool.reserveM) + " MINIMA + " + trim(pool.reserveT) + " " + tokenName, txpowid, act.chainBlock());
                act.runOnUiThread(() -> {
                    busy = false;
                    pendingCreate = pool; pendingBlock = act.chainBlock(); pendingLabel = tokenName;
                    status("Submitted ✓ " + txpowid + " — your pool is confirming on-chain (usually 1–3 blocks). "
                            + "It will appear below automatically; no need to resubmit.");
                    startPendingPoll();
                    act.pools().refresh();
                    // The pool now exists on chain and its recipe is on this phone only. Force the off-device
                    // export immediately: the window where the pool is unrecoverable starts NOW, and a user who
                    // closes the app here has a live pool with no backup and no prompt telling them so.
                    forceBackupAfterCreate(pool);
                });
            }
            @Override public void onFailed(String message) {
                ActivityLog.recordFailed(act, ActivityLog.CREATE, "Create MINIMA / " + tokenName + " pool", message);
                act.runOnUiThread(() -> { busy = false; status("Create: " + message); });
            }
        });
    }

    // ---- add liquidity ----

    private void showAddDialog(Pool p) {
        LinearLayout box = dialogBox();
        // Deposits must be in the pool's ratio so they never move the price — the user enters ONLY the
        // MINIMA side and the token side is dictated (read-only) by the pool price. No lopsided adds.
        EditText mAmt = field(box, "MINIMA to add", "e.g. 50");
        TextView note = new TextView(act);
        note.setTextColor(Design.dim()); note.setTextSize(13);
        note.setPadding(0, Ui.dp(act, 10), 0, 0);
        box.addView(note);
        final BigDecimal price = p.spotPrice();
        final BigDecimal[] tSide = { BigDecimal.ZERO };
        watch(mAmt, () -> {
            BigDecimal m = parse(mAmt.getText().toString());
            if (m != null && m.signum() > 0) {
                BigDecimal t = m.multiply(price).setScale(p.tokDecimals, RoundingMode.DOWN);
                tSide[0] = t;
                note.setText("Balanced at the pool price: this also adds " + trim(t) + " " + p.tokenLabel()
                        + ".\nBoth sides are added in the pool's ratio so the price doesn't move.");
            } else {
                tSide[0] = BigDecimal.ZERO;
                note.setText("Enter the MINIMA to add; the " + p.tokenLabel()
                        + " side is set automatically at the pool price so the deposit stays balanced.");
            }
        });
        new AlertDialog.Builder(act)
                .setTitle("Add liquidity")
                .setView(box)
                .setPositiveButton("Add", (d, w) -> {
                    BigDecimal m = parse(mAmt.getText().toString());
                    if (m == null || m.signum() <= 0) { toast("Enter the MINIMA amount to add."); return; }
                    doDeposit(p, m, tSide[0]);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void doDeposit(Pool p, BigDecimal m, BigDecimal tPreview) {
        busy = true; status("Adding liquidity…");
        final BigDecimal fm = m;
        // Re-read the live pool coin first — a deposit grows reserves in place and so spends the current
        // covenant coin; a stale snapshot would fail with "already spent (the pool moved)".
        withFreshCoins(p, () -> {
            // Re-derive the balanced token side from the LIVE price (same formula the dialog previewed,
            // against current reserves). If a swap moved the pool between the dialog and now, the
            // dialog-time amount would be off-ratio and shift the price; this keeps the add balanced,
            // honouring the dialog's "both sides in the pool's ratio so the price doesn't move" promise.
            final BigDecimal ft = m.multiply(p.spotPrice()).setScale(p.tokDecimals, RoundingMode.DOWN);
            mgr.deposit(p, m, ft, new PoolManager.Result() {
                @Override public void onPosted(String txpowid) {
                    // reset the fee baseline to the grown product so the added capital isn't counted as fees
                    LpStore.updateFeeBase(act, p.address, p.reserveM.add(fm), p.reserveT.add(ft));
                    ActivityLog.record(act, ActivityLog.DEPOSIT, "Add to MINIMA / " + p.tokenLabel() + "  ·  "
                            + trim(fm) + " MINIMA + " + trim(ft) + " " + p.tokenLabel(), txpowid, act.chainBlock());
                    act.runOnUiThread(() -> { busy = false; status("Liquidity added ✓ " + txpowid + " — confirming on-chain.", txpowid); act.pools().refresh(); });
                }
                @Override public void onFailed(String message) {
                    ActivityLog.recordFailed(act, ActivityLog.DEPOSIT, "Add to MINIMA / " + p.tokenLabel(), message);
                    act.runOnUiThread(() -> { busy = false; status("Add: " + message); });
                }
            });
        });
    }

    // ---- migrate ----

    private void showMigrateDialog(Pool p) {
        LinearLayout box = dialogBox();
        TextView blurb = new TextView(act);
        blurb.setTextColor(Design.dim()); blurb.setTextSize(13);
        blurb.setText("Migrate resets the pool at a fresh address with the KMIN floor recomputed for the new "
                + "size. Your current reserves return to you and the new reserves are taken from your wallet. "
                + "Defaults keep the same size.");
        blurb.setPadding(0, 0, 0, Ui.dp(act, 10));
        box.addView(blurb);
        EditText mAmt = field(box, "New MINIMA reserve", trim(p.reserveM));
        EditText tAmt = field(box, "New " + p.tokenLabel() + " reserve", trim(p.reserveT));
        mAmt.setText(trim(p.reserveM)); tAmt.setText(trim(p.reserveT));
        new AlertDialog.Builder(act)
                .setTitle("Migrate pool")
                .setView(box)
                .setPositiveButton("Migrate", (d, w) -> {
                    BigDecimal x = parse(mAmt.getText().toString());
                    BigDecimal y = parse(tAmt.getText().toString());
                    if (x == null || y == null) { toast("Enter the new reserves."); return; }
                    doMigrate(p, x, y);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** READ-ONLY owner-key and signing-state pre-flight before an owner-signed action. Never creates a key and
     *  never advances a counter: re-minting an owner key would insert it at {@code uses = 0}, and signing from
     *  there re-signs leaves the original node already spent. Every blocked case arrives in {@code unreachable} —
     *  key absent (a different seed), the node's key list unreadable, the key exhausted, the recipe
     *  signing-quarantined, or the node's counter below the recipe's recorded floor — and all of them mean
     *  "do not sign". The second callback argument is always 0; nothing is regenerated. */
    private void ensureOwner(List<String> opks, OwnerKeyRecovery.Cb cb) {
        NodeApi n = act.node();
        if (n == null) { cb.done(OwnerKeyRecovery.nodeUnavailable(opks)); return; }
        OwnerKeyRecovery.ensure(act, n, opks, cb);
    }

    /** Why this pool's owner key cannot sign, or null when it can. */
    private static OwnerKeyRecovery.Blocked blockedKey(String opk, Map<String, OwnerKeyRecovery.Blocked> blocked) {
        return (opk == null || blocked == null) ? null : blocked.get(opk.toLowerCase());
    }

    /** Re-read the pool's LIVE covenant coin right before an owner txn (close / add / migrate) so we
     *  spend its CURRENT coin, not a stale snapshot — a swap, or this node's own keep-fresh, may have
     *  moved it since the last scan. Runs {@code action} on success; aborts with a status message if
     *  the pool can't be read or now shows no reserves. Reuses {@link PoolRefresher#readLiveReserves}. */
    private void withFreshCoins(Pool p, Runnable action) {
        NodeApi n = act.node();
        if (n == null) { act.runOnUiThread(() -> { busy = false; status("No node connection — try again in a moment."); }); return; }
        PoolRefresher.readLiveReserves(n, p, ok -> act.runOnUiThread(() -> {
            if (!ok) { busy = false; status("Couldn't read the pool's current reserves — refresh and try again."); return; }
            action.run();
        }));
    }

    private void doMigrate(Pool p, BigDecimal x, BigDecimal y) {
        busy = true; status("Migrating your pool…");
        ensureOwner(java.util.Collections.singletonList(p.opk), blocked -> {
            OwnerKeyRecovery.Blocked why = blockedKey(p.opk, blocked);
            if (why != null) {
                act.runOnUiThread(() -> { busy = false; status(why.message()); });
                return;
            }
            // Scanned pools never carry kidx — the recipes do. Enrich before migrate so the NEW recipe
            // (same opk, same index) records it instead of -1.
            if (p.kidx < 0 && p.opk != null) {
                Integer known = OwnerKeyRecovery.kidxFromStore(act).get(p.opk.toLowerCase());
                if (known != null) p.kidx = known;
            }
            // Re-read the live pool coin first — migrate sweeps the CURRENT reserves to $OADR, so a stale
            // snapshot would fail with "already spent (the pool moved)".
            withFreshCoins(p, () -> mgr.migrate(p, x, y, new PoolManager.CreateResult() {
                @Override public void onCreated(Pool pool, String txpowid) {
                    pool.tokName = p.tokName;
                    LpStore.record(act, pool.address, pool.reserveM, pool.reserveT, act.chainBlock());
                    OwnPoolStore.record(act, pool);   // recipe for the new pool
                    act.ensureKeepAlive();            // ensure the keep-alive is up (idempotent)
                    LpStore.remove(act, p.address);   // the old pool's display snapshot is stale — drop it
                    // KEEP the old pool's recovery recipe until the migrate CONFIRMS: if the tx never lands the
                    // old pool is still live and must stay recoverable. Retire it — hidden, never deleted, and
                    // un-retired automatically by render() if a scan ever finds the old pool still live.
                    OwnPoolStore.setRetired(act, p.address, true);
                    ActivityLog.record(act, ActivityLog.MIGRATE, "Migrate MINIMA / " + p.tokenLabel() + " pool  ·  new size "
                            + trim(pool.reserveM) + " MINIMA + " + trim(pool.reserveT) + " " + p.tokenLabel(), txpowid, act.chainBlock());
                    act.runOnUiThread(() -> { busy = false; status("Migrated ✓ " + txpowid + " — confirming on-chain.", txpowid); act.pools().refresh(); forceBackupAfterCreate(pool); });
                }
                @Override public void onFailed(String message) {
                    ActivityLog.recordFailed(act, ActivityLog.MIGRATE, "Migrate MINIMA / " + p.tokenLabel() + " pool", message);
                    act.runOnUiThread(() -> { busy = false; status("Migrate: " + message); });
                }
            }));
        });
    }

    // ---- close ----

    private void confirmClose(Pool p) {
        new AlertDialog.Builder(act)
                .setTitle("Withdraw all liquidity?")
                .setMessage("This closes the MINIMA / " + p.tokenLabel() + " pool and sweeps "
                        + trim(p.reserveM) + " MINIMA + " + trim(p.reserveT) + " " + p.tokenLabel()
                        + " (including earned fees) back to your wallet.")
                .setPositiveButton("Withdraw", (d, w) -> {
                    busy = true; status("Closing pool…");
                    final String closeOadr = p.oadr;
                    ensureOwner(java.util.Collections.singletonList(p.opk), blocked -> {
                        OwnerKeyRecovery.Blocked why = blockedKey(p.opk, blocked);
                        if (why != null) {
                            act.runOnUiThread(() -> { busy = false; status(why.message()); });
                            return;
                        }
                        // The cached pool coin can be stale — a counterparty swap, or this node's own
                        // keep-fresh, spends and recreates the reserve coins, so the coin id from the last
                        // scan is a SPENT coin and close dies at txncheck ("already spent — the pool moved").
                        // Re-read the LIVE coin right before building, and retry ONCE if it moves under us.
                        final boolean[] retried = { false };
                        final Runnable[] attempt = new Runnable[1];
                        attempt[0] = () -> withFreshCoins(p, () -> {
                            // Log the ACTUAL (live) swept amounts, read fresh above — not the pre-read snapshot.
                            final String closeSummary = "Withdraw MINIMA / " + p.tokenLabel() + "  ·  "
                                    + trim(p.reserveM) + " MINIMA + " + trim(p.reserveT) + " " + p.tokenLabel() + " back to wallet";
                            mgr.close(p, new PoolManager.Result() {
                            @Override public void onPosted(String txpowid) {
                                LpStore.remove(act, p.address);   // pool closed — drop its display snapshot
                                // KEEP the OwnPoolStore recovery recipe here: a posted-but-unconfirmed close that
                                // never lands would otherwise strip the recipe from a STILL-LIVE pool (exactly the
                                // wiped-node case this feature backstops). Retire it instead — hidden from the
                                // lists, still in every backup, and un-retired by render() if the pool turns out
                                // to still be there. The old "harmless no-op" assumption stopped being true once
                                // unavailable recipes started rendering a card of their own.
                                OwnPoolStore.setRetired(act, p.address, true);
                                ActivityLog.record(act, ActivityLog.CLOSE, closeSummary, txpowid, act.chainBlock());
                                act.runOnUiThread(() -> {
                                    busy = false;
                                    status("Pool closed ✓ " + txpowid + " — moving funds to a wallet address (confirming on-chain)…", txpowid);
                                    act.pools().refresh();
                                    // Second hop: once the covenant→$OADR sweep confirms, forward it to a default-64
                                    // wallet address so the withdrawn funds survive a seed-only restore.
                                    collectAfterClose(closeOadr, 8);
                                });
                            }
                            @Override public void onFailed(String message) {
                                if (!retried[0] && message != null && message.contains("already spent")) {
                                    retried[0] = true;   // the pool moved between our read and the post — re-read once
                                    act.runOnUiThread(() -> { status("The pool moved — retrying with its current coin…"); attempt[0].run(); });
                                    return;
                                }
                                ActivityLog.recordFailed(act, ActivityLog.CLOSE, closeSummary, message);
                                act.runOnUiThread(() -> { busy = false; status("Close: " + message); });
                            }
                            });
                        });
                        attempt[0].run();
                    });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** After a close, the covenant→$OADR sweep is unconfirmed for ~a block; once it lands, forward those
     *  funds onward to a default-64 wallet address (so they survive a seed-only restore). Retries on the UI
     *  handler until the coins are sendable, or we run out of tries — the launch-time sweep is the backstop. */
    /**
     * Get the withdrawn funds off the payout address and into the default-64 wallet addresses a seed
     * restore reproduces.
     *
     * This used to be eight tries, twenty seconds apart, only while this screen was alive — a ~160 s
     * budget for coins that are not spendable until three blocks after the close confirms. It also
     * treated one forward posting as completion, so a forward that moved only the MINIMA leg stopped the
     * loop and left the token behind. That cost 2934.95626348 MxUSD on 2026-09-14.
     *
     * Now the address is queued durably ({@link PendingCollect}) and the job is finished by
     * {@link CollectSweeper} from the keep-alive pass, which survives app death and Doze and only clears
     * an entry once the address reads back EMPTY. The immediate attempt below is an optimisation, not
     * the mechanism.
     */
    private void collectAfterClose(final String oadr, final int unusedTries) {
        if (oadr == null || oadr.isEmpty()) return;
        PendingCollect.add(act, oadr);          // durable BEFORE any attempt: a crash here must not lose the job
        act.ui().postDelayed(() -> {
            NodeApi n = act.node();
            if (n == null) return;              // the keep-alive pass will pick it up
            new CollectSweeper(act, n).run((cleared, pending, stranded) -> act.runOnUiThread(() -> {
                if (cleared > 0) status("Withdrawn funds moved to your wallet ✓");
                act.pools().refresh();
            }));
        }, 20000);
    }

    /** Manual "Collect to wallet": forward any funds still sitting at this node's pool owner addresses
     *  ($OADR) onward to default-64 wallet addresses. Rescues funds a pre-fix close left stranded. */
    private void doCollect() {
        List<String> oadrs = new ArrayList<>();
        List<String> opks = new ArrayList<>();
        for (Pool p : OwnPoolStore.all(act)) {
            if (p.oadr != null && !p.oadr.isEmpty()) oadrs.add(p.oadr);
            if (p.opk != null && !p.opk.isEmpty()) opks.add(p.opk);
        }
        if (oadrs.isEmpty()) { status("No pools on record to collect from."); return; }
        status("Collecting withdrawn funds to your wallet…");
        // $OADR is RETURN SIGNEDBY($OPK), so the auto sweep needs every owner key. The pre-flight is read-only.
        // ONE unusable key aborts the WHOLE sweep: core's consolidation picks its own inputs, so a sweep that
        // proceeded could select a coin belonging to the blocked key and sign with it. Aborting is therefore the
        // correct behaviour, not a limitation — a per-pool sweep would be a separate change.
        ensureOwner(opks, blocked -> {
            if (blocked != null && !blocked.isEmpty()) {
                status("Nothing collected.\n\n" + OwnerKeyRecovery.worst(blocked).message()); return;
            }
            final String skipped = "";
            mgr.sweepOwnerFunds(oadrs, (addressesForwarded, coins, error) -> act.runOnUiThread(() -> {
                if (addressesForwarded > 0) {
                    status("Submitted collection from " + addressesForwarded + " pool"
                            + (addressesForwarded == 1 ? "" : "s") + " to your wallet. Check Activity for confirmation." + skipped);
                    act.pools().refresh();
                } else status(error.isEmpty() ? "No available, stateless coins to collect at your pool payout addresses." + skipped
                        : "Collection could not finish: " + error);
                if (addressesForwarded > 0 && !error.isEmpty()) status("Submitted collection from " + addressesForwarded
                        + " address(es). Other collection attempts failed: " + error);
            }));
        });
    }

    // ---- network discoverability (Layer 5) ----

    /** Once per session, GOSSIP: re-publish the announce beacon for ANY funded pool this node knows (its own
     *  + any it has discovered) whose beacon has faded from the recent registry window — so pools created on
     *  now-offline nodes stay findable by fresh nodes. One tiny dust tx per faded pool (no covenant coin
     *  spent, no owner signature); shuffled + capped in {@link ReAnnouncer}; silently does nothing if nothing
     *  faded or there's no spare MINIMA. {@code allFunded} is the full deduped funded scan result. */
    private void maybeReannounce(List<Pool> allFunded) {
        if (reannounceChecked || allFunded == null || allFunded.isEmpty() || act.node() == null) return;
        reannounceChecked = true;
        reannouncer.refreshFaded(new ArrayList<>(allFunded), posted -> {
            if (posted > 0) status("Re-published " + posted + " pool" + (posted == 1 ? "" : "s")
                    + " to the registry so fresh nodes can find " + (posted == 1 ? "it" : "them") + ".");
        });
    }

    /** Once per session, KEEP-FRESH: for each of MY pools whose reserves are aging toward the ~1700-block cascade
     *  edge, recreate them in place (owner grow-in-place, same amounts + a fresh beacon) so they stay young and
     *  every light node keeps seeing them. Owner-signed, no fund movement, capped in {@link PoolRefresher}. Age is
     *  unaffected (My LP age reads LpStore, not the coin). */
    private void maybeRefresh(List<Pool> mine) {
        if (refreshChecked || mine == null || mine.isEmpty() || act.node() == null || act.chainBlock() <= 0) return;
        refreshChecked = true;
        refresher.refreshAging(new ArrayList<>(mine), act.chainBlock(), posted -> {
            if (posted > 0) status("Refreshed " + posted + " pool" + (posted == 1 ? "" : "s")
                    + " so " + (posted == 1 ? "it stays" : "they stay") + " visible to other devices.");
        });
    }

    // ---- backup & restore (recovery) ----

    // ---- statement export ----

    /**
     * Export a per-pool statement: what you put in, your trades, what is in the pool now, and the profit.
     *
     * Hands {@link ExportWriter} the pool list this tab is already showing, so the file and the pool cards
     * cannot disagree — they are the same scan.
     */
    private void showStatementDialog() {
        if (myPools.isEmpty()) {
            Toast.makeText(act, "No pools to report on yet.", Toast.LENGTH_SHORT).show();
            return;
        }
        LinearLayout box = dialogBox();
        TextView t = text("A statement for each pool you own: what you put in, your own trades against it, "
                + "what is in the pool now, and the profit.\n\nTrades listed are YOUR transactions only — "
                + "not every trade against the pool. The profit figures are unaffected: they read the pool's "
                + "reserves live, so everyone else's trading is already in them.",
                Design.text(), 13, false);
        box.addView(t);
        new AlertDialog.Builder(act)
                .setTitle("Export statement")
                .setView(box)
                .setPositiveButton("Save file…", (d, w) -> runStatement(false))
                .setNeutralButton("Share…", (d, w) -> runStatement(true))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void runStatement(final boolean share) {
        status("Building statement…");
        final PandaAddrBook book = new PandaAddrBook(act);
        book.seed();
        book.addAll(myPools);
        book.persist();
        ExportWriter.run(act, book, myPools, new ExportWriter.Cb() {
            @Override public void onDone(String csv, String filename, PoolStatement.Report report) {
                status("");
                if (share) shareStatement(csv, filename, report); else saveStatement(csv, filename, report);
            }
            @Override public void onError(String message) {
                status("Export failed: " + message);
            }
        });
    }

    private void saveStatement(final String csv, String filename, final PoolStatement.Report report) {
        act.pickSaveCsv(filename, uri -> {
            if (uri == null) return;                       // cancelled
            boolean ok = ExportWriter.writeTo(act, uri, csv);
            Toast.makeText(act, ok ? "Saved  ·  " + ExportWriter.describe(report) : "Could not write the file",
                    Toast.LENGTH_LONG).show();
        });
    }

    private void shareStatement(String csv, String filename, PoolStatement.Report report) {
        java.io.File f = ExportWriter.stageForShare(act, filename, csv);
        if (f == null) { Toast.makeText(act, "Could not prepare the file", Toast.LENGTH_LONG).show(); return; }
        try {
            android.net.Uri uri = androidx.core.content.FileProvider.getUriForFile(
                    act, act.getPackageName() + ".fileprovider", f);
            android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_SEND);
            i.setType("text/csv");
            i.putExtra(android.content.Intent.EXTRA_STREAM, uri);
            i.putExtra(android.content.Intent.EXTRA_SUBJECT, "PandaPools statement");
            i.putExtra(android.content.Intent.EXTRA_TEXT, ExportWriter.describe(report));
            i.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
            act.startActivity(android.content.Intent.createChooser(i, "Send statement"));
        } catch (Exception e) {
            Toast.makeText(act, "Could not share the file", Toast.LENGTH_LONG).show();
        }
    }

    private void showRecoveryDialog() {
        // "Check a backup file" sits directly above Restore on purpose: it answers the same question without
        // changing anything, so nobody has to commit to a restore just to find out whether their file is good.
        String[] items = { "Collect withdrawn funds to my wallet", "Back up my pools to a file",
                "Check a backup file (changes nothing)", "Restore pools from a file",
                "Archive connection", "Confirm wallet signing state", "How recovery works",
                "Recover & withdraw (one-time exit)", "Recover on existing wallet" };
        new AlertDialog.Builder(act)
                .setTitle("Pool recovery")
                .setItems(items, (d, w) -> {
                    if (w == 0) doCollect();
                    else if (w == 1) doBackup();
                    else if (w == 2) doCheckBackup();
                    else if (w == 3) doRestore();
                    else if (w == 4) archiveConnection();
                    else if (w == 5) confirmSigningState();
                    else if (w == 6) showRecoveryGuide();
                    else if (w == 7) recoverAndWithdraw();
                    else if (w == 8) recoverExistingWallet();
                })
                .setNegativeButton("Close", null)
                .show();
    }

    private void archiveConnection() {
        copyableReport("Recovery archive", "eurobuddha.com:9001\n\nPandaPools requests fresh reserve proofs from this archive only. Each proof must pass validation on this node before it is imported. No seed or wallet signing state is sent.\n\nA pool older than the cascade does not by itself require a full resync. If current proofs cannot be validated, recovery reports the failure. Register the pool recipe before any full resync and preserve the existing wallet and its signing state.");
    }

    /** The recovery menu's entry point: no pool in hand, so it still has to ask which key. */
    private void confirmSigningState() {
        if (act.node() == null) { info("Signing remains paused", "Connect the node before checking its wallet state."); return; }
        List<String> keys = new ArrayList<>();
        for (Pool p : OwnPoolStore.all(act)) if ((p.signingStateUnverified || OwnPoolStore.confirmationFailed(p.opk)) && !keys.contains(p.opk)) keys.add(p.opk);
        if (keys.isEmpty()) { info("Wallet signing state", "No restored owner keys are awaiting confirmation."); return; }
        if (keys.size() == 1) { confirmSigningState(keys.get(0)); return; }
        new AlertDialog.Builder(act).setTitle("Select restored owner key")
                .setItems(keys.toArray(new String[0]), (d, i) -> confirmSigningState(keys.get(i)))
                .setNegativeButton("Close", null).show();
    }

    /** Confirm ONE key — the one on the card the user tapped. A card that names a key must not then open a
     *  picker listing every key: that is how someone attests to the wrong one. */
    private void confirmSigningState(String key) {
        if (BuildConfig.VERSION_NAME.endsWith("-diagnostic")) {
            info("Signing remains paused", "This diagnostic build cannot enable restored pool signing. Copy the recovery result for support.");
            return;
        }
        if (act.node() == null) { info("Signing remains paused", "Connect the node before checking its wallet state."); return; }
        if (key == null || key.isEmpty()) { confirmSigningState(); return; }
        new AlertDialog.Builder(act).setTitle("Confirm current wallet signing state")
                .setMessage(key + "\n\nOnly continue if this node holds the latest complete wallet signing state and every other copy of this wallet has stopped signing. A seed, old backup, or counter above the recipe's recorded count cannot prove this.\n\nConfirming enables owner actions and automatic pool refresh for this key.")
                .setNegativeButton("Keep signing paused", null)
                .setPositiveButton("I have verified this", (dd, w) -> {
                    act.node().cmd("keys action:list publickey:" + key, new NodeApi.Cb() {
                        public void onResult(JSONObject j) {
                            Integer uses = KeyUses.extractUses(j, key);
                            if (uses != null && OwnPoolStore.acknowledgeSigningState(act, key, uses)) {
                                status("Signing state confirmed by you. Owner actions are enabled."); act.pools().refresh();
                            } else info("Signing remains paused", "The owner key is missing, exhausted, below a recorded count, or the confirmation could not be saved.");
                        }
                        public void onError(String m) { info("Signing remains paused", "Could not verify the owner key on this node."); }
                    });
                }).show();
    }

    private void doBackup() {
        if (busy) return;
        busy = true;
        CombinedBackupUi.backup(act, recovery, new ArrayList<>(myPools), this::status, () -> busy = false);
    }

    /**
     * Non-dismissible prompt to save the pool file, immediately after a create is posted.
     *
     * "Non-dismissible" only goes so far — Back still closes a dialog — so the real enforcement is
     * {@link BackupState}: the amber MY LP card stays until a VERIFIED export covers this pool, and it reappears
     * on every render. This dialog is the nudge; the state is the guarantee.
     *
     * The pool is 1-3 blocks from confirming at this point, so Recovery cannot read its reserve coins yet and
     * the file will carry no coin proofs. That is expected and harmless — the recipe is the durable part — so the
     * wording says so instead of showing the generic proof warning, which would teach the user to dismiss
     * warnings on the one screen where they must not.
     */
    private void forceBackupAfterCreate(Pool pool) {
        markProofless(pool == null ? "" : pool.address);
        doBackup();
    }

    /** Remember that this pool's exported recipe has no coin proofs yet, so we can offer a re-export once the
     *  pool confirms and its reserves become readable. Proofs are only a shortcut, so this is an offer, never a
     *  block — the recipe alone already recovers the pool through an archive. */
    private void markProofless(String address) {
        if (address == null || address.isEmpty()) return;
        android.content.SharedPreferences sp = act.getSharedPreferences("pandapools_recovery", android.content.Context.MODE_PRIVATE);
        java.util.Set<String> set = new java.util.HashSet<>(sp.getStringSet("backup_proofless", new java.util.HashSet<>()));
        set.add(address.toLowerCase());
        sp.edit().putStringSet("backup_proofless", set).commit();
    }

    /** Open a backup file and report on it without changing anything. */
    private void doCheckBackup() {
        act.pickOpenFile(uri -> {
            if (uri == null) { status("Check cancelled."); return; }
            CombinedBackupUi.inspect(act,uri,json->{
            NodeApi n = act.node();
            if (n == null) { status("Connect the node before checking a backup file."); return; }
            if (busy) return;
            busy = true;
            status("Checking that backup file…");
            new BackupCheck(n).check(json, new BackupCheck.Cb() {
                @Override public void onProgress(String line) { act.runOnUiThread(() -> status(line)); }
                @Override public void onDone(String report, int usable, int total) {
                    act.runOnUiThread(() -> {
                        busy = false;
                        status(total == 0 ? "That file isn't a PandaPools backup."
                                : "Checked " + total + (total == 1 ? " pool." : " pools."));
                        copyableReport("Backup check", report);
                    });
                }
            });
            },this::status);
        });
    }

    /** A selectable, copyable report dialog — identifiers in these reports are the point of them. */
    private void copyableReport(String title, String report) {
        TextView body = new TextView(act);
        body.setText(report);
        body.setTextIsSelectable(true);
        body.setTypeface(android.graphics.Typeface.MONOSPACE);
        body.setTextSize(11f);
        body.setTextColor(Design.text());
        int pad = Ui.dp(act, 16);
        body.setPadding(pad, pad, pad, pad);
        android.widget.ScrollView scroll = new android.widget.ScrollView(act);
        scroll.addView(body);
        new AlertDialog.Builder(act).setTitle(title).setView(scroll)
                .setPositiveButton("OK", null)
                .setNeutralButton("Copy", (d, w) -> {
                    Ui.copyable(body, "backup check", report);
                    body.performClick();
                }).show();
    }

    private void doRestore() {
        new AlertDialog.Builder(act)
                .setTitle("Restore your pools")
                .setMessage("This check-only option verifies pool reserves and finds "
                        + "matching owner keys on block-based nodes. It may generate missing addresses.\n\n"
                        + "Signing remains paused. It does not sign, withdraw pools, or enable automatic refresh "
                        + "for restored pools, in either key mode.\n\n"
                        + "Save or copy the result for support. A matching key alone does not prove which "
                        + "one-time signing indexes were previously used.")
                .setPositiveButton("Choose pool backup", (d, w) -> pickRestoreFile())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void pickRestoreFile() {
        act.pickOpenFile(uri -> {
            if (uri == null) { status("Restore cancelled."); return; }
            CombinedBackupUi.open(act, uri, json -> restoreJson(json, true), this::status);
        });
    }

    private void recoverExistingWallet() {
        if (busy) return;
        new AlertDialog.Builder(act).setTitle("Recover on existing wallet")
                .setMessage("Use this when this node still has the original wallet keys and their current signing state, including after a chain resync that preserved the wallet. A seed-only restore does not meet that condition. Stop every other copy of the wallet first.\n\nChoose the pool backup. PandaPools will register its recipes, fetch missing reserve proofs from eurobuddha.com:9001, check existing owner keys and counters, then attempt one withdrawal and confirmed collection for each eligible pool. Missing data or uncertain transactions stop with a report. No keys will be regenerated.\n\nContinue only if you confirm that this wallet's signing state has been preserved. PandaPools cannot prove that from the seed or pool backup alone.")
                .setPositiveButton("Confirm wallet & choose backup", (d, which) -> act.pickOpenFile(uri -> {
                    if (uri == null) return;
                    CombinedBackupUi.open(act,uri,json->restoreJson(json,true,true,true),this::status);
                })).setNegativeButton("Cancel", null).show();
    }

    private void recoverAndWithdraw() {
        if (busy) return;
        new AlertDialog.Builder(act).setTitle("Recover & withdraw")
                .setMessage("Use this only after stopping every other copy of this wallet. The original seed must already be restored locally on this node; never share it here.\n\n"
                        + "This attempts one withdrawal per verified pool to its fixed owner payout address, waits for confirmation, then attempts one transfer into this node's wallet.\n\n"
                        + "The seed does not prove previous signature use. Reusing a signing slot can expose funds even on a throwaway node. Normal pool signing stays paused. Uncertain attempts stop for review.\n\n"
                        + "By continuing you confirm the other copies are stopped and accept that remaining risk.")
                .setPositiveButton("Accept risk & choose backup", (d, which) -> act.pickOpenFile(uri -> {
                    if (uri == null) return;
                    CombinedBackupUi.open(act,uri,json->restoreJson(json,true,true),this::status);
                })).setNegativeButton("Cancel", null).show();
    }

    /** Diagnostic file restore discovers keys; neither this nor a reserve check can withdraw. */
    private void restoreJson(String json) { restoreJson(json, false); }

    private void restoreJson(String json, boolean withdraw) { restoreJson(json, withdraw, false, false); }

    private void restoreJson(String json, boolean withdraw, boolean acceptedExit) { restoreJson(json, withdraw, acceptedExit, false); }

    private void restoreJson(String json, boolean withdraw, boolean acceptedExit, boolean existingOnly) {
            if (busy) return;
            busy = true;
            status("Restoring pools…");
            final StringBuilder log = new StringBuilder();
            final List<Pool> verified = new ArrayList<>();
            recovery.restore(act, json, act.chainBlock(), withdraw, existingOnly, new Recovery.RestoreCb() {
                private BlockOwnerRecovery.Result owners;
                @Override public void onOwnerRecovery(BlockOwnerRecovery.Result result) { owners = result; }
                @Override public void onProgress(String line) { log.append(line).append('\n'); status("Restoring… " + line); }
                @Override public void onVerified(Pool p) { verified.add(p); }
                @Override public void onDone(int restored, int total) {
                    if (total == 0) {
                        busy = false;
                        status("That file isn't a PandaPools backup.");
                        info("Nothing restored", "That file isn't a readable PandaPools backup — pick the "
                                + "pandapools-backup.json you saved from Back up.");
                        return;
                    }
                    if (acceptedExit && owners != null) {
                        List<Pool> eligible = new ArrayList<>();
                        for (Pool p : verified) if (owners.matched(p)) eligible.add(p);
                        autoWithdraw(eligible, log, restored, total, existingOnly);
                        return;
                    }
                    if (withdraw && owners != null) {
                        int running = 0;
                        for (Pool p : verified) if (owners.matched(p)) running++;
                        busy = false;
                        status(running + " of " + total + " pool(s) have matching keys and verified reserves. Signing paused.");
                        copyableReport("Recovery diagnostic",
                                running + " of " + total + " pool(s) have verified reserves and matching owner keys. "
                                + "Signing remains paused. No recovery signature or withdrawal was sent.\n\n" + log.toString().trim());
                        loadKeys(); act.pools().refresh();
                        return;
                    }
                    if (!withdraw || owners == null || verified.isEmpty()) {
                        busy = false;
                        status("Reserve check finished ·  " + restored + " of " + total + " pool(s) verified.");
                        info(restored == total ? "Reserves verified" : "Recovery needs attention", restored + " of " + total + " pool(s) have both reserves verified on this node.\n\n"
                                + log.toString().trim() + (withdraw
                                    ? "\n\nNothing was withdrawn, because no pool's reserves could be verified. Saved recipes remain available below."
                                    : "\n\nSaved recipes remain available below. This was a reserve check only; nothing was withdrawn."));
                        loadKeys();
                        act.pools().refresh();
                        return;
                    }
                    // Unmatched or unsupported wallets never receive an exit exception.
                    busy = false;
                }
            });
    }

    /** Explicit opt-in authorizes one close and one confirmed collection, never unlimited signing. */
    private void autoWithdraw(List<Pool> verified, StringBuilder log, int restored, int total, boolean existingOnly) {
        status("Recovering and withdrawing eligible pools…");
        NodeApi n = act.node();
        if (n == null) { busy = false; status("Connect the node to withdraw the restored pools."); return; }
        new RestoreExit(act, n, existingOnly).run(verified, act.chainBlock(), new RestoreExit.Cb() {
            @Override public void onProgress(String line) {
                log.append(line).append('\n');
                act.runOnUiThread(() -> status(line));
            }
            @Override public void onDone(int withdrawn, int skipped) {
                act.runOnUiThread(() -> {
                    busy = false;
                    int notSubmitted = Math.max(skipped, total - withdrawn);
                    if (withdrawn > 0) resumeRecoveryCollection(24);
                    String result = withdrawn + " withdrawal(s) submitted; " + notSubmitted + " not submitted.";
                    status(result);
                    copyableReport(notSubmitted == 0 ? "Recovery submitted" : "Recovery needs attention",
                            restored + " of " + total + " pool(s) had verified reserves.\n\n" + result + "\n\n" + log.toString().trim()
                            + "\n\nSubmitted does not mean confirmed or collected. Keep the app and node running. "
                            + "After the withdrawal confirms, PandaPools attempts one collection into this wallet and verifies confirmation. "
                            + "Uncertain attempts stop for review; normal restored-pool signing remains paused.");
                    loadKeys(); act.pools().refresh();
                });
            }
        });
    }

    private void resumeRecoveryCollection(int remaining) {
        if (remaining <= 0) return; // keep-alive service continues after leaving the screen
        act.ui().postDelayed(() -> {
            NodeApi node = act.node();
            if (node == null) return;
            new CollectSweeper(act, node).run((cleared, pending, stranded) -> {
                if (cleared > 0) status("Recovery collection confirmed. Check your wallet balance.");
                if (pending > 0) resumeRecoveryCollection(remaining - 1);
            });
        }, 20000);
    }

    private void showRecoveryGuide() {
        info("How pool recovery works", "Recover on existing wallet handles the usual case: the original keys and current signing state are still on this node, including after a chain resync that preserved the wallet. Confirm that condition and choose your backup once. PandaPools checks recipes and keys, requests missing proofs from eurobuddha.com:9001, and attempts a bounded withdrawal followed by confirmed collection.\n\n"
                + "Use Recover & withdraw only for the separately explained seed-restore case. Matching keys alone do not prove previous signature use. Missing data, invalid proofs, counter regression and uncertain transactions stop with a report. A full node resync is never started automatically.\n\n"
                + "Keep a verified, password-encrypted combined pool and node backup off this device. A phone hosting pools must keep its node running and connected 24/7, preferably on reliable power and Wi-Fi. Extended downtime may strand reserves and require recovery.");
    }

    private boolean writeUri(android.net.Uri uri, String content) {
        try (java.io.OutputStream os = act.getContentResolver().openOutputStream(uri, "w")) {
            if (os == null) return false;
            os.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return true;
        } catch (Exception e) { return false; }
    }

    private String readUri(android.net.Uri uri) {
        try (java.io.InputStream is = act.getContentResolver().openInputStream(uri)) {
            if (is == null) return null;
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192]; int n;
            while ((n = is.read(buf)) > 0) {
                if (bo.size() + n > 8 * 1024 * 1024) return null;
                bo.write(buf, 0, n);
            }
            return new String(bo.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) { return null; }
    }

    // ---- small view helpers ----

    private LinearLayout dialogBox() {
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(act, 20);
        box.setPadding(pad, Ui.dp(act, 8), pad, 0);
        return box;
    }

    private EditText field(LinearLayout box, String label, String hint) {
        TextView l = text(label, Design.dim(), 12, false);
        l.setPadding(0, Ui.dp(act, 8), 0, Ui.dp(act, 2));
        box.addView(l);
        EditText e = new EditText(act);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        e.setHint(hint); e.setTextColor(Design.heading()); e.setHintTextColor(Design.dim2());
        e.setTextSize(16);
        box.addView(e);
        return e;
    }

    private void watch(EditText e, Runnable r) {
        e.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable ed) { r.run(); }
        });
    }

    private TextView text(String s, int color, int sp, boolean bold) {
        TextView t = new TextView(act);
        t.setText(s); t.setTextColor(color); t.setTextSize(sp);
        t.setTypeface(bold ? Design.typefaceBold() : Design.typeface());
        return t;
    }

    private View kv(String k, String v) { return kvColored(k, v, Design.text()); }

    private View kvColored(String k, String v, int valueColor) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, Ui.dp(act, 4), 0, 0);
        TextView kk = text(k, Design.dim(), 12, false);
        // Label keeps its natural width (capped), NOT weight-1: a weighted label was starved to ~1 char
        // (wrapping one glyph per line, e.g. "Your liquidity") whenever the value filled the row.
        kk.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        kk.setMaxWidth(Ui.dp(act, 190));
        TextView vv = text(v, valueColor, 12, false);
        vv.setGravity(Gravity.END);
        vv.setSingleLine(false);   // a long value wraps within its own column instead of overflowing it
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        vlp.leftMargin = Ui.dp(act, 8);
        vv.setLayoutParams(vlp);
        row.addView(kk); row.addView(vv);
        return row;
    }

    private static String fmt(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) return "—";
        return BigDecimal.valueOf(d).setScale(4, RoundingMode.DOWN).stripTrailingZeros().toPlainString();
    }

    private void status(String s) { status(s, null); }

    /** Status line, optionally tap-to-copy. `copyValue` is the FULL identifier the message mentions — a
     *  TxPoW id in a confirmation is the one thing a user needs to paste into an explorer, so it is printed
     *  whole (the row wraps) and one tap puts the complete value on the clipboard. */
    private void status(String s, String copyValue) {
        TextView st = find(R.id.lpStatus);
        st.setVisibility(s.isEmpty() ? View.GONE : View.VISIBLE);
        st.setText(s);
        if (copyValue == null || copyValue.isEmpty()) { st.setOnClickListener(null); st.setClickable(false); }
        else Ui.copyable(st, "transaction id", copyValue);
    }
    private void toast(String s) { status(s); }
    private void info(String title, String msg) {
        AlertDialog dialog = new AlertDialog.Builder(act).setTitle(title).setMessage(msg).setPositiveButton("OK", null).show();
        TextView message = dialog.findViewById(android.R.id.message);
        if (message != null) message.setTextIsSelectable(true);
    }

    private static BigDecimal parse(String s) {
        if (s == null || s.trim().isEmpty()) return null;
        try { return new BigDecimal(s.trim()); } catch (Exception e) { return null; }
    }
    private static String trim(BigDecimal b) { return b == null ? "—" : b.stripTrailingZeros().toPlainString(); }
    private static String trim8(BigDecimal b) { return b == null ? "—" : b.setScale(8, RoundingMode.DOWN).stripTrailingZeros().toPlainString(); }
    private static String trim2(BigDecimal b) { return b == null ? "—" : b.setScale(2, RoundingMode.HALF_UP).toPlainString(); }
}
