package com.eurobuddha.dexicon;

import android.app.AlertDialog;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import com.eurobuddha.dexicon.maker.Ladder;
import com.eurobuddha.dexicon.maker.LadderRunner;
import com.eurobuddha.dexicon.maker.MexcFeed;

import java.math.BigDecimal;
import java.util.List;

/**
 * ORDERS › MARKET MAKER — AtomiX's market editor (apks/atomix MainActivity.editOrderDialog) for Dexicon's FAST book:
 * enable, peg to MEXC (skew, reprice threshold), auto-fill (mid · step % · levels · ask/bid size), the A6..A1 / B1..B6
 * rungs, and a live preview of what the ladder commits against what you hold. The maker service keeps it live.
 */
final class MarketMaker {
    private MarketMaker() {}

    static Ladder.Config load(MainActivity act) { return Ladder.Config.fromJson(act.db().meta(LadderRunner.CONFIG, "")); }
    static void save(MainActivity act, Ladder.Config c) {
        act.db().setMeta(LadderRunner.CONFIG, c.toJson().toString());
        act.db().setMeta(LadderRunner.DIRTY, "1");
        if (!c.autoCross) act.db().setMeta(CROSS_ACK, "");   // saved off: whoever turns it on next sees the warning again
        // and wake the service so it reconciles NOW (owner 2026-10-26: cancel-to-repost was ~18 s of waiting)
        try { act.startService(new android.content.Intent(act, com.eurobuddha.dexicon.maker.MakerService.class).setAction(com.eurobuddha.dexicon.maker.MakerService.ACTION_LADDER)); }
        catch (RuntimeException ignored) {}   // service not allowed to start right now: the next tick still picks DIRTY up
    }

