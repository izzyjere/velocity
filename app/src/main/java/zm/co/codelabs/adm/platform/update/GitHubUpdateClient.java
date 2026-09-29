package zm.co.codelabs.adm.platform.update;

import android.content.Context;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public final class GitHubUpdateClient {
    public interface Progress { void onProgress(long completed, long total); }
    private static final String LATEST = "https://api.github.com/repos/izzyjere/velocity/releases/latest";
    private static final long MAX_APK_BYTES = 300L * 1024L * 1024L;
    private final Context context;
    private final OkHttpClient client = new OkHttpClient.Builder().followRedirects(true).followSslRedirects(false)
            .connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build();
    private volatile Call active;

    public GitHubUpdateClient(Context context) { this.context = context.getApplicationContext(); }

    public UpdateInfo latest() throws IOException {
        Request request = request(LATEST).header("Accept", "application/vnd.github+json").header("X-GitHub-Api-Version", "2022-11-28").build();
        try (Response response = execute(request)) {
            if (!response.isSuccessful() || response.body() == null) throw new IOException("GitHub release check failed with HTTP " + response.code());
            String json = response.body().string();
            try {
                JSONObject release = new JSONObject(json);
                String version = SemanticVersion.parse(release.getString("tag_name")).toString();
                String apkName = "velocity-" + version + ".apk";
                String checksumName = apkName + ".sha256";
                String apkUrl = null, checksumUrl = null, digest = null; long size = -1;
                JSONArray assets = release.getJSONArray("assets");
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject asset = assets.getJSONObject(i); String name = asset.optString("name");
                    if (apkName.equals(name)) { apkUrl = asset.getString("browser_download_url"); size = asset.optLong("size", -1); digest = asset.optString("digest", null); }
                    else if (checksumName.equals(name)) checksumUrl = asset.getString("browser_download_url");
                }
                validateAssetUrl(apkUrl, version, apkName);
                if (checksumUrl != null) validateAssetUrl(checksumUrl, version, checksumName);
                if ((digest == null || !digest.matches("(?i)sha256:[0-9a-f]{64}")) && checksumUrl == null) throw new IOException("Release has no trusted checksum");
                if (size <= 0 || size > MAX_APK_BYTES) throw new IOException("Release APK size is invalid");
                return new UpdateInfo(version, apkUrl, checksumUrl, digest, "https://github.com/izzyjere/velocity/releases/tag/v" + version, release.optString("body", ""), size);
            } catch (JSONException | IllegalArgumentException e) { throw new IOException("GitHub returned invalid release metadata", e); }
        }
    }

    public File download(UpdateInfo info, Progress progress) throws IOException {
        String expected = expectedDigest(info);
        File root = context.getExternalFilesDir("updates");
        if (root == null) root = new File(context.getFilesDir(), "updates");
        if (!root.exists() && !root.mkdirs()) throw new IOException("Cannot create update directory");
        File destination = new File(root, "velocity-" + info.version + ".apk");
        File partial = new File(destination.getPath() + ".part");
        if (destination.isFile() && expected.equals(sha256(destination))) return destination;
        if (partial.exists() && !partial.delete()) throw new IOException("Cannot replace partial update");
        Request request = request(info.apkUrl).build();
        try (Response response = execute(request)) {
            if (!response.isSuccessful() || response.body() == null) throw new IOException("APK download failed with HTTP " + response.code());
            long total = response.body().contentLength();
            if (total <= 0) total = info.size;
            if (total <= 0 || total > MAX_APK_BYTES) throw new IOException("Downloaded APK size is invalid");
            long completed = 0, lastUpdate = 0;
            try (InputStream input = response.body().byteStream(); FileOutputStream output = new FileOutputStream(partial)) {
                byte[] buffer = new byte[128 * 1024]; int count;
                while ((count = input.read(buffer)) != -1) {
                    completed += count; if (completed > MAX_APK_BYTES) throw new IOException("Downloaded APK exceeds size limit");
                    output.write(buffer, 0, count);
                    long now = android.os.SystemClock.elapsedRealtime();
                    if (now - lastUpdate >= 200) { progress.onProgress(completed, total); lastUpdate = now; }
                }
                output.getFD().sync();
            } catch (IOException e) { partial.delete(); throw e; }
            if (completed != total || completed != info.size) { partial.delete(); throw new IOException("Downloaded APK length does not match release metadata"); }
            if (!expected.equals(sha256(partial))) { partial.delete(); throw new IOException("Downloaded APK checksum does not match"); }
            if (destination.exists() && !destination.delete()) { partial.delete(); throw new IOException("Cannot replace old update"); }
            if (!partial.renameTo(destination)) { partial.delete(); throw new IOException("Cannot finalize update APK"); }
            removeOtherUpdates(root, destination);
            progress.onProgress(completed, total);
            return destination;
        }
    }

    public void clearDownloadedUpdates() {
        File root = context.getExternalFilesDir("updates");
        if (root == null) root = new File(context.getFilesDir(), "updates");
        removeOtherUpdates(root, null);
    }

    public void cancel() { Call call = active; if (call != null) call.cancel(); }

    private String expectedDigest(UpdateInfo info) throws IOException {
        String apiDigest = info.digest != null && info.digest.matches("(?i)sha256:[0-9a-f]{64}")
                ? info.digest.substring(7).toLowerCase(Locale.ROOT) : null;
        if (info.checksumUrl == null) return apiDigest;
        Request request = request(info.checksumUrl).build();
        try (Response response = execute(request)) {
            if (!response.isSuccessful() || response.body() == null) throw new IOException("Checksum download failed with HTTP " + response.code());
            byte[] buffer = new byte[4097]; int length = 0, count;
            try (InputStream input = response.body().byteStream()) {
                while (length < buffer.length && (count = input.read(buffer, length, buffer.length - length)) != -1) length += count;
            }
            if (length > 4096) throw new IOException("Checksum response is too large");
            String value = new String(buffer, 0, length, StandardCharsets.US_ASCII).trim();
            String digest = value.split("\\s+", 2)[0];
            if (!digest.matches("(?i)[0-9a-f]{64}")) throw new IOException("Release checksum is invalid");
            digest = digest.toLowerCase(Locale.ROOT);
            if (apiDigest != null && !apiDigest.equals(digest)) throw new IOException("GitHub and published checksums disagree");
            return digest;
        }
    }

    private Response execute(Request request) throws IOException {
        Call call = client.newCall(request); active = call;
        return call.execute();
    }

    private static Request.Builder request(String url) { return new Request.Builder().url(url).header("User-Agent", "Velocity-Android-Updater"); }

    private static void validateAssetUrl(String value, String version, String fileName) throws IOException {
        if (value == null) throw new IOException("Release APK asset is missing");
        HttpUrl url = HttpUrl.parse(value);
        String expectedPath = "/izzyjere/velocity/releases/download/v" + version + "/" + fileName;
        if (url == null || !"https".equals(url.scheme()) || !"github.com".equalsIgnoreCase(url.host()) || !expectedPath.equals(url.encodedPath())) {
            throw new IOException("Release asset URL is not trusted");
        }
    }

    private static String sha256(File file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = new FileInputStream(file)) { byte[] buffer = new byte[128 * 1024]; int count; while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count); }
            StringBuilder result = new StringBuilder(64); for (byte value : digest.digest()) result.append(String.format(Locale.ROOT, "%02x", value)); return result.toString();
        } catch (java.security.NoSuchAlgorithmException e) { throw new IOException("SHA-256 unavailable", e); }
    }

    private static void removeOtherUpdates(File root, File keep) {
        File[] files = root.listFiles(); if (files == null) return;
        for (File file : files) {
            String name = file.getName();
            if (!file.equals(keep) && file.isFile() && name.matches("velocity-[0-9]+\\.[0-9]+\\.[0-9]+\\.apk(?:\\.part)?")) file.delete();
        }
    }
}
