package zm.co.codelabs.adm.platform.update;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;
import android.widget.Toast;
import zm.co.codelabs.adm.App;
import zm.co.codelabs.adm.R;

public final class UpdateInstallReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirmation = Build.VERSION.SDK_INT >= 33
                    ? intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class)
                    : intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirmation != null) context.startActivity(confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return;
        }
        if (status == PackageInstaller.STATUS_SUCCESS) return;
        String detail = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        ((App) context.getApplicationContext()).logs().warning("Updater/Installer", "Installation failed: " + (detail == null ? status : detail));
        Toast.makeText(context, R.string.update_install_failed, Toast.LENGTH_LONG).show();
    }
}
