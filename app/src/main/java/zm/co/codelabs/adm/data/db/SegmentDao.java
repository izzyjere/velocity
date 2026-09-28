package zm.co.codelabs.adm.data.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import java.util.List;
import zm.co.codelabs.adm.data.db.entity.SegmentEntity;

@Dao
public interface SegmentDao {
    @Insert(onConflict = OnConflictStrategy.ABORT) void insertAll(List<SegmentEntity> segments);
    @Query("SELECT * FROM segments WHERE download_id = :downloadId ORDER BY start_byte") List<SegmentEntity> forDownload(long downloadId);
    @Query("UPDATE segments SET completed_bytes = :completed, end_byte = :end, state = :state, retry_count = :retries, updated_at = :now WHERE id = :id") int updateProgress(long id, long completed, long end, String state, int retries, long now);
    @Query("DELETE FROM segments WHERE download_id = :downloadId") void deleteForDownload(long downloadId);
    @Insert(onConflict = OnConflictStrategy.ABORT) long insert(SegmentEntity segment);
    @Query("UPDATE segments SET end_byte = :end, updated_at = :now WHERE id = :id") int updateEnd(long id, long end, long now);
    @Query("SELECT COALESCE(SUM(completed_bytes), 0) FROM segments WHERE download_id = :downloadId") long completedBytes(long downloadId);
}
