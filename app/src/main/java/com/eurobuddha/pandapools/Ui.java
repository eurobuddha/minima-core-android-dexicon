// VENDORED VERBATIM from apks/pandapools 0.10.12 app/src/main/java/com/eurobuddha/pandapools/Ui.java by scripts/vendor-pandapools.sh — do not edit here; re-vendor.
package com.eurobuddha.pandapools;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.util.TypedValue;
import android.view.View;
import android.widget.TextView;

/**
 * Small, theme-aware view-styling helpers so every screen shares one polished visual language
 * (rounded surfaces, an accent-filled primary CTA, soft chips) instead of flat rectangles. Everything
 * reads its colours + corner radius from {@link Design}, so the design toggle restyles it for free.
 */
public final class Ui {

    private Ui() {}

    public static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics()));
    }

    private static float radiusPx(Context c) { return dp(c, Math.max(Design.radiusDp(), 10f)); }

    /** A raised content card: surface fill, rounded, hairline border. */
    public static void card(View v) {
        Context c = v.getContext();
        GradientDrawable g = new GradientDrawable();
        g.setColor(Design.surface());
        g.setCornerRadius(radiusPx(c));
        g.setStroke(dp(c, 1), withAlpha(Design.border2(), Design.isOriginal() ? 255 : 90));
        v.setBackground(g);
    }

    /** A slightly recessed inner panel (e.g. the from/to swap fields). */
    public static void panel(View v) {
        Context c = v.getContext();
        GradientDrawable g = new GradientDrawable();
        g.setColor(Design.surface2());
        g.setCornerRadius(radiusPx(c));
        g.setStroke(dp(c, 1), withAlpha(Design.border2(), Design.isOriginal() ? 255 : 70));
        v.setBackground(g);
    }

    /** The primary call-to-action: accent fill, on-accent text, ripple, rounded. */
    public static void primaryButton(TextView t) {
        Context c = t.getContext();
        boolean enabled = t.isEnabled();
        GradientDrawable g = new GradientDrawable();
        g.setColor(enabled ? Design.accent() : withAlpha(Design.accent(), 60));
        g.setCornerRadius(radiusPx(c));
        t.setBackground(ripple(c, g, Design.onAccent()));
        t.setTextColor(enabled ? Design.onAccent() : withAlpha(Design.onAccent(), 120));
        t.setTypeface(Design.typefaceBold());
        t.setClickable(enabled);
    }

    /** A secondary / outline button: transparent fill, accent border + text. */
    public static void outlineButton(TextView t) {
        Context c = t.getContext();
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.TRANSPARENT);
        g.setCornerRadius(radiusPx(c));
        g.setStroke(dp(c, 1), Design.accent());
        t.setBackground(ripple(c, g, Design.accent()));
        t.setTextColor(Design.accent());
        t.setTypeface(Design.typefaceBold());
    }

    /** A small rounded chip (token tag / toggle). {@code on} = accent-tinted, else neutral. */
    public static void chip(TextView t, boolean on) {
        Context c = t.getContext();
        GradientDrawable g = new GradientDrawable();
        g.setColor(on ? Design.accentSoft() : withAlpha(Design.surface2(), 255));
        g.setCornerRadius(dp(c, 100));
        g.setStroke(dp(c, 1), on ? Design.accent() : withAlpha(Design.border2(), 120));
        t.setBackground(g);
        t.setTextColor(on ? Design.accent() : Design.dim());
        t.setTypeface(Design.typefaceBold());
    }

    /** A coloured status banner background (info / warn / error), soft-tinted + rounded. */
    public static void banner(View v, int softColor, int strokeColor) {
        Context c = v.getContext();
        GradientDrawable g = new GradientDrawable();
        g.setColor(softColor);
        g.setCornerRadius(radiusPx(c));
        g.setStroke(dp(c, 1), withAlpha(strokeColor, 120));
        v.setBackground(g);
    }

    /**
     * Make a view copy an identifier to the clipboard when tapped, and say so.
     *
     * An address, TxPoW id or token id exists to be pasted — into a block explorer, a support request, another
     * wallet. A shortened one is not information: it cannot be pasted anywhere and cannot be searched for. So
     * nothing in this app abbreviates one; where a value is too long to sit comfortably on a line, it wraps, and
     * what lands on the clipboard is always the COMPLETE string, never what happens to be on screen.
     */
    public static void copyable(final View v, final String label, final String fullValue) {
        if (v == null || fullValue == null || fullValue.isEmpty()) return;
        v.setOnClickListener(view -> {
            Context c = view.getContext();
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(android.content.ClipData.newPlainText(label, fullValue));
            android.widget.Toast.makeText(c, "Copied " + label, android.widget.Toast.LENGTH_SHORT).show();
        });
    }

    /** A monospaced, wrapping, tap-to-copy row for one full identifier. */
    public static TextView identifierRow(Context c, String label, String fullValue) {
        TextView t = new TextView(c);
        t.setText(label + ":  " + fullValue + "   (tap to copy)");
        t.setTextColor(Design.dim());
        t.setTextSize(12f);
        t.setTypeface(android.graphics.Typeface.MONOSPACE);
        t.setPadding(0, dp(c, 4), 0, dp(c, 4));
        copyable(t, label, fullValue);
        return t;
    }

    private static RippleDrawable ripple(Context c, GradientDrawable content, int rippleColor) {
        return new RippleDrawable(ColorStateList.valueOf(withAlpha(rippleColor, 60)), content, null);
    }

    public static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (Math.min(255, Math.max(0, alpha)) << 24);
    }
}
