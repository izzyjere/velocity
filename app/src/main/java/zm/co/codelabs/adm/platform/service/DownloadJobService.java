package zm.co.codelabs.adm.platform.service;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.job.JobParameters;
import android.app.job.JobService;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import zm.co.codelabs.adm.App;
import zm.co.codelabs.adm.data.db.entity.DownloadEntity;
import zm.co.codelabs.adm.engine.DownloadCoordinator;
import zm.co.codelabs.adm.engine.model.DownloadState;
import zm.co.codelabs.adm.platform.notification.DownloadNotifications;

public final class DownloadJobService extends JobService implements DownloadCoordinator.Observer {
    private final Map<Long, JobParameters> jobs = new ConcurrentHashMap<>();
    private final Map<Long, Long> lastNotifications = new ConcurrentHashMap<>();
    private final ExecutorService database = Executors.newSingleThreadExecutor(r -> new Thread(r, "download-job-state"));
    private final Handler main = new Handler(Looper.getMainLooper());
    @Override public void onCreate() { super.onCreate(); ((App) getApplication()).coordinator().addObserver(this); }
    @Override public boolean onStartJob(JobParameters params) {
        long id = downloadId(params); if (id < 0) return false;
        App app = (App) getApplication();
        DownloadNotifications.createChannel(this); Notification notification = DownloadNotifications.progress(this, id, "Download", 0, -1, 0, false);
        if (Build.VERSION.SDK_INT >= 34) setNotification(params, DownloadService.notificationId(id), notification, JOB_END_NOTIFICATION_POLICY_REMOVE);
        jobs.put(id, params);
        database.execute(() -> {
            DownloadEntity item = app.repository().get(id);
            if (jobs.get(id) != params) return;
            if (item == null || isTerminal(item.state)) main.post(() -> finishWithoutTransfer(id, params));
            else app.coordinator().resume(id);
        });
        return true;
    }
    @Override public boolean onStopJob(JobParameters params) { long id = downloadId(params); jobs.remove(id, params); lastNotifications.remove(id); if (id >= 0) ((App) getApplication()).coordinator().pause(id); return true; }
    @Override public void onProgress(long downloadId, long bytes, long total, double speed) {
        long now = android.os.SystemClock.elapsedRealtime(); JobParameters value = jobs.get(downloadId);
        long previous = lastNotifications.getOrDefault(downloadId, 0L);
        if (value == null || now - previous < 500 || Build.VERSION.SDK_INT < 34) return;
        lastNotifications.put(downloadId, now); DownloadEntity item = ((App) getApplication()).repository().get(downloadId);
        setNotification(value, DownloadService.notificationId(downloadId), DownloadNotifications.progress(this, downloadId, item == null ? "Download" : item.fileName, bytes, total, speed, false), JOB_END_NOTIFICATION_POLICY_REMOVE);
    }
    @Override public void onTerminal(long downloadId, DownloadState state) {
        JobParameters value = jobs.remove(downloadId); lastNotifications.remove(downloadId);
        if (value != null) jobFinished(value, state == DownloadState.FAILED);
        cancelNotification(downloadId);
    }
    @Override public void onDestroy() { ((App) getApplication()).coordinator().removeObserver(this); jobs.clear(); lastNotifications.clear(); database.shutdownNow(); super.onDestroy(); }
    private long downloadId(JobParameters params) { return params.getExtras().getLong(DownloadService.EXTRA_ID, -1); }
    private boolean isTerminal(String state) { return DownloadState.COMPLETED.name().equals(state) || DownloadState.FAILED.name().equals(state) || DownloadState.CANCELED.name().equals(state); }
    private void cancelNotification(long id) { getSystemService(NotificationManager.class).cancel(DownloadService.notificationId(id)); }
    private void finishWithoutTransfer(long id, JobParameters expected) {
        if (!jobs.remove(id, expected)) return;
        lastNotifications.remove(id); jobFinished(expected, false); cancelNotification(id);
    }
}