    /** The card at the top of ORDERS › OPEN. */
    static void card(MainActivity act, LinearLayout into) {
        Ladder.Config c = load(act);
        LinearLayout box = new LinearLayout(act); box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(Design.card(act, 10));
        int p = Design.dp(act, 10); box.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.bottomMargin = Design.dp(act, 10);
        into.addView(box, lp);
        LinearLayout top = new LinearLayout(act); top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(text(act, "MARKET MAKER", Design.DIM(), 10.5f, true), new LinearLayout.LayoutParams(0, -2, 1f));
        String mode = !c.enabled ? "OFF" : c.peg ? "PEGGED · MEXC" : "MANUAL";
        top.addView(Design.pill(act, mode, c.enabled ? Design.ACCENT() : Design.SURFACE2(), c.enabled ? Design.ON_ACCENT() : Design.DIM()));
        box.addView(top);
        String st = act.db().meta(LadderRunner.STATUS, "");
        if (c.enabled && c.peg) box.addView(text(act, MexcFeed.describe(), Design.DIM2(), 9.5f, false));
        box.addView(text(act, st.isEmpty() ? (c.enabled ? "starting — the maker service picks it up within seconds" : "A ladder of FAST asks and bids, pegged to MEXC or set by hand. Your node keeps it live and co-signs every fill.") : st, Design.TEXT(), 10f, false));
        String pool = act.db().meta(com.eurobuddha.dexicon.maker.AutoCross.POOL_STATUS, "");
        if (c.enabled && !pool.isEmpty()) box.addView(text(act, "PandaPools: " + pool, Design.DIM(), 10f, false));
        LinearLayout btns = new LinearLayout(act);
        TextView set = button(act, c.enabled ? "EDIT LADDER ▸" : "SET UP ▸", Design.ACCENT(), Design.ON_ACCENT());
        set.setOnClickListener(v -> edit(act));
        btns.addView(set, new LinearLayout.LayoutParams(0, -2, 1f));
        if (c.enabled) {
            TextView stop = button(act, "STOP", Design.SURFACE2(), Design.RED());
            stop.setOnClickListener(v -> new AlertDialog.Builder(act, Design.dialogTheme()).setTitle("Stop the market maker?")
                    .setMessage("Every ladder order is cancelled on the relays within seconds. Your settings are kept.")
                    .setPositiveButton("Stop", (d, w) -> { Ladder.Config x = load(act); x.enabled = false; save(act, x); act.toast("Stopping — ladder orders are being cancelled"); act.repaint(); })
                    .setNegativeButton("Back", null).show());
            LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(0, -2, 1f); sl.leftMargin = Design.dp(act, 8);
            btns.addView(stop, sl);
        }
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, -2); bl.topMargin = Design.dp(act, 8);
        box.addView(btns, bl);
    }

    static void edit(MainActivity act) {
        final Ladder.Config c = load(act);
        LinearLayout box = new LinearLayout(act); box.setOrientation(LinearLayout.VERTICAL);
        int pad = Design.dp(act, 18); box.setPadding(pad, Design.dp(act, 8), pad, Design.dp(act, 8));

        box.addView(text(act, "Your FAST ladder for MINIMA / MxUSD (price = MxUSD per MINIMA):\n"
                + "•  ASKS — where YOU SELL MINIMA (higher).   BIDS — where YOU BUY (lower).\n"
                + "•  Each rung is one fast order: instant to place, reprice and cancel; your node co-signs each fill.\n"
                + "•  Leave rows blank to skip them. A filled rung is topped back up to its size.", Design.DIM(), 11.5f, false));
        final Switch en = sw(act, "Enabled", c.enabled); box.addView(en);

        box.addView(label(act, "AUTO MARKET-MAKE — PEG TO MEXC"));
        final Switch peg = sw(act, "Peg ladder to MEXC (auto-reprice)", c.peg); box.addView(peg);
        final TextView feed = text(act, MexcFeed.describe(), Design.DIM(), 10.5f, false); box.addView(feed);
        box.addView(text(act, "While pegged, the ladder regenerates around the MEXC MINIMA/USDT mid (± step %, your size per rung) and each "
                + "rung is repriced the moment the market moves ≥ your threshold — a fast order edit, no block. If the feed goes stale the "
                + "spread widens (up to ×10); with no price at all the ladder is withdrawn, then restored when it recovers. MEXC's MINIMA "
                + "market is THIN — keep step % above its typical spread and sizes small; you auto-trade at these prices.", Design.DIM2(), 10f, false));
        LinearLayout pr = row(act); final EditText skew = field(act, "skew ±%", c.skew, true), rep = field(act, "reprice ≥ %", c.reprice, false);
        pr.addView(skew, weight(act, true)); pr.addView(rep, weight(act, false)); box.addView(pr);


        box.addView(label(act, "AUTO-FILL (mid · step % · levels, then ask/bid size in MINIMA — seeds the rungs; edit any rung after)"));
        LinearLayout g = row(act); final EditText mid = field(act, "mid", c.mid, false), step = field(act, "step %", c.step, false), lev = field(act, "levels", c.levels, false);
        lev.setInputType(InputType.TYPE_CLASS_NUMBER);
        g.addView(mid, weight(act, true)); g.addView(step, weight(act, true)); g.addView(lev, weight(act, false)); box.addView(g);
        LinearLayout gs = row(act); final EditText askS = field(act, "ask size", c.askSize, false), bidS = field(act, "bid size", c.bidSize, false);
        gs.addView(askS, weight(act, true)); gs.addView(bidS, weight(act, false)); box.addView(gs);

        box.addView(text(act, "ASKS — you SELL MINIMA (higher price)", Design.RED(), 12f, true));
        final EditText[][] asks = new EditText[Ladder.MAX_LEVELS][];
        for (int i = Ladder.MAX_LEVELS - 1; i >= 0; i--) asks[i] = rung(act, box, "A" + (i + 1), c.asks[i], Design.RED());   // A6 on top, A1 by the spread
        box.addView(text(act, "BIDS — you BUY MINIMA (lower price)", Design.IN(), 12f, true));
        final EditText[][] bids = new EditText[Ladder.MAX_LEVELS][];
        for (int i = 0; i < Ladder.MAX_LEVELS; i++) bids[i] = rung(act, box, "B" + (i + 1), c.bids[i], Design.IN());
        box.addView(label(act, "AUTO-CROSS — TRADE PANDAPOOLS BACK TO YOUR LADDER"));
        final Switch ax = sw(act, "Auto-cross PandaPools", c.autoCross); box.addView(ax);
        box.addView(text(act, "When the pool's price moves past your best ask, your node sells MINIMA into the pool until it is back at that "
                + "rung; past your best bid, it buys. At most the size of the rungs the pool moved past, only while the MEXC price is fresh, "
                + "and only when it beats your rung after the pool's 0.5 % fee. One on-chain trade, signed by your node alone; it counts as "
                + "a fill and the ladder refills. The card shows where the pool stands even while this is off.", Design.DIM2(), 10f, false));
        final EditText cph = field(act, "max crosses per hour", c.crossPerHour, false);
        cph.setInputType(InputType.TYPE_CLASS_NUMBER); box.addView(cph);
        ax.setOnCheckedChangeListener((b, on) -> {   // owner 29 Oct: say exactly what switching it on lets loose, before it does
            if (!on || "1".equals(act.db().meta(CROSS_ACK, ""))) return;
            LinearLayout v = new LinearLayout(act); v.setOrientation(LinearLayout.VERTICAL);
            int wp = Design.dp(act, 20); v.setPadding(wp, Design.dp(act, 8), wp, 0);
            v.addView(text(act, crossWarning(act.db().meta(com.eurobuddha.dexicon.maker.AutoCross.POOL_STATUS, ""), s(cph)), Design.TEXT(), 12f, false));
            final android.widget.CheckBox again = new android.widget.CheckBox(act); again.setText("Don't show again"); again.setTextColor(Design.DIM());
            v.addView(again);
            ScrollView ws = new ScrollView(act); ws.addView(v);
            new AlertDialog.Builder(act, Design.dialogTheme()).setTitle("Turn on auto-cross?").setView(ws)
                    .setPositiveButton("Turn on", (x, w) -> { if (again.isChecked()) act.db().setMeta(CROSS_ACK, "1"); })
                    .setNegativeButton("Keep off", (x, w) -> ax.setChecked(false))
                    .setOnCancelListener(x -> ax.setChecked(false)).show();
        });
        final TextView pv = text(act, "", Design.TEXT(), 11f, false); pv.setPadding(0, Design.dp(act, 10), 0, 0); box.addView(pv);

        final boolean[] filling = {false};
        final Runnable read = () -> {
            c.enabled = en.isChecked(); c.peg = peg.isChecked(); c.autoCross = ax.isChecked(); c.crossPerHour = s(cph);
            c.skew = s(skew); c.reprice = s(rep); c.mid = s(mid); c.step = s(step); c.levels = s(lev); c.askSize = s(askS); c.bidSize = s(bidS);
            for (int i = 0; i < Ladder.MAX_LEVELS; i++) { c.asks[i] = new String[]{s(asks[i][0]), s(asks[i][1])}; c.bids[i] = new String[]{s(bids[i][0]), s(bids[i][1])}; }
        };
        final Runnable preview = () -> {
            read.run();
            String err = Ladder.validate(c);
            List<Ladder.Rung> rs = c.peg ? Ladder.desired(withEnabled(c), MexcFeed.mid(), MexcFeed.widen()) : Ladder.desired(withEnabled(c), 0, 1);
            BigDecimal[] k = Ladder.commits(rs);
            Funds f = act.paired() ? Funds.of(act) : null;
            StringBuilder b = new StringBuilder();
            b.append(rs.size()).append(rs.size() == 1 ? " rung" : " rungs").append(" · asks commit ").append(plain(k[0])).append(" MINIMA · bids commit ").append(plain(k[1])).append(" MxUSD");
            if (f != null) b.append("\nyou hold ").append(Util.fmtBal(f.minimaAvailable)).append(" MINIMA · ").append(Util.fmtBal(f.mxusdAvailable)).append(" MxUSD");
            boolean short_ = f != null && (k[0].compareTo(f.minimaAvailable) > 0 || k[1].compareTo(f.mxusdAvailable) > 0);
            if (short_) b.append("\n⚠ the ladder commits more than you hold — rungs that cannot be backed stay off");
            if (rs.size() > 1) b.append("\none coin per rung: if your wallet is one big coin, the maker splits it once (one block)");
            if (err != null) b.append("\n✗ ").append(err);
            pv.setText(b.toString());
            pv.setTextColor(err != null ? Design.RED() : short_ ? Design.ACCENT() : Design.TEXT());
        };
        // AtomiX behaviour: the rungs regenerate from mid · step · levels · sizes as you type (manual) or from the
        // live MEXC mid every 2 s (pegged); a rung edited by hand stays until those inputs change again.
        final Runnable fill = () -> {
            read.run();
            BigDecimal m;
            if (peg.isChecked()) {
                if (!(MexcFeed.mid() > 0)) { preview.run(); return; }
                BigDecimal sk = Ladder.parse(c.skew, true); if (sk == null) sk = BigDecimal.ZERO;
                m = BigDecimal.valueOf(MexcFeed.mid()).multiply(BigDecimal.ONE.add(sk.max(BigDecimal.valueOf(-20)).min(BigDecimal.valueOf(20)).movePointLeft(2)), new java.math.MathContext(34));
            } else m = Ladder.parse(c.mid, false);
            BigDecimal st = Ladder.parse(c.step, false), a = Ladder.parse(c.askSize, false), bd = Ladder.parse(c.bidSize, false);
            if (m == null || st == null || a == null || bd == null || m.signum() <= 0 || st.signum() <= 0 || (a.signum() <= 0 && bd.signum() <= 0)) { preview.run(); return; }
            filling[0] = true;
            if (peg.isChecked()) mid.setText(m.setScale(8, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString());
            for (int i = 0; i < Ladder.MAX_LEVELS; i++) for (EditText e : new EditText[]{asks[i][0], asks[i][1], bids[i][0], bids[i][1]}) e.setText("");
            for (Ladder.Rung r : Ladder.generate(m, st, c.levelCount(), a, bd)) {
                EditText[] row = (r.key.charAt(0) == 'A' ? asks : bids)[r.key.charAt(1) - '1'];
                row[0].setText(r.price.stripTrailingZeros().toPlainString()); row[1].setText(r.amount.stripTrailingZeros().toPlainString());
            }
            filling[0] = false;
            preview.run();
        };
        TextWatcher gen = watcher(() -> { if (!filling[0]) fill.run(); });
        for (EditText e : new EditText[]{mid, step, lev, askS, bidS, skew}) e.addTextChangedListener(gen);
        TextWatcher pw = watcher(() -> { if (!filling[0]) preview.run(); });
        rep.addTextChangedListener(pw);
        for (int i = 0; i < Ladder.MAX_LEVELS; i++) for (EditText e : new EditText[]{asks[i][0], asks[i][1], bids[i][0], bids[i][1]}) e.addTextChangedListener(pw);
        CompoundButton.OnCheckedChangeListener sl = (b, on) -> { mid.setEnabled(!peg.isChecked()); if (peg.isChecked()) { MexcFeed.refreshAsync(); fill.run(); } else preview.run(); };
        en.setOnCheckedChangeListener(sl); peg.setOnCheckedChangeListener(sl);
        mid.setEnabled(!peg.isChecked());
        preview.run();

        final Handler h = new Handler(Looper.getMainLooper());
        final boolean[] open = {true};
        final double[] lastMid = {MexcFeed.mid()};
        h.post(new Runnable() {
            @Override public void run() {
                if (!open[0]) return;
                MexcFeed.refreshAsync();
                feed.setText(MexcFeed.describe());
                if (peg.isChecked() && MexcFeed.mid() != lastMid[0]) { lastMid[0] = MexcFeed.mid(); fill.run(); }
                h.postDelayed(this, 2000);
            }
        });

        ScrollView sv = new ScrollView(act); sv.addView(box);
        AlertDialog d = new AlertDialog.Builder(act, Design.dialogTheme()).setTitle("Market maker — FAST ladder").setView(sv)
                .setPositiveButton("Save", null).setNegativeButton("Cancel", null).create();
        d.setOnDismissListener(x -> open[0] = false);
        d.setOnShowListener(x -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            read.run();
            String err = Ladder.validate(c);
            if (err != null) { act.toast(err); preview.run(); return; }
            save(act, c);
            act.toast(c.enabled ? "Saved — the maker service puts the ladder live within seconds" : "Saved — market maker off");
            d.dismiss(); act.repaint();
        }));
        d.show();
    }

    // ---- small view helpers ----
    private static Ladder.Config withEnabled(Ladder.Config c) { Ladder.Config x = Ladder.Config.fromJson(c.toJson().toString()); x.enabled = true; return x; }
    private static String plain(BigDecimal v) { return v.signum() == 0 ? "0" : v.stripTrailingZeros().toPlainString(); }
    private static String s(EditText e) { return e.getText().toString().trim(); }
    private static TextView text(MainActivity act, String s, int color, float size, boolean bold) {
        TextView t = new TextView(act); t.setText(s); t.setTextColor(color); t.setTextSize(size);
        t.setTypeface(bold ? Design.sansBold() : Design.mono()); t.setPadding(0, Design.dp(act, 4), 0, Design.dp(act, 2)); return t;
    }
    private static TextView label(MainActivity act, String s) { TextView t = text(act, s, Design.ACCENT(), 10f, true); t.setPadding(0, Design.dp(act, 12), 0, Design.dp(act, 2)); return t; }
    private static TextView button(MainActivity act, String s, int bg, int fg) {
        TextView b = text(act, s, fg, 11f, true); b.setGravity(Gravity.CENTER);
        int p = Design.dp(act, 10); b.setPadding(p, p, p, p); b.setBackground(Design.roundBg(act, bg, 10)); Design.pressable(b); return b;
    }
    private static Switch sw(MainActivity act, String s, boolean on) { Switch w = new Switch(act); w.setText(s); w.setTextColor(Design.DIM()); w.setChecked(on); return w; }
    private static LinearLayout row(MainActivity act) { LinearLayout r = new LinearLayout(act); r.setGravity(Gravity.CENTER_VERTICAL); r.setPadding(0, Design.dp(act, 2), 0, Design.dp(act, 2)); return r; }
    private static LinearLayout.LayoutParams weight(MainActivity act, boolean gap) { LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(0, -2, 1f); if (gap) l.rightMargin = Design.dp(act, 6); return l; }
    private static EditText field(MainActivity act, String hint, String value, boolean signed) {
        EditText e = new EditText(act);
        e.setHint(hint); e.setHintTextColor(Design.DIM2()); e.setTextColor(Design.TEXT()); e.setTypeface(Design.mono()); e.setTextSize(12f);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | (signed ? InputType.TYPE_NUMBER_FLAG_SIGNED : 0));
        e.setText(value == null ? "" : value);
        return e;
    }
    private static EditText[] rung(MainActivity act, LinearLayout box, String key, String[] v, int color) {
        LinearLayout r = row(act);
        TextView k = text(act, key, color, 11f, true); k.setMinWidth(Design.dp(act, 30));
        EditText p = field(act, "price", v[0], false), a = field(act, "MINIMA", v[1], false);
        r.addView(k); r.addView(p, weight(act, true)); r.addView(a, weight(act, false));
        box.addView(r);
        return new EditText[]{p, a};
    }
    private static TextWatcher watcher(Runnable r) {
        return new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(Editable e) { r.run(); }
        };
    }

    /** Meta key: the owner ticked "Don't show again" on the auto-cross warning. */
    static final String CROSS_ACK = "mm_cross_ack";

    /** The switch-on warning: what auto-cross does, what would cross right now, and what the owner is agreeing to.
     *  Same text as warning() in the MiniDapp's lib/autocross.js — edit both. */
    static String crossWarning(String poolLine, String perHour) {
        String now = poolLine.endsWith(" (auto-cross off)") ? poolLine.substring(0, poolLine.length() - 17) : poolLine, hold = null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(.*) \\(([^)]*)\\)$").matcher(now);
        if (m.matches()) { now = m.group(1); hold = m.group(2); }   // e.g. "waiting: 3 crosses this hour" — it would NOT trade on SAVE
        String right = now.isEmpty() || now.startsWith("waiting") ? "Not known yet — the pool position shows once the ladder is running."
                : now.startsWith("pool in line") ? "Nothing. The pool is in line with your ladder, so nothing would cross."
                : "YES — " + now + ".\n" + (hold == null ? "This trade is made as soon as you SAVE (if the MEXC price is fresh)."
                        : "Not straight away (" + hold + ") — it is made once that clears, if the pool is still out of line.");
        int n; try { n = Math.max(0, Integer.parseInt(perHour.trim())); } catch (Exception e) { n = 3; }   // as AutoCross.perHour reads it
        String cap = String.valueOf(n);
        return "Auto-cross trades your own funds against PandaPools, automatically, with no further confirmation.\n\n"
                + "WHAT IT DOES\nWhen the pool's price rises past your best ask, your node SELLS MINIMA into the pool until it is back at that ask. "
                + "When it falls past your best bid, your node BUYS. Each trade is the size of your rungs the pool moved past — the coins behind those "
                + "rungs are spent, exactly as if a taker had filled them.\n\n"
                + "WHAT WOULD CROSS RIGHT NOW (your live ladder)\n" + right + "\n\n"
                + "WHAT YOU ARE AGREEING TO\n"
                + "• Up to " + cap + " trades an hour. If someone keeps pushing the pool back, it crosses again each time and your inventory moves "
                + "one way: selling turns MINIMA into MxUSD, buying the reverse. A side of your ladder can empty until you top it up.\n"
                + "• Trades are on-chain and final. Each one beats your rung price after the pool's 0.5 % fee and the MEXC price at that moment, "
                + "but MEXC can move afterwards.\n"
                + "• It never trades more than your backed rungs hold, never while the MEXC price is stale, and the whole trade is rejected if the pool "
                + "moves first.\n\n"
                + "Every cross appears in ACTIVITY and HISTORY. Switch it off here and SAVE to stop.";
    }
}
