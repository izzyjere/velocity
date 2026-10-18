package zm.co.codelabs.adm.storage;

import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.webkit.MimeTypeMap;
import androidx.core.content.FileProvider;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.Locale;
import zm.co.codelabs.adm.data.db.entity.DownloadEntity;

/**
 * Resolves a download's destination, which is a plain path while in progress and a
 * {@code content://} MediaStore/SAF uri once {@link DownloadPublisher} has published it.
 */
public final class DownloadFiles {
    private static final String FALLBACK_MIME = "application/octet-stream";

    private DownloadFiles() {
    }

    public static boolean isContentUri(DownloadEntity item) {
        return item.destination != null && item.destination.startsWith("content://");
    }

    public static Uri uri(Context context, DownloadEntity item) {
        if (isContentUri(item)) return Uri.parse(item.destination);
        return FileProvider.getUriForFile(context, context.getPackageName() + ".files", new File(item.destination));
    }

    public static boolean exists(Context context, DownloadEntity item) {
        if (item.destination == null || item.destination.isEmpty()) return false;
        if (!isContentUri(item)) return new File(item.destination).exists();
        try (android.os.ParcelFileDescriptor fd = context.getContentResolver().openFileDescriptor(Uri.parse(item.destination), "r")) {
            return fd != null;
        } catch (Exception e) {
            return false;
        }
    }

    public static InputStream openInput(Context context, DownloadEntity item) throws FileNotFoundException {
        if (!isContentUri(item)) return new FileInputStream(item.destination);
        InputStream in = context.getContentResolver().openInputStream(Uri.parse(item.destination));
        if (in == null) throw new FileNotFoundException("Cannot read " + item.fileName);
        return in;
    }

    /** Deletes the published file (or the local file and its partial data). */
    public static void delete(Context context, DownloadEntity item) {
        if (item.destination == null || item.destination.isEmpty()) return;
        if (isContentUri(item)) {
            try {
                Uri uri = Uri.parse(item.destination);
                if (android.provider.DocumentsContract.isDocumentUri(context, uri)) {
                    android.provider.DocumentsContract.deleteDocument(context.getContentResolver(), uri);
                } else {
                    context.getContentResolver().delete(uri, null, null);
                }
            } catch (Exception ignored) {
            }
            return;
        }
        new File(item.destination).delete();
        new File(item.destination + ".part").delete();
    }

    /**
     * Viewer apps match on a concrete MIME type, so prefer what the provider reports, then the file
     * extension, and only then the server's (possibly generic or parameterised) Content-Type.
     */
    public static String mimeType(Context context, DownloadEntity item, Uri uri) {
        String type = null;
        if (isContentUri(item)) {
            try { type = context.getContentResolver().getType(uri); } catch (Exception ignored) { }
        }
        if (isGeneric(type)) type = mimeFromName(item.fileName);
        if (isGeneric(type)) type = clean(item.mimeType);
        return isGeneric(type) ? FALLBACK_MIME : type;
    }

    public static Intent viewIntent(Context context, DownloadEntity item) {
        Uri uri = uri(context, item);
        Intent intent = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, mimeType(context, item, uri));
        return intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    }

    public static Intent shareIntent(Context context, DownloadEntity item) {
        Uri uri = uri(context, item);
        Intent send = new Intent(Intent.ACTION_SEND).setType(mimeType(context, item, uri))
                .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        send.setClipData(ClipData.newRawUri(item.fileName, uri));
        return Intent.createChooser(send, "Share file");
    }

    static String mimeFromName(String name) {
        if (name == null) return null;
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot + 1 >= name.length()) return null;
        String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        String type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        if (type != null) return type;
        return switch (ext) {
            case "mkv" -> "video/x-matroska";
            case "m4a" -> "audio/mp4";
            case "opus" -> "audio/ogg";
            default -> null;
        };
    }

    private static String clean(String type) {
        if (type == null) return null;
        int semi = type.indexOf(';');
        return (semi >= 0 ? type.substring(0, semi) : type).trim().toLowerCase(Locale.ROOT);
    }

    private static boolean isGeneric(String type) {
        return type == null || type.isBlank() || type.equals(FALLBACK_MIME) || type.equals("binary/octet-stream")
                || type.equals("application/binary") || type.equals("application/force-download");
    }
}
