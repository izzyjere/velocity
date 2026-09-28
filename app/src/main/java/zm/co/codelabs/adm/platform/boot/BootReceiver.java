package zm.co.codelabs.adm.platform.boot;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) WorkManager.getInstance(context).enqueueUniqueWork(
                "download-recovery", ExistingWorkPolicy.REPLACE, new OneTimeWorkRequest.Builder(RecoveryWorker.class).build());
    }
}
