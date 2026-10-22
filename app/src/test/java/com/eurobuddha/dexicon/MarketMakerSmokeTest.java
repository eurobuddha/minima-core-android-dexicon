package com.eurobuddha.dexicon;

import static org.junit.Assert.*;

import android.app.AlertDialog;
import android.view.View;
import android.widget.EditText;

import com.eurobuddha.dexicon.maker.Ladder;
import com.eurobuddha.dexicon.maker.LadderRunner;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;

/** ORDERS › MARKET MAKER: the card renders, the editor opens, the auto-fill seeds the rungs, SAVE stores the config. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class MarketMakerSmokeTest {
    @Test public void editorAutoFillsAndSaves() {
        android.app.Application app = org.robolectric.RuntimeEnvironment.getApplication();
        org.robolectric.Shadows.shadowOf(app).setComponentNameAndServiceForBindService(new android.content.ComponentName(app, NodeTransportService.class),
                new android.os.Messenger(new android.os.Handler(android.os.Looper.getMainLooper(), m -> true)).getBinder());
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        View root = a.getWindow().getDecorView();
        DoorsTabSmokeTest.find(root, "ORDERS").performClick();
        assertNotNull(DoorsTabSmokeTest.find(root, "MARKET MAKER"));
        DoorsTabSmokeTest.find(root, "SET UP ▸").performClick();
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();   // the dialog's OnShow (SAVE handler) is a posted message
        AlertDialog d = (AlertDialog) ShadowDialog.getLatestDialog();
        assertNotNull(d);
        View dv = d.getWindow().getDecorView();
        java.util.List<EditText> f = new java.util.ArrayList<>(); collect(dv, f);
        // order in the dialog: skew, reprice, mid, step, levels, ask size, bid size, then A6..A1 and B1..B6 (price, amount)
        f.get(2).setText("0.004"); f.get(3).setText("1"); f.get(4).setText("2"); f.get(5).setText("100"); f.get(6).setText("50");
        EditText a1price = f.get(7 + 2 * 5), b1price = f.get(7 + 12);
        assertEquals("0.00404", a1price.getText().toString());
        assertEquals("0.00396", b1price.getText().toString());
        ((android.widget.Switch) find(dv, android.widget.Switch.class)).setChecked(true);   // Enabled
        d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        Ladder.Config c = Ladder.Config.fromJson(a.db().meta(LadderRunner.CONFIG, ""));
        assertTrue(c.enabled); assertFalse(c.peg);
        assertEquals(4, Ladder.desired(c, 0, 1).size());
        assertEquals("1", a.db().meta(LadderRunner.DIRTY, ""));
    }
    static void collect(View v, java.util.List<EditText> out) {
        if (v instanceof EditText) out.add((EditText) v);
        if (v instanceof android.view.ViewGroup) for (int i = 0; i < ((android.view.ViewGroup) v).getChildCount(); i++) collect(((android.view.ViewGroup) v).getChildAt(i), out);
    }
    static View find(View v, Class<?> k) {
        if (k.isInstance(v)) return v;
        if (v instanceof android.view.ViewGroup) for (int i = 0; i < ((android.view.ViewGroup) v).getChildCount(); i++) { View x = find(((android.view.ViewGroup) v).getChildAt(i), k); if (x != null) return x; }
        return null;
    }
}
