package zm.co.codelabs.adm.platform.notification;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.service.notification.StatusBarNotification;
import androidx.core.app.NotificationCompat;
import zm.co.codelabs.adm.R;
import zm.co.codelabs.adm.engine.ProgressMath;
import zm.co.codelabs.adm.platform.service.DownloadService;
import zm.co.codelabs.adm.ui.MainActivity;

public final class DownloadNotifications {
    public static final String CHANNEL = "active_downloads";

    private DownloadNotifications() {
    }

    public static void createChannel(Context context) {
        NotificationChannel channel = new NotificationChannel(CHANNEL, context.getString(R.string.notification_channel_downloads), NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(context.getString(R.string.notification_channel_description));
        channel.setShowBadge(false);
        context.getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    public static void cancelStale(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        for (StatusBarNotification active : manager.getActiveNotifications()) {
            if (CHANNEL.equals(active.getNotification().getChannelId()))
                manager.cancel(active.getTag(), active.getId());
        }
    }

    public static Notification progress(Context context, long id, String name, long bytes, long total, double speed, boolean paused) {
        Intent open = new Intent(context, MainActivity.class).putExtra("download_id", id);
        PendingIntent content = PendingIntent.getActivity(context, (int) id, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_download)
                .setContentTitle(name == null ? context.getString(R.string.app_name) : name)
                .setContentText(paused ? "Paused" : humanSpeed(speed)).setOnlyAlertOnce(true).setOngoing(!paused).setContentIntent(content)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS).setGroup("downloads");
        if (total > 0)
            builder.setProgress(1000, ProgressMath.permille(bytes, total, false), false).setSubText(humanBytes(bytes) + " / " + humanBytes(total));
        else builder.setProgress(0, 0, !paused).setSubText(humanBytes(bytes));
        String action = paused ? DownloadService.ACTION_RESUME : DownloadService.ACTION_PAUSE;
        builder.addAction(0, paused ? "Resume" : "Pause", serviceAction(context, id, action, paused ? 2 : 1));
        builder.addAction(0, "Cancel", serviceAction(context, id, DownloadService.ACTION_CANCEL, 3));
        return builder.build();
    }

    private static PendingIntent serviceAction(Context context, long id, String action, int suffix) {
        Intent intent = new Intent(context, DownloadService.class).setAction(action).putExtra(DownloadService.EXTRA_ID, id);
        return PendingIntent.getService(context, (int) (id * 10 + suffix), intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    public static String humanSpeed(double value) {
        return value <= 0 ? "Starting…" : humanBytes((long) value) + "/s";
    }

    public static String humanBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        String[] units = {"KB", "MB", "GB", "TB"};
        double v = bytes;
        int i = -1;
        do {
            v /= 1024;
            i++;
        } while (v >= 1024 && i < units.length - 1);
        return String.format(java.util.Locale.getDefault(), v >= 100 ? "%.0f %s" : "%.1f %s", v, units[i]);
    }
}
