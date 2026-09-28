package zm.co.codelabs.adm.platform.boot;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import zm.co.codelabs.adm.App;

public final class RecoveryWorker extends Worker {
    public RecoveryWorker(@NonNull Context context, @NonNull WorkerParameters params) { super(context, params); }
    @NonNull @Override public Result doWork() { ((App) getApplicationContext()).coordinator().recover(); return Result.success(); }
}
