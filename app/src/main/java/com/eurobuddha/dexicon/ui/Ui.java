package com.eurobuddha.dexicon.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.eurobuddha.dexicon.Design;
import com.eurobuddha.dexicon.Ledger;
import com.eurobuddha.dexicon.MainActivity;

import java.util.List;

/**
 * The shared quiet pieces every tab builds from: text, the one card (radius 12, 12dp padding), folds whose open state
 * is saved per key (toggle visibility, never rebuild — TradeView's YOUR FUNDS pattern), the ⓘ info header, evidence
 * lines (a full, wrapped id; tap copies the COMPLETE value; long-press selects) and step lines. No helper here ever
 * shortens an identifier.
 */
public final class Ui {
    private Ui() {}

    private static final String PREFS = "dexicon_folds";

    public static TextView text(Context c, String s, float sp, int color, Typeface tf) {
        TextView t = new TextView(c);
        t.setText(s); t.setTextSize(sp); t.setTextColor(color); t.setTypeface(tf);
        return t;
    }
    /** Sets text only when it changed, so a selection in progress is not torn down by a repaint. */
    public static void set(TextView t, String s) { if (!s.contentEquals(t.getText())) t.setText(s); }

    public static LinearLayout.LayoutParams wide(Context c, int topDp, int bottomDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Design.dp(c, topDp); lp.bottomMargin = Design.dp(c, bottomDp);
        return lp;
    }

    /** The one card: SURFACE, hairline border, radius 12, 12dp padding, 10dp below. */
    public static LinearLayout card(Context c) {
        LinearLayout v = new LinearLayout(c);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setBackground(Design.card(c, 12));
        int p = Design.dp(c, 12); v.setPadding(p, p, p, p);
        v.setLayoutParams(wide(c, 0, 10));
        return v;
    }
    public static LinearLayout card(LinearLayout parent) { LinearLayout c = card(parent.getContext()); parent.addView(c); return c; }

    /** A small uppercase section label. */
    public static TextView label(Context c, String s) {
        TextView t = text(c, s, 10.5f, Design.DIM2(), Design.sansBold());
        t.setLetterSpacing(0.08f);
        return t;
    }

    /** A quiet outlined button, 44dp tall. */
    public static TextView button(Context c, String s) {
        TextView b = text(c, s, 12f, Design.DIM(), Design.sansBold());
        b.setGravity(Gravity.CENTER);
        int h = Design.dp(c, 14); b.setPadding(h, 0, h, 0);
        b.setMinHeight(Design.dp(c, 44));
        b.setBackground(Design.stroked(c, Design.SURFACE2(), 12));
        Design.pressable(b);
        return b;
    }

