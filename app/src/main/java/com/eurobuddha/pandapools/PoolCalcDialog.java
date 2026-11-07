// VENDORED VERBATIM from apks/pandapools 0.9.88 app/src/main/java/com/eurobuddha/pandapools/PoolCalcDialog.java by scripts/vendor-pandapools.sh — do not edit here; re-vendor.
package com.eurobuddha.pandapools;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

/**
 * The what-if pool calculator: enter a starting pool (or open it seeded from a live pool), move the MINIMA price,
 * and watch the reserves, their ratio, the pool's value versus holding, and the effect of fees — all on the
 * constant-product curve the covenant enforces. Pure display; nothing here touches the node or the chain.
 * Maths in {@link PoolCalc}. Mirrored in the MiniDapp + Desktop "Pools" tab (3-surface parity).
 */
public final class PoolCalcDialog {

    private static final int SLIDER_STEPS = 800;   // log10 multiple −4 … +4 in 0.01 steps
    private static final double[] CHIPS = { 0.01, 0.1, 0.25, 0.5, 1, 2, 4, 10, 100 };

    private final Context ctx;
    private EditText inX, inY, inTok, inP, inFee, inVol;
    private SeekBar slider;
    private TextView derived, outMinima, outToken, outRatio, outValue, outHold, outMove, outFees, outK, outBreakEven, note;
    private CurveView curve;
    private boolean syncing = false;   // guards the price-field ↔ slider echo loop
    private String tok = "mxUSDT";

    private PoolCalcDialog(Context c) { ctx = c; }

    /** Open the calculator. {@code seed} (nullable) pre-fills the starting pool from a live pool's reserves. */
    public static void show(Context c, Pool seed) {
        PoolCalcDialog d = new PoolCalcDialog(c);
        d.build(seed);
    }

    private void build(Pool seed) {
        int pad = Ui.dp(ctx, 20);
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, Ui.dp(ctx, 8), pad, Ui.dp(ctx, 8));

        TextView blurb = text("A PandaPool sits on MINIMA × token = K at every price, so the reserves, their ratio and the "
                + "pool's value follow from the price alone. Enter a starting pool, then move the price. Nothing here "
                + "touches your funds.", Design.dim(), 13, false);
        box.addView(blurb);

        // ---- starting pool
        box.addView(section("STARTING POOL"));
        String seedX = "100000", seedY = "500";
        if (seed != null && seed.funded()) {
            seedX = seed.reserveM.stripTrailingZeros().toPlainString();
            seedY = seed.reserveT.stripTrailingZeros().toPlainString();
            tok = seed.tokenLabel();
        }
        inX = field(box, "MINIMA you add", seedX);
        inY = field(box, tok + " you add", seedY);
        inTok = field(box, "Token symbol", tok);
        inTok.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        derived = text("", Design.dim(), 12, false);
        derived.setPadding(0, Ui.dp(ctx, 6), 0, 0);
        box.addView(derived);

        // ---- price
        box.addView(section("PRICE NOW"));
        inP = field(box, "MINIMA price in " + tok, PoolCalc.fmtPrice(Double.parseDouble(seedY) / Double.parseDouble(seedX)));
        inP.setTextSize(20);
        slider = new SeekBar(ctx);
        slider.setMax(SLIDER_STEPS);
        slider.setProgress(SLIDER_STEPS / 2);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = Ui.dp(ctx, 8);
        slider.setLayoutParams(slp);
        box.addView(slider);
        LinearLayout ends = new LinearLayout(ctx);
        ends.setOrientation(LinearLayout.HORIZONTAL);
        TextView l = text("÷10,000", Design.dim2(), 11, false);
        l.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView mid = text("entry", Design.dim2(), 11, false);
        mid.setGravity(Gravity.CENTER);
        mid.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView r = text("×10,000", Design.dim2(), 11, false);
        r.setGravity(Gravity.END);
        r.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        ends.addView(l); ends.addView(mid); ends.addView(r);
        box.addView(ends);
        box.addView(chipRow());

        // ---- fees
        box.addView(section("FEES EARNED"));
        inFee = field(box, "Swap fee rate (%)", "0.5");
        inVol = field(box, "Volume traded through the pool, in " + tok + " value", "0");
        note = text("PandaPools keeps 0.5 % of every swap's input inside the pool. Both directions count as volume. "
                + "Fees are valued at the current price, so pool value with fees is exactly the fee-free value plus "
                + "the fees kept.", Design.dim2(), 11, false);
        note.setPadding(0, Ui.dp(ctx, 6), 0, 0);
        box.addView(note);

