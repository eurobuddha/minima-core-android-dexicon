// VENDORED VERBATIM from apks/atomix 0.2.8 app/src/main/java/com/eurobuddha/atomix/CcyRing.java by scripts/vendor-atomix.sh — do not edit here; re-vendor.
package com.eurobuddha.atomix;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.graphics.drawable.Drawable;

/**
 * The currency switch's background: the pill body, plus a moving ring drawn in the OTHER currency's
 * accent.
 *
 * Why the other currency's colour is the whole idea: the control's job is "go to the other market",
 * and colour can say that without the label ever lying. The chip keeps naming the market your money
 * is actually in - which matters, because settlement is currency-agnostic and history is
 * currency-scoped, so a chip that periodically displayed the currency you are NOT trading would be a
 * real-funds hazard at a glance. An orange dot orbiting a green pill says "MINIMA is over here" and
 * says it continuously, without ever claiming you are in MINIMA.
 *
 *   dollar market (green pill)  -> ORBIT: an orange dot travelling the perimeter
 *   MINIMA market (orange pill) -> SWEEP: a green arc of glow rotating around the edge
 *
 * Both run off one phase 0..1. Motion is the point here, but it is still not the only signal: with
 * animations disabled the ring is drawn as a full static rim in the other accent (see
 * {@link #setPhase}'s caller), so the control is still marked.
 */
public class CcyRing extends Drawable {

    public enum Style { ORBIT, SWEEP }

    private final Paint bodyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint halo      = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Path ringPath = new Path();
    private final PathMeasure measure = new PathMeasure();
    private final RectF rect = new RectF();
    private final float[] pos = new float[2];
    private final float[] tan = new float[2];

    // draw() runs on every animator frame - 60fps, continuously, for as long as the screen is up - so
    // nothing in it may allocate. Both shaders and both matrices are built once per bounds change and
    // then only MUTATED: the sweep is spun with setRotate, the halo is moved with setTranslate.
    private SweepGradient sweep;
    private RadialGradient haloShader;
    private final Matrix spin = new Matrix();
    private final Matrix haloMove = new Matrix();

    private final float radius, stroke, dotR;
    private final int otherAccent;
    private final Style style;

    private float phase = 0f;
    private boolean still = false;

    public CcyRing(int bodyColor, int otherAccent, Style style, float radiusPx, float strokePx, float dotRadiusPx) {
        this.otherAccent = otherAccent;
        this.style = style;
        this.radius = radiusPx;
        this.stroke = strokePx;
        this.dotR = dotRadiusPx;

        bodyPaint.setStyle(Paint.Style.FILL);
        bodyPaint.setColor(bodyColor);

        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(strokePx);
        ringPaint.setStrokeCap(Paint.Cap.ROUND);

        dotPaint.setStyle(Paint.Style.FILL);
        dotPaint.setColor(otherAccent);

        halo.setStyle(Paint.Style.FILL);
    }

    /** 0..1 around the ring. {@code still} draws the full rim instead — the reduce-motion form. */
    public void setPhase(float p, boolean still) {
        this.phase = p - (float) Math.floor(p);
        this.still = still;
        invalidateSelf();
    }

    @Override protected void onBoundsChange(android.graphics.Rect bounds) {
        super.onBoundsChange(bounds);
        float in = stroke / 2f;
        rect.set(bounds.left + in, bounds.top + in, bounds.right - in, bounds.bottom - in);
        ringPath.reset();
        ringPath.addRoundRect(rect, radius, radius, Path.Direction.CW);
        measure.setPath(ringPath, true);

        if (rect.isEmpty()) { sweep = null; haloShader = null; return; }

        // The sweep is anchored at the pill's centre and rotated per frame.
        sweep = new SweepGradient(rect.centerX(), rect.centerY(),
                new int[]{ alpha(otherAccent, 0), alpha(otherAccent, 0), otherAccent,
                           alpha(otherAccent, 0), alpha(otherAccent, 0) },
                new float[]{ 0f, 0.55f, 0.75f, 0.95f, 1f });
        // The halo is built at the ORIGIN and translated to the dot each frame, so its geometry
        // never has to be rebuilt as the dot travels.
        haloShader = new RadialGradient(0f, 0f, dotR * 3f,
                new int[]{ alpha(otherAccent, 0xB0), alpha(otherAccent, 0) },
                new float[]{ 0f, 1f }, Shader.TileMode.CLAMP);
    }

    @Override public void draw(Canvas canvas) {
        canvas.drawRoundRect(rect, radius, radius, bodyPaint);
        if (rect.isEmpty()) return;

        if (still) {                      // animations off: a plain full rim, no motion, still marked
            ringPaint.setShader(null);
            ringPaint.setColor(otherAccent);
            canvas.drawPath(ringPath, ringPaint);
            return;
        }

        if (style == Style.SWEEP) {
            // A rotating arc of glow: a sweep gradient that is transparent for most of the turn and
            // peaks in one short band, spun by `phase`. Drawn along the rounded-rect stroke, so the
            // bright band tracks the pill's actual edge rather than a circle.
            if (sweep == null) return;
            spin.setRotate(phase * 360f, rect.centerX(), rect.centerY());
            sweep.setLocalMatrix(spin);
            ringPaint.setShader(sweep);
            ringPaint.setColor(otherAccent);
            canvas.drawPath(ringPath, ringPaint);
            // A faint constant rim underneath so the edge never fully disappears between passes.
            ringPaint.setShader(null);
            ringPaint.setColor(alpha(otherAccent, 0x3A));
            canvas.drawPath(ringPath, ringPaint);
            return;
        }

        // ORBIT: a dot running the perimeter. A faint rim marks the track it runs on.
        ringPaint.setShader(null);
        ringPaint.setColor(alpha(otherAccent, 0x3A));
        canvas.drawPath(ringPath, ringPaint);

        float len = measure.getLength();
        if (len <= 0f) return;
        measure.getPosTan(phase * len, pos, tan);

        if (haloShader == null) return;
        haloMove.setTranslate(pos[0], pos[1]);
        haloShader.setLocalMatrix(haloMove);
        halo.setShader(haloShader);
        canvas.drawCircle(pos[0], pos[1], dotR * 3f, halo);
        canvas.drawCircle(pos[0], pos[1], dotR, dotPaint);
    }

    /** Same RGB, explicit alpha. */
    static int alpha(int argb, int a) { return ((a & 0xFF) << 24) | (argb & 0x00FFFFFF); }

    @Override public void setAlpha(int a) { /* colours are fixed per currency */ }
    @Override public void setColorFilter(ColorFilter cf) { /* not tinted */ }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
