package zm.co.codelabs.adm.engine;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ConcurrentLinkedQueue;
import zm.co.codelabs.adm.data.db.entity.DownloadEntity;
import zm.co.codelabs.adm.data.repository.DownloadRepository;
import zm.co.codelabs.adm.engine.model.DownloadState;
import zm.co.codelabs.adm.transport.TransportClient;
import zm.co.codelabs.adm.storage.StorageCapacity;

public final class DownloadCoordinator implements DownloadJob.Listener {
    public interface Observer extends DownloadJob.Listener { }
    private final DownloadRepository repository;
    private final TransportClient transport;
    private final ExecutorService commands = Executors.newSingleThreadExecutor(r -> new Thread(r, "download-coordinator"));
    private final ExecutorService jobs = Executors.newFixedThreadPool(3, r -> new Thread(r, "download-job"));
    private final ExecutorService segments = Executors.newFixedThreadPool(16, r -> new Thread(r, "download-segment"));
    private final Map<Long, DownloadJob> active = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<Long> pendingStarts = new ConcurrentLinkedQueue<>();
    private final TokenBucket globalLimiter = new TokenBucket(0);
    private final StorageCapacity storageCapacity;
    private final CopyOnWriteArraySet<Observer> observers = new CopyOnWriteArraySet<>();
    public DownloadCoordinator(DownloadRepository repository, TransportClient transport, StorageCapacity storageCapacity) { this.repository = repository; this.transport = transport; this.storageCapacity = storageCapacity; }
    public void addObserver(Observer observer) { observers.add(observer); }
    public void removeObserver(Observer observer) { observers.remove(observer); }
    public void setGlobalSpeedLimit(long bytesPerSecond) { globalLimiter.setRate(bytesPerSecond); }
    public void recover() { commands.execute(() -> { IntegrityVerifier verifier = new IntegrityVerifier(); for (DownloadEntity item : repository.interrupted()) { boolean completed = false; if (DownloadState.VERIFYING.name().equals(item.state)) { java.io.File file = new java.io.File(item.destination); try { completed = file.isFile() && verifier.sizeMatches(file, item.totalBytes) && (item.checksumType == null || item.checksumValue == null || verifier.checksumMatches(file, item.checksumType, item.checksumValue)); } catch (java.io.IOException ignored) { } } repository.setRecoveredState(item, completed ? DownloadState.COMPLETED : DownloadState.PAUSED, completed ? null : "Paused after process restart"); } drainQueue(); }); }
    public void start(long id) { commands.execute(() -> startInternal(id)); }
    public void resume(long id) { start(id); }
    public void pause(long id) { commands.execute(() -> { DownloadJob job = active.get(id); if (job != null) job.pause(); }); }
    public void cancel(long id) { commands.execute(() -> { DownloadJob job = active.get(id); if (job != null) job.cancel(); else cancelIdle(id); }); }
    public void retry(long id) { commands.execute(() -> { DownloadEntity item = repository.get(id); if (item != null && DownloadState.FAILED.name().equals(item.state)) { if (item.resolvedUrl == null || "RESOURCE_CHANGED".equals(item.errorCode) || "RANGE_UNSUPPORTED".equals(item.errorCode)) { new java.io.File(item.destination + ".part").delete(); repository.resetForFreshProbe(item); } else repository.transition(id, DownloadState.FAILED, DownloadState.QUEUED); startInternal(id); } }); }
    public void onNetworkChanged(boolean connected, boolean unmetered) { commands.execute(() -> { if (!connected) active.values().forEach(DownloadJob::pause); else if (!unmetered) active.forEach((id, job) -> { DownloadEntity item = repository.get(id); if (item != null && item.wifiOnly) job.pause(); }); }); }
    private void startInternal(long id) {
        if (active.containsKey(id)) return;
        if (active.size() >= 3) { if (!pendingStarts.contains(id)) pendingStarts.offer(id); return; }
        DownloadEntity item = repository.get(id); if (item == null) return;
        DownloadState state = DownloadState.valueOf(item.state);
        if (!(state == DownloadState.NEW || state == DownloadState.QUEUED || state == DownloadState.PAUSED)) return;
        DownloadJob job = new DownloadJob(id, repository, transport, segments, globalLimiter, storageCapacity, this); active.put(id, job); jobs.submit(job);
    }
    private void cancelIdle(long id) { DownloadEntity item = repository.get(id); if (item == null) return; DownloadState state = DownloadState.valueOf(item.state); if (state.canTransitionTo(DownloadState.CANCELED)) repository.transition(id, state, DownloadState.CANCELED); }
    private void drainQueue() { while (active.size() < 3 && !pendingStarts.isEmpty()) { Long id = pendingStarts.poll(); if (id != null) startInternal(id); } if (active.size() >= 3) return; for (DownloadEntity item : repository.queued()) { if (active.size() >= 3) break; startInternal(item.id); } }
    @Override public void onProgress(long id, long bytes, long total, double bytesPerSecond) { observers.forEach(value -> value.onProgress(id, bytes, total, bytesPerSecond)); }
    @Override public void onTerminal(long id, DownloadState state) { commands.execute(() -> { active.remove(id); observers.forEach(value -> value.onTerminal(id, state)); drainQueue(); }); }
    public void shutdown() { active.values().forEach(DownloadJob::pause); commands.shutdown(); jobs.shutdown(); segments.shutdown(); try { transport.close(); } catch (Exception ignored) { } }
}
