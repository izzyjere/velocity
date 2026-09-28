package zm.co.codelabs.adm.data.db.entity;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(tableName = "downloads", indices = {@Index(value = {"canonical_url", "destination"})})
public class DownloadEntity {
    @PrimaryKey(autoGenerate = true) public long id;
    @NonNull @ColumnInfo(name = "canonical_url") public String canonicalUrl = "";
    @ColumnInfo(name = "resolved_url") public String resolvedUrl;
    @NonNull @ColumnInfo(name = "file_name") public String fileName = "download";
    @NonNull public String destination = "";
    @ColumnInfo(name = "mime_type") public String mimeType;
    @ColumnInfo(name = "total_bytes") public long totalBytes = -1;
    @ColumnInfo(name = "completed_bytes") public long completedBytes;
    @ColumnInfo(name = "speed_bps") public double speedBytesPerSecond;
    @NonNull public String state = "NEW";
    @ColumnInfo(name = "error_code") public String errorCode;
    @ColumnInfo(name = "error_message") public String errorMessage;
    public String etag;
    @ColumnInfo(name = "range_supported") public boolean rangeSupported;
    public String protocol;
    @ColumnInfo(name = "last_modified") public String lastModified;
    @ColumnInfo(name = "checksum_type") public String checksumType;
    @ColumnInfo(name = "checksum_value") public String checksumValue;
    @ColumnInfo(name = "encrypted_headers", typeAffinity = ColumnInfo.BLOB) public byte[] encryptedHeaders;
    @ColumnInfo(name = "connection_mode") @NonNull public String connectionMode = "AUTO";
    @ColumnInfo(name = "max_connections") public int maxConnections = 16;
    public int priority;
    @ColumnInfo(name = "queue_position") public long queuePosition;
    @ColumnInfo(name = "speed_limit") public long speedLimit;
    @ColumnInfo(name = "wifi_only") public boolean wifiOnly;
    @ColumnInfo(name = "created_at") public long createdAt;
    @ColumnInfo(name = "updated_at") public long updatedAt;
    @ColumnInfo(name = "started_at") public Long startedAt;
    @ColumnInfo(name = "completed_at") public Long completedAt;
}
