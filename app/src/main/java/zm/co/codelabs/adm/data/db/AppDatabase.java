package zm.co.codelabs.adm.data.db;

import android.content.Context;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;
import androidx.annotation.NonNull;
import zm.co.codelabs.adm.data.db.entity.DownloadEntity;
import zm.co.codelabs.adm.data.db.entity.SegmentEntity;

@Database(entities = {DownloadEntity.class, SegmentEntity.class}, version = 2, exportSchema = true)
public abstract class AppDatabase extends RoomDatabase {
    public static final Migration MIGRATION_1_2 = new Migration(1, 2) { @Override public void migrate(@NonNull SupportSQLiteDatabase db) { db.execSQL("ALTER TABLE downloads ADD COLUMN speed_bps REAL NOT NULL DEFAULT 0"); } };
    public abstract DownloadDao downloads();
    public abstract SegmentDao segments();
    private static volatile AppDatabase INSTANCE;
    public static AppDatabase get(Context context) {
        if (INSTANCE == null) synchronized (AppDatabase.class) {
            if (INSTANCE == null) INSTANCE = Room.databaseBuilder(context.getApplicationContext(), AppDatabase.class, "velocity.db")
                    .addMigrations(MIGRATION_1_2).setJournalMode(JournalMode.WRITE_AHEAD_LOGGING).build();
        }
        return INSTANCE;
    }
}
