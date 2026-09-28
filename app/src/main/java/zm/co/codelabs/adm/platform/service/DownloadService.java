package zm.co.codelabs.adm.platform.service;

import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import androidx.annotation.Nullable;
import zm.co.codelabs.adm.App;
import zm.co.codelabs.adm.data.db.entity.DownloadEntity;
import zm.co.codelabs.adm.engine.DownloadCoordinator;
import zm.co.codelabs.adm.engine.model.DownloadState;
import zm.co.codelabs.adm.platform.notification.DownloadNotifications;

public final class DownloadService extends Service implements DownloadCoordinator.Observer {
    public static final String ACTION_START = "zm.co.codelabs.adm.START";
    public static final String ACTION_PAUSE = "zm.co.codelabs.adm.PAUSE";
    public static final String ACTION_RESUME = "zm.co.codelabs.adm.RESUME";
    public static final String ACTION_CANCEL = "zm.co.codelabs.adm.CANCEL";
    public static final String EXTRA_ID = "download_id";
    private long currentId = -1;
    private long lastNotification;
    @Override public void onCreate() { super.onCreate(); DownloadNotifications.createChannel(this); ((App) getApplication()).coordinator().addObserver(this); }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY; long id = intent.getLongExtra(EXTRA_ID, -1); if (id < 0) return START_NOT_STICKY;
        currentId = id; App app = (App) getApplication();
        String action = intent.getAction();
        if (ACTION_START.equals(action) || ACTION_RESUME.equals(action)) {
            startForeground(notificationId(id), DownloadNotifications.progress(this, id, "Download", 0, -1, 0, false)); app.coordinator().resume(id);
        } else if (ACTION_PAUSE.equals(action)) app.coordinator().pause(id);
        else if (ACTION_CANCEL.equals(action)) app.coordinator().cancel(id);
        return START_NOT_STICKY;
    }
    @Override public void onProgress(long id, long bytes, long total, double speed) { long now = android.os.SystemClock.elapsedRealtime(); if (id != currentId || now - lastNotification < 500) return; lastNotification = now; DownloadEntity item = ((App) getApplication()).repository().get(id); getSystemService(NotificationManager.class).notify(notificationId(id), DownloadNotifications.progress(this, id, item == null ? "Download" : item.fileName, bytes, total, speed, false)); }
    @Override public void onTerminal(long id, DownloadState state) { if (id != currentId) return; if (state == DownloadState.PAUSED) { DownloadEntity item = ((App) getApplication()).repository().get(id); getSystemService(NotificationManager.class).notify(notificationId(id), DownloadNotifications.progress(this, id, item == null ? "Download" : item.fileName, item == null ? 0 : item.completedBytes, item == null ? -1 : item.totalBytes, 0, true)); stopForeground(STOP_FOREGROUND_DETACH); } else { stopForeground(STOP_FOREGROUND_REMOVE); } stopSelf(); }
    @Override public void onTimeout(int startId, int fgsType) { if (currentId >= 0) ((App) getApplication()).coordinator().pause(currentId); stopSelf(); }
    @Override public void onDestroy() { ((App) getApplication()).coordinator().removeObserver(this); super.onDestroy(); }
    @Nullable @Override public IBinder onBind(Intent intent) { return null; }
    public static int notificationId(long id) { return 2000 + (int) (Math.abs(id) % 1_000_000); }
}
