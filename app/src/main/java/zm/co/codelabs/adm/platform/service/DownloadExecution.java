package zm.co.codelabs.adm.platform.service;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.PersistableBundle;
import androidx.core.content.ContextCompat;

public final class DownloadExecution {
    private DownloadExecution() { }
    public static boolean start(Context context, long id, long estimatedBytes, boolean wifiOnly) {
        if (Build.VERSION.SDK_INT >= 34) {
            PersistableBundle extras = new PersistableBundle(); extras.putLong(DownloadService.EXTRA_ID, id);
            JobInfo.Builder builder = new JobInfo.Builder(jobId(id), new ComponentName(context, DownloadJobService.class)).setUserInitiated(true)
                    .setRequiredNetworkType(wifiOnly ? JobInfo.NETWORK_TYPE_UNMETERED : JobInfo.NETWORK_TYPE_ANY).setExtras(extras);
            if (estimatedBytes > 0) builder.setEstimatedNetworkBytes(estimatedBytes, 0);
            context.getSystemService(JobScheduler.class).schedule(builder.build());
        } else {
            if (wifiOnly) {
                android.net.ConnectivityManager manager = context.getSystemService(android.net.ConnectivityManager.class);
                android.net.NetworkCapabilities caps = manager.getNetworkCapabilities(manager.getActiveNetwork());
                if (caps == null || !caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) return false;
            }
            Intent intent = new Intent(context, DownloadService.class).setAction(DownloadService.ACTION_START).putExtra(DownloadService.EXTRA_ID, id);
            ContextCompat.startForegroundService(context, intent);
        }
        return true;
    }
    public static int jobId(long id) { return 10_000 + (int) (Math.abs(id) % 1_000_000); }
}
