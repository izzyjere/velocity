package zm.co.codelabs.adm.ui.files;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.PopupMenu;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.io.File;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import zm.co.codelabs.adm.App;
import zm.co.codelabs.adm.data.db.entity.DownloadEntity;
import zm.co.codelabs.adm.databinding.FragmentSimpleListBinding;
import zm.co.codelabs.adm.ui.downloads.DownloadAdapter;
import zm.co.codelabs.adm.ui.downloads.DownloadsViewModel;
import zm.co.codelabs.adm.R;

public final class FilesFragment extends Fragment implements DownloadAdapter.Actions {
    private static final java.util.concurrent.ExecutorService IO = Executors.newSingleThreadExecutor(r -> new Thread(r, "file-actions"));
    private FragmentSimpleListBinding binding;
    private App application;
    @Override public void onAttach(@NonNull android.content.Context context) { super.onAttach(context); application = (App) context.getApplicationContext(); }
    @Nullable @Override public View onCreateView(@NonNull LayoutInflater i, @Nullable ViewGroup c, @Nullable Bundle s) { binding = FragmentSimpleListBinding.inflate(i,c,false); return binding.getRoot(); }
    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle state) { DownloadAdapter adapter = new DownloadAdapter(this); binding.list.setLayoutManager(new LinearLayoutManager(requireContext())); binding.list.setAdapter(adapter); new ViewModelProvider(this).get(DownloadsViewModel.class).downloads().observe(getViewLifecycleOwner(), all -> { List<DownloadEntity> files = all == null ? List.of() : all.stream().filter(x -> "COMPLETED".equals(x.state) && exists(x)).collect(Collectors.toList()); adapter.submitList(files); binding.empty.setText(R.string.files_empty); binding.empty.setVisibility(files.isEmpty() ? View.VISIBLE : View.GONE); }); }
    @Override public void primary(DownloadEntity item) { open(item); }
    @Override public void menu(DownloadEntity item, View anchor) { PopupMenu popup = new PopupMenu(requireContext(), anchor); popup.getMenu().add("Open").setOnMenuItemClickListener(x -> { open(item); return true; }); popup.getMenu().add("Share").setOnMenuItemClickListener(x -> { share(item); return true; }); popup.getMenu().add("Delete file").setOnMenuItemClickListener(x -> { new MaterialAlertDialogBuilder(requireContext()).setTitle("Delete file?").setMessage("This cannot be undone.").setNegativeButton("Cancel", null).setPositiveButton("Delete", (d,w) -> IO.execute(() -> { delete(item); application.repository().delete(item.id); })).show(); return true; }); popup.show(); }
    private boolean exists(DownloadEntity item) { if (item.destination == null) return false; if (item.destination.startsWith("content://")) { try (android.os.ParcelFileDescriptor fd = requireContext().getContentResolver().openFileDescriptor(Uri.parse(item.destination), "r")) { return fd != null; } catch (Exception e) { return false; } } return new File(item.destination).exists(); }
    private Uri uri(DownloadEntity item) { if (item.destination.startsWith("content://")) return Uri.parse(item.destination); return androidx.core.content.FileProvider.getUriForFile(requireContext(), requireContext().getPackageName() + ".files", new File(item.destination)); }
    private void delete(DownloadEntity item) { if (item.destination != null && item.destination.startsWith("content://")) { try { requireContext().getContentResolver().delete(Uri.parse(item.destination), null, null); } catch (Exception ignored) { } } else if (item.destination != null) new File(item.destination).delete(); }
    private void open(DownloadEntity item) { try { startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(uri(item), item.mimeType == null ? "application/octet-stream" : item.mimeType).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)); } catch (Exception e) { Toast.makeText(requireContext(), "No app can open this file", Toast.LENGTH_SHORT).show(); } }
    private void share(DownloadEntity item) { startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType(item.mimeType == null ? "application/octet-stream" : item.mimeType).putExtra(Intent.EXTRA_STREAM, uri(item)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share file")); }
    @Override public void onDestroyView() { binding = null; super.onDestroyView(); }
}
