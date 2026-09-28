package zm.co.codelabs.adm.storage;

import android.content.Context;
import android.os.Environment;
import java.io.File;
import java.io.IOException;

public final class DestinationResolver {
    private final File root;
    public DestinationResolver(Context context) {
        File external = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        root = external != null ? external : new File(context.getFilesDir(), "downloads");
    }
    public File uniqueFile(String requestedName) throws IOException {
        if (!root.exists() && !root.mkdirs()) throw new IOException("Cannot create downloads directory");
        String safe = FilenameSanitizer.sanitize(requestedName); File candidate = new File(root, safe).getCanonicalFile();
        File canonicalRoot = root.getCanonicalFile();
        if (!candidate.toPath().startsWith(canonicalRoot.toPath())) throw new IOException("Invalid destination");
        if (!candidate.exists() && !new File(candidate + ".part").exists()) return candidate;
        int dot = safe.lastIndexOf('.'); String base = dot > 0 ? safe.substring(0, dot) : safe; String ext = dot > 0 ? safe.substring(dot) : "";
        for (int i = 1; i < 10_000; i++) { candidate = new File(root, base + " (" + i + ")" + ext).getCanonicalFile(); if (!candidate.exists() && !new File(candidate + ".part").exists()) return candidate; }
        throw new IOException("Too many filename collisions");
    }
    public File root() { return root; }
}
