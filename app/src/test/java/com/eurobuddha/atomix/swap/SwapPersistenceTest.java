// VENDORED VERBATIM from apks/atomix 0.1.84 app/src/test/java/com/eurobuddha/atomix/swap/SwapPersistenceTest.java by scripts/vendor-atomix.sh — do not edit here; re-vendor.
package com.eurobuddha.atomix.swap;
import android.content.Context;
import android.database.sqlite.*;
import java.lang.reflect.Field;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
public class SwapPersistenceTest {
    @Test public void ignoredInsertCannotMasqueradeAsSavedReservation() throws Exception {
        Context context=RuntimeEnvironment.getApplication();context.deleteDatabase("atomix.db");SwapDb db=new SwapDb(context);
        Field f=SwapDb.class.getDeclaredField("helper");f.setAccessible(true);SQLiteOpenHelper helper=(SQLiteOpenHelper)f.get(db);
        try {
            SQLiteDatabase sql=helper.getWritableDatabase();
            sql.execSQL("CREATE TRIGGER fixture_ignore BEFORE INSERT ON swaps BEGIN SELECT RAISE(IGNORE); END");
            SwapDb.Swap s=new SwapDb.Swap();s.hash="AB";s.role="RESPONDER";s.status=SwapDb.ST_LOCKED;
            try {db.upsertSwap(s);fail("Failed reservation must stop the caller");}catch(SQLiteException expected){}
            assertNull(db.getSwap(s.hash));sql.execSQL("DROP TRIGGER fixture_ignore");db.upsertSwap(s);assertNotNull(db.getSwap(s.hash));
        } finally {helper.close();context.deleteDatabase("atomix.db");}
    }
}
