// VENDORED VERBATIM from apks/atomix 0.2.5 app/src/main/java/com/eurobuddha/comms/QrUtil.java by scripts/vendor-atomix.sh — do not edit here; re-vendor.
package com.eurobuddha.comms;

import android.graphics.Bitmap;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;

import java.util.HashMap;
import java.util.Map;

/** Render a string as a QR bitmap (black on white, scannable). No camera — generation only. */
public final class QrUtil {

    public static Bitmap qr(String text, int sizePx) {
        // MI-11: bound the requested size — a w*h int[] plus a 4-byte/px bitmap is quadratic and can OOM (or
        // overflow int) on an unbounded value. Callers already handle a null return.
        if (sizePx <= 0) return null;
        if (sizePx > 4096) sizePx = 4096;
        try {
            Map<EncodeHintType, Object> hints = new HashMap<>();
            hints.put(EncodeHintType.MARGIN, 1);
            BitMatrix m = new MultiFormatWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints);
            int w = m.getWidth(), h = m.getHeight();
            int[] px = new int[w * h];
            for (int y = 0; y < h; y++)
                for (int x = 0; x < w; x++)
                    px[y * w + x] = m.get(x, y) ? 0xFF000000 : 0xFFFFFFFF;
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            bmp.setPixels(px, 0, w, 0, 0, w, h);
            return bmp;
        } catch (Exception e) {
            return null;
        }
    }

    private QrUtil() {}
}
