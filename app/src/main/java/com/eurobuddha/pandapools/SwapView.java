// VENDORED VERBATIM from apks/pandapools 0.9.81 app/src/main/java/com/eurobuddha/pandapools/SwapView.java by scripts/vendor-pandapools.sh — do not edit here; re-vendor.
package com.eurobuddha.pandapools;

import android.app.AlertDialog;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The Swap tab: pick the pair, type an amount, and the router splits the trade across EVERY pool for
 * that pair (water-filling, best marginal price first) into ONE transaction. Because the tx hard-codes
 * each recreated reserve, the aggregate "you receive" is GUARANTEED if the swap confirms — if any pool
 * moves first the whole tx is rejected pre-broadcast (no partial fill, no slippage surprise).
 */
public class SwapView extends BaseView {

    private final PoolTxn txn;

    private final List<Pool> pairPools = new ArrayList<>();
    private String pairLabel = "token";
    private boolean minimaToToken = true;
    // Static: the Dexicon hand-over screen (another Activity, same process) must see a swap mid-flight.
    static volatile boolean posting = false;

    // Receives the shared PoolRepository's scan result. Skip repaints WHILE POSTING so an in-flight swap's
    // quote/inputs aren't reset under the user; doSwap re-scans via pools().refresh() when the post lands.
    private final PoolBook.Listener poolListener = new PoolBook.Listener() {
        @Override public void onPools(List<Pool> pools) { if (posting) return; renderPools(pools); }
        @Override public void onError(String msg) { if (!posting) setBusy("Scan error: " + msg); }
    };

    private EditText amount;
    private LinearLayout quotePanel, swapActivity;
    private TextView fromTok, toTok, out, poolLine, swapBtn, status, flip, fromLabel, balanceLine, marketLine;

    // cached sendable balances keyed by tokenid (from the last `balance` fetch); the pair-token half is
    // recomputed against the CURRENTLY shown pair at render time, so switching pairs needs no re-fetch.
    private final Map<String, String> sendableByTok = new HashMap<>();

    // one-shot repaint so the first async MEXC price shows without waiting for the next block
    private final Runnable marketRepaint = this::renderMarket;

