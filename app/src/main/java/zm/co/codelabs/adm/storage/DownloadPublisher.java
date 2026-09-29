package zm.co.codelabs.adm.storage;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import androidx.documentfile.provider.DocumentFile;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Locale;

public final class DownloadPublisher {
    private static final String PREFS = "settings";
    private static final String KEY_TREE_URI = "destination_tree_uri";
    private static final String DEFAULT_ROOT = "Velocity";

    private final Context context;
    private final ContentResolver resolver;
    private final SharedPreferences prefs;

    public DownloadPublisher(Context context) {
        this.context = context.getApplicationContext();
        this.resolver = this.context.getContentResolver();
        this.prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public Uri publish(File source, String displayName, String mimeType) throws IOException {
        String category = categoryFor(displayName, mimeType);
        String tree = prefs.getString(KEY_TREE_URI, null);
        Uri published = tree == null || tree.isBlank()
                ? publishToDownloads(source, displayName, mimeType, category)
                : publishToTree(source, displayName, mimeType, category, Uri.parse(tree));
        if (!source.delete() && source.exists()) source.deleteOnExit();
        return published;
    }

    private Uri publishToDownloads(File source, String displayName, String mimeType, String category) throws IOException {
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, displayName);
        values.put(MediaStore.MediaColumns.MIME_TYPE, safeMime(mimeType));
        values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/" + DEFAULT_ROOT + "/" + category);
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IOException("Cannot create destination in Downloads/Velocity");
        try {
            copy(source, uri);
            ContentValues ready = new ContentValues();
            ready.put(MediaStore.MediaColumns.IS_PENDING, 0);
            resolver.update(uri, ready, null, null);
            return uri;
        } catch (IOException e) {
            resolver.delete(uri, null, null);
            throw e;
        }
    }

    private Uri publishToTree(File source, String displayName, String mimeType, String category, Uri treeUri) throws IOException {
        DocumentFile root = DocumentFile.fromTreeUri(context, treeUri);
        if (root == null || !root.canWrite()) throw new IOException("Selected destination folder is no longer writable");
        DocumentFile folder = root.findFile(category);
        if (folder == null) folder = root.createDirectory(category);
        if (folder == null) throw new IOException("Cannot create " + category + " folder");

        String name = uniqueName(folder, displayName);
        DocumentFile file = folder.createFile(safeMime(mimeType), name);
        if (file == null) throw new IOException("Cannot create destination file");
        try {
            copy(source, file.getUri());
            return file.getUri();
        } catch (IOException e) {
            file.delete();
            throw e;
        }
    }

    private void copy(File source, Uri target) throws IOException {
        try (FileInputStream in = new FileInputStream(source);
             OutputStream out = resolver.openOutputStream(target, "w")) {
            if (out == null) throw new IOException("Cannot open destination for writing");
            byte[] buffer = new byte[256 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            out.flush();
        }
    }

    private static String uniqueName(DocumentFile folder, String requested) {
        if (folder.findFile(requested) == null) return requested;
        int dot = requested.lastIndexOf('.');
        String base = dot > 0 ? requested.substring(0, dot) : requested;
        String ext = dot > 0 ? requested.substring(dot) : "";
        for (int i = 1; i < 10_000; i++) {
            String candidate = base + " (" + i + ")" + ext;
            if (folder.findFile(candidate) == null) return candidate;
        }
        return System.currentTimeMillis() + "-" + requested;
    }

    private static String categoryFor(String name, String mimeType) {
        String mime = mimeType == null ? "" : mimeType.toLowerCase(Locale.ROOT);
        String ext = extension(name);
        if (mime.startsWith("audio/") || in(ext, "mp3","m4a","aac","ogg","oga","opus","wav","flac","alac","ape","amr","wma","mid","midi")) return "Music";
        if (mime.startsWith("video/") || in(ext, "mp4","webm","mkv","mov","m4v","avi","3gp","ts")) return "Videos";
        if (mime.startsWith("image/") || in(ext, "jpg","jpeg","png","gif","webp","bmp","heic","heif","svg")) return "Images";
        if (mime.contains("zip") || mime.contains("rar") || mime.contains("7z") || mime.contains("gzip") || mime.contains("tar")
                || in(ext, "zip","rar","7z","gz","gzip","tar","bz2","xz","tgz")) return "Compressed";
        if (mime.startsWith("text/") || mime.contains("pdf") || mime.contains("document") || mime.contains("sheet")
                || mime.contains("presentation") || in(ext, "pdf","doc","docx","xls","xlsx","ppt","pptx","txt","rtf","csv","odt","ods","odp")) return "Documents";
        if (in(ext, "apk","aab","exe","msi","dmg","pkg","deb","rpm")) return "Apps";
        return "Other";
    }

    private static String extension(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        return dot >= 0 && dot + 1 < name.length() ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    }

    private static boolean in(String value, String... options) {
        for (String option : options) if (option.equals(value)) return true;
        return false;
    }

    private static String safeMime(String mime) {
        return mime == null || mime.isBlank() ? "application/octet-stream" : mime;
    }
}
