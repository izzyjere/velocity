package zm.co.codelabs.adm.ui.downloads;

import android.view.LayoutInflater;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;
import zm.co.codelabs.adm.data.db.entity.DownloadEntity;
import zm.co.codelabs.adm.databinding.RowDownloadBinding;
import zm.co.codelabs.adm.engine.ProgressMath;
import zm.co.codelabs.adm.platform.notification.DownloadNotifications;
import zm.co.codelabs.adm.R;
import androidx.core.content.ContextCompat;
import zm.co.codelabs.adm.util.CommonUtils;

public final class DownloadAdapter extends ListAdapter<DownloadEntity, DownloadAdapter.Holder> {
    public interface Actions { void primary(DownloadEntity item); void menu(DownloadEntity item, android.view.View anchor); }
    private final Actions actions;
    public DownloadAdapter(Actions actions) { super(DIFF); this.actions = actions; setHasStableIds(true); }
    @Override public long getItemId(int position) { return getItem(position).id; }
    @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) { return new Holder(RowDownloadBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false)); }
    @Override public void onBindViewHolder(@NonNull Holder holder, int position) { holder.bind(getItem(position)); }
    final class Holder extends RecyclerView.ViewHolder {
        private final RowDownloadBinding b;
        Holder(RowDownloadBinding binding) { super(binding.getRoot()); b = binding; }
        void bind(DownloadEntity item) {
            b.name.setText(item.fileName); b.status.setText(status(item));
            b.progress.setIndicatorColor(ContextCompat.getColor(b.progress.getContext(), "FAILED".equals(item.state)
                    ? R.color.error : R.color.primary));
            boolean known = item.totalBytes > 0; b.progress.setIndeterminate(!known); if (known) b.progress.setProgressCompat(ProgressMath.permille(item.completedBytes, item.totalBytes, "COMPLETED".equals(item.state)), true);
            String transfer = item.speedBytesPerSecond > 0 ? " • " + DownloadNotifications.humanSpeed(item.speedBytesPerSecond) + eta(item) : "";
            b.meta.setText(known ? CommonUtils.humanSize(item.completedBytes) + " / " + CommonUtils.humanSize(item.totalBytes) + transfer + connectionLabel(item) : CommonUtils.humanSize(item.completedBytes) + transfer);
            b.primaryAction.setText(primaryLabel(item.state)); b.primaryAction.setEnabled(!"CANCELED".equals(item.state)); b.primaryAction.setOnClickListener(v -> actions.primary(item)); b.moreAction.setOnClickListener(v -> actions.menu(item, v));
            b.getRoot().setContentDescription(item.fileName + ", " + status(item) + ", " + b.meta.getText());
        }
    }
    private static String connectionLabel(DownloadEntity item) { return item.protocol == null ? "" : " • " + item.protocol + " • up to " + item.maxConnections + " connections"; }
    private static String eta(DownloadEntity item) { if (item.totalBytes <= item.completedBytes || item.speedBytesPerSecond < 1) return ""; long seconds = (long) ((item.totalBytes - item.completedBytes) / item.speedBytesPerSecond); return seconds < 60 ? " • " + seconds + "s left" : seconds < 3600 ? " • " + seconds / 60 + "m left" : " • " + seconds / 3600 + "h " + seconds % 3600 / 60 + "m left"; }
    private static String status(DownloadEntity item) { return "FAILED".equals(item.state) ? "Failed" : item.state.replace('_', ' ').toLowerCase(java.util.Locale.ROOT); }
    private static String primaryLabel(String state) { return switch (state) { case "RUNNING", "PROBING" -> "Pause"; case "COMPLETED" -> "Open"; case "FAILED" -> "Retry"; case "CANCELED" -> "Canceled"; default -> "Start"; }; }
    private static final DiffUtil.ItemCallback<DownloadEntity> DIFF = new DiffUtil.ItemCallback<>() {
        @Override public boolean areItemsTheSame(@NonNull DownloadEntity a, @NonNull DownloadEntity b) { return a.id == b.id; }
        @Override public boolean areContentsTheSame(@NonNull DownloadEntity a, @NonNull DownloadEntity b) { return a.updatedAt == b.updatedAt && a.completedBytes == b.completedBytes && a.state.equals(b.state); }
    };
}