    public SwapView(MainActivity a) {
        super(a, R.layout.view_swap);
        a.pools().subscribe(poolListener);
        txn = new PoolTxn(a.node());

        poolLine   = find(R.id.swapPool);
        balanceLine = find(R.id.swapBalance);
        marketLine = find(R.id.swapMarket);
        amount     = find(R.id.swapAmount);
        fromTok    = find(R.id.swapFromTok);
        toTok      = find(R.id.swapToTok);
        fromLabel  = find(R.id.swapFromLabel);
        out        = find(R.id.swapOut);
        quotePanel = find(R.id.swapQuote);
        swapActivity = find(R.id.swapActivity);
        swapBtn    = find(R.id.swapBtn);
        status     = find(R.id.swapStatus);
        flip       = find(R.id.swapFlip);

        Ui.panel(find(R.id.swapFromCard));
        Ui.panel(find(R.id.swapToCard));
        Ui.primaryButton(swapBtn);

        poolLine.setTextColor(Design.dim());
        balanceLine.setTextColor(Design.dim());
        marketLine.setTextColor(Design.dim());
        amount.setTextColor(Design.heading());
        amount.setHintTextColor(Design.dim2());
        out.setTextColor(Design.heading());
        fromTok.setTextColor(Design.accent());
        toTok.setTextColor(Design.accent());
        fromLabel.setTextColor(Design.dim());
        status.setTextColor(Design.dim());
        flip.setTextColor(Design.accent());
        flip.setTypeface(Design.typefaceBold());

        flip.setOnClickListener(v -> { minimaToToken = !minimaToToken; syncLabels(); requote(); });
        swapBtn.setOnClickListener(v -> confirmAndSwap());
        amount.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable e) { requote(); }
        });

        setBusy("Finding pools…");
    }

    @Override public void refresh() { act.pools().requestNow(poolListener); }
    @Override public void onShown() { act.pools().requestNow(poolListener); }
    @Override public boolean isBusy() { return posting; }
    @Override public void onDestroy() {
        act.pools().unsubscribe(poolListener);
        if (marketLine != null) marketLine.removeCallbacks(marketRepaint);
    }

    // ---- pool discovery (shared scan — see poolListener) ----

    private void renderPools(List<Pool> pools) {
        renderActivity();   // keep the inline recent-activity strip fresh on every scan
        List<List<Pool>> byTok = PoolRouter.byToken(pools);
        if (byTok.isEmpty()) {
            pairPools.clear();
            poolLine.setText("No live pools yet — create one in the My LP tab to seed liquidity.");
            swapBtn.setEnabled(false); Ui.primaryButton(swapBtn);
            out.setText("0.0");
            quotePanel.removeAllViews();
            renderBalances();
            return;
        }
        // deepest pair first (byToken already sorts by aggregate depth)
        pairPools.clear();
        pairPools.addAll(byTok.get(0));
        pairLabel = pairPools.get(0).tokenLabel();
        BigDecimal depth = PoolRouter.aggregateDepth(pairPools);
        int n = pairPools.size();
        poolLine.setText("MINIMA / " + pairLabel + "   ·   " + n + (n == 1 ? " pool" : " pools")
                + "   ·   depth " + trim(depth) + " MINIMA");
        syncLabels();
        renderBalances(); // paint instantly from the cached snapshot against the now-current pair…
        loadBalances();   // …then refresh the cache (also fires on onNewBlock via scan) and re-render
        requote();
    }

    // ---- sendable balances (both legs) ----

    /** Fetch MINIMA + the pair token's sendable balance and render them under the pool line. Mirrors
     *  {@link WalletView#loadBalances()} but keeps only the two amounts the swap page needs. */
    private void loadBalances() {
        if (act.node() == null) return;
        act.node().cmd("balance", new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                sendableByTok.clear();
                JSONArray arr = j.optJSONArray("response");
                if (arr != null) for (int i = 0; i < arr.length(); i++) {
                    JSONObject b = arr.optJSONObject(i);
                    if (b == null) continue;
                    String tid = b.optString("tokenid", "");
                    String snd = b.optString("sendable", "0");
                    // cache EVERY sendable so the pair-token half can be resolved at render time against
                    // whichever pair is shown — no dependency on which pair was known when we fetched.
                    if (Util.isMinima(tid)) sendableByTok.put(Util.MINIMA_TOKENID, snd);
                    else if (!tid.isEmpty()) sendableByTok.put(tid, snd);
                }
                renderBalances();
            }
            @Override public void onError(String m) {}
        });
    }

    private void renderBalances() {
        if (balanceLine == null) return;
        if (pairPools.isEmpty()) {
            balanceLine.setText(""); balanceLine.setVisibility(View.GONE);
            renderMarket();   // hides the market line too
            return;
        }
        // resolve BOTH legs against the CURRENTLY shown pair, from the cached balance snapshot
        String pairTid = pairPools.get(0).tok;
        String m = sendableByTok.get(Util.MINIMA_TOKENID);               // MINIMA sendable (null ⇒ shows 0)
        String t = pairTid == null ? null : sendableByTok.get(pairTid);  // pair-token sendable (null ⇒ none held ⇒ 0)
        balanceLine.setVisibility(View.VISIBLE);
        // ALWAYS show BOTH halves; round DOWN to 6 dp. The node returns up to 42 dp for MINIMA — that giant
        // number filled the line and pushed the "· <Y> <LABEL>" tail out of view (the "missing USDT" bug).
        balanceLine.setText("Your balance: " + fmt6s(m) + " MINIMA  ·  " + fmt6s(t) + " " + pairLabel);
        refreshMarketFeed();   // kick a background fetch + a one-shot repaint
        renderMarket();        // paint whatever snapshot we have right now
    }

    // ---- live external market price (DISPLAY ONLY) ----

    /** Kick a rate-limited background MEXC fetch and schedule ONE delayed repaint, so the first value shows
     *  without waiting for the next block. USDT pairs only; all network work stays off the main thread. */
    private void refreshMarketFeed() {
        if (!isUsdtPair()) return;
        MarketPrice.refreshAsync();
        if (marketLine != null) {
            marketLine.removeCallbacks(marketRepaint);   // at most one pending repaint
            marketLine.postDelayed(marketRepaint, 1200);
        }
    }

    /** Show live MEXC MINIMA/USDT vs the blended pool spot (Σ reserveT / Σ reserveM). Purely a read-only
     *  arbitrage hint — only rendered when the pair token is USDT (the feed is MINIMA/USDT). */
    private void renderMarket() {
        if (marketLine == null) return;
        if (!isUsdtPair()) { marketLine.setVisibility(View.GONE); return; }

        BigDecimal sumM = BigDecimal.ZERO, sumT = BigDecimal.ZERO;
        for (Pool p : pairPools) {
            if (p != null && p.reserveM != null) sumM = sumM.add(p.reserveM);
            if (p != null && p.reserveT != null) sumT = sumT.add(p.reserveT);
        }
        if (sumM.signum() <= 0) { marketLine.setVisibility(View.GONE); return; }   // guard divide-by-zero
        BigDecimal poolSpot = sumT.divide(sumM, 12, RoundingMode.DOWN);            // USDT per MINIMA

        marketLine.setVisibility(View.VISIBLE);
        if (!MarketPrice.fresh()) { marketLine.setText("Market price unavailable (MEXC)"); return; }

        BigDecimal mid = BigDecimal.valueOf(MarketPrice.mid());   // fresh() ⇒ mid > 0
        BigDecimal pct = poolSpot.subtract(mid).divide(mid, 8, RoundingMode.DOWN).multiply(new BigDecimal("100"));
        String verdict;
        if (pct.abs().compareTo(new BigDecimal("0.1")) < 0) {
            verdict = "at market";
        } else {
            String mag = pct.abs().setScale(1, RoundingMode.HALF_UP).toPlainString();
            verdict = mag + "% " + (pct.signum() > 0 ? "above market" : "below market");
        }
        marketLine.setText("Market ≈ " + fmt6(mid) + " USDT/MINIMA (MEXC)  ·  Pool " + fmt6(poolSpot)
                + "  ·  " + verdict);
    }

    /** True only when the current pair token is USDT (the MEXC feed is MINIMA/USDT — nothing else compares). */
    private boolean isUsdtPair() {
        return !pairPools.isEmpty() && pairLabel != null && pairLabel.equalsIgnoreCase("USDT");
    }

    /** Round DOWN to ≤ 6 dp and strip trailing zeros for tidy display (locale-independent). */
    private static String fmt6(BigDecimal b) {
        if (b == null) return "0";
        return Util.tidyAmount(b.setScale(6, RoundingMode.DOWN).toPlainString());
    }

    /** {@link #fmt6} for a raw node amount string; null/blank/malformed/none-held ⇒ "0". */
    private static String fmt6s(String amt) {
        return fmt6(Util.decOr(amt, null));
    }

    private void syncLabels() {
        fromTok.setText(minimaToToken ? "MINIMA" : pairLabel);
        toTok.setText(minimaToToken ? pairLabel : "MINIMA");
    }

    // ---- live quote (routed) ----

    private void requote() {
        if (pairPools.isEmpty()) return;
        BigDecimal in = parse(amount.getText().toString());
        quotePanel.removeAllViews();
        if (in == null || in.signum() <= 0) {
            out.setText("0.0");
            swapBtn.setEnabled(false); Ui.primaryButton(swapBtn);
            return;
        }
        PoolRouter.Route r = PoolRouter.route(pairPools, minimaToToken, in);
        if (!r.ok) {
            out.setText("—");
            addRow("This trade is too large for the pools' depth.", Design.amber());
            swapBtn.setEnabled(false); Ui.primaryButton(swapBtn);
            return;
        }
        out.setText(trim8(r.totalOut));
        addRow2("Rate", "≈ " + trim8(r.effPrice) + " " + pairLabel + " / MINIMA");
        addRow2("Price impact", impact(r) + " %");
        if (r.poolsAvailable > 1)
            addRow2("Routed across", r.poolsUsed + " of " + r.poolsAvailable + " pools"
                    + (r.capped ? " (top " + PoolRouter.MAX_POOLS + ")" : ""));
        addRow2("Pool fee (0.50%)", "kept by LPs");
        addRow2("You receive", trim(r.totalOut) + " " + (minimaToToken ? pairLabel : "MINIMA"));

        swapBtn.setEnabled(!posting);
        swapBtn.setText(posting ? "SWAPPING…" : "SWAP");
        Ui.primaryButton(swapBtn);
    }

    private String impact(PoolRouter.Route r) {
        if (r.spotBefore == null || r.spotBefore.signum() == 0) return "0.00";
        BigDecimal d = r.effPrice.subtract(r.spotBefore).abs()
                .divide(r.spotBefore, 6, RoundingMode.HALF_UP).multiply(new BigDecimal("100"));
        return d.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    // ---- execute ----

    private void confirmAndSwap() {
        if (pairPools.isEmpty() || posting) return;
        final boolean dir = minimaToToken;
        final List<Pool> pin = new ArrayList<>(pairPools);   // pin the pools + direction being confirmed
        BigDecimal in = parse(amount.getText().toString());
        if (in == null || in.signum() <= 0) { toast("Enter an amount first."); return; }
        final PoolRouter.Route r = PoolRouter.route(pin, dir, in);
        if (!r.ok) { toast("That trade is too large for these pools."); return; }

        String pay  = trim(r.totalIn) + " " + (dir ? "MINIMA" : pairLabel);
        String recv = trim(r.totalOut) + " " + (dir ? pairLabel : "MINIMA");
        String routed = r.poolsUsed > 1 ? "\nRouted across " + r.poolsUsed + " pools in one transaction." : "";
        new AlertDialog.Builder(act)
                .setTitle("Confirm swap")
                .setMessage("Pay  " + pay + "\nReceive  " + recv + "\n\nPrice impact " + impact(r) + "%." + routed
                        + "\n\nThis posts a real on-chain transaction — if a pool moves before it confirms the "
                        + "swap is rejected (you keep your funds).")
                .setPositiveButton("Swap", (d, w) -> doSwap(r, dir))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void doSwap(PoolRouter.Route r, boolean dir) {
        posting = true;
        swapBtn.setEnabled(false); swapBtn.setText("SWAPPING…"); Ui.primaryButton(swapBtn);
        setStatus("Building + posting your swap…", Design.dim());
        final String fromT = dir ? "MINIMA" : pairLabel, toT = dir ? pairLabel : "MINIMA";
        final String summary = "−" + trim8(r.totalIn) + " " + fromT + "  ·  +" + trim8(r.totalOut) + " " + toT;
        txn.swap(r, dir, new PoolTxn.Result() {
            @Override public void onPosted(String txpowid) {
                ActivityLog.record(act, ActivityLog.SWAP, summary, txpowid, act.chainBlock());
                act.runOnUiThread(() -> {
                    posting = false;
                    amount.setText("");
                    setStatus("Swap submitted. Check Activity for confirmation. " + txpowid, Design.amber());
                    swapBtn.setEnabled(true); swapBtn.setText("SWAP"); Ui.primaryButton(swapBtn);
                    act.pools().refresh();
                });
            }
            @Override public void onFailed(String message) {
                ActivityLog.recordFailed(act, ActivityLog.SWAP, summary, message);
                act.runOnUiThread(() -> {
                    posting = false;
                    setStatus("Swap: " + message, NodeApi.ERR_WRITE_UNCERTAIN.equals(message) ? Design.amber() : Design.red());
                    swapBtn.setEnabled(true); swapBtn.setText("SWAP"); Ui.primaryButton(swapBtn);
                });
            }
        });
    }

    // ---- small helpers ----

    private void addRow2(String k, String v) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, Ui.dp(act, 4), 0, Ui.dp(act, 4));
        TextView kk = new TextView(act);
        kk.setText(k); kk.setTextColor(Design.dim()); kk.setTextSize(13);
        kk.setTypeface(Design.typeface());
        kk.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView vv = new TextView(act);
        vv.setText(v); vv.setTextColor(Design.text()); vv.setTextSize(13);
        vv.setTypeface(Design.typeface());
        vv.setGravity(Gravity.END);
        row.addView(kk); row.addView(vv);
        quotePanel.addView(row);
    }

    private void addRow(String s, int color) {
        TextView t = new TextView(act);
        t.setText(s); t.setTextColor(color); t.setTextSize(13);
        t.setPadding(0, Ui.dp(act, 4), 0, Ui.dp(act, 4));
        quotePanel.addView(t);
    }

    // ---- inline recent-activity strip (borrowed from MinimaSwap: activity right under the swap) ----

    void refreshConfirmations() { renderActivity(); }

    private void renderActivity() {
        swapActivity.removeAllViews();
        java.util.List<ActivityLog.Entry> recent = ActivityLog.recent(act, 3);
        if (recent.isEmpty()) return;
        int cb = act.chainBlock();

        TextView h = new TextView(act);
        h.setText("RECENT ACTIVITY"); h.setAllCaps(true); h.setTextColor(Design.dim()); h.setTextSize(11);
        h.setLetterSpacing(0.1f); h.setPadding(0, 0, 0, Ui.dp(act, 6));
        swapActivity.addView(h);

        for (ActivityLog.Entry e : recent) swapActivity.addView(activityRow(e, cb));

        TextView all = new TextView(act);
        all.setText("See all  →  Activity"); all.setTextColor(Design.accent()); all.setTextSize(13);
        all.setTypeface(Design.typefaceBold()); all.setPadding(0, Ui.dp(act, 10), 0, 0);
        all.setOnClickListener(v -> act.goToTab(MainActivity.TAB_ACTIVITY));
        swapActivity.addView(all);
    }

    private View activityRow(ActivityLog.Entry e, int cb) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Ui.dp(act, 5), 0, Ui.dp(act, 5));

        TextView desc = new TextView(act);
        desc.setText(e.summary); desc.setTextColor(Design.text()); desc.setTextSize(13);
        // Wraps instead of clipping at one line: a summary can carry a full tokenid when a token name has
        // not resolved, and this row has no tap-to-copy of its own to recover a clipped one from.
        desc.setMaxLines(3);
        desc.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(desc);

        int color = e.failed ? Design.red() : e.confirmed(cb) ? Design.success() : Design.amber();
        TextView st = new TextView(act);
        st.setText(e.statusText(cb)); st.setTextColor(color); st.setTextSize(11);
        st.setTypeface(Design.typefaceBold()); st.setGravity(Gravity.END);
        st.setPadding(Ui.dp(act, 8), 0, 0, 0);
        row.addView(st);
        return row;
    }

    private void setBusy(String s) { poolLine.setText(s); swapBtn.setEnabled(false); Ui.primaryButton(swapBtn); }
    private void setStatus(String s, int color) {
        status.setVisibility(s.isEmpty() ? View.GONE : View.VISIBLE);
        status.setText(s); status.setTextColor(color);
    }
    private void toast(String s) { setStatus(s, Design.amber()); }

    private static BigDecimal parse(String s) {
        if (s == null) return null;
        s = s.trim();
        if (s.isEmpty()) return null;
        try { return new BigDecimal(s); } catch (Exception e) { return null; }
    }
    private static String trim(BigDecimal b) {
        if (b == null) return "—";
        return b.stripTrailingZeros().toPlainString();
    }
    private static String trim8(BigDecimal b) {
        if (b == null) return "—";
        return b.setScale(8, RoundingMode.DOWN).stripTrailingZeros().toPlainString();
    }
}
