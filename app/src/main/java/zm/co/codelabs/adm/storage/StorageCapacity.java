package zm.co.codelabs.adm.storage;

import android.content.Context;
import android.os.storage.StorageManager;
import java.io.File;
import java.io.IOException;
import java.util.UUID;

public final class StorageCapacity {
    private static final long RESERVE = 32L * 1024 * 1024;
    private final StorageManager manager;
    public StorageCapacity(Context context) { manager = context.getSystemService(StorageManager.class); }
    public void requireSpace(File destination, long bytesNeeded) throws IOException {
        if (bytesNeeded <= 0) return; File directory = destination.getParentFile(); if (directory == null) throw new IOException("Destination has no parent directory");
        UUID volume = manager.getUuidForPath(directory); long allocatable = manager.getAllocatableBytes(volume);
        if (allocatable < bytesNeeded + RESERVE) throw new DiskFullException();
    }
}
