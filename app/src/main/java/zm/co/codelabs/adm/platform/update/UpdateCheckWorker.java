package zm.co.codelabs.adm.platform.update;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import java.util.concurrent.TimeUnit;
import zm.co.codelabs.adm.App;
import zm.co.codelabs.adm.BuildConfig;
import zm.co.codelabs.adm.R;
import zm.co.codelabs.adm.ui.update.UpdateActivity;

/** Periodically asks GitHub for the latest release and notifies once per newer version. */
public final class UpdateCheckWorker extends Worker {
    public static final String PREF_ENABLED = "update_notifications";
    private static final String PREF_NOTIFIED_VERSION = "update_notified_version";
    private static final String WORK_NAME = "update-check";
    private static final String CHANNEL = "app_updates";
    private static final int NOTIFICATION_ID = 7_000;

    public UpdateCheckWorker(@NonNull Context context, @NonNull WorkerParameters params) { super(context, params); }

    /** Schedules (or cancels) the twice-daily background check according to the user's setting. */
    public static void schedule(Context context) {
        WorkManager work = WorkManager.getInstance(context);
        if (!prefs(context).getBoolean(PREF_ENABLED, true)) { work.cancelUniqueWork(WORK_NAME); return; }
        Constraints constraints = new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();
        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(UpdateCheckWorker.class, 12, TimeUnit.HOURS)
                .setConstraints(constraints).setInitialDelay(15, TimeUnit.MINUTES).build();
        work.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request);
    }

    @NonNull @Override public Result doWork() {
        Context context = getApplicationContext();
        SharedPreferences prefs = prefs(context);
        if (!prefs.getBoolean(PREF_ENABLED, true)) return Result.success();
        try {
            UpdateInfo latest = new GitHubUpdateClient(context).latest();
            String lastNotified = prefs.getString(PREF_NOTIFIED_VERSION, null);
            if (!shouldNotify(latest.version, BuildConfig.VERSION_NAME, lastNotified)) return Result.success();
            if (notify(context, latest.version)) prefs.edit().putString(PREF_NOTIFIED_VERSION, latest.version).apply();
            return Result.success();
        } catch (Exception e) {
            ((App) context).logs().error("Updater/Background check", e);
            return Result.retry();
        }
    }

    /** Notify only for releases newer than the installed build that the user hasn't been told about. */
    static boolean shouldNotify(String latest, String installed, String lastNotified) {
        SemanticVersion candidate = SemanticVersion.parse(latest);
        if (candidate.compareTo(SemanticVersion.parse(installed)) <= 0) return false;
        if (lastNotified == null) return true;
        try { return candidate.compareTo(SemanticVersion.parse(lastNotified)) > 0; }
        catch (IllegalArgumentException e) { return true; }
    }

    /** Removes a pending update notification, e.g. once the user opens the updater. */
    public static void dismiss(Context context) { NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID); }

    private static boolean notify(Context context, String version) {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false;
        NotificationChannel channel = new NotificationChannel(CHANNEL, context.getString(R.string.notification_channel_updates), NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription(context.getString(R.string.notification_channel_updates_description));
        context.getSystemService(NotificationManager.class).createNotificationChannel(channel);
        Intent open = new Intent(context, UpdateActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent content = PendingIntent.getActivity(context, NOTIFICATION_ID, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_download)
                .setContentTitle(context.getString(R.string.update_notification_title, version))
                .setContentText(context.getString(R.string.update_notification_text))
                .setContentIntent(content).setAutoCancel(true).setCategory(NotificationCompat.CATEGORY_RECOMMENDATION);
        try { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build()); return true; }
        catch (SecurityException e) { return false; }
    }

    private static SharedPreferences prefs(Context context) { return context.getSharedPreferences("settings", Context.MODE_PRIVATE); }
}
