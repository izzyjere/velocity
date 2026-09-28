package zm.co.codelabs.adm.ui.downloads;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.PopupMenu;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import zm.co.codelabs.adm.App;
import zm.co.codelabs.adm.data.db.entity.DownloadEntity;
import zm.co.codelabs.adm.databinding.FragmentDownloadsBinding;
import zm.co.codelabs.adm.platform.service.DownloadExecution;

public final class DownloadsFragment extends Fragment implements DownloadAdapter.Actions {
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> new Thread(r, "download-actions"));
    private FragmentDownloadsBinding binding;
    private DownloadAdapter adapter;
    private List<DownloadEntity> all = List.of();
    private String filter = "ALL";
    private DownloadEntity pendingMove;
    private App application;
    private final ActivityResultLauncher<String> moveFile = registerForActivityResult(new ActivityResultContracts.CreateDocument("application/octet-stream"), uri -> {
        DownloadEntity item = pendingMove; pendingMove = null; if (uri == null || item == null) return;
        android.content.Context context = requireContext().getApplicationContext();
        IO.execute(() -> { try (java.io.InputStream in = new java.io.FileInputStream(item.destination); java.io.OutputStream out = context.getContentResolver().openOutputStream(uri, "w")) { if (out == null) throw new java.io.IOException("Destination is not writable"); byte[] data = new byte[128 * 1024]; int n; while ((n = in.read(data)) != -1) out.write(data, 0, n); } catch (Exception e) { toast("Move failed: " + e.getMessage()); return; } new File(item.destination).delete(); app().repository().delete(item.id); toast("File moved"); });
    });
    @Override public void onAttach(@NonNull android.content.Context context) { super.onAttach(context); application = (App) context.getApplicationContext(); }
    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle state) { binding = FragmentDownloadsBinding.inflate(inflater, container, false); return binding.getRoot(); }
    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        adapter = new DownloadAdapter(this); binding.list.setLayoutManager(new LinearLayoutManager(requireContext())); binding.list.setAdapter(adapter); binding.list.setHasFixedSize(true);
        binding.filters.setOnCheckedStateChangeListener((group, ids) -> { int id = ids.isEmpty() ? binding.filterAll.getId() : ids.get(0); filter = id == binding.filterActive.getId() ? "ACTIVE" : id == binding.filterComplete.getId() ? "COMPLETED" : id == binding.filterFailed.getId() ? "FAILED" : "ALL"; render(); });
        new ViewModelProvider(this).get(DownloadsViewModel.class).downloads().observe(getViewLifecycleOwner(), items -> { all = items == null ? List.of() : items; render(); });
    }
    private void render() {
        List<DownloadEntity> items = new ArrayList<>();
        for (DownloadEntity item : all) if ("ALL".equals(filter) || ("ACTIVE".equals(filter) && List.of("NEW","PROBING","QUEUED","RUNNING","PAUSING","PAUSED","RETRY_WAIT").contains(item.state)) || filter.equals(item.state)) items.add(item);
        adapter.submitList(items); binding.empty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
    }
    @Override public void primary(DownloadEntity item) {
        App app = (App) requireActivity().getApplication();
        switch (item.state) {
            case "RUNNING", "PROBING" -> app.coordinator().pause(item.id);
            case "FAILED" -> app.coordinator().retry(item.id);
            case "COMPLETED" -> open(item);
            default -> DownloadExecution.start(requireContext(), item.id, remaining(item), item.wifiOnly);
        }
    }
    @Override public void menu(DownloadEntity item, View anchor) {
        PopupMenu popup = new PopupMenu(requireContext(), anchor); popup.getMenu().add(0, 1, 0, "Open").setEnabled("COMPLETED".equals(item.state));
        popup.getMenu().add(0, 2, 1, "Share file").setEnabled("COMPLETED".equals(item.state)); popup.getMenu().add(0, 3, 2, "Copy source URL");
        popup.getMenu().add(0, 4, 3, "Move to top of queue"); popup.getMenu().add(0, 5, 4, "Connection limit"); popup.getMenu().add(0, 6, 5, "Speed limit");
        boolean busy = List.of("RUNNING","PROBING","PAUSING","VERIFYING").contains(item.state);
        popup.getMenu().add(1, 7, 6, "Rename").setEnabled(!busy); popup.getMenu().add(1, 8, 7, "Move / export to…").setEnabled("COMPLETED".equals(item.state)); popup.getMenu().add(1, 9, 8, "Cancel").setEnabled(!List.of("COMPLETED","CANCELED").contains(item.state));
        popup.getMenu().add(2, 10, 9, "Delete file and history").setEnabled(!busy); popup.getMenu().add(2, 11, 10, "Remove from history").setEnabled(!busy);
        popup.setOnMenuItemClickListener(menu -> { handleMenu(item, menu.getItemId()); return true; }); popup.show();
    }
    private void handleMenu(DownloadEntity item, int action) {
        switch (action) {
            case 1 -> open(item); case 2 -> share(item); case 3 -> copyUrl(item); case 4 -> IO.execute(() -> app().repository().moveToTop(item.id));
            case 5 -> editNumber(item, false); case 6 -> editNumber(item, true); case 7 -> rename(item); case 8 -> { pendingMove = item; moveFile.launch(item.fileName); } case 9 -> app().coordinator().cancel(item.id);
            case 10 -> confirmDelete(item, true); case 11 -> confirmDelete(item, false); default -> { }
        }
    }
    private void editNumber(DownloadEntity item, boolean speed) {
        EditText input = new EditText(requireContext()); input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER); input.setText(String.valueOf(speed ? item.speedLimit / 1024 : item.maxConnections)); input.setMinHeight(dp(56));
        new MaterialAlertDialogBuilder(requireContext()).setTitle(speed ? "Speed limit in KB/s (0 unlimited)" : "Maximum connections (1–16)").setView(input).setNegativeButton("Cancel", null).setPositiveButton("Save", (d, w) -> IO.execute(() -> {
            try { long value = Long.parseLong(input.getText().toString()); if (speed) item.speedLimit = Math.max(0, value) * 1024; else item.maxConnections = (int) Math.max(1, Math.min(16, value)); app().repository().update(item); } catch (NumberFormatException ignored) { }
        })).show();
    }
    private void rename(DownloadEntity item) {
        EditText input = new EditText(requireContext()); input.setText(item.fileName); input.setSelectAllOnFocus(true); input.setMinHeight(dp(56));
        new MaterialAlertDialogBuilder(requireContext()).setTitle("Rename download").setView(input).setNegativeButton("Cancel", null).setPositiveButton("Rename", (d, w) -> IO.execute(() -> {
            try { File old = new File(item.destination), partial = new File(item.destination + ".part"), target = app().destinations().uniqueFile(input.getText().toString()); if (old.exists() && !old.renameTo(target)) throw new Exception("Rename failed"); if (partial.exists() && !partial.renameTo(new File(target + ".part"))) throw new Exception("Partial file rename failed"); item.fileName = target.getName(); item.destination = target.getPath(); app().repository().update(item); }
            catch (Exception e) { toast(e.getMessage()); }
        })).show();
    }
    private void confirmDelete(DownloadEntity item, boolean deleteFile) { new MaterialAlertDialogBuilder(requireContext()).setTitle(deleteFile ? "Delete downloaded data?" : "Remove from history?").setMessage(deleteFile ? "The file and any partial data will be permanently deleted." : "The downloaded file will remain on this device.").setNegativeButton("Cancel", null).setPositiveButton("Delete", (d,w) -> IO.execute(() -> { app().coordinator().cancel(item.id); if (deleteFile) { new File(item.destination).delete(); new File(item.destination + ".part").delete(); } app().repository().delete(item.id); })).show(); }
    private void open(DownloadEntity item) { try { Uri uri = FileProvider.getUriForFile(requireContext(), requireContext().getPackageName() + ".files", new File(item.destination)); startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(uri, item.mimeType == null ? "application/octet-stream" : item.mimeType).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)); } catch (Exception e) { toast("No app can open this file"); } }
    private void share(DownloadEntity item) { try { Uri uri = FileProvider.getUriForFile(requireContext(), requireContext().getPackageName() + ".files", new File(item.destination)); startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType(item.mimeType == null ? "application/octet-stream" : item.mimeType).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share file")); } catch (Exception e) { toast("Unable to share this file"); } }
    private void copyUrl(DownloadEntity item) { requireContext().getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("Download URL", item.canonicalUrl)); toast("Source URL copied"); }
    private long remaining(DownloadEntity item) { return item.totalBytes > 0 ? Math.max(0, item.totalBytes - item.completedBytes) : -1; }
    private App app() { return application; }
    private void toast(String text) { if (isAdded()) requireActivity().runOnUiThread(() -> Toast.makeText(requireContext(), text, Toast.LENGTH_SHORT).show()); }
    private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density); }
    @Override public void onDestroyView() { binding = null; super.onDestroyView(); }
}
