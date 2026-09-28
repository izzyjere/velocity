package zm.co.codelabs.adm.data.db;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;
import java.util.List;
import zm.co.codelabs.adm.data.db.entity.DownloadEntity;

@Dao
public interface DownloadDao {
    @Insert(onConflict = OnConflictStrategy.ABORT) long insert(DownloadEntity entity);
    @Update int update(DownloadEntity entity);
    @Query("SELECT * FROM downloads WHERE id = :id") DownloadEntity get(long id);
    @Query("SELECT * FROM downloads ORDER BY CASE state WHEN 'RUNNING' THEN 0 WHEN 'QUEUED' THEN 1 WHEN 'PAUSED' THEN 2 ELSE 3 END, queue_position, created_at DESC") LiveData<List<DownloadEntity>> observeAll();
    @Query("SELECT * FROM downloads WHERE state IN ('QUEUED','RETRY_WAIT') ORDER BY priority DESC, queue_position ASC, created_at ASC") List<DownloadEntity> nextQueued();
    @Query("SELECT * FROM downloads WHERE state IN ('RUNNING','PAUSING','VERIFYING')") List<DownloadEntity> interrupted();
    @Query("UPDATE downloads SET state = :next, updated_at = :now WHERE id = :id AND state = :expected") int transition(long id, String expected, String next, long now);
    @Query("UPDATE downloads SET completed_bytes = :bytes, speed_bps = :speed, updated_at = :now WHERE id = :id") int updateProgress(long id, long bytes, double speed, long now);
    @Query("UPDATE downloads SET state = :state, error_code = :code, error_message = :message, speed_bps = 0, updated_at = :now WHERE id = :id") int setFailure(long id, String state, String code, String message, long now);
    @Query("UPDATE downloads SET queue_position = :position, updated_at = :now WHERE id = :id") int setQueuePosition(long id, long position, long now);
    @Query("DELETE FROM downloads WHERE id = :id") int delete(long id);
}
