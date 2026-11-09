package com.eurobuddha.dexicon.account;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.SharedPreferences;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

@RunWith(RobolectricTestRunner.class)
public class KeyCountMigrationTest {

    private SharedPreferences prefs(String name) { return RuntimeEnvironment.getApplication().getSharedPreferences(name, Context.MODE_PRIVATE); }

    @Test public void anUpgradedAccountKeepsEveryAddressItAllocated() {
        SharedPreferences sp = prefs("m1");
        sp.edit().putInt("keys", 3).putString("pub128x4_0", "0xAA").commit();   // 0.2.99: three keys, key 0 = 0xAA
        Account.migrateKeyCount(sp, "keys_fp1", () -> "0xaa");
        assertEquals(3, sp.getInt("keys_fp1", 0));
        assertFalse(sp.contains("keys"));
    }

    @Test public void anotherSeedNeverInheritsTheCount() {
        SharedPreferences sp = prefs("m2");
        sp.edit().putInt("keys", 3).putString("pub128x4_0", "0xAA").commit();
        Account.migrateKeyCount(sp, "keys_fp2", () -> "0xBB");                         // a different account's key 0
        assertFalse(sp.contains("keys_fp2"));
        assertEquals(3, sp.getInt("keys", 0));                                    // left for its own seed
    }

    @Test public void anExistingSlotIsNeverOverwritten() {
        SharedPreferences sp = prefs("m3");
        sp.edit().putInt("keys", 3).putString("pub128x4_0", "0xAA").putInt("keys_fp3", 5).commit();
        Account.migrateKeyCount(sp, "keys_fp3", () -> "0xAA");
        assertEquals(5, sp.getInt("keys_fp3", 0));
    }

    @Test public void aSlotThatAnInterimVersionCreatedAsOneIsMergedUp() {
        SharedPreferences sp = prefs("m4");
        sp.edit().putInt("keys", 3).putString("pub128x4_0", "0xAA").putInt("keys_fp4", 1).commit();   // 0.2.99 -> 0.3.0
        Account.migrateKeyCount(sp, "keys_fp4", () -> "0xAA");
        assertEquals(3, sp.getInt("keys_fp4", 0));
        assertFalse(sp.contains("keys"));
    }

    @Test public void nothingToMigrateNeverBuildsKeyZero() {
        SharedPreferences sp = prefs("m5");
        Account.migrateKeyCount(sp, "keys_fp5", () -> { throw new AssertionError("key 0 built with nothing to migrate"); });
    }
}
