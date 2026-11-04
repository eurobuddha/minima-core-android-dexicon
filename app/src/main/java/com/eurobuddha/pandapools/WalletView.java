// VENDORED VERBATIM from apks/pandapools 0.9.82 app/src/main/java/com/eurobuddha/pandapools/WalletView.java by scripts/vendor-pandapools.sh — do not edit here; re-vendor.
package com.eurobuddha.pandapools;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * The Wallet tab: the node's AVAILABLE (non-pool) balances — a port of the utxoWallet's rich balances view.
 * `sendable` is the amount free OUTSIDE any contract; funds locked in your pools show as LOCKED. So this is
 * exactly "what can I seed new liquidity with?". Rich cards: token icon (deterministic identicon base +
 * real icon over it), web-validation checkmark, sendable / locked / pending split, tap-for-detail. A
 * copyable receive address sits at the top so you can top up.
 */
public class WalletView extends BaseView {

    private static final int MP = LinearLayout.LayoutParams.MATCH_PARENT;
    private static final int WC = LinearLayout.LayoutParams.WRAP_CONTENT;

    private final LinearLayout container;
    private final List<TokenBalance> balances = new ArrayList<>();
    private String receiveAddr = "";
    private boolean loading = false;
    /** When the last `balance` reply landed — drives the "updated Ns ago" stamp (AtomiX parity). */
    private long lastBalanceUpdate = 0;
    private boolean unpaired = false;
    private String balanceError = "";
    /** Every known pool covenant address, so the coin list can say WHICH coins are the locked ones. */
    private PandaAddrBook addrBook;

    public WalletView(MainActivity a) {
        super(a, R.layout.view_balances);
        container = find(R.id.balancesContainer);
        root.setBackgroundColor(Design.bg());
        loadAddress();
        refresh();
    }

    @Override public void refresh() { loadBalances(); }
    @Override public void onShown() { loadAddress(); loadBalances(); }
    @Override public void onNewBlock() { loadBalances(); }
    @Override public void onStop() { container.removeCallbacks(refreshTask); }
    @Override public void onDestroy() { container.removeCallbacks(refreshTask); }

