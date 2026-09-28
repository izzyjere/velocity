package zm.co.codelabs.adm.engine;

import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Phaser;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import zm.co.codelabs.adm.data.db.entity.DownloadEntity;
import zm.co.codelabs.adm.data.db.entity.SegmentEntity;
import zm.co.codelabs.adm.data.repository.DownloadRepository;
import zm.co.codelabs.adm.engine.model.ByteRange;
import zm.co.codelabs.adm.engine.model.DownloadRequest;
import zm.co.codelabs.adm.engine.model.DownloadState;
import zm.co.codelabs.adm.engine.model.ErrorCode;
import zm.co.codelabs.adm.engine.model.ProbeResult;
import zm.co.codelabs.adm.engine.model.TransferRequest;
import zm.co.codelabs.adm.storage.PositionedFileWriter;
import zm.co.codelabs.adm.storage.StorageCapacity;
import zm.co.codelabs.adm.transport.TransportCall;
import zm.co.codelabs.adm.transport.TransportClient;
import zm.co.codelabs.adm.transport.HttpStatusException;

public final class DownloadJob implements Runnable {
    public interface Listener {
        void onProgress(long id, long bytes, long total, double bytesPerSecond);
        void onTerminal(long id, DownloadState state);
    }
    private final long id;
    private final DownloadRepository repository;
    private final TransportClient transport;
    private final ExecutorService workers;
    private final RetryPolicy retryPolicy = new RetryPolicy(5);
    private final IntegrityVerifier verifier = new IntegrityVerifier();
    private final AtomicBoolean stop = new AtomicBoolean();
    private final AtomicBoolean abort = new AtomicBoolean();
    private final AtomicBoolean cancel = new AtomicBoolean();
    private final Set<TransportCall> calls = ConcurrentHashMap.newKeySet();
    private final Listener listener;
    private final TokenBucket globalLimiter;
    private final StorageCapacity storageCapacity;

    public DownloadJob(long id, DownloadRepository repository, TransportClient transport, ExecutorService workers, TokenBucket globalLimiter, StorageCapacity storageCapacity, Listener listener) {
        this.id = id;
        this.repository = repository;
        this.transport = transport;
        this.workers = workers;
        this.globalLimiter = globalLimiter;
        this.storageCapacity = storageCapacity;
        this.listener = listener;
    }
    public void pause() { stop.set(true); calls.forEach(TransportCall::cancel); }
    public void cancel() { cancel.set(true); stop.set(true); calls.forEach(TransportCall::cancel); }

