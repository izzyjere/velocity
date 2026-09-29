package zm.co.codelabs.adm.platform.update;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;

public final class UpdateInstaller {
    public static final String ACTION_STATUS = "zm.co.codelabs.adm.UPDATE_INSTALL_STATUS";
    private UpdateInstaller() { }

    public static void install(Context context, File apk, Uri releaseUri) throws Exception {
        PackageInstaller installer = context.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(context.getPackageName());
        params.setSize(apk.length());
        params.setInstallReason(PackageManager.INSTALL_REASON_USER);
        params.setOriginatingUri(releaseUri);
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED);
        if (Build.VERSION.SDK_INT >= 33) params.setPackageSource(PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE);
        int sessionId = installer.createSession(params);
        try (PackageInstaller.Session session = installer.openSession(sessionId)) {
            try (FileInputStream input = new FileInputStream(apk);
                 OutputStream output = session.openWrite("velocity.apk", 0, apk.length())) {
                byte[] buffer = new byte[128 * 1024]; int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                session.fsync(output);
            }
            Intent status = new Intent(context, UpdateInstallReceiver.class).setAction(ACTION_STATUS);
            PendingIntent pending = PendingIntent.getBroadcast(context, sessionId, status, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
            session.commit(pending.getIntentSender());
        } catch (Exception e) { installer.abandonSession(sessionId); throw e; }
    }
}
