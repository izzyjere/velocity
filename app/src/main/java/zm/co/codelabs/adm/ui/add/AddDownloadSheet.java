package zm.co.codelabs.adm.ui.add;

import android.app.Dialog;
import android.content.DialogInterface;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Toast;
import androidx.annotation.NonNull;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import zm.co.codelabs.adm.App;
import zm.co.codelabs.adm.data.db.entity.DownloadEntity;
import zm.co.codelabs.adm.databinding.SheetAddDownloadBinding;
import zm.co.codelabs.adm.platform.service.DownloadExecution;
import zm.co.codelabs.adm.storage.FilenameSanitizer;

public final class AddDownloadSheet extends BottomSheetDialogFragment {
    public static final String TAG = "add_download";
    public static final String RESULT = "add_download_closed";
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> new Thread(r, "add-download"));
    private SheetAddDownloadBinding binding;
    public static AddDownloadSheet newInstance(String url, Map<String, String> headers) {
        AddDownloadSheet sheet = new AddDownloadSheet(); Bundle args = new Bundle(); args.putString("url", url);
        Bundle values = new Bundle(); headers.forEach(values::putString); args.putBundle("headers", values); sheet.setArguments(args); return sheet;
    }
    @NonNull @Override public Dialog onCreateDialog(Bundle state) {
        BottomSheetDialog dialog = new BottomSheetDialog(requireContext()); binding = SheetAddDownloadBinding.inflate(LayoutInflater.from(requireContext())); dialog.setContentView(binding.getRoot());
        String initial = requireArguments().getString("url", ""); binding.url.setText(initial); binding.name.setText(suggestedName(initial));
        String[] modes = {"Auto (recommended)", "1", "2", "4", "8", "12", "16"}; binding.connections.setAdapter(new ArrayAdapter<>(requireContext(), android.R.layout.simple_dropdown_item_1line, modes)); binding.connections.setText(modes[0], false);
        binding.wifiOnly.setChecked(requireContext().getSharedPreferences("settings", android.content.Context.MODE_PRIVATE).getBoolean("wifi_only", false));
        binding.start.setOnClickListener(v -> submit(true)); binding.queue.setOnClickListener(v -> submit(false)); return dialog;
    }
    private void submit(boolean startNow) {
        String url = text(binding.url), name = FilenameSanitizer.sanitize(text(binding.name)); Uri uri = Uri.parse(url);
        if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme())) || uri.getHost() == null) { binding.urlLayout.setError("Enter a valid HTTP or HTTPS URL"); return; }
        if (name.isBlank()) { binding.nameLayout.setError("Enter a file name"); return; }
        binding.start.setEnabled(false); binding.queue.setEnabled(false); App app = (App) requireActivity().getApplication(); Bundle headerBundle = requireArguments().getBundle("headers");
        String connectionChoice = binding.connections.getText().toString(); boolean wifiOnly = binding.wifiOnly.isChecked();
        android.content.SharedPreferences prefs = requireContext().getSharedPreferences("settings", android.content.Context.MODE_PRIVATE);
        int preferredConnections = prefs.getInt("connections", 16); long speedLimit = prefs.getLong("speed_limit", 0);
        android.content.Context appContext = requireContext().getApplicationContext(); android.app.Activity activity = requireActivity();
        IO.execute(() -> {
            try {
                File destination = app.destinations().uniqueFile(name); DownloadEntity item = new DownloadEntity(); item.canonicalUrl = url; item.fileName = destination.getName(); item.destination = destination.getPath();
                item.createdAt = item.updatedAt = System.currentTimeMillis(); item.queuePosition = item.createdAt; item.maxConnections = connectionChoice.startsWith("Auto") ? preferredConnections : connectionLimit(connectionChoice); item.wifiOnly = wifiOnly; item.speedLimit = speedLimit;
                Map<String, String> headers = new HashMap<>(); if (headerBundle != null) for (String key : headerBundle.keySet()) { String value = headerBundle.getString(key); if (value != null) headers.put(key, value); }
                item.encryptedHeaders = app.repository().encryptHeaders(headers); long id = app.repository().create(item);
                boolean executionStarted = startNow && DownloadExecution.start(appContext, id, -1, item.wifiOnly);
                activity.runOnUiThread(() -> { if (!isAdded() || binding == null) return; Toast.makeText(requireContext(), executionStarted ? "Download started" : "Added to queue", Toast.LENGTH_SHORT).show(); dismiss(); });
            } catch (Exception e) { activity.runOnUiThread(() -> { if (binding == null) return; binding.start.setEnabled(true); binding.queue.setEnabled(true); binding.urlLayout.setError(e.getMessage()); }); }
        });
    }
    private static int connectionLimit(String value) { if (value.startsWith("Auto")) return 16; try { return Integer.parseInt(value); } catch (NumberFormatException e) { return 16; } }
    private static String text(android.widget.TextView view) { return view.getText() == null ? "" : view.getText().toString().trim(); }
    private static String suggestedName(String url) { String path = Uri.parse(url).getLastPathSegment(); return path == null || path.isBlank() ? "download" : FilenameSanitizer.sanitize(path); }
    @Override public void onDismiss(@NonNull DialogInterface dialog) { super.onDismiss(dialog); getParentFragmentManager().setFragmentResult(RESULT, new Bundle()); }
    @Override public void onDestroyView() { binding = null; super.onDestroyView(); }
}
