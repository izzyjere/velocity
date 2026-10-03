package zm.co.codelabs.adm.platform.service;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.IBinder;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import zm.co.codelabs.adm.R;
import zm.co.codelabs.adm.platform.notification.DownloadNotifications;
import zm.co.codelabs.adm.storage.DownloadPublisher;

public final class YouTubeAdaptiveDownloadService extends Service {
    public static final String ACTION_START = "zm.co.codelabs.adm.action.YOUTUBE_ADAPTIVE";
    public static final String EXTRA_VIDEO_URL = "video_url";
    public static final String EXTRA_AUDIO_URL = "audio_url";
    public static final String EXTRA_FILE_NAME = "file_name";
    public static final String EXTRA_VIDEO_HEADERS = "video_headers";
    public static final String EXTRA_AUDIO_HEADERS = "audio_headers";
    private static final int NOTIFICATION_ID = 73_001;

    private final ExecutorService coordinator = Executors.newSingleThreadExecutor(r -> new Thread(r, "youtube-adaptive"));
    private final ExecutorService transfers = Executors.newFixedThreadPool(2, r -> new Thread(r, "youtube-track"));
    private final OkHttpClient client = new OkHttpClient.Builder().retryOnConnectionFailure(true).build();

    @Override public void onCreate() {
        super.onCreate();
        DownloadNotifications.createChannel(this);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || !ACTION_START.equals(intent.getAction())) return START_NOT_STICKY;
        String videoUrl = intent.getStringExtra(EXTRA_VIDEO_URL);
        String audioUrl = intent.getStringExtra(EXTRA_AUDIO_URL);
        String fileName = intent.getStringExtra(EXTRA_FILE_NAME);
        Map<String, String> videoHeaders = bundle(intent.getBundleExtra(EXTRA_VIDEO_HEADERS));
        Map<String, String> audioHeaders = bundle(intent.getBundleExtra(EXTRA_AUDIO_HEADERS));
        if (videoUrl == null || audioUrl == null || fileName == null) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        startForeground(NOTIFICATION_ID, notification("Preparing " + fileName, true));
        coordinator.execute(() -> runDownload(startId, videoUrl, audioUrl, fileName, videoHeaders, audioHeaders));
        return START_NOT_STICKY;
    }

    private void runDownload(int startId, String videoUrl, String audioUrl, String fileName,
                             Map<String, String> videoHeaders, Map<String, String> audioHeaders) {
        File dir = new File(getCacheDir(), "youtube-merge");
        File video = new File(dir, "video-" + System.nanoTime() + ".mp4");
        File audio = new File(dir, "audio-" + System.nanoTime() + ".m4a");
        File merged = new File(dir, "merged-" + System.nanoTime() + ".mp4");
        try {
            if (!dir.exists() && !dir.mkdirs()) throw new IOException("Cannot create YouTube temporary directory");
            update("Downloading video and audio", true);
            Future<?> v = transfers.submit(() -> download(videoUrl, videoHeaders, video));
            Future<?> a = transfers.submit(() -> download(audioUrl, audioHeaders, audio));
            v.get(); a.get();
            update("Merging tracks", true);
            muxMp4(video, audio, merged);
            Uri published = new DownloadPublisher(this).publish(merged, ensureMp4(fileName), "video/mp4");
            update("Saved to " + published, false);
        } catch (Exception e) {
            update("YouTube download failed: " + rootMessage(e), false);
        } finally {
            video.delete(); audio.delete(); merged.delete();
            stopForeground(false);
            stopSelf(startId);
        }
    }

    private void download(String url, Map<String, String> headers, File target) {
        Request.Builder request = new Request.Builder().url(url);
        headers.forEach((name, value) -> {
            if (value != null && !value.isBlank() && !name.equalsIgnoreCase("Host")
                    && !name.equalsIgnoreCase("Content-Length")) request.header(name, value);
        });
        request.header("Accept-Encoding", "identity");
        try (Response response = client.newCall(request.get().build()).execute()) {
            if (!response.isSuccessful()) throw new IOException("HTTP " + response.code());
            ResponseBody body = response.body();
            if (body == null) throw new IOException("Empty media response");
            try (InputStream in = body.byteStream(); FileOutputStream out = new FileOutputStream(target)) {
                byte[] buffer = new byte[256 * 1024];
                int n;
                while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
                out.getFD().sync();
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static void muxMp4(File video, File audio, File output) throws IOException {
        MediaExtractor videoExtractor = new MediaExtractor();
        MediaExtractor audioExtractor = new MediaExtractor();
        MediaMuxer muxer = null;
        try {
            videoExtractor.setDataSource(video.getAbsolutePath());
            audioExtractor.setDataSource(audio.getAbsolutePath());
            int videoTrack = findTrack(videoExtractor, "video/");
            int audioTrack = findTrack(audioExtractor, "audio/");
            if (videoTrack < 0 || audioTrack < 0) throw new IOException("Compatible MP4 tracks were not found");
            videoExtractor.selectTrack(videoTrack);
            audioExtractor.selectTrack(audioTrack);
            muxer = new MediaMuxer(output.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int muxVideo = muxer.addTrack(videoExtractor.getTrackFormat(videoTrack));
            int muxAudio = muxer.addTrack(audioExtractor.getTrackFormat(audioTrack));
            muxer.start();
            copyTrack(videoExtractor, muxer, muxVideo);
            copyTrack(audioExtractor, muxer, muxAudio);
        } finally {
            try { videoExtractor.release(); } catch (RuntimeException ignored) { }
            try { audioExtractor.release(); } catch (RuntimeException ignored) { }
            if (muxer != null) {
                try { muxer.stop(); } catch (RuntimeException ignored) { }
                try { muxer.release(); } catch (RuntimeException ignored) { }
            }
        }
    }

    private static int findTrack(MediaExtractor extractor, String prefix) {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith(prefix)) return i;
        }
        return -1;
    }

    private static void copyTrack(MediaExtractor extractor, MediaMuxer muxer, int muxTrack) {
        ByteBuffer buffer = ByteBuffer.allocateDirect(2 * 1024 * 1024);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (true) {
            buffer.clear();
            int size = extractor.readSampleData(buffer, 0);
            if (size < 0) break;
            info.offset = 0;
            info.size = size;
            info.presentationTimeUs = extractor.getSampleTime();
            info.flags = extractor.getSampleFlags();
            muxer.writeSampleData(muxTrack, buffer, info);
            extractor.advance();
        }
    }

    private Notification notification(String text, boolean ongoing) {
        return new NotificationCompat.Builder(this, DownloadNotifications.CHANNEL)
                .setSmallIcon(R.drawable.ic_download)
                .setContentTitle("Velocity · YouTube")
                .setContentText(text)
                .setOnlyAlertOnce(true)
                .setOngoing(ongoing)
                .setProgress(0, 0, ongoing)
                .build();
    }

    private void update(String text, boolean ongoing) {
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification(text, ongoing));
    }

    private static Map<String, String> bundle(android.os.Bundle values) {
        Map<String, String> result = new LinkedHashMap<>();
        if (values != null) for (String key : values.keySet()) {
            String value = values.getString(key);
            if (value != null) result.put(key, value);
        }
        return result;
    }

    public static android.os.Bundle headers(Map<String, String> values) {
        android.os.Bundle bundle = new android.os.Bundle();
        values.forEach(bundle::putString);
        return bundle;
    }

    private static String ensureMp4(String name) {
        String safe = name == null || name.isBlank() ? "youtube-video.mp4" : name;
        int dot = safe.lastIndexOf('.');
        if (dot > 0) safe = safe.substring(0, dot);
        return safe + ".mp4";
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onDestroy() {
        coordinator.shutdownNow();
        transfers.shutdownNow();
        super.onDestroy();
    }
}
