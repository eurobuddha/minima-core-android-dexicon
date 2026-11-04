package com.eurobuddha.pandapools;

import android.content.Context;
import android.graphics.Typeface;

/**
 * DEXICON-OWNED SHIM (not vendored): PandaPools' design tokens, answered from Dexicon's {@link com.eurobuddha.dexicon.Design}
 * so the vendored pool screens (SwapView, PoolsView, MyLpView, ActivityView, PoolCalcDialog) draw in Dexicon's palette and
 * follow its light / dark toggle. Same API as apks/pandapools Design.java; the "original" brutalist look is never on here.
 */
public final class Design {
    public enum Mode { ORIGINAL_LIGHT, ORIGINAL_DARK, CURRENT }
    private Design() {}

    public static void load(Context c) { com.eurobuddha.dexicon.Design.load(c); }
    public static void set(Context c, Mode m) { /* Dexicon's own ☀/☾ toggle owns the theme */ }
    public static Mode mode() { return isDark() ? Mode.CURRENT : Mode.ORIGINAL_LIGHT; }
    public static boolean isOriginal() { return false; }
    public static boolean isDark() { return com.eurobuddha.dexicon.Design.isDark(); }
    public static Mode next() { return mode(); }
    public static String label() { return com.eurobuddha.dexicon.Design.label(); }

    private static int pick(int dark, int light) { return isDark() ? dark : light; }

    public static int bg()         { return com.eurobuddha.dexicon.Design.BG(); }
    public static int surface()    { return com.eurobuddha.dexicon.Design.SURFACE(); }
    public static int surface2()   { return com.eurobuddha.dexicon.Design.SURFACE2(); }
    public static int border()     { return com.eurobuddha.dexicon.Design.DIM2(); }
    public static int border2()    { return com.eurobuddha.dexicon.Design.DIM2(); }
    public static int accent()     { return com.eurobuddha.dexicon.Design.ACCENT(); }
    public static int accent2()    { return com.eurobuddha.dexicon.Design.GRAD_START(); }
    public static int accentSoft() { return com.eurobuddha.dexicon.Design.ACCENT_SOFT(); }
    public static int text()       { return com.eurobuddha.dexicon.Design.TEXT(); }
    public static int dim()        { return com.eurobuddha.dexicon.Design.DIM(); }
    public static int dim2()       { return com.eurobuddha.dexicon.Design.DIM2(); }
    public static int heading()    { return com.eurobuddha.dexicon.Design.TEXT(); }
    public static int red()        { return com.eurobuddha.dexicon.Design.RED(); }
    public static int redSoft()    { return (com.eurobuddha.dexicon.Design.RED() & 0x00FFFFFF) | 0x33000000; }
    public static int amber()      { return pick(0xFFE6A23C, 0xFF9A6B00); }
    public static int amberSoft()  { return pick(0x33E6A23C, 0xFFFBF0D6); }
    public static int blue()       { return pick(0xFF6F9BFF, 0xFF1C46E0); }
    public static int blueSoft()   { return pick(0x336F9BFF, 0xFFE4E9FF); }
    public static int onAccent()   { return com.eurobuddha.dexicon.Design.ON_ACCENT(); }
    public static int success()    { return com.eurobuddha.dexicon.Design.IN(); }

    public static Typeface typeface()     { return com.eurobuddha.dexicon.Design.sans(); }
    public static Typeface typefaceBold() { return com.eurobuddha.dexicon.Design.sansBold(); }
    public static float radiusDp()        { return 12f; }
    public static boolean upperLabels()   { return false; }
    public static float labelTracking()   { return 0f; }

}
