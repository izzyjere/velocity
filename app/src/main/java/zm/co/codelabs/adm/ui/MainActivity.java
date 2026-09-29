package zm.co.codelabs.adm.ui;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Map;
import zm.co.codelabs.adm.R;
import zm.co.codelabs.adm.databinding.ActivityMainBinding;
import zm.co.codelabs.adm.ui.add.AddDownloadSheet;
import zm.co.codelabs.adm.ui.browser.BrowserFragment;
import zm.co.codelabs.adm.ui.downloads.DownloadsFragment;
import zm.co.codelabs.adm.ui.files.FilesFragment;
import zm.co.codelabs.adm.ui.more.MoreFragment;
import zm.co.codelabs.adm.ui.queue.QueueFragment;
import zm.co.codelabs.adm.App;
import zm.co.codelabs.adm.platform.notification.DownloadNotifications;

public final class MainActivity extends AppCompatActivity {
    public static final String EXTRA_URLS = "candidate_urls";
    public static final String EXTRA_BROWSER_URL = "browser_url";
    private ActivityMainBinding binding;
    private final ArrayDeque<String> pendingUrls = new ArrayDeque<>();
    private final ActivityResultLauncher<String> notifications = registerForActivityResult(new ActivityResultContracts.RequestPermission(), ignored -> { });
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state); binding = ActivityMainBinding.inflate(getLayoutInflater()); setContentView(binding.getRoot());
        ViewCompat.setOnApplyWindowInsetsListener(binding.getRoot(), (view, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.statusBars());
            view.setPadding(view.getPaddingLeft(), bars.top, view.getPaddingRight(), view.getPaddingBottom());
            return windowInsets;
        });
        binding.navigation.setOnItemSelectedListener(this::select); binding.add.setOnClickListener(v -> showAddDownload("", Map.of()));
        getSupportFragmentManager().setFragmentResultListener(AddDownloadSheet.RESULT, this, (key, bundle) -> showNextSharedUrl());
        if (state == null) { binding.navigation.setSelectedItemId(R.id.nav_downloads); consume(getIntent()); }
        ((App) getApplication()).repository().observeAll().observe(this, items -> { double speed = 0; int active = 0; if (items != null) for (zm.co.codelabs.adm.data.db.entity.DownloadEntity item : items) if ("RUNNING".equals(item.state)) { speed += item.speedBytesPerSecond; active++; } binding.aggregateSpeed.setText(active == 0 ? getString(R.string.adaptive_ready) : getResources().getQuantityString(R.plurals.active_downloads, active, active, DownloadNotifications.humanSpeed(speed))); });
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) notifications.launch(Manifest.permission.POST_NOTIFICATIONS);
    }
    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); consume(intent); }
    @Override protected void onPostCreate(Bundle state) {
        super.onPostCreate(state);
        if (state != null) binding.navigation.post(() -> {
            android.view.MenuItem selected = binding.navigation.getMenu().findItem(binding.navigation.getSelectedItemId());
            if (selected != null) select(selected);
        });
    }
    private void consume(Intent intent) {
        String browserUrl = intent.getStringExtra(EXTRA_BROWSER_URL);
        if (browserUrl != null) { intent.removeExtra(EXTRA_BROWSER_URL); openBrowser(browserUrl); return; }
        ArrayList<String> urls = intent.getStringArrayListExtra(EXTRA_URLS); if (urls != null) pendingUrls.addAll(urls); showNextSharedUrl();
    }
    private void openBrowser(String url) {
        binding.navigation.setSelectedItemId(R.id.nav_browser); binding.toolbar.setTitle(R.string.browser); binding.add.setVisibility(android.view.View.GONE);
        getSupportFragmentManager().beginTransaction().replace(R.id.content, BrowserFragment.newInstance(url)).commit();
    }
    private void showNextSharedUrl() { if (getSupportFragmentManager().findFragmentByTag(AddDownloadSheet.TAG) == null && !pendingUrls.isEmpty()) showAddDownload(pendingUrls.removeFirst(), Map.of()); }
    public void showAddDownload(String url, Map<String, String> headers) { AddDownloadSheet.newInstance(url, headers).show(getSupportFragmentManager(), AddDownloadSheet.TAG); }
    public void setBrowserControlsReveal(boolean visible, Runnable action) {
        android.view.MenuItem item = binding.toolbar.getMenu().findItem(R.id.action_show_browser_controls);
        if (item == null) {
            item = binding.toolbar.getMenu().add(android.view.Menu.NONE, R.id.action_show_browser_controls, android.view.Menu.NONE, R.string.show_browser_controls);
            android.graphics.drawable.Drawable icon = androidx.appcompat.content.res.AppCompatResources.getDrawable(this, R.drawable.ic_expand_more);
            if (icon != null) { icon = androidx.core.graphics.drawable.DrawableCompat.wrap(icon.mutate()); androidx.core.graphics.drawable.DrawableCompat.setTint(icon, com.google.android.material.color.MaterialColors.getColor(binding.toolbar, com.google.android.material.R.attr.colorOnSurface)); }
            item.setIcon(icon); item.setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS);
        }
        item.setVisible(visible); item.setOnMenuItemClickListener(clicked -> { action.run(); return true; });
    }
    private boolean select(android.view.MenuItem item) {
        Fragment fragment; String title;
        int id = item.getItemId();
        if (id == R.id.nav_browser) { fragment = new BrowserFragment(); title = getString(R.string.browser); }
        else if (id == R.id.nav_files) { fragment = new FilesFragment(); title = getString(R.string.files); }
        else if (id == R.id.nav_queue) { fragment = new QueueFragment(); title = getString(R.string.queue); }
        else if (id == R.id.nav_more) { fragment = new MoreFragment(); title = getString(R.string.more); }
        else { fragment = new DownloadsFragment(); title = getString(R.string.downloads); }
        binding.toolbar.setTitle(title);
        if (id != R.id.nav_browser) setBrowserControlsReveal(false, () -> { });
        binding.add.setVisibility(id == R.id.nav_downloads ? android.view.View.VISIBLE : android.view.View.GONE);
        getSupportFragmentManager().beginTransaction().replace(R.id.content, fragment).commit(); return true;
    }
}
