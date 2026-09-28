package zm.co.codelabs.adm.platform.service;

import android.app.Notification;
import android.app.job.JobParameters;
import android.app.job.JobService;
import android.os.Build;
import zm.co.codelabs.adm.App;
import zm.co.codelabs.adm.data.db.entity.DownloadEntity;
import zm.co.codelabs.adm.engine.DownloadCoordinator;
import zm.co.codelabs.adm.engine.model.DownloadState;
import zm.co.codelabs.adm.platform.notification.DownloadNotifications;

public final class DownloadJobService extends JobService implements DownloadCoordinator.Observer {
    private volatile JobParameters parameters;
    private long id = -1;
    private long lastNotification;
    @Override public boolean onStartJob(JobParameters params) {
        parameters = params; id = params.getExtras().getLong(DownloadService.EXTRA_ID, -1); if (id < 0) return false;
        App app = (App) getApplication();
        DownloadNotifications.createChannel(this); Notification notification = DownloadNotifications.progress(this, id, "Download", 0, -1, 0, false);
        if (Build.VERSION.SDK_INT >= 34) setNotification(params, DownloadService.notificationId(id), notification, JOB_END_NOTIFICATION_POLICY_REMOVE);
        app.coordinator().addObserver(this); app.coordinator().resume(id); return true;
    }
    @Override public boolean onStopJob(JobParameters params) { if (id >= 0) ((App) getApplication()).coordinator().pause(id); cleanup(); return true; }
    @Override public void onProgress(long downloadId, long bytes, long total, double speed) { long now = android.os.SystemClock.elapsedRealtime(); JobParameters value = parameters; if (downloadId != id || value == null || now - lastNotification < 500 || Build.VERSION.SDK_INT < 34) return; lastNotification = now; DownloadEntity item = ((App) getApplication()).repository().get(id); setNotification(value, DownloadService.notificationId(id), DownloadNotifications.progress(this, id, item == null ? "Download" : item.fileName, bytes, total, speed, false), JOB_END_NOTIFICATION_POLICY_REMOVE); }
    @Override public void onTerminal(long downloadId, DownloadState state) { if (downloadId != id) return; JobParameters value = parameters; cleanup(); if (value != null) jobFinished(value, state == DownloadState.FAILED); }
    private void cleanup() { ((App) getApplication()).coordinator().removeObserver(this); parameters = null; }
}
