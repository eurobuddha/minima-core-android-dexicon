package com.eurobuddha.dexicon.ui;

import android.annotation.SuppressLint;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.eurobuddha.dexicon.Design;
import com.eurobuddha.dexicon.Ledger;
import com.eurobuddha.dexicon.MainActivity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One ledger line ({@link Ledger.Item}) as a card: icon, title + chip, amount, sub-line, chevron. Live items show their
 * steps always, with the refund / retry buttons and a "Proof ▸" fold; finished items fold to the headline and tapping
 * opens "What happened", the facts and "Proof · tap to copy". Updated in place ({@link #sync}): a section is rebuilt only
 * when its content changed, so a per-second ticker and a text selection both survive the screen's refresh.
 */
@SuppressLint("ViewConstructor")
public final class LedgerView extends LinearLayout {

    /** Swap actions + the per-second re-derive (refund countdown). Trades need none of it (null-safe). */
    public interface Hooks {
        Ledger.Item tick(String id);
        void refund(String id);
        void retry(String id);
    }

    private static final int USD_GREEN = 0xFF26A17B;   // AtomiX MxUSD market green (DoorsTab.USD_GREEN)

    private final MainActivity act;
    private final String prefix;
    private final Hooks hooks;
    private final TextView icon, title, amount, sub, time, chev, stepsLabel, refund, retry, proofBtn;
    private final LinearLayout head, body, steps, facts, actions, proofBox, proofList;
    private TextView chip;
    private Ledger.Item item;
    private String stepsKey = null, bodyKey = null;
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (item == null || !item.ticking || hooks == null) return;
            Ledger.Item n = hooks.tick(item.id);
            if (n != null) update(n);
            postDelayed(this, 1000);
        }
    };

    public LedgerView(MainActivity act, String prefix, Hooks hooks) {
        super(act);
        this.act = act; this.prefix = prefix; this.hooks = hooks;
        setOrientation(VERTICAL);
        setBackground(Design.card(act, 12));
        setLayoutParams(Ui.wide(act, 0, 10));
        int p = Design.dp(act, 12);

        head = new LinearLayout(act);
        head.setPadding(p, p, p, p);
        head.setMinimumHeight(Design.dp(act, 56));
        icon = Ui.text(act, "", 13f, Design.TEXT(), Design.sansBold());
        icon.setGravity(Gravity.CENTER);
        LayoutParams il = new LayoutParams(Design.dp(act, 30), Design.dp(act, 30)); il.rightMargin = Design.dp(act, 12);
        head.addView(icon, il);
        LinearLayout mid = new LinearLayout(act); mid.setOrientation(VERTICAL);
        LinearLayout t1 = new LinearLayout(act); t1.setGravity(Gravity.CENTER_VERTICAL);
        title = Ui.text(act, "", 13f, Design.TEXT(), Design.sansBold());
        t1.addView(title, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        mid.addView(t1);
        amount = Ui.text(act, "", 12.5f, Design.TEXT(), Design.mono());
        mid.addView(amount, Ui.wide(act, 3, 0));
        LinearLayout t3 = new LinearLayout(act); t3.setGravity(Gravity.CENTER_VERTICAL);
        sub = Ui.text(act, "", 11.5f, Design.DIM2(), Design.sans());
        t3.addView(sub, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        time = Ui.text(act, "", 11.5f, Design.DIM2(), Design.mono());
        t3.addView(time);
        chev = Ui.text(act, "", 12f, Design.DIM2(), Design.sans());
        chev.setPadding(Design.dp(act, 8), 0, 0, 0);
        t3.addView(chev);
        mid.addView(t3, Ui.wide(act, 3, 0));
        head.addView(mid, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        addView(head);

        body = new LinearLayout(act); body.setOrientation(VERTICAL);
        body.setPadding(Design.dp(act, 54), 0, p, p);
        stepsLabel = Ui.label(act, "WHAT HAPPENED");
        body.addView(stepsLabel, Ui.wide(act, 2, 4));
        steps = new LinearLayout(act); steps.setOrientation(VERTICAL);
        body.addView(steps);
        facts = new LinearLayout(act); facts.setOrientation(VERTICAL);
        body.addView(facts, Ui.wide(act, 4, 0));
        actions = new LinearLayout(act); actions.setGravity(Gravity.CENTER_VERTICAL);
        refund = Ui.button(act, "");
        refund.setOnClickListener(v -> { if (hooks != null && item != null) { refund.setEnabled(false); hooks.refund(item.id); } });
        LayoutParams bl = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT); bl.rightMargin = Design.dp(act, 8);
        actions.addView(refund, bl);
        retry = Ui.button(act, "Retry forward");
        retry.setOnClickListener(v -> { if (hooks != null && item != null) hooks.retry(item.id); });
        actions.addView(retry, new LayoutParams(bl));
        proofBtn = Ui.text(act, "", 12f, Design.DIM(), Design.sansBold());
        proofBtn.setMinHeight(Design.dp(act, 44)); proofBtn.setGravity(Gravity.CENTER_VERTICAL);
        proofBtn.setPadding(Design.dp(act, 4), 0, Design.dp(act, 4), 0);
        proofBtn.setOnClickListener(v -> toggle(proofKey()));
        actions.addView(proofBtn);
        body.addView(actions, Ui.wide(act, 6, 0));
        proofBox = new LinearLayout(act); proofBox.setOrientation(VERTICAL);
        proofBox.addView(Ui.label(act, "PROOF · TAP TO COPY"), Ui.wide(act, 8, 2));
        proofList = new LinearLayout(act); proofList.setOrientation(VERTICAL);
        proofBox.addView(proofList);
        body.addView(proofBox);
        addView(body);

        head.setOnClickListener(v -> { if (item != null) toggle(item.live ? proofKey() : openKey()); });

        addOnAttachStateChangeListener(new OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) { removeCallbacks(ticker); postDelayed(ticker, 1000); }
            @Override public void onViewDetachedFromWindow(View v) { removeCallbacks(ticker); }
        });
    }

    private String openKey() { return prefix + "_" + item.id; }
    private String proofKey() { return prefix + "proof_" + item.id; }
    private void toggle(String key) { Ui.setOpen(act, key, !Ui.isOpen(act, key)); paintFolds(); }

    private int liveColor() { return item != null && item.usd ? USD_GREEN : Design.ACCENT(); }
    private int tone(Ledger.Tone t) {
        switch (t) {
            case OK: return Design.IN();
            case BAD: return Design.RED();
            case WARN: return Design.ACCENT();
            case LIVE: return liveColor();
            default: return Design.DIM();
        }
    }

    public void update(Ledger.Item it) {
        boolean wasTicking = item != null && item.ticking;
        item = it;
        Ui.set(icon, it.icon);
        int ic = tone(it.tone);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL); circle.setColor((ic & 0x00FFFFFF) | 0x26000000);
        icon.setBackground(circle); icon.setTextColor(ic);
        Ui.set(title, it.title);
        Ui.set(amount, it.amount);
        Ui.set(sub, it.sub);
        Ui.set(time, it.time);
        time.setVisibility(it.time.isEmpty() ? GONE : VISIBLE);
        LinearLayout t1 = (LinearLayout) title.getParent();
        if (chip != null) t1.removeView(chip);
        chip = it.chip.isEmpty() ? null : Design.chip(act, it.chip, tone(it.chipTone));
        if (chip != null) t1.addView(chip);

        String sk = it.stepsKey() + it.live;
        if (!sk.equals(stepsKey)) { Ui.steps(steps, it.steps, liveColor()); stepsKey = sk; }
        String bk = it.bodyKey();
        if (!bk.equals(bodyKey)) { paintBody(it); bodyKey = bk; }

        refund.setVisibility(it.refundLabel == null ? GONE : VISIBLE);
        if (it.refundLabel != null) {
            Ui.set(refund, it.refundLabel.replace("REFUND NOW", "Refund now"));
            refund.setEnabled(it.refundEnabled); refund.setAlpha(it.refundEnabled ? 1f : 0.5f);
            refund.setTextColor(it.refundEnabled ? liveColor() : Design.DIM());
        }
        retry.setVisibility(it.retryForward ? VISIBLE : GONE);
        retry.setTextColor(liveColor());
        paintFolds();
        if (it.ticking && !wasTicking && isAttachedToWindow()) { removeCallbacks(ticker); postDelayed(ticker, 1000); }
    }

    private void paintBody(Ledger.Item it) {
        facts.removeAllViews();
        for (String[] f : it.facts) {
            LinearLayout r = new LinearLayout(act);
            r.setPadding(0, Design.dp(act, 3), 0, Design.dp(act, 3));
            r.addView(Ui.text(act, f[0], 12f, Design.DIM2(), Design.sans()), new LayoutParams(Design.dp(act, 96), LayoutParams.WRAP_CONTENT));
            TextView v = Ui.text(act, f[1], 12f, Design.TEXT(), Design.mono());
            v.setTextIsSelectable(true);
            r.addView(v, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
            facts.addView(r);
        }
        proofList.removeAllViews();
        for (Ledger.Proof p : it.proof) proofList.addView(Ui.evidence(act, p.label, p.value));
        for (int g = 0; g < it.groups.size(); g++) {
            Ledger.Group grp = it.groups.get(g);
            TextView row = Ui.text(act, "", 12f, Design.DIM(), Design.sansBold());
            row.setMinHeight(Design.dp(act, 44)); row.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout ids = new LinearLayout(act); ids.setOrientation(VERTICAL);
            for (Ledger.Proof p : grp.ids) ids.addView(Ui.evidence(act, p.label, p.value));
            proofList.addView(row); proofList.addView(ids);
            Ui.fold(row, ids, row, grp.label, prefix + "g" + g + "_" + it.id);
        }
    }

    private void paintFolds() {
        Ledger.Item it = item;
        boolean hasProof = !it.proof.isEmpty() || !it.groups.isEmpty();
        boolean proofOpen = Ui.isOpen(act, proofKey()), open = Ui.isOpen(act, openKey());
        facts.setVisibility(it.facts.isEmpty() ? GONE : VISIBLE);
        if (it.live) {
            body.setVisibility(VISIBLE);
            stepsLabel.setVisibility(GONE);
            proofBtn.setVisibility(hasProof ? VISIBLE : GONE);
            Ui.set(proofBtn, proofOpen ? "Hide proof ▾" : "Proof ▸");
            proofBox.setVisibility(hasProof && proofOpen ? VISIBLE : GONE);
            chev.setVisibility(GONE);
        } else {
            body.setVisibility(open ? VISIBLE : GONE);
            stepsLabel.setVisibility(it.steps.isEmpty() ? GONE : VISIBLE);
            proofBtn.setVisibility(GONE);
            proofBox.setVisibility(hasProof ? VISIBLE : GONE);
            chev.setVisibility(VISIBLE);
            Ui.set(chev, open ? "▾" : "▸");
        }
        boolean anyAction = refund.getVisibility() == VISIBLE || retry.getVisibility() == VISIBLE || proofBtn.getVisibility() == VISIBLE;
        actions.setVisibility(anyAction ? VISIBLE : GONE);
    }

    // ------------------------------------------------------------------ keyed in-place list

    /** Make {@code box} show {@code items} in order, reusing the view already showing each id (and day labels). */
    public static void sync(MainActivity act, LinearLayout box, List<Ledger.Item> items, String prefix, Hooks hooks) {
        Map<Object, View> have = new HashMap<>();
        for (int i = 0; i < box.getChildCount(); i++) have.put(box.getChildAt(i).getTag(), box.getChildAt(i));
        for (int i = 0; i < items.size(); i++) {
            Ledger.Item it = items.get(i);
            View v = have.remove(it.id);
            if (v == null) {
                if (it.header) {
                    TextView d = Ui.label(act, it.title.toUpperCase(java.util.Locale.ROOT));
                    d.setPadding(Design.dp(act, 4), Design.dp(act, 8), 0, Design.dp(act, 6));
                    v = d;
                } else v = new LedgerView(act, prefix, hooks);
                v.setTag(it.id);
            }
            if (v instanceof LedgerView) ((LedgerView) v).update(it);
            if (box.getChildAt(i) != v) {
                if (v.getParent() != null) box.removeView(v);
                box.addView(v, i);
            }
        }
        while (box.getChildCount() > items.size()) box.removeViewAt(items.size());
    }
}