    // ------------------------------------------------------------------ folds (state saved per key)

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }
    public static boolean isOpen(Context c, String key) { return prefs(c).getBoolean(key, false); }
    public static void setOpen(Context c, String key, boolean open) { prefs(c).edit().putBoolean(key, open).apply(); }

    /** Tapping {@code trigger} shows / hides {@code body}; {@code chev} (may be null) reads "{label} ▸ / ▾". */
    public static void fold(View trigger, View body, TextView chev, String label, String key) {
        Context c = trigger.getContext();
        Runnable paint = () -> {
            boolean o = isOpen(c, key);
            body.setVisibility(o ? View.VISIBLE : View.GONE);
            if (chev != null) chev.setText(label + (o ? " ▾" : " ▸"));
        };
        trigger.setOnClickListener(v -> { setOpen(c, key, !isOpen(c, key)); paint.run(); });
        paint.run();
    }

    /** "How this works ▸" — a quiet row that folds a short paragraph. Returns the row; the paragraph follows it. */
    public static TextView foldText(LinearLayout parent, String label, String key, TextView paragraph) {
        Context c = parent.getContext();
        TextView row = text(c, label, 12f, Design.DIM(), Design.sansBold());
        row.setMinHeight(Design.dp(c, 44)); row.setGravity(Gravity.CENTER_VERTICAL);
        parent.addView(row);
        parent.addView(paragraph);
        fold(row, paragraph, row, label, key);
        return row;
    }

    /** A plain-English paragraph (sans, DIM, 1.4 line spacing). */
    public static TextView para(Context c, String s) {
        TextView t = text(c, s, 12.5f, Design.DIM(), Design.sans());
        t.setLineSpacing(0, 1.35f);
        return t;
    }

    /** ⓘ: a 44dp round button that folds a short friendly paragraph below {@code row}. */
    public static TextView info(LinearLayout row, LinearLayout under, String key, String help) {
        Context c = row.getContext();
        TextView i = text(c, "i", 14f, Design.DIM(), Design.sansBold());
        i.setGravity(Gravity.CENTER);
        int s = Design.dp(c, 44);
        android.graphics.drawable.GradientDrawable ring = new android.graphics.drawable.GradientDrawable();
        ring.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        ring.setStroke(Math.max(1, Design.dp(c, 1)), (Design.DIM2() & 0x00FFFFFF) | 0x55000000);
        i.setBackground(ring);
        i.setContentDescription("What is this?");
        row.addView(i, new LinearLayout.LayoutParams(s, s));
        TextView p = para(c, help);
        p.setBackground(Design.card(c, 12));
        int pd = Design.dp(c, 12); p.setPadding(pd, pd, pd, pd);
        under.addView(p, wide(c, 4, 8));
        fold(i, p, null, "", key);
        return p;
    }

    /** The screen title with its ⓘ, e.g. "Activity". */
    public static LinearLayout titleRow(LinearLayout parent, String title, String key, String help) {
        Context c = parent.getContext();
        LinearLayout row = new LinearLayout(c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Design.dp(c, 4), 0, 0, Design.dp(c, 2));
        row.addView(text(c, title, 20f, Design.TEXT(), Design.sansBold()), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        parent.addView(row);
        info(row, parent, key, help);
        return row;
    }

    /** A card header: LABEL on the left, a Copy button on the right copying the complete value. */
    public static LinearLayout copyRow(MainActivity act, String label, String what, java.util.function.Supplier<String> value) {
        LinearLayout row = new LinearLayout(act);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(label(act, label), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView copy = button(act, "Copy");
        copy.setOnClickListener(v -> { String s = value.get(); if (s != null && !s.isEmpty()) act.copy(what, s); else act.toast("Not available yet"); });
        row.addView(copy);
        return row;
    }

    // ------------------------------------------------------------------ evidence + steps

    /** A full identifier, wrapped, never shortened. Tap copies the complete value; long-press selects. */
    public static LinearLayout evidence(MainActivity act, String label, String value) {
        LinearLayout v = new LinearLayout(act);
        v.setOrientation(LinearLayout.VERTICAL);
        int p = Design.dp(act, 6); v.setPadding(0, p, 0, p);
        v.setMinimumHeight(Design.dp(act, 44));
        v.addView(text(act, label, 11f, Design.DIM2(), Design.sans()));
        TextView val = text(act, value, 11f, Design.TEXT(), Design.mono());
        val.setLineSpacing(0, 1.2f);
        val.setTextIsSelectable(true);
        val.setFocusableInTouchMode(false);   // so the FIRST tap copies (a focusable view spends it on focus)
        val.setOnClickListener(x -> act.copy(label, value));
        v.setOnClickListener(x -> act.copy(label, value));
        v.addView(val);
        return v;
    }

    /** ✓ / … / ! lines with a time column; NOTE lines are plain paragraphs. */
    public static void steps(LinearLayout box, List<Ledger.Step> steps, int liveColor) {
        Context c = box.getContext();
        box.removeAllViews();
        for (Ledger.Step s : steps) {
            if (s.mark == Ledger.Mark.NOTE) { TextView n = para(c, s.text); n.setTextSize(12f); box.addView(n, wide(c, 4, 4)); continue; }
            LinearLayout r = new LinearLayout(c);
            r.setPadding(0, Design.dp(c, 3), 0, Design.dp(c, 3));
            String m = s.mark == Ledger.Mark.OK ? "✓" : s.mark == Ledger.Mark.WAIT ? "…" : "!";
            int col = s.mark == Ledger.Mark.OK ? Design.IN() : s.mark == Ledger.Mark.WAIT ? liveColor : Design.ACCENT();
            r.addView(text(c, m, 12.5f, col, Design.sansBold()), new LinearLayout.LayoutParams(Design.dp(c, 16), LinearLayout.LayoutParams.WRAP_CONTENT));
            TextView tm = text(c, s.time, 11f, Design.DIM2(), Design.mono());
            r.addView(tm, new LinearLayout.LayoutParams(Design.dp(c, 64), LinearLayout.LayoutParams.WRAP_CONTENT));
            TextView tx = text(c, s.text, 12.5f, Design.TEXT(), Design.sans());
            tx.setLineSpacing(0, 1.2f);
            r.addView(tx, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            box.addView(r);
        }
    }
}
