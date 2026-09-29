package zm.co.codelabs.adm.ui.update;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import zm.co.codelabs.adm.App;
import zm.co.codelabs.adm.BuildConfig;
import zm.co.codelabs.adm.R;
import zm.co.codelabs.adm.databinding.ActivityUpdateBinding;
import zm.co.codelabs.adm.platform.update.ApkVerifier;
import zm.co.codelabs.adm.platform.update.GitHubUpdateClient;
import zm.co.codelabs.adm.platform.update.SemanticVersion;
import zm.co.codelabs.adm.platform.update.UpdateInfo;
import zm.co.codelabs.adm.platform.update.UpdateInstaller;

public final class UpdateActivity extends AppCompatActivity {
    private enum Stage { CHECK, DOWNLOAD, INSTALL }
    private ActivityUpdateBinding binding;
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> new Thread(r, "app-updater"));
    private GitHubUpdateClient client;
    private UpdateInfo update;
    private File verifiedApk;
    private Stage stage = Stage.CHECK;
    private boolean working;
    private final ActivityResultLauncher<Intent> unknownSources = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (verifiedApk != null && getPackageManager().canRequestPackageInstalls()) install();
                else if (binding != null) binding.status.setText(R.string.allow_update_installs);
            });

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        binding = ActivityUpdateBinding.inflate(getLayoutInflater()); setContentView(binding.getRoot());
        client = new GitHubUpdateClient(this);
        ViewCompat.setOnApplyWindowInsetsListener(binding.root, (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(view.getPaddingLeft(), bars.top, view.getPaddingRight(), bars.bottom); return insets;
        });
        binding.toolbar.setNavigationContentDescription(R.string.back);
        binding.toolbar.setNavigationOnClickListener(v -> finish());
        binding.currentVersion.setText(getString(R.string.current_version, BuildConfig.VERSION_NAME));
        binding.action.setOnClickListener(v -> { if (stage == Stage.CHECK) check(); else if (stage == Stage.DOWNLOAD) download(); else install(); });
        binding.viewRelease.setOnClickListener(v -> { if (update != null) startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(update.releaseUrl))); });
        check();
    }

    private void check() {
        if (working) return;
        working = true; stage = Stage.CHECK; binding.action.setEnabled(false); binding.progress.setVisibility(View.VISIBLE);
        binding.progress.setIndeterminate(true); binding.status.setText(R.string.checking_for_updates); binding.releaseNotes.setVisibility(View.GONE); binding.viewRelease.setVisibility(View.GONE);
        io.execute(() -> {
            try {
                UpdateInfo result = client.latest();
                boolean newer = SemanticVersion.parse(result.version).compareTo(SemanticVersion.parse(BuildConfig.VERSION_NAME)) > 0;
                if (!newer) client.clearDownloadedUpdates();
                runOnUiThread(() -> showCheckResult(result, newer));
            } catch (Exception e) { fail("Updater/Check", e, R.string.update_check_failed, Stage.CHECK); }
        });
    }

    private void showCheckResult(UpdateInfo result, boolean newer) {
        if (binding == null) return;
        working = false; update = result; binding.progress.setVisibility(View.GONE); binding.action.setEnabled(true); binding.viewRelease.setVisibility(View.VISIBLE);
        if (newer) {
            stage = Stage.DOWNLOAD; binding.status.setText(getString(R.string.update_available, result.version)); binding.action.setText(R.string.download_update);
            String notes = result.notes == null ? "" : result.notes.trim();
            if (!notes.isEmpty()) { binding.releaseNotes.setText(notes.length() > 4000 ? notes.substring(0, 4000) : notes); binding.releaseNotes.setVisibility(View.VISIBLE); }
        } else {
            stage = Stage.CHECK; binding.status.setText(R.string.up_to_date); binding.action.setText(R.string.check_again);
        }
    }

    private void download() {
        if (working || update == null) return;
        working = true; binding.action.setEnabled(false); binding.progress.setVisibility(View.VISIBLE); binding.progress.setIndeterminate(false); binding.progress.setProgressCompat(0, false);
        binding.status.setText(getString(R.string.downloading_update, 0));
        io.execute(() -> {
            try {
                File apk = client.download(update, (completed, total) -> {
                    int progress = total <= 0 ? 0 : (int) Math.min(1000, completed * 1000d / total);
                    runOnUiThread(() -> { if (binding != null) { binding.progress.setProgressCompat(progress, true); binding.status.setText(getString(R.string.downloading_update, progress / 10)); } });
                });
                runOnUiThread(() -> { if (binding != null) { binding.progress.setIndeterminate(true); binding.status.setText(R.string.verifying_update); } });
                ApkVerifier.verify(this, apk, update.version);
                runOnUiThread(() -> showVerified(apk));
            } catch (Exception e) { fail("Updater/Download", e, R.string.update_download_failed, Stage.DOWNLOAD); }
        });
    }

    private void showVerified(File apk) {
        if (binding == null) return;
        verifiedApk = apk; working = false; stage = Stage.INSTALL; binding.progress.setVisibility(View.GONE);
        binding.status.setText(getString(R.string.update_ready, update.version)); binding.action.setText(R.string.install_update); binding.action.setEnabled(true);
    }

    private void install() {
        if (working || verifiedApk == null || update == null) return;
        if (!getPackageManager().canRequestPackageInstalls()) {
            binding.status.setText(R.string.allow_update_installs);
            unknownSources.launch(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
            return;
        }
        working = true; binding.action.setEnabled(false); binding.status.setText(R.string.opening_installer); binding.progress.setVisibility(View.VISIBLE); binding.progress.setIndeterminate(true);
        io.execute(() -> {
            try {
                UpdateInstaller.install(this, verifiedApk, Uri.parse(update.releaseUrl));
                runOnUiThread(() -> { if (binding != null) { working = false; binding.progress.setVisibility(View.GONE); binding.action.setEnabled(true); } });
            }
            catch (Exception e) { fail("Updater/Installer", e, R.string.update_install_failed, Stage.INSTALL); }
        });
    }

    private void fail(String area, Exception error, int message, Stage retryStage) {
        ((App) getApplication()).logs().error(area, error);
        runOnUiThread(() -> {
            if (binding == null) return;
            working = false; stage = retryStage; binding.progress.setVisibility(View.GONE); binding.status.setText(message); binding.action.setEnabled(true);
            binding.action.setText(retryStage == Stage.CHECK ? R.string.check_again : retryStage == Stage.DOWNLOAD ? R.string.download_update : R.string.install_update);
        });
    }

    @Override protected void onDestroy() { client.cancel(); io.shutdownNow(); binding = null; super.onDestroy(); }
}