    @Override public void run() {
        DownloadEntity record = repository.get(id);
        if (record == null) return;
        List<SegmentEntity> segments = new ArrayList<>();
        try {
            record = prepare(record);
            if (!repository.transition(id, DownloadState.QUEUED, DownloadState.RUNNING)) return;
            segments = new CopyOnWriteArrayList<>(repository.segments(id));
            File finalFile = new File(record.destination).getCanonicalFile();
            File partial = new File(finalFile.getPath() + ".part");
            if (record.totalBytes > 0) storageCapacity.requireSpace(partial, Math.max(0, record.totalBytes - partial.length()));
            AtomicLong aggregate = new AtomicLong(segments.stream().mapToLong(s -> s.completedBytes).sum());
            SpeedEstimator speed = new SpeedEstimator();
            AtomicLong lastPublish = new AtomicLong(); AtomicLong lastPersist = new AtomicLong();
            TokenBucket limiter = new TokenBucket(record.speedLimit);
            try (PositionedFileWriter writer = new PositionedFileWriter(partial, Math.max(0, record.totalBytes))) {
                if (!record.rangeSupported) {
                    writer.truncate(0); aggregate.set(0);
                    for (SegmentEntity segment : segments) { segment.completedBytes = 0; segment.state = "PENDING"; }
                }
                ExecutorCompletionService<Void> completion = new ExecutorCompletionService<>(workers);
                List<SegmentEntity> jobSegments = segments;
                AdaptiveParallelism adaptive = new AdaptiveParallelism(record.totalBytes, record.maxConnections);
                Phaser runningTasks = new Phaser(1);
                Set<Long> activeSegments = ConcurrentHashMap.newKeySet();
                int submitted = 0, active = 0, completed = 0, desired = adaptive.current(); long sampleBytes = aggregate.get(), sampleNanos = System.nanoTime();
                try {
                    while (completed < jobSegments.size()) {
                        while (submitted < jobSegments.size() && active < desired) {
                            SegmentEntity segment = jobSegments.get(submitted++); DownloadEntity snapshot = record;
                            runningTasks.register();
                            completion.submit(() -> { activeSegments.add(segment.id); try { transfer(snapshot, segment, writer, limiter, aggregate, speed, lastPublish, lastPersist, jobSegments); return null; } finally { activeSegments.remove(segment.id); runningTasks.arriveAndDeregister(); } }); active++;
                        }
                        Future<Void> future = completion.take(); active--; future.get(); completed++;
                        long now = System.nanoTime(), bytesNow = aggregate.get(); double measured = (bytesNow - sampleBytes) * 1_000_000_000d / Math.max(1, now - sampleNanos);
                        desired = adaptive.sample(measured, 0, false, 0); sampleBytes = bytesNow; sampleNanos = now;
                        if (submitted >= jobSegments.size() && active < desired) splitLargestActive(jobSegments, activeSegments);
                    }
                } catch (ExecutionException | InterruptedException e) {
                    abort.set(true); calls.forEach(TransportCall::cancel); runningTasks.arriveAndAwaitAdvance(); throw e;
                }
                runningTasks.arriveAndDeregister();
                writer.force(false);
                repository.forcePersistProgress(id, segments);
            }
            if (stop.get()) { settleStopped(segments); return; }
            if (!repository.transition(id, DownloadState.RUNNING, DownloadState.VERIFYING)) return;
            record = repository.get(id);
            if (!verifier.sizeMatches(partial, record.totalBytes)) throw new IOException("Downloaded size does not match server metadata");
            if (record.checksumType != null && record.checksumValue != null
                    && !verifier.checksumMatches(partial, record.checksumType, record.checksumValue)) throw new IOException("Checksum mismatch");
            moveAtomically(partial, finalFile);
            record.completedBytes = finalFile.length(); record.speedBytesPerSecond = 0;
            record.completedAt = System.currentTimeMillis();
            repository.update(record);
            repository.transition(id, DownloadState.VERIFYING, DownloadState.COMPLETED);
            listener.onTerminal(id, DownloadState.COMPLETED);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); stop.set(true); settleStopped(segments);
        } catch (Exception e) {
            if (stop.get()) { settleStopped(segments); return; }
            Throwable root = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
            ErrorCode code = root.getMessage() != null && root.getMessage().toLowerCase(java.util.Locale.ROOT).contains("checksum")
                    ? ErrorCode.CHECKSUM_MISMATCH : root instanceof HttpStatusException status ? RetryPolicy.classifyHttp(status.statusCode()) : RetryPolicy.classify(root);
            fail(code, safeMessage(root));
            listener.onTerminal(id, DownloadState.FAILED);
        }
    }

    private DownloadEntity prepare(DownloadEntity record) throws IOException {
        DownloadState state = DownloadState.valueOf(record.state);
        if (state == DownloadState.NEW) {
            if (!repository.transition(id, DownloadState.NEW, DownloadState.PROBING)) throw new IOException("Download changed before probe");
            ProbeResult probe = transport.probe(new DownloadRequest(record.canonicalUrl, repository.headers(record)));
            ErrorCode http = RetryPolicy.classifyHttp(probe.statusCode());
            if (http != ErrorCode.NONE) throw new HttpStatusException(probe.statusCode(), null);
            record = repository.get(id);
            record.resolvedUrl = probe.resolvedUrl(); record.totalBytes = probe.contentLength(); record.mimeType = probe.mimeType();
            record.etag = probe.etag(); record.lastModified = probe.lastModified(); record.rangeSupported = probe.safeForMultipart(); record.protocol = probe.protocol();
            repository.update(record);
            List<ByteRange> plan = new DownloadPlanner().plan(probe, record.maxConnections);
            if (plan.isEmpty()) plan = List.of(new ByteRange(0, Long.MAX_VALUE - 1));
            repository.replaceSegments(id, plan);
            if (!repository.transition(id, DownloadState.PROBING, DownloadState.QUEUED)) throw new IOException("Download changed after probe");
            record = repository.get(id);
        } else if (state == DownloadState.PAUSED) {
            if (!repository.transition(id, DownloadState.PAUSED, DownloadState.QUEUED)) throw new IOException("Download cannot resume");
            record = repository.get(id);
        }
        return record;
    }

    private void transfer(DownloadEntity record, SegmentEntity segment, PositionedFileWriter writer, TokenBucket limiter,
                          AtomicLong aggregate, SpeedEstimator speed, AtomicLong lastPublish, AtomicLong lastPersist, List<SegmentEntity> all) throws Exception {
        int attempts = segment.retryCount;
        while (!shouldStop()) {
            long remaining = segment.endByte == Long.MAX_VALUE - 1 ? Long.MAX_VALUE : segment.length() - segment.completedBytes;
            if (remaining <= 0) { segment.state = "COMPLETE"; return; }
            long absolute = segment.startByte + segment.completedBytes;
            ByteRange range = record.rangeSupported ? new ByteRange(absolute, segment.endByte) : null;
            if (!record.rangeSupported && segment.completedBytes > 0) {
                aggregate.addAndGet(-segment.completedBytes); segment.completedBytes = 0; absolute = segment.startByte;
            }
            String validator = record.etag != null && !record.etag.startsWith("W/") ? record.etag : record.lastModified;
            try (TransportCall call = transport.open(new TransferRequest(
                    record.resolvedUrl != null ? record.resolvedUrl : record.canonicalUrl, repository.headers(record), range, validator))) {
                calls.add(call); segment.state = "RUNNING";
                if (!record.rangeSupported && (call.statusCode() < 200 || call.statusCode() >= 300)) throw new HttpStatusException(call.statusCode(), firstHeader(call.headers(), "Retry-After"));
                byte[] buffer = new byte[128 * 1024]; InputStream in = call.body(); int n;
                while (!shouldStop() && (n = in.read(buffer, 0, remaining == Long.MAX_VALUE ? buffer.length : (int) Math.min(buffer.length, remaining))) != -1) {
                    int allowed;
                    synchronized (segment) { long available = segment.endByte == Long.MAX_VALUE - 1 ? n : Math.max(0, segment.endByte - absolute + 1); allowed = (int) Math.min(n, available); if (allowed > 0) { globalLimiter.acquire(allowed); limiter.acquire(allowed); writer.write(absolute, buffer, 0, allowed); absolute += allowed; segment.completedBytes += allowed; aggregate.addAndGet(allowed); } }
                    if (allowed == 0) break;
                    if (remaining != Long.MAX_VALUE) remaining = Math.max(0, segment.endByte - absolute + 1);
                    long now = System.nanoTime(); double rate = speed.update(aggregate.get(), now);
                    long previousPublish = lastPublish.get(); if (now - previousPublish >= 200_000_000L && lastPublish.compareAndSet(previousPublish, now)) listener.onProgress(id, aggregate.get(), record.totalBytes, rate);
                    long previousPersist = lastPersist.get(); if (now - previousPersist >= 1_000_000_000L && lastPersist.compareAndSet(previousPersist, now)) repository.persistProgressAsync(id, new ArrayList<>(all), rate);
                    if (remaining == 0) break;
                }
                calls.remove(call);
                if (shouldStop()) return;
                if (remaining != Long.MAX_VALUE && remaining != 0) throw new EOFException("Response ended before the segment was complete");
                if (remaining == Long.MAX_VALUE) {
                    segment.endByte = segment.startByte + segment.completedBytes - 1;
                    DownloadEntity latest = repository.get(id); latest.totalBytes = segment.completedBytes; repository.update(latest);
                }
                segment.state = "COMPLETE"; return;
            } catch (IOException e) {
                attempts++; segment.retryCount = attempts; segment.state = "RETRY_WAIT";
                ErrorCode code = e instanceof HttpStatusException status ? RetryPolicy.classifyHttp(status.statusCode()) : RetryPolicy.classify(e);
                if (shouldStop() || !retryPolicy.shouldRetry(code, attempts)) throw e;
                Thread.sleep(retryPolicy.delayMillis(attempts, e instanceof HttpStatusException status ? status.retryAfterMillis() : -1));
            }
        }
    }
    private boolean shouldStop() { return stop.get() || abort.get(); }
    private void splitLargestActive(List<SegmentEntity> segments, Set<Long> activeIds) {
        SegmentEntity largest = null; long largestRemaining = 0;
        for (SegmentEntity candidate : segments) if (activeIds.contains(candidate.id)) { long remaining = candidate.length() - candidate.completedBytes; if (remaining > largestRemaining) { largest = candidate; largestRemaining = remaining; } }
        if (largest == null) return;
        synchronized (largest) {
            List<ByteRange> split = SegmentScheduler.splitRemaining(new ByteRange(largest.startByte, largest.endByte), largest.completedBytes);
            if (split.size() != 2) return; long originalEnd = largest.endByte; largest.endByte = split.get(0).endInclusive();
            try { segments.add(repository.persistSplit(largest, split.get(1).start(), originalEnd)); } catch (RuntimeException e) { largest.endByte = originalEnd; }
        }
    }

    private void settleStopped(List<SegmentEntity> segments) {
        try { if (!segments.isEmpty()) repository.forcePersistProgress(id, segments); } catch (RuntimeException ignored) { }
        DownloadEntity latest = repository.get(id); if (latest == null) return;
        DownloadState state = DownloadState.valueOf(latest.state);
        if (cancel.get()) {
            if (state.canTransitionTo(DownloadState.CANCELED)) repository.transition(id, state, DownloadState.CANCELED);
            listener.onTerminal(id, DownloadState.CANCELED);
        } else {
            if (state == DownloadState.RUNNING) repository.transition(id, DownloadState.RUNNING, DownloadState.PAUSING);
            latest = repository.get(id);
            if (latest != null && DownloadState.valueOf(latest.state) == DownloadState.PAUSING) repository.transition(id, DownloadState.PAUSING, DownloadState.PAUSED);
            listener.onTerminal(id, DownloadState.PAUSED);
        }
    }
    private void fail(ErrorCode code, String message) {
        DownloadEntity record = repository.get(id); if (record == null) return;
        record.state = DownloadState.FAILED.name(); record.errorCode = code.name(); record.errorMessage = message; record.speedBytesPerSecond = 0; repository.update(record);
    }
    private static String safeMessage(Throwable e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message.replaceAll("https?://\\S+", "remote resource");
    }
    private static void moveAtomically(File source, File target) throws IOException {
        try { Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException e) { Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING); }
    }
    private static String firstHeader(Map<String, List<String>> headers, String name) { for (Map.Entry<String, List<String>> entry : headers.entrySet()) if (entry.getKey().equalsIgnoreCase(name) && !entry.getValue().isEmpty()) return entry.getValue().get(0); return null; }
}
