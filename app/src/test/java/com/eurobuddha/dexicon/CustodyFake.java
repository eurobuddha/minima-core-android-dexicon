package com.eurobuddha.dexicon;

import android.app.Application;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.pm.PackageInfo;
import android.content.pm.ProviderInfo;
import android.content.pm.Signature;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

import com.eurobuddha.pandapools.DexiconPools;

import org.robolectric.Robolectric;
import org.robolectric.Shadows;

/** Standalone PandaPools' custody provider (PandaPools 0.9.81) as a test stand-in: {@link #rows} is what it answers
 *  ({@code {address, state}}); null = it answers null (e.g. Dexicon not signature-matched). */
public class CustodyFake extends ContentProvider {
    public static volatile String[][] rows = {};
    /** Provider queries answered so far. */
    public static final java.util.concurrent.atomic.AtomicInteger queries = new java.util.concurrent.atomic.AtomicInteger();

    public enum Install { NONE, OLD, NEW, FOREIGN_SIGNED }

    /** NONE removes PandaPools; OLD = installed without the provider; NEW = with it, same signing key; FOREIGN_SIGNED =
     *  with it, but signed with another key. Then re-reads custody. */
    public static void install(Application app, Install how, long version) {
        DexiconPools.custodyExecutorForTest(Runnable::run);   // the UI's background custody read runs inline in tests
        Shadows.shadowOf(app.getPackageManager()).removePackage(TradingGate.PANDAPOOLS);
        if (how != Install.NONE) {
            Signature mine = new Signature("0a0b0c"), other = new Signature("0d0e0f");
            try {
                PackageInfo self = app.getPackageManager().getPackageInfo(app.getPackageName(), 0);
                self.signatures = new Signature[]{mine};
                Shadows.shadowOf(app.getPackageManager()).installPackage(self);
            } catch (Exception e) { throw new RuntimeException(e); }
            PackageInfo pi = new PackageInfo();
            pi.packageName = TradingGate.PANDAPOOLS;
            pi.setLongVersionCode(version);
            pi.signatures = new Signature[]{how == Install.FOREIGN_SIGNED ? other : mine};
            if (how != Install.OLD) {
                ProviderInfo p = new ProviderInfo();
                p.authority = DexiconPools.CUSTODY_AUTHORITY; p.packageName = TradingGate.PANDAPOOLS; p.name = CustodyFake.class.getName();
                p.exported = true;
                pi.providers = new ProviderInfo[]{p};
                Robolectric.buildContentProvider(CustodyFake.class).create(p);
            }
            Shadows.shadowOf(app.getPackageManager()).installPackage(pi);
        }
        DexiconPools.readCustody(app);
    }

    @Override public boolean onCreate() { return true; }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        queries.incrementAndGet();
        String[][] r = rows;
        if (r == null) return null;
        MatrixCursor c = new MatrixCursor(new String[]{"address", "state"});
        for (String[] row : r) c.addRow(row);
        return c;
    }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String s, String[] a) { return 0; }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { return 0; }
}
