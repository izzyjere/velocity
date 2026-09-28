package zm.co.codelabs.adm;

import androidx.room.testing.MigrationTestHelper;
import androidx.sqlite.db.SupportSQLiteDatabase;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import zm.co.codelabs.adm.data.db.AppDatabase;

@RunWith(AndroidJUnit4.class)
public class MigrationInstrumentedTest {
    private static final String NAME = "migration-test";
    @Rule public final MigrationTestHelper helper = new MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase.class);
    @Test public void migratesActiveHistoryWithoutDataLoss() throws Exception {
        SupportSQLiteDatabase db = helper.createDatabase(NAME, 1); db.execSQL("INSERT INTO downloads (id,canonical_url,file_name,destination,total_bytes,completed_bytes,state,range_supported,connection_mode,max_connections,priority,queue_position,speed_limit,wifi_only,created_at,updated_at) VALUES (1,'https://example.test/file','file','/tmp/file',100,40,'PAUSED',0,'AUTO',8,0,1,0,0,1,1)"); db.close();
        db = helper.runMigrationsAndValidate(NAME, 2, true, AppDatabase.MIGRATION_1_2); android.database.Cursor cursor = db.query("SELECT completed_bytes,speed_bps,state FROM downloads WHERE id=1"); org.junit.Assert.assertTrue(cursor.moveToFirst()); org.junit.Assert.assertEquals(40, cursor.getLong(0)); org.junit.Assert.assertEquals(0d, cursor.getDouble(1), 0); org.junit.Assert.assertEquals("PAUSED", cursor.getString(2)); cursor.close(); db.close();
    }
}
