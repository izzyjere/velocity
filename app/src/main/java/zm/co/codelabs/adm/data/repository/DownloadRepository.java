package zm.co.codelabs.adm.data.repository;

import androidx.lifecycle.LiveData;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.io.IOException;
import java.util.Map;
import zm.co.codelabs.adm.data.db.AppDatabase;
import zm.co.codelabs.adm.data.db.entity.DownloadEntity;
import zm.co.codelabs.adm.data.db.entity.SegmentEntity;
import zm.co.codelabs.adm.engine.model.ByteRange;
import zm.co.codelabs.adm.engine.model.DownloadState;
import zm.co.codelabs.adm.security.HeaderCipher;

public final class DownloadRepository {
    private final AppDatabase db;
    private final HeaderCipher headerCipher;
    private final ExecutorService writes = Executors.newSingleThreadExecutor(r -> new Thread(r, "download-store"));
    public DownloadRepository(AppDatabase db, HeaderCipher headerCipher) { this.db = db; this.headerCipher = headerCipher; }
    public byte[] encryptHeaders(Map<String, String> headers) throws IOException { return headerCipher.encrypt(headers); }
    public Map<String, String> headers(DownloadEntity entity) throws IOException { return headerCipher.decrypt(entity.encryptedHeaders); }
    public LiveData<List<DownloadEntity>> observeAll() { return db.downloads().observeAll(); }
    public DownloadEntity get(long id) { return db.downloads().get(id); }
    public void update(DownloadEntity entity) { entity.updatedAt = System.currentTimeMillis(); db.downloads().update(entity); }
    public List<SegmentEntity> segments(long id) { return db.segments().forDownload(id); }
    public long create(DownloadEntity entity) { return db.downloads().insert(entity); }
    public synchronized void replaceSegments(long id, List<ByteRange> ranges) {
        db.runInTransaction(() -> {
            db.segments().deleteForDownload(id);
            long now = System.currentTimeMillis(); List<SegmentEntity> records = new ArrayList<>();
            for (ByteRange range : ranges) { SegmentEntity e = new SegmentEntity(); e.downloadId = id; e.startByte = range.start(); e.endByte = range.endInclusive(); e.updatedAt = now; records.add(e); }
            db.segments().insertAll(records);
        });
    }
    public SegmentEntity persistSplit(SegmentEntity source, long splitStart, long originalEnd) {
        SegmentEntity tail = new SegmentEntity(); tail.downloadId = source.downloadId; tail.startByte = splitStart; tail.endByte = originalEnd; tail.updatedAt = System.currentTimeMillis();
        db.runInTransaction(() -> { db.segments().updateEnd(source.id, source.endByte, tail.updatedAt); tail.id = db.segments().insert(tail); }); return tail;
    }
    public boolean transition(long id, DownloadState expected, DownloadState next) {
        if (!expected.canTransitionTo(next)) throw new IllegalStateException("Illegal transition " + expected + " -> " + next);
        return db.downloads().transition(id, expected.name(), next.name(), System.currentTimeMillis()) == 1;
    }
    public void persistProgress(long id, List<SegmentEntity> segments, double speed) {
        db.runInTransaction(() -> {
            long now = System.currentTimeMillis();
            for (SegmentEntity s : segments) db.segments().updateProgress(s.id, s.completedBytes, s.endByte, s.state, s.retryCount, now);
            db.downloads().updateProgress(id, db.segments().completedBytes(id), speed, now);
        });
    }
    public void persistProgressAsync(long id, List<SegmentEntity> segments, double speed) { writes.execute(() -> persistProgress(id, segments, speed)); }
    public void forcePersistProgress(long id, List<SegmentEntity> segments) { persistProgress(id, segments, 0); }
    public List<DownloadEntity> queued() { return db.downloads().nextQueued(); }
    public void delete(long id) { db.downloads().delete(id); }
    public void moveToTop(long id) { db.downloads().setQueuePosition(id, 0, System.currentTimeMillis()); }
    public void resetForFreshProbe(DownloadEntity item) { db.runInTransaction(() -> { db.segments().deleteForDownload(item.id); item.resolvedUrl = null; item.totalBytes = -1; item.completedBytes = 0; item.etag = null; item.lastModified = null; item.rangeSupported = false; item.protocol = null; item.errorCode = null; item.errorMessage = null; item.state = DownloadState.NEW.name(); update(item); }); }
    public void recoverInterrupted() {
        long now = System.currentTimeMillis();
        db.runInTransaction(() -> { for (DownloadEntity e : db.downloads().interrupted()) db.downloads().setFailure(e.id, DownloadState.PAUSED.name(), null, "Paused after process restart", now); });
    }
    public List<DownloadEntity> interrupted() { return db.downloads().interrupted(); }
    public void setRecoveredState(DownloadEntity item, DownloadState state, String message) { item.state = state.name(); item.errorCode = null; item.errorMessage = message; item.speedBytesPerSecond = 0; if (state == DownloadState.COMPLETED) item.completedAt = System.currentTimeMillis(); update(item); }
    public void close() { writes.shutdown(); }
}
