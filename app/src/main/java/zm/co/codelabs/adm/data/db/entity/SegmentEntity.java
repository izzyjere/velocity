package zm.co.codelabs.adm.data.db.entity;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(tableName = "segments",
        foreignKeys = @ForeignKey(entity = DownloadEntity.class, parentColumns = "id", childColumns = "download_id", onDelete = ForeignKey.CASCADE),
        indices = {@Index("download_id"), @Index(value = {"download_id", "start_byte", "end_byte"}, unique = true)})
public class SegmentEntity {
    @PrimaryKey(autoGenerate = true) public long id;
    @ColumnInfo(name = "download_id") public long downloadId;
    @ColumnInfo(name = "start_byte") public long startByte;
    @ColumnInfo(name = "end_byte") public long endByte;
    @ColumnInfo(name = "completed_bytes") public long completedBytes;
    @NonNull public String state = "PENDING";
    @ColumnInfo(name = "retry_count") public int retryCount;
    @ColumnInfo(name = "updated_at") public long updatedAt;
    public long length() { return endByte - startByte + 1; }
}
