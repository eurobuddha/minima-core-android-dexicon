package com.eurobuddha.dexicon;

import static org.junit.Assert.*;

import android.app.AlertDialog;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import com.eurobuddha.atomix.eth.EthWallet;
import com.eurobuddha.dexicon.doors.DoorHost;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;

/** 0.1.97: AtomiX's Wallet actions on Assets' Ethereum card — Fund / QR, Send, Export key (screenshot-blocked). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class WalletActionsTest {
    private static final String KEY = "4c0883a69102937d6231471b5dbb6204fe5129617082792ae468d01a3f362318";

    private static void wallet(EthWallet w, String key) throws Exception {
        java.lang.reflect.Field f = EthWallet.class.getDeclaredField("creds");
        f.setAccessible(true);
        f.set(w, key == null ? null : org.web3j.crypto.Credentials.create(key));
    }

    @After public void clear() throws Exception { wallet(DoorHost.get(RuntimeEnvironment.getApplication()).wallet(), null); }

    private static String message(AlertDialog d) { TextView m = d.findViewById(android.R.id.message); return m == null ? "" : m.getText().toString(); }

    @Test public void assetsCarriesAtomixsWalletActions() throws Exception {
        android.app.Application app = RuntimeEnvironment.getApplication();
        org.robolectric.Shadows.shadowOf(app).setComponentNameAndServiceForBindService(new android.content.ComponentName(app, NodeTransportService.class),
                new android.os.Messenger(new android.os.Handler(android.os.Looper.getMainLooper(), m -> true)).getBinder());
        DoorHost host = DoorHost.get(app);
        wallet(host.wallet(), KEY);
        String addr = host.ethAddress();
        assertNotNull(addr);
        MainActivity a = DoorsTabSmokeTest.launch();
        View root = a.getWindow().getDecorView();
        DoorsTabSmokeTest.tab(root, "Assets").performClick();
        for (String b : new String[]{"↻  Refresh", "⤓  Fund / QR", "↑  Send", "🔑  Export key"}) assertNotNull(b, DoorsTabSmokeTest.find(root, b));

        DoorsTabSmokeTest.find(root, "⤓  Fund / QR").performClick();
        AlertDialog fund = (AlertDialog) ShadowDialog.getLatestDialog();
        assertTrue(fund.isShowing());
        assertNotNull("the address in full", DoorsTabSmokeTest.find(fund.getWindow().getDecorView(), addr));
        fund.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();   // AlertDialog posts its button handler
        android.content.ClipboardManager cm = (android.content.ClipboardManager) a.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
        assertEquals("Copy address copies ALL of it", addr, cm.getPrimaryClip().getItemAt(0).getText().toString());

        DoorsTabSmokeTest.find(root, "↑  Send").performClick();
        AlertDialog send = (AlertDialog) ShadowDialog.getLatestDialog();
        assertTrue(send.isShowing());
        assertNotNull("ETH or USDT", DoorsTabSmokeTest.find(send.getWindow().getDecorView(), "USDT"));
        assertNotNull(DoorsTabSmokeTest.find(send.getWindow().getDecorView(), "Max"));
        send.dismiss();

        DoorsTabSmokeTest.find(root, "🔑  Export key").performClick();
        AlertDialog warn = (AlertDialog) ShadowDialog.getLatestDialog();
        assertTrue(message(warn).contains("Anyone who sees it can take them"));
        warn.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        AlertDialog secret = (AlertDialog) ShadowDialog.getLatestDialog();
        assertNotSame(warn, secret);
        assertNotNull("the key, in full", DoorsTabSmokeTest.find(secret.getWindow().getDecorView(), host.wallet().privateKeyHex()));
        assertTrue("no screenshots of the key", (secret.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
    }
}
