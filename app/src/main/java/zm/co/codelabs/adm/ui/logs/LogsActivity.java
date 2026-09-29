package zm.co.codelabs.adm.ui.logs;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import zm.co.codelabs.adm.App;
import zm.co.codelabs.adm.R;
import zm.co.codelabs.adm.databinding.ActivityLogsBinding;

public final class LogsActivity extends AppCompatActivity {
    private ActivityLogsBinding binding;
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> new Thread(r, "logs-screen"));
    private String displayedLogs = "";

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        binding = ActivityLogsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        ViewCompat.setOnApplyWindowInsetsListener(binding.root, (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(view.getPaddingLeft(), bars.top, view.getPaddingRight(), bars.bottom);
            return insets;
        });
        binding.toolbar.setNavigationContentDescription(R.string.back);
        binding.toolbar.setNavigationOnClickListener(v -> finish());
        binding.refresh.setOnClickListener(v -> refresh());
        binding.copy.setOnClickListener(v -> copy());
        binding.clear.setOnClickListener(v -> confirmClear());
        refresh();
    }

    private void refresh() {
        setLoading(true);
        io.execute(() -> {
            String content = ((App) getApplication()).logs().read();
            runOnUiThread(() -> {
                if (isFinishing() || binding == null) return;
                displayedLogs = content;
                binding.content.setText(content.isBlank() ? getString(R.string.logs_empty) : content);
                binding.copy.setEnabled(!content.isBlank());
                binding.clear.setEnabled(!content.isBlank());
                setLoading(false);
            });
        });
    }

    private void copy() {
        if (displayedLogs.isBlank()) return;
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.logs), displayedLogs));
        Toast.makeText(this, R.string.logs_copied, Toast.LENGTH_SHORT).show();
    }

    private void confirmClear() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.clear_logs_title)
                .setMessage(R.string.clear_logs_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.clear, (dialog, which) -> clearLogs())
                .show();
    }

    private void clearLogs() {
        setLoading(true);
        io.execute(() -> {
            ((App) getApplication()).logs().clear();
            runOnUiThread(() -> {
                if (isFinishing() || binding == null) return;
                Toast.makeText(this, R.string.logs_cleared, Toast.LENGTH_SHORT).show();
                refresh();
            });
        });
    }

    private void setLoading(boolean loading) {
        binding.loading.setVisibility(loading ? View.VISIBLE : View.GONE);
        binding.refresh.setEnabled(!loading);
    }

    @Override protected void onDestroy() {
        binding = null;
        io.shutdownNow();
        super.onDestroy();
    }
}
