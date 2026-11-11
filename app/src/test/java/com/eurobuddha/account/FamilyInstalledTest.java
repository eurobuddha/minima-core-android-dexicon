package com.eurobuddha.account;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.os.Bundle;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

/** otherInstalled reads the sibling's declared leaf share from its manifest (review, 11 Nov 2026). Vendored into every
 *  app, so each expectation follows the app running it (Dexicon, AtomiX or PandaPools). */
@RunWith(RobolectricTestRunner.class) @Config(sdk = 33)
public class FamilyInstalledTest {
    static void install(Context c, String pkg, Integer slot) {
        PackageInfo pi = new PackageInfo();
        pi.packageName = pkg;
        pi.applicationInfo = new ApplicationInfo();
        pi.applicationInfo.packageName = pkg;
        if (slot != null) { pi.applicationInfo.metaData = new Bundle(); pi.applicationInfo.metaData.putInt(Family.META_LEAF_SLOT, slot); }
        Shadows.shadowOf(c.getPackageManager()).installPackage(pi);
    }

    /** A sibling of another kind than this app (PandaPools, or AtomiX when this app is PandaPools). */
    static String sibling(Context c) { return Family.slotOf(c.getPackageName()) == AccountKeys.SLOT_PANDAPOOLS ? "com.eurobuddha.atomix" : "com.eurobuddha.pandapools"; }
    static String name(String pkg) { return pkg.endsWith("atomix") ? "AtomiX" : "PandaPools"; }

    @Test public void aSiblingFromBeforeLeafSharesAlwaysConflicts() {
        Context c = RuntimeEnvironment.getApplication();
        Assume.assumeTrue("runs only where the test app carries a family package name", Family.slotOf(c.getPackageName()) >= 0);
        assertNull(Family.otherInstalled(c));
        String sib = sibling(c);
        install(c, sib, null);   // no declared share: it may sign any leaf
        assertEquals(name(sib), Family.otherInstalled(c));
    }

    @Test public void aShareAwareSiblingConflictsOnlyWhenTheRuleSaysSo() {
        Context c = RuntimeEnvironment.getApplication();
        Assume.assumeTrue("runs only where the test app carries a family package name", Family.slotOf(c.getPackageName()) >= 0);
        String sib = sibling(c);
        install(c, sib, Family.slotOf(sib));
        boolean expected = Family.conflicts(c.getPackageName(), sib, Family.slotOf(sib));   // Dexicon: yes; AtomiX/PandaPools: no
        assertEquals(expected ? name(sib) : null, Family.otherInstalled(c));
    }

    @Test public void theDeclaredShareIsReadFromTheManifest() throws Exception {
        Context c = RuntimeEnvironment.getApplication();
        install(c, "com.eurobuddha.dexicon.stock", null);
        install(c, "com.eurobuddha.pandapools.stock", AccountKeys.SLOT_PANDAPOOLS);
        assertNull(c.getPackageManager().getApplicationInfo("com.eurobuddha.dexicon.stock", android.content.pm.PackageManager.GET_META_DATA).metaData);
        assertEquals(AccountKeys.SLOT_PANDAPOOLS, c.getPackageManager().getApplicationInfo("com.eurobuddha.pandapools.stock",
                android.content.pm.PackageManager.GET_META_DATA).metaData.getInt(Family.META_LEAF_SLOT, -1));
    }
}