    private void loadAddress() {
        if (act.node() == null) return;
        act.node().cmd("getaddress", new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                JSONObject r = j.optJSONObject("response");
                if (r != null) { receiveAddr = r.optString("miniaddress", r.optString("address", "")); renderCards(); }
            }
            @Override public void onError(String m) {}
        });
    }

    private void loadBalances() {
        if (loading || act.node() == null) return;
        loading = true;
        act.node().cmd("balance", new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                loading = false;
                JSONArray arr = FundingCoins.rows(j);
                if (arr == null) { onError(j.optString("error", j.optString("message", "Balance unavailable."))); return; }
                unpaired = false;
                balanceError = "";
                lastBalanceUpdate = System.currentTimeMillis();
                balances.clear();
                if (arr != null) for (int i = 0; i < arr.length(); i++) {
                    JSONObject b = arr.optJSONObject(i);
                    if (b != null) balances.add(TokenBalance.from(b));
                }
                // MINIMA first, then largest sendable first
                Collections.sort(balances, new Comparator<TokenBalance>() {
                    @Override public int compare(TokenBalance x, TokenBalance y) {
                        if (x.isMinima() != y.isMinima()) return x.isMinima() ? -1 : 1;
                        return bd(y.sendable).compareTo(bd(x.sendable));
                    }
                });
                renderCards();
            }
            // Preserve the successful-read timestamp. Failed attempts cannot freshen old balances.
            @Override public void onError(String m) {
                loading = false;
                balanceError = m;
                unpaired = NodeApi.ERR_NOT_ENABLED.equals(m);
                if (unpaired) balances.clear();
                renderCards();
            }
        });
    }

    private final Runnable refreshTask = this::renderCards;
    /** Coalesce the async web-validation callbacks (one per token) into a single re-render. */
    private void scheduleRefresh() {
        container.removeCallbacks(refreshTask);
        container.postDelayed(refreshTask, 120);
    }

    private void renderCards() {
        container.removeAllViews();
        container.addView(receiveHeader());
        if (!balanceError.isEmpty()) container.addView(mono("Balance refresh failed: " + balanceError
                + (balances.isEmpty() ? "" : " Showing the last successful read."), 12f, Design.amber(), false));
        if (balances.isEmpty()) {
            TextView tv = new TextView(act);
            tv.setText(unpaired ? "Connect your node in Minima Core → Apps to see your balances."
                    : loading ? "Loading balances…" : "No balances yet.");
            tv.setTextColor(Design.dim()); tv.setGravity(Gravity.CENTER); tv.setPadding(0, dp(40), 0, 0);
            container.addView(tv);
            return;
        }
        for (TokenBalance b : balances) container.addView(buildCard(b));
    }

    // ---- receive address header ----

    private View receiveHeader() {
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(14), dp(12), dp(14), dp(12));
        box.setBackground(borderBox(Design.surface(), Design.border()));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MP, WC);
        lp.bottomMargin = dp(10); box.setLayoutParams(lp);

        TextView label = mono("AVAILABLE TO POOL", 10f, Design.dim(), true);
        label.setLetterSpacing(0.08f);
        box.addView(label);
        TextView note = mono("Sendable balances below — funds locked in your pools show as LOCKED.", 11f, Design.dim(), false);
        note.setPadding(0, dp(3), 0, dp(8));
        box.addView(note);

        TextView addr = mono(receiveAddr.isEmpty() ? "…" : "Receive:  " + receiveAddr, 12f, Design.accent(), false);
        // Wraps rather than ellipsising: a middle-truncated address is unusable, and this is the value a
        // sender has to reproduce exactly.
        addr.setSingleLine(false);
        Ui.copyable(addr, "address", receiveAddr);
        box.addView(addr);
        TextView hint = mono("tap to copy your receive address", 10f, Design.dim(), false);
        hint.setPadding(0, dp(3), 0, 0);
        box.addView(hint);
        TextView tools = mono("Consolidate MINIMA coins", 14f, Design.accent(), true);
        tools.setPadding(0, dp(12), 0, dp(12));
        tools.setOnClickListener(v -> new WalletTools(act).showConsolidate(Util.MINIMA_TOKENID, "MINIMA"));
        box.addView(tools);
        TextView check = mono("Check node connection", 14f, Design.accent(), true);
        check.setPadding(0, dp(12), 0, dp(12));
        check.setOnClickListener(v -> new WalletTools(act).checkNode()); box.addView(check);
        if (act.node().hasInterruptedWrite()) {
            TextView resolve = mono("Resolve interrupted write", 14f, Design.amber(), true);
            resolve.setPadding(0, dp(12), 0, dp(12));
            resolve.setOnClickListener(v -> new WalletTools(act).resolveInterruptedWrite()); box.addView(resolve);
        }
        return box;
    }

    // ---- one balance card (from the utxoWallet dapp) ----

    private View buildCard(TokenBalance b) {
        boolean nativeCoin = b.isMinima();

        LinearLayout card = new LinearLayout(act);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        card.setBackground(nativeCoin
                ? leftAccentBox(Design.bg(), Design.border(), Design.accent())
                : borderBox(Design.bg(), Design.border()));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(MP, WC);
        clp.bottomMargin = dp(8);
        card.setLayoutParams(clp);

        LinearLayout top = new LinearLayout(act);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        FrameLayout slot = new FrameLayout(act);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(dp(40), dp(40));
        slp.rightMargin = dp(12);
        slot.setLayoutParams(slp);
        if (nativeCoin) {
            slot.setBackground(borderBox(0xFFFFFFFF, Design.border()));        // official coin: white tile, black M
            ImageView g = new ImageView(act);
            g.setLayoutParams(new FrameLayout.LayoutParams(MP, MP));
            g.setScaleType(ImageView.ScaleType.FIT_CENTER);
            int mp = dp(8); g.setPadding(mp, mp, mp, mp);
            Bitmap logo = renderMinimaLogo(dp(40));
            if (logo != null) g.setImageBitmap(logo); else g.setImageBitmap(Identicon.minima(dp(40), Design.accent()));
            slot.addView(g);
            ImageView badge = new ImageView(act);
            int bs = dp(15);
            badge.setLayoutParams(new FrameLayout.LayoutParams(bs, bs, Gravity.BOTTOM | Gravity.END));
            badge.setImageBitmap(Identicon.checkBadge(bs));
            slot.addView(badge);
        } else {
            slot.setBackground(borderBox(Design.surface(), Design.border()));
            ImageView icon = new ImageView(act);
            icon.setLayoutParams(new FrameLayout.LayoutParams(MP, MP));
            icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
            icon.setImageBitmap(Identicon.forToken(b.tokenid, dp(40)));
            slot.addView(icon);
            ImageLoader.loadOver(act, b.meta.iconUrl, icon, null);
        }

        if (!nativeCoin && notEmpty(b.meta.webvalidate)) {
            WebValidate.ensure(act, b.tokenid, b.meta.webvalidate, this::scheduleRefresh);
            if (Boolean.TRUE.equals(WebValidate.status(b.tokenid))) {
                ImageView badge = new ImageView(act);
                int bs = dp(15);
                badge.setLayoutParams(new FrameLayout.LayoutParams(bs, bs, Gravity.BOTTOM | Gravity.END));
                badge.setImageBitmap(Identicon.checkBadge(bs));
                slot.addView(badge);
            }
        }
        top.addView(slot);

        LinearLayout ident = new LinearLayout(act);
        ident.setOrientation(LinearLayout.VERTICAL);
        String symbol = notEmpty(b.meta.ticker) ? b.meta.ticker : b.name;
        TextView sym = mono(symbol.toUpperCase(), 13f, Design.heading(), true);
        sym.setLetterSpacing(0.115f); sym.setMaxLines(1); sym.setEllipsize(TextUtils.TruncateAt.END);
        ident.addView(sym);
        String secondary = (notEmpty(b.name) && !b.name.equalsIgnoreCase(symbol)) ? b.name : null;
        if (b.isNft()) secondary = (secondary == null ? "NFT" : secondary + "  ·  NFT");
        if (secondary != null) {
            TextView nm = mono(secondary, 11f, b.isNft() ? Design.accent() : Design.dim(), false);
            nm.setMaxLines(1); nm.setEllipsize(TextUtils.TruncateAt.END); nm.setPadding(0, dp(2), 0, 0);
            ident.addView(nm);
        }
        top.addView(ident, new LinearLayout.LayoutParams(0, WC, 1f));

        LinearLayout amtCol = new LinearLayout(act);
        amtCol.setOrientation(LinearLayout.VERTICAL);
        amtCol.setGravity(Gravity.END);
        TextView amt = mono(Util.tidyAmount(b.sendable), 17f, Design.heading(), true);
        amt.setGravity(Gravity.END);
        amtCol.addView(amt);
        TextView cnt = mono((b.coins + (b.coins == 1 ? " coin" : " coins")).toUpperCase(), 10f, Design.dim(), false);
        cnt.setLetterSpacing(0.04f); cnt.setGravity(Gravity.END); cnt.setPadding(0, dp(3), 0, 0);
        amtCol.addView(cnt);
        card.addView(top);
        card.addView(amtCol, new LinearLayout.LayoutParams(MP, WC));

        // The full breakdown, ALWAYS — zeros included. Hiding a zero is what made this unreadable: on a node
        // whose funds are all in a pool you saw a headline 0 and nothing explaining where the money went.
        TextView bd = mono(b.breakdown(lastBalanceUpdate, System.currentTimeMillis()), 11.5f, Design.dim(), false);
        bd.setPadding(0, dp(6), 0, 0);
        card.addView(bd);

        card.setOnClickListener(v -> showTokenDetail(b));
        return card;
    }

    // ---- token detail ----

    private void showTokenDetail(TokenBalance b) {
        android.widget.ScrollView sv = new android.widget.ScrollView(act);
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(16), dp(20), dp(16));
        box.setBackgroundColor(Design.bg());
        sv.addView(box);

        ImageView big = new ImageView(act);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(128), dp(128));
        ip.gravity = Gravity.CENTER_HORIZONTAL; ip.bottomMargin = dp(6); big.setLayoutParams(ip);
        if (b.isMinima()) {
            big.setBackgroundColor(0xFFFFFFFF); int mp = dp(20); big.setPadding(mp, mp, mp, mp);
            Bitmap logo = renderMinimaLogo(dp(128));
            if (logo != null) big.setImageBitmap(logo); else big.setImageBitmap(Identicon.minima(dp(128), Design.accent()));
            big.setScaleType(ImageView.ScaleType.FIT_CENTER);
        } else {
            big.setImageBitmap(Identicon.forToken(b.tokenid, dp(128))); ImageLoader.loadOver(act, b.meta.iconUrl, big, null);
        }
        box.addView(big);
        if (b.hasIcon()) {
            big.setOnClickListener(v -> showImageFull(b.meta.iconUrl));
            TextView hint = new TextView(act);
            hint.setText(b.isNft() ? "Tap image for full resolution" : "Tap image to enlarge");
            hint.setTextColor(Design.dim()); hint.setTextSize(11f); hint.setGravity(Gravity.CENTER);
            hint.setPadding(0, 0, 0, dp(8)); box.addView(hint);
        }

        TextView title = new TextView(act);
        title.setText(b.name + (b.isNft() ? "   ·  NFT" : ""));
        title.setTextColor(Design.accent()); title.setTextSize(18f);
        title.setGravity(Gravity.CENTER); title.setTypeface(Design.typeface(), Typeface.BOLD);
        title.setPadding(0, 0, 0, dp(8)); box.addView(title);

        // All four, always — same rule as the card. A hidden zero is what we're fixing.
        addKv(box, "Sendable", Util.tidyAmount(b.sendable));
        addKv(box, "Confirmed", Util.tidyAmount(b.confirmed));
        addKv(box, "Unconfirmed", Util.tidyAmount(b.unconfirmed));
        addKv(box, "Locked ≈", b.locked());
        addKv(box, "Coins", String.valueOf(b.coins));
        addKv(box, "Supply", Util.tidyAmount(b.total));
        if (notEmpty(b.meta.ticker)) addKv(box, "Ticker", b.meta.ticker);
        if (notEmpty(b.meta.decimals)) addKv(box, "Decimals", b.meta.decimals);
        if (notEmpty(b.meta.owner)) addKv(box, "Owner", b.meta.owner);

        TextView consolidate = mono("Consolidate " + b.name + " coins", 14f, Design.accent(), true);
        consolidate.setPadding(0, dp(12), 0, dp(12));
        consolidate.setOnClickListener(v -> new WalletTools(act).showConsolidate(b.tokenid, b.name));
        box.addView(consolidate);
        box.addView(sectionLabel("Coins"));
        TextView coinsView = new TextView(act);
        coinsView.setText("Loading coins…");
        coinsView.setTextColor(Design.dim()); coinsView.setTextSize(12f); coinsView.setTypeface(Typeface.MONOSPACE);
        // Selectable so any single coinid can be lifted out; the copy row below grabs the whole list at once.
        coinsView.setTextIsSelectable(true);
        TextView copyAll = new TextView(act);
        copyAll.setText("copy all coins");
        copyAll.setTextColor(Design.accent()); copyAll.setTextSize(12f);
        copyAll.setPadding(0, dp(2), 0, dp(6));
        copyAll.setOnClickListener(v -> {
            CharSequence all = coinsView.getText();
            if (all == null || all.length() == 0) return;
            Ui.copyable(copyAll, "coins", all.toString());
            copyAll.performClick();
        });
        box.addView(copyAll);
        box.addView(coinsView);
        loadCoins(b, coinsView);

        box.addView(sectionLabel("Token ID"));
        TextView idv = new TextView(act);
        idv.setText(b.tokenid); idv.setTextColor(Design.text()); idv.setTextSize(12f);
        idv.setTextIsSelectable(true); idv.setTypeface(Typeface.MONOSPACE);
        box.addView(idv);

        if (notEmpty(b.meta.description)) {
            box.addView(sectionLabel("Description"));
            TextView d = new TextView(act);
            d.setText(b.meta.description); d.setTextColor(Design.dim()); d.setTextSize(13f);
            box.addView(d);
        }
        if (notEmpty(b.meta.externalUrl)) box.addView(linkRow("Website", b.meta.externalUrl));
        if (notEmpty(b.meta.webvalidate)) box.addView(linkRow("Web validation", b.meta.webvalidate));

        new AlertDialog.Builder(act).setView(sv).setPositiveButton("Close", null).show();
    }

    // ---- coin list (AtomiX's coin dump, tagged for PandaPools) ----

    /**
     * List the coins the node counts as relevant for this token, largest first, tagging the ones sitting at
     * a pool covenant address. That turns the section into a direct answer to "why is confirmed bigger than
     * sendable" — those tagged coins ARE the difference.
     *
     * Count first using balance, then list only a small token set. A reactive too-long fallback
     * alone cannot protect legacy nodes: Android may kill the receiver before delivering a reply.
     */
    private void loadCoins(TokenBalance b, TextView out) {
        if (act.node() == null) { out.setText("No node connection."); return; }
        if (addrBook == null) { addrBook = new PandaAddrBook(act); addrBook.seed(); }
        act.node().cmd("balance tokenid:" + b.tokenid, new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                int count = FundingCoins.coinsFor(j, b.tokenid);
                if (!FundingCoins.listIsSafe(count)) {
                    out.setText(count < 0 ? "Coin count unavailable. Try again when the node responds."
                            : "The node reports " + count + " coins. Full coin listing is paused to protect this phone's node connection. "
                            + "Balances above remain available. Use Consolidate to combine wallet coins."); return;
                }
                final String base = "coins relevant:true tokenid:" + b.tokenid;
                act.node().cmd(base + " sendable:true", new NodeApi.Cb() {
                    @Override public void onResult(JSONObject js) {
                        if (FundingCoins.rows(js) == null) { out.setText("Could not read spendable coins. Try again."); return; }
                        withSendable(base, sendableIds(js), out);
                    }
                    @Override public void onError(String m) { out.setText("Could not load coins: " + m); }
                });
            }
            @Override public void onError(String m) { out.setText("Could not load coins: " + m); }
        });
    }

    private void withSendable(final String base, final java.util.Set<String> sendable, final TextView out) {
        act.node().cmd(base, new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) { out.setText(renderCoins(j, sendable, false)); }
            @Override public void onError(String m) {
                if (!NodeApi.ERR_TOO_LONG.equals(m)) { out.setText("Couldn't load coins: " + m); return; }
                act.node().cmd(base + " sendable:true", new NodeApi.Cb() {
                    @Override public void onResult(JSONObject j2) { out.setText(renderCoins(j2, sendable, true)); }
                    @Override public void onError(String m2) { out.setText("Too many coins to list."); }
                });
            }
        });
    }

    private static java.util.Set<String> sendableIds(JSONObject j) {
        java.util.Set<String> ids = new java.util.HashSet<>();
        JSONArray arr = j.optJSONArray("response");
        if (arr != null) for (int i = 0; i < arr.length(); i++) {
            JSONObject c = arr.optJSONObject(i);
            if (c != null) {
                String id = c.optString("coinid", "");
                if (!id.isEmpty()) ids.add(id);
            }
        }
        return ids;
    }

    private CharSequence renderCoins(JSONObject j, java.util.Set<String> sendable, boolean sendableOnly) {
        JSONArray arr = FundingCoins.rows(j);
        if (arr == null) return "Could not read coins. Try again.";
        if (arr.length() == 0) return "No coins.";
        List<Coin> list = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject c = arr.optJSONObject(i);
            if (c != null) list.add(Coin.from(c));
        }
        Collections.sort(list, (x, y) -> bd(y.amount).compareTo(bd(x.amount)));

        // EVERY coin, with its FULL coinid — this is the audit view, so nothing is capped or elided.
        StringBuilder sb = new StringBuilder();
        if (sendableOnly) sb.append("(the node's reply was over its size cap — sendable coins only)\n\n");
        for (Coin c : list) {
            sb.append(Util.tidyAmount(c.amount))
              .append(state(c, sendable))
              .append(tag(c))
              .append('\n').append(c.coinid).append('\n');
        }
        return sb.toString().trim();
    }

    /** Spendable or locked, straight from the node's own sendable set — blank if that query didn't answer,
     *  because guessing which coins are spendable is exactly what this list exists to avoid. */
    private String state(Coin c, java.util.Set<String> sendable) {
        if (sendable == null) return "";
        return sendable.contains(c.coinid) ? "   spendable" : "   locked";
    }

    /**
     * WHY this coin isn't sendable. A pool reserve sits at the covenant address; a discovery beacon is dust
     * at the shared sentinel. Between them they account for most of the gap between confirmed and sendable,
     * so naming them turns the list into an answer rather than a wall of hashes. Anything locked but
     * untagged is held by something else — another contract, or an owner payout address awaiting collection.
     */
    private String tag(Coin c) {
        if (addrBook.contains(c.address) || addrBook.contains(c.miniaddress)) return "  (pool)";
        if (PoolCovenant.SENTINEL.equalsIgnoreCase(c.address)) return "  (beacon)";
        return "";
    }

    private void showImageFull(String url) {
        ImageView iv = new ImageView(act);
        iv.setAdjustViewBounds(true);
        iv.setBackgroundColor(0xFF000000);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        ImageLoader.loadFull(act, url, iv, R.drawable.ic_coin_placeholder);
        AlertDialog dlg = new AlertDialog.Builder(act).setView(iv).create();
        iv.setOnClickListener(v -> dlg.dismiss());
        dlg.show();
    }

    // ---- drawing / small helpers ----

    private TextView mono(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(act);
        t.setText(s); t.setTextColor(color); t.setTextSize(sp);
        t.setTypeface(Design.typeface(), bold ? Typeface.BOLD : Typeface.NORMAL);
        return t;
    }

    private GradientDrawable borderBox(int bg, int border) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(bg);
        d.setCornerRadius(dp((int) Math.max(Design.radiusDp(), 0)));
        d.setStroke(Math.max(1, dp(1)), border);
        return d;
    }

    private Drawable leftAccentBox(int bg, int border, int accent) {
        LayerDrawable ld = new LayerDrawable(new Drawable[]{ borderBox(bg, border), new ColorDrawable(accent) });
        ld.setLayerGravity(1, Gravity.LEFT);
        ld.setLayerWidth(1, dp(3));
        return ld;
    }

    /** The real Minima mark, rasterised from the bundled official SVG (native Minima carries no on-chain icon). */
    private Bitmap renderMinimaLogo(int px) {
        try {
            java.io.InputStream is = act.getResources().openRawResource(R.raw.minima_icon);
            com.caverock.androidsvg.SVG svg = com.caverock.androidsvg.SVG.getFromInputStream(is);
            is.close();
            float dw = svg.getDocumentWidth(), dh = svg.getDocumentHeight();
            int w = px, h = px;
            if (dw > 0 && dh > 0) {
                if (dw >= dh) { w = px; h = Math.max(1, Math.round(px * dh / dw)); }
                else { h = px; w = Math.max(1, Math.round(px * dw / dh)); }
            }
            svg.setDocumentWidth(w); svg.setDocumentHeight(h);
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            svg.renderToCanvas(new android.graphics.Canvas(bmp));
            return bmp;
        } catch (Throwable t) {
            return null;
        }
    }

    private void addKv(LinearLayout box, String label, String value) {
        LinearLayout r = new LinearLayout(act);
        r.setOrientation(LinearLayout.HORIZONTAL); r.setPadding(0, dp(4), 0, dp(4));
        TextView l = new TextView(act); l.setText(label); l.setTextColor(Design.dim()); l.setTextSize(13f);
        TextView v = new TextView(act); v.setText(value); v.setTextColor(Design.text()); v.setTextSize(13f); v.setGravity(Gravity.END);
        r.addView(l, new LinearLayout.LayoutParams(0, WC, 1f));
        r.addView(v, new LinearLayout.LayoutParams(0, WC, 1.6f));
        box.addView(r);
    }

    private TextView sectionLabel(String s) {
        TextView t = new TextView(act);
        t.setText(s); t.setAllCaps(true); t.setTextColor(Design.dim()); t.setTextSize(11f);
        t.setPadding(0, dp(12), 0, dp(2));
        return t;
    }

    private LinearLayout linkRow(String label, String url) {
        LinearLayout r = new LinearLayout(act);
        r.setOrientation(LinearLayout.HORIZONTAL); r.setPadding(0, dp(8), 0, dp(8));
        TextView l = new TextView(act); l.setText(label); l.setTextColor(Design.dim()); l.setTextSize(13f);
        TextView v = new TextView(act); v.setText("↗ open"); v.setTextColor(Design.accent()); v.setTextSize(13f); v.setGravity(Gravity.END);
        r.addView(l, new LinearLayout.LayoutParams(0, WC, 1f));
        r.addView(v, new LinearLayout.LayoutParams(0, WC, 1f));
        r.setOnClickListener(x -> {
            try { act.startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))); }
            catch (Exception ignore) {}
        });
        return r;
    }

    private static boolean notEmpty(String s) { return s != null && !s.isEmpty(); }

    private static BigDecimal bd(String s) { try { return new BigDecimal(s); } catch (Exception e) { return BigDecimal.ZERO; } }
    private int dp(int v) { return Math.round(v * act.getResources().getDisplayMetrics().density); }
}
