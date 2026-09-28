package zm.co.codelabs.adm.ui.queue;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.PopupMenu;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import java.util.List;
import java.util.stream.Collectors;
import zm.co.codelabs.adm.App;
import zm.co.codelabs.adm.data.db.entity.DownloadEntity;
import zm.co.codelabs.adm.databinding.FragmentSimpleListBinding;
import zm.co.codelabs.adm.platform.service.DownloadExecution;
import zm.co.codelabs.adm.ui.downloads.DownloadAdapter;
import zm.co.codelabs.adm.ui.downloads.DownloadsViewModel;
import zm.co.codelabs.adm.R;

public final class QueueFragment extends Fragment implements DownloadAdapter.Actions {
    private FragmentSimpleListBinding binding;
    private App application;
    @Override public void onAttach(@NonNull android.content.Context context) { super.onAttach(context); application = (App) context.getApplicationContext(); }
    @Nullable @Override public View onCreateView(@NonNull LayoutInflater i, @Nullable ViewGroup c, @Nullable Bundle s) { binding = FragmentSimpleListBinding.inflate(i,c,false); return binding.getRoot(); }
    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle state) { DownloadAdapter adapter = new DownloadAdapter(this); binding.list.setLayoutManager(new LinearLayoutManager(requireContext())); binding.list.setAdapter(adapter); new ViewModelProvider(this).get(DownloadsViewModel.class).downloads().observe(getViewLifecycleOwner(), all -> { List<DownloadEntity> queued = all == null ? List.of() : all.stream().filter(x -> List.of("NEW","QUEUED","PAUSED","RETRY_WAIT","FAILED").contains(x.state)).collect(Collectors.toList()); adapter.submitList(queued); binding.empty.setText(R.string.queue_empty); binding.empty.setVisibility(queued.isEmpty() ? View.VISIBLE : View.GONE); }); }
    @Override public void primary(DownloadEntity item) { DownloadExecution.start(requireContext(), item.id, item.totalBytes > 0 ? item.totalBytes - item.completedBytes : -1, item.wifiOnly); }
    @Override public void menu(DownloadEntity item, View anchor) { PopupMenu popup = new PopupMenu(requireContext(), anchor); popup.getMenu().add("Move to top").setOnMenuItemClickListener(x -> { new Thread(() -> application.repository().moveToTop(item.id)).start(); return true; }); popup.getMenu().add("Start now").setOnMenuItemClickListener(x -> { primary(item); return true; }); popup.getMenu().add("Cancel").setOnMenuItemClickListener(x -> { application.coordinator().cancel(item.id); return true; }); popup.show(); }
    @Override public void onDestroyView() { binding = null; super.onDestroyView(); }
}
