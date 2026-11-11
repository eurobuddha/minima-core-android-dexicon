package com.eurobuddha.account;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.os.Bundle;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

/** otherInstalled reads the sibling's declared leaf share from its manifest (review, 11 Nov 2026). Runs as Dexicon. */
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

    @Test public void dexiconSeesAnyAtomixOrPandaPoolsAsAConflict() {
        Context c = RuntimeEnvironment.getApplication();
        assertNull(Family.otherInstalled(c));
        install(c, "com.eurobuddha.pandapools", AccountKeys.SLOT_PANDAPOOLS);
        assertEquals("PandaPools", Family.otherInstalled(c));
    }

    @Test public void theDeclaredShareIsReadFromTheManifest() {
        Context c = RuntimeEnvironment.getApplication();
        install(c, "com.eurobuddha.atomix", null);   // an AtomiX from before leaf shares
        assertEquals(-1, metaSlot(c, "com.eurobuddha.atomix"));
        install(c, "com.eurobuddha.atomix.stock", AccountKeys.SLOT_ATOMIX);
        assertEquals(AccountKeys.SLOT_ATOMIX, metaSlot(c, "com.eurobuddha.atomix.stock"));
    }

    static int metaSlot(Context c, String pkg) {
        try {
            ApplicationInfo ai = c.getPackageManager().getApplicationInfo(pkg, android.content.pm.PackageManager.GET_META_DATA);
            return ai.metaData == null ? -1 : ai.metaData.getInt(Family.META_LEAF_SLOT, -1);
        } catch (Exception e) { return -2; }
    }
}
