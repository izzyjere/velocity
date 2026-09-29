package zm.co.codelabs.adm;

import android.app.Application;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import zm.co.codelabs.adm.data.db.AppDatabase;
import zm.co.codelabs.adm.data.repository.DownloadRepository;
import zm.co.codelabs.adm.engine.DownloadCoordinator;
import zm.co.codelabs.adm.storage.DestinationResolver;
import zm.co.codelabs.adm.storage.DownloadPublisher;
import zm.co.codelabs.adm.security.HeaderCipher;
import zm.co.codelabs.adm.transport.FallbackTransport;
import zm.co.codelabs.adm.transport.TransportClient;
import zm.co.codelabs.adm.transport.cronet.CronetTransport;
import zm.co.codelabs.adm.transport.okhttp.OkHttpTransport;
import zm.co.codelabs.adm.platform.connectivity.ConnectivityMonitor;
import zm.co.codelabs.adm.platform.notification.DownloadNotifications;
import zm.co.codelabs.adm.platform.logging.AppLogStore;
import zm.co.codelabs.adm.storage.StorageCapacity;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.work.Configuration;

public final class App extends Application implements Configuration.Provider {
    private final ExecutorService startup = Executors.newSingleThreadExecutor(r -> new Thread(r, "app-startup"));
    private DownloadRepository repository;
    private DownloadCoordinator coordinator;
    private DestinationResolver destinations;
    private ConnectivityMonitor connectivity;
    private AppLogStore logs;
    @Override public void onCreate() {
        super.onCreate();
        logs = new AppLogStore(this);
        Thread.UncaughtExceptionHandler previousHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            logs.errorNow("Uncaught/" + thread.getName(), error);
            if (previousHandler != null) previousHandler.uncaughtException(thread, error);
        });
        logs.info("Application", "Velocity process started");
        AppCompatDelegate.setDefaultNightMode(getSharedPreferences("settings", MODE_PRIVATE).getBoolean("dark_theme", false)
                ? AppCompatDelegate.MODE_NIGHT_YES : AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        // The process never auto-resumes interrupted transfers, so any download notification left
        // from a killed service/job is stale until a new execution host explicitly posts one.
        DownloadNotifications.cancelStale(this);
        repository = new DownloadRepository(AppDatabase.get(this), new HeaderCipher());
        destinations = new DestinationResolver(this);
        TransportClient transport;
        try { transport = new FallbackTransport(new CronetTransport(this), new OkHttpTransport()); }
        catch (RuntimeException e) { logs.error("Transport/Cronet initialization", e); transport = new OkHttpTransport(); }
        coordinator = new DownloadCoordinator(repository, transport, new StorageCapacity(this), new DownloadPublisher(this), logs);
        coordinator.setGlobalSpeedLimit(getSharedPreferences("settings", MODE_PRIVATE).getLong("speed_limit", 0));
        connectivity = new ConnectivityMonitor(this, coordinator);
        startup.execute(coordinator::recover);
    }
    public DownloadRepository repository() { return repository; }
    public DownloadCoordinator coordinator() { return coordinator; }
    public DestinationResolver destinations() { return destinations; }
    public AppLogStore logs() { return logs; }
    @NonNull @Override public Configuration getWorkManagerConfiguration() { return new Configuration.Builder().setJobSchedulerJobIdRange(2_000_000, 2_100_000).build(); }
    @Override public void onTerminate() { connectivity.close(); coordinator.shutdown(); repository.close(); startup.shutdown(); logs.close(); super.onTerminate(); }
}