        // ---- results
        box.addView(section("THE POOL AT THIS PRICE"));
        outMinima = kv(box, "MINIMA in pool");
        outToken = kv(box, tok + " in pool");
        outRatio = kv(box, "Ratio MINIMA : " + tok);
        outValue = kv(box, "Pool value, fees included");
        outHold = kv(box, "Versus holding both amounts");
        outMove = kv(box, "Price move from entry");
        outFees = kv(box, "Fees kept by the pool");
        outK = kv(box, "K with fees");
        outBreakEven = kv(box, "Volume to break even with holding");

        // ---- curve
        box.addView(section("WHERE THE POOL SITS ON ITS CURVE"));
        curve = new CurveView(ctx);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(ctx, 220));
        clp.topMargin = Ui.dp(ctx, 6);
        curve.setLayoutParams(clp);
        box.addView(curve);
        TextView legend = text("Small dot = entry · large accent dot = now · dashed = K at entry (the KMIN floor) once fees lift the curve. "
                + "The slope of the curve at the pool's point is the price.", Design.dim2(), 11, false);
        legend.setPadding(0, Ui.dp(ctx, 6), 0, 0);
        box.addView(legend);

        TextView reading = text("MINIMA in pool = √(K ÷ price); token in pool = √(K × price). Each 4× move in price moves "
                + "each reserve 2×. The pool is 50 % MINIMA and 50 % token by value at every price — only the counts change. "
                + "Neither side ever reaches zero: 90 % of one side is gone at ×100 or ÷100, 99 % at ×10,000 or ÷10,000.",
                Design.dim(), 12, false);
        reading.setPadding(0, Ui.dp(ctx, 14), 0, 0);
        box.addView(reading);

        // ---- wiring
        TextWatcher recompute = watcher(this::render);
        inX.addTextChangedListener(watcher(this::onStartChanged));
        inY.addTextChangedListener(watcher(this::onStartChanged));
        inTok.addTextChangedListener(watcher(this::onTokChanged));
        inFee.addTextChangedListener(recompute);
        inVol.addTextChangedListener(recompute);
        inP.addTextChangedListener(watcher(() -> { if (!syncing) render(); }));
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (!fromUser) return;
                double p0 = entryPrice();
                if (Double.isNaN(p0)) return;
                double m = Math.pow(10, (progress - SLIDER_STEPS / 2) / 100.0);
                syncing = true;
                inP.setText(PoolCalc.fmtPrice(p0 * m));
                syncing = false;
                render();
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });

        ScrollView scroll = new ScrollView(ctx);
        scroll.addView(box);
        render();
        new AlertDialog.Builder(ctx)
                .setTitle("Pool calculator")
                .setView(scroll)
                .setPositiveButton("Done", null)
                .show();
    }

    // ---- state ----

    private double num(EditText e) {
        try { return Double.parseDouble(e.getText().toString().trim().replace(",", "")); }
        catch (Exception ex) { return Double.NaN; }
    }

    private double entryPrice() {
        double x0 = num(inX), y0 = num(inY);
        return (x0 > 0 && y0 > 0) ? y0 / x0 : Double.NaN;
    }

    /** A new starting pool keeps the same RELATIVE move, so pool sizes can be compared at, say, ×4 directly. */
    private void onStartChanged() {
        double p0 = entryPrice();
        if (Double.isNaN(p0)) { render(); return; }
        double m = Math.pow(10, (slider.getProgress() - SLIDER_STEPS / 2) / 100.0);
        syncing = true;
        inP.setText(PoolCalc.fmtPrice(p0 * m));
        syncing = false;
        render();
    }

    private void onTokChanged() {
        String t = inTok.getText().toString().trim();
        tok = t.isEmpty() ? "token" : t;
        render();
    }

    private void setPriceFromMultiple(double m) {
        double p0 = entryPrice();
        if (Double.isNaN(p0)) return;
        syncing = true;
        inP.setText(PoolCalc.fmtPrice(p0 * m));
        syncing = false;
        render();
    }

    private void render() {
        double x0 = num(inX), y0 = num(inY), p = num(inP), fee = num(inFee), vol = num(inVol);
        if (Double.isNaN(fee)) fee = 0; if (Double.isNaN(vol)) vol = 0;
        PoolCalc.Result r = PoolCalc.compute(x0, y0, p, fee, vol);
        labelOf(outToken).setText(tok + " in pool");
        labelOf(outRatio).setText("Ratio MINIMA : " + tok);
        if (r == null) {
            derived.setText("Enter a MINIMA amount, a " + tok + " amount and a price above zero.");
            for (TextView t : new TextView[]{ outMinima, outToken, outRatio, outValue, outHold, outMove, outFees, outK, outBreakEven }) t.setText("—");
            curve.set(null, tok);
            return;
        }
        derived.setText("Entry price " + PoolCalc.fmtPrice(r.entryPrice) + " " + tok + " per MINIMA  ·  K " + PoolCalc.fmt(r.k)
                + "  ·  " + PoolCalc.fmt(x0 / y0) + " : 1 MINIMA per " + tok + "  ·  entry value " + PoolCalc.fmt(2 * y0) + " " + tok);
        outMinima.setText(PoolCalc.fmt(r.minima) + "  (" + PoolCalc.fmt(r.minima / x0 * 100) + " % of what you added)");
        outToken.setText(PoolCalc.fmt(r.token) + "  (" + PoolCalc.fmt(r.token / y0 * 100) + " % of what you added)");
        outRatio.setText(PoolCalc.fmt(r.ratio) + " : 1  (always 1 ÷ price)");
        outValue.setText(PoolCalc.fmt(r.value) + " " + tok + "  =  " + PoolCalc.fmt(r.token) + " in MINIMA + " + PoolCalc.fmt(r.token) + " in " + tok);
        outHold.setText((r.vsHoldPct > 0.005 ? "+" : "") + PoolCalc.fmt(r.vsHoldPct) + " %  (holding would be worth " + PoolCalc.fmt(r.hold) + " " + tok + ")");
        outHold.setTextColor(r.vsHoldPct < -0.005 ? Design.red() : r.vsHoldPct > 0.005 ? Design.success() : Design.text());
        outMove.setText(PoolCalc.fmtMove(r.move) + "  (" + PoolCalc.fmtPrice(r.entryPrice) + " → " + PoolCalc.fmtPrice(p) + ")");
        outFees.setText(PoolCalc.fmt(r.feesKept) + " " + tok);
        outK.setText(PoolCalc.fmt(r.kWithFees) + "  (+" + PoolCalc.fmt(r.kGrowthPct) + " %)");
        outBreakEven.setText(Double.isNaN(r.breakEvenVolume) ? "— (fee is 0)" : PoolCalc.fmt(r.breakEvenVolume) + " " + tok);
        // keep the slider in step with a typed price
        double lm = Math.max(-4, Math.min(4, Math.log10(r.move)));
        int want = (int) Math.round(lm * 100) + SLIDER_STEPS / 2;
        if (Math.abs(slider.getProgress() - want) > 1) slider.setProgress(want);
        curve.set(new CurveView.State(x0, y0, r.k, r.kWithFees, r.minima, r.token, r.entryPrice, p), tok);
    }

    // ---- widgets ----

    private TextView text(String s, int color, int sp, boolean bold) {
        TextView t = new TextView(ctx);
        t.setText(s); t.setTextColor(color); t.setTextSize(sp);
        t.setTypeface(bold ? Design.typefaceBold() : Design.typeface());
        return t;
    }

    private TextView section(String s) {
        TextView t = text(s, Design.dim(), 11, true);
        t.setLetterSpacing(0.1f);
        t.setPadding(0, Ui.dp(ctx, 18), 0, Ui.dp(ctx, 2));
        return t;
    }

    private EditText field(LinearLayout box, String label, String value) {
        TextView l = text(label, Design.dim(), 12, false);
        l.setPadding(0, Ui.dp(ctx, 8), 0, Ui.dp(ctx, 2));
        box.addView(l);
        EditText e = new EditText(ctx);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        e.setText(value); e.setTextColor(Design.heading()); e.setHintTextColor(Design.dim2());
        e.setTextSize(16);
        e.setTag(l);
        box.addView(e);
        return e;
    }

    /** A label/value pair; the value TextView is returned and its label is reachable via {@link #labelOf}. */
    private TextView kv(LinearLayout box, String label) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, Ui.dp(ctx, 7), 0, 0);
        TextView k = text(label, Design.dim(), 12, false);
        TextView v = text("—", Design.text(), 14, true);
        row.addView(k); row.addView(v);
        box.addView(row);
        v.setTag(k);
        return v;
    }

    private static TextView labelOf(TextView value) { return (TextView) value.getTag(); }

    private View chipRow() {
        HorizontalScrollView hs = new HorizontalScrollView(ctx);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, Ui.dp(ctx, 8), 0, 0);
        for (double m : CHIPS) {
            TextView c = text(m == 1 ? "entry" : PoolCalc.fmtMove(m), Design.dim(), 12, true);
            c.setPadding(Ui.dp(ctx, 12), Ui.dp(ctx, 6), Ui.dp(ctx, 12), Ui.dp(ctx, 6));
            Ui.chip(c, m == 1);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = Ui.dp(ctx, 6);
            c.setLayoutParams(lp);
            final double mm = m;
            c.setOnClickListener(v -> setPriceFromMultiple(mm));
            row.addView(c);
        }
        hs.addView(row);
        return hs;
    }

    private static TextWatcher watcher(Runnable r) {
        return new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable e) { r.run(); }
        };
    }

    // ---- the curve ----

    /** Draws MINIMA × token = K (with fees) with the entry point and the current pool state; the entry-K curve is
     *  dashed underneath once fees have lifted the pool off its floor. Axes auto-scale to 4× entry, stretching
     *  to keep the current point in view up to 40×. */
    static final class CurveView extends View {
        static final class State {
            final double x0, y0, k, k2, x, y, p0, p;
            State(double x0, double y0, double k, double k2, double x, double y, double p0, double p) {
                this.x0 = x0; this.y0 = y0; this.k = k; this.k2 = k2; this.x = x; this.y = y; this.p0 = p0; this.p = p;
            }
        }
        private State s;
        private String tok = "token";
        private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG), curveP = new Paint(Paint.ANTI_ALIAS_FLAG),
                floorP = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG),
                guide = new Paint(Paint.ANTI_ALIAS_FLAG), dot = new Paint(Paint.ANTI_ALIAS_FLAG),
                ring = new Paint(Paint.ANTI_ALIAS_FLAG), label = new Paint(Paint.ANTI_ALIAS_FLAG);

        CurveView(Context c) {
            super(c);
            grid.setColor(Ui.withAlpha(Design.border2(), 140)); grid.setStrokeWidth(Ui.dp(c, 1)); grid.setStyle(Paint.Style.STROKE);
            curveP.setColor(Design.dim()); curveP.setStrokeWidth(Ui.dp(c, 2)); curveP.setStyle(Paint.Style.STROKE); curveP.setStrokeCap(Paint.Cap.ROUND);
            floorP.setColor(Ui.withAlpha(Design.dim2(), 200)); floorP.setStrokeWidth(Ui.dp(c, 1.5f)); floorP.setStyle(Paint.Style.STROKE);
            floorP.setPathEffect(new DashPathEffect(new float[]{ Ui.dp(c, 4), Ui.dp(c, 4) }, 0));
            fill.setColor(Ui.withAlpha(Design.accent(), 28)); fill.setStyle(Paint.Style.FILL);
            guide.setColor(Ui.withAlpha(Design.accent(), 160)); guide.setStrokeWidth(Ui.dp(c, 1)); guide.setStyle(Paint.Style.STROKE);
            guide.setPathEffect(new DashPathEffect(new float[]{ Ui.dp(c, 3), Ui.dp(c, 4) }, 0));
            dot.setStyle(Paint.Style.FILL);
            ring.setColor(Design.surface()); ring.setStyle(Paint.Style.FILL);
            label.setColor(Design.dim()); label.setTextSize(Ui.dp(c, 10)); label.setTypeface(Design.typeface());
            Ui.panel(this);
        }

        void set(State st, String tokenLabel) { s = st; tok = tokenLabel; invalidate(); }

        @Override protected void onDraw(Canvas cv) {
            super.onDraw(cv);
            if (s == null) return;
            float W = getWidth(), H = getHeight();
            float L = Ui.dp(getContext(), 54), R = Ui.dp(getContext(), 14), T = Ui.dp(getContext(), 16), B = Ui.dp(getContext(), 30);
            double xmax = Math.max(4 * s.x0, Math.min(s.x * 1.15, 40 * s.x0));
            double ymax = Math.max(4 * s.y0, Math.min(s.y * 1.15, 40 * s.y0));
            float pw = W - L - R, ph = H - T - B;
            // 4 gridlines each way, labelled at the axes
            for (int i = 0; i <= 4; i++) {
                float gx = L + pw * i / 4f, gy = T + ph * (4 - i) / 4f;
                cv.drawLine(gx, T, gx, T + ph, grid);
                cv.drawLine(L, gy, L + pw, gy, grid);
                String xl = PoolCalc.fmt(xmax * i / 4.0), yl = PoolCalc.fmt(ymax * i / 4.0);
                label.setTextAlign(Paint.Align.CENTER); cv.drawText(xl, gx, T + ph + Ui.dp(getContext(), 14), label);
                label.setTextAlign(Paint.Align.RIGHT); cv.drawText(yl, L - Ui.dp(getContext(), 6), gy + Ui.dp(getContext(), 4), label);
            }
            label.setTextAlign(Paint.Align.RIGHT);
            cv.drawText("MINIMA in pool →", L + pw, T + ph + Ui.dp(getContext(), 26), label);
            label.setTextAlign(Paint.Align.LEFT);
            cv.drawText(tok + " in pool ↑", L + Ui.dp(getContext(), 4), T - Ui.dp(getContext(), 4), label);

            Path c2 = curvePath(s.k2, xmax, ymax, L, T, pw, ph);
            Path wash = new Path(c2);
            wash.lineTo(L + pw, T + ph); wash.lineTo(sx(s.k2 / ymax, xmax, L, pw), T + ph); wash.close();
            cv.drawPath(wash, fill);
            if (s.k2 > s.k * 1.0005) cv.drawPath(curvePath(s.k, xmax, ymax, L, T, pw, ph), floorP);
            cv.drawPath(c2, curveP);

            float nx = sx(s.x, xmax, L, pw), ny = sy(s.y, ymax, T, ph);
            float ex = sx(s.x0, xmax, L, pw), ey = sy(s.y0, ymax, T, ph);
            cv.drawLine(nx, ny, nx, T + ph, guide);
            cv.drawLine(L, ny, nx, ny, guide);
            float r1 = Ui.dp(getContext(), 5), r2 = Ui.dp(getContext(), 7), rg = Ui.dp(getContext(), 2);
            dot.setColor(Design.heading());
            cv.drawCircle(ex, ey, r1 + rg, ring); cv.drawCircle(ex, ey, r1, dot);
            dot.setColor(Design.accent());
            cv.drawCircle(nx, ny, r2 + rg, ring); cv.drawCircle(nx, ny, r2, dot);
            // one label: the current state, placed on whichever side has room
            label.setColor(Design.heading());
            String now = "now " + PoolCalc.fmtPrice(s.p) + ": " + PoolCalc.fmt(s.x) + " · " + PoolCalc.fmt(s.y);
            boolean right = nx < L + pw * 0.55f;
            label.setTextAlign(right ? Paint.Align.LEFT : Paint.Align.RIGHT);
            float ly = ny < T + Ui.dp(getContext(), 20) ? ny + Ui.dp(getContext(), 20) : ny - Ui.dp(getContext(), 12);
            cv.drawText(now, nx + (right ? r2 + Ui.dp(getContext(), 6) : -(r2 + Ui.dp(getContext(), 6))), ly, label);
            label.setColor(Design.dim());
        }

        private Path curvePath(double k, double xmax, double ymax, float L, float T, float pw, float ph) {
            Path p = new Path();
            double xs = k / ymax;   // where the curve enters the plot from the top
            int n = 120;
            for (int i = 0; i <= n; i++) {
                double xx = xs + (xmax - xs) * i / (double) n;
                float px = sx(xx, xmax, L, pw), py = sy(k / xx, ymax, T, ph);
                if (i == 0) p.moveTo(px, py); else p.lineTo(px, py);
            }
            return p;
        }
        private static float sx(double v, double max, float L, float pw) { return (float) (L + v / max * pw); }
        private static float sy(double v, double max, float T, float ph) { return (float) (T + (1 - v / max) * ph); }
    }
}
