package zm.co.codelabs.adm.platform.connectivity;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import zm.co.codelabs.adm.engine.DownloadCoordinator;

public final class ConnectivityMonitor implements AutoCloseable {
    private final ConnectivityManager manager;
    private final ConnectivityManager.NetworkCallback callback;
    public ConnectivityMonitor(Context context, DownloadCoordinator coordinator) {
        manager = context.getSystemService(ConnectivityManager.class);
        callback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) { publish(network); }
            @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) { coordinator.onNetworkChanged(capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET), capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)); }
            @Override public void onLost(Network network) { Network active = manager.getActiveNetwork(); if (active == null) coordinator.onNetworkChanged(false, false); else publish(active); }
            private void publish(Network network) { NetworkCapabilities caps = manager.getNetworkCapabilities(network); coordinator.onNetworkChanged(caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET), caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)); }
        };
        manager.registerNetworkCallback(new NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), callback);
    }
    @Override public void close() { try { manager.unregisterNetworkCallback(callback); } catch (IllegalArgumentException ignored) { } }
}
