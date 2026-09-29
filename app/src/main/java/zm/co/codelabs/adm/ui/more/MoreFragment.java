package zm.co.codelabs.adm.ui.more;

import android.content.SharedPreferences;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.fragment.app.Fragment;
import zm.co.codelabs.adm.App;
import zm.co.codelabs.adm.databinding.FragmentMoreBinding;
import zm.co.codelabs.adm.R;
import zm.co.codelabs.adm.ui.logs.LogsActivity;
import zm.co.codelabs.adm.ui.update.UpdateActivity;

public final class MoreFragment extends Fragment {
    private FragmentMoreBinding binding;
    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle state) { binding = FragmentMoreBinding.inflate(inflater, container, false); return binding.getRoot(); }
    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        SharedPreferences prefs = prefs(); int ceiling = prefs.getInt("connections", 16); binding.connections.setValue(ceiling); binding.connectionsValue.setText(getResources().getQuantityString(R.plurals.connection_count, ceiling, ceiling));
        binding.connections.addOnChangeListener((slider, value, user) -> { int count = (int) value; binding.connectionsValue.setText(getResources().getQuantityString(R.plurals.connection_count, count, count)); }); binding.wifiOnly.setChecked(prefs.getBoolean("wifi_only", false));
        binding.darkTheme.setChecked(prefs.getBoolean("dark_theme", false)); long bytes = prefs.getLong("speed_limit", 0); binding.speedLimit.setText(String.valueOf(bytes / 1024));
        binding.save.setOnClickListener(v -> save());
        binding.logs.setOnClickListener(v -> startActivity(new Intent(requireContext(), LogsActivity.class)));
        binding.updates.setOnClickListener(v -> startActivity(new Intent(requireContext(), UpdateActivity.class)));
        binding.github.setOnClickListener(v -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/izzyjere"))));
    }
    private void save() {
        long kb; try { kb = Long.parseLong(binding.speedLimit.getText() == null ? "0" : binding.speedLimit.getText().toString()); } catch (NumberFormatException e) { kb = 0; }
        long bytes = Math.max(0, kb) * 1024; prefs().edit().putInt("connections", (int) binding.connections.getValue()).putBoolean("wifi_only", binding.wifiOnly.isChecked()).putBoolean("dark_theme", binding.darkTheme.isChecked()).putLong("speed_limit", bytes).apply();
        ((App) requireActivity().getApplication()).coordinator().setGlobalSpeedLimit(bytes);
        AppCompatDelegate.setDefaultNightMode(binding.darkTheme.isChecked() ? AppCompatDelegate.MODE_NIGHT_YES : AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        Toast.makeText(requireContext(), "Settings saved", Toast.LENGTH_SHORT).show();
    }
    private SharedPreferences prefs() { return requireContext().getSharedPreferences("settings", android.content.Context.MODE_PRIVATE); }
    @Override public void onDestroyView() { binding = null; super.onDestroyView(); }
}
