package zm.co.codelabs.adm;

import static org.junit.Assert.*;
import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import zm.co.codelabs.adm.data.db.AppDatabase;
import zm.co.codelabs.adm.data.db.entity.DownloadEntity;
import zm.co.codelabs.adm.data.db.entity.SegmentEntity;

@RunWith(AndroidJUnit4.class)
public class DatabaseInstrumentedTest {
    private AppDatabase db;
    @Before public void open() { db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase.class).allowMainThreadQueries().build(); }
    @After public void close() { db.close(); }
    @Test public void cascadeAndAggregateProgressRemainConsistent() {
        DownloadEntity download = new DownloadEntity(); download.canonicalUrl = "https://example.test/file"; download.fileName = "file"; download.destination = "/tmp/file"; download.createdAt = download.updatedAt = 1;
        long id = db.downloads().insert(download); SegmentEntity first = segment(id, 0, 99, 40), second = segment(id, 100, 199, 60); db.segments().insertAll(List.of(first, second));
        assertEquals(100, db.segments().completedBytes(id)); db.downloads().delete(id); assertTrue(db.segments().forDownload(id).isEmpty());
    }
    private static SegmentEntity segment(long id, long start, long end, long complete) { SegmentEntity value = new SegmentEntity(); value.downloadId = id; value.startByte = start; value.endByte = end; value.completedBytes = complete; value.updatedAt = 1; return value; }
}
