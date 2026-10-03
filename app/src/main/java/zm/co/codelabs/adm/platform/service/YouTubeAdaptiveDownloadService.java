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
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import zm.co.codelabs.adm.engine.DownloadPlanner;
import zm.co.codelabs.adm.engine.model.ByteRange;
import zm.co.codelabs.adm.engine.model.DownloadRequest;
import zm.co.codelabs.adm.engine.model.ProbeResult;
import zm.co.codelabs.adm.engine.model.TransferRequest;
import zm.co.codelabs.adm.storage.PositionedFileWriter;
import zm.co.codelabs.adm.transport.TransportCall;
import zm.co.codelabs.adm.transport.HttpStatusException;
import zm.co.codelabs.adm.transport.okhttp.OkHttpTransport;
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
    private final ExecutorService segments = Executors.newFixedThreadPool(16, r -> new Thread(r, "youtube-segment"));

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
        Future<?> videoTransfer = null;
        Future<?> audioTransfer = null;
        try {
            if (!dir.exists() && !dir.mkdirs()) throw new IOException("Cannot create YouTube temporary directory");
            update("Downloading video and audio", true);
            videoTransfer = transfers.submit(() -> download(videoUrl, videoHeaders, video));
            audioTransfer = transfers.submit(() -> download(audioUrl, audioHeaders, audio));
            videoTransfer.get();
            audioTransfer.get();
            update("Merging tracks", true);
            muxMp4(video, audio, merged);
            Uri published = new DownloadPublisher(this).publish(merged, ensureMp4(fileName), "video/mp4");
            update("Saved to " + published, false);
        } catch (Exception e) {
            if (videoTransfer != null) videoTransfer.cancel(true);
            if (audioTransfer != null) audioTransfer.cancel(true);
            update("YouTube download failed: " + failureMessage(e), false);
        } finally {
            video.delete(); audio.delete(); merged.delete();
            stopForeground(STOP_FOREGROUND_DETACH);
            stopSelf(startId);
        }
    }

    private void download(String url, Map<String, String> headers, File target) {
        try (OkHttpTransport transport = new OkHttpTransport()) {
            ProbeResult probe = transport.probe(new DownloadRequest(url, headers));
            List<ByteRange> ranges = new DownloadPlanner().plan(probe, 8);
            if (!ranges.isEmpty() && probe.safeForMultipart()) {
                String validator = probe.etag() != null && !probe.etag().startsWith("W/")
                        ? probe.etag() : probe.lastModified();
                try (PositionedFileWriter writer = new PositionedFileWriter(target, probe.contentLength())) {
                    List<Future<?>> workers = new ArrayList<>();
                    for (ByteRange range : ranges) {
                        workers.add(segments.submit(() -> copyRange(transport, probe.resolvedUrl(),
                                headers, range, validator, writer)));
                    }
                    for (Future<?> worker : workers) worker.get();
                    writer.force(false);
                }
                return;
            }
            try (TransportCall call = transport.open(new TransferRequest(
                    probe.resolvedUrl(), headers, null, null));
                 java.io.FileOutputStream out = new java.io.FileOutputStream(target)) {
                byte[] buffer = new byte[256 * 1024];
                InputStream in = call.body();
                int n;
                while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
                out.getFD().sync();
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void copyRange(OkHttpTransport transport, String url, Map<String, String> headers,
                                  ByteRange range, String validator, PositionedFileWriter writer) {
        try (TransportCall call = transport.open(new TransferRequest(url, headers, range, validator))) {
            byte[] buffer = new byte[128 * 1024];
            InputStream in = call.body();
            long position = range.start();
            long remaining = range.length();
            while (remaining > 0) {
                int n = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (n < 0) throw new IOException("Range ended early");
                writer.write(position, buffer, 0, n);
                position += n;
                remaining -= n;
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

    private static void copyTrack(MediaExtractor extractor, MediaMuxer muxer, int muxTrack)
            throws IOException {
        ByteBuffer buffer = ByteBuffer.allocateDirect(2 * 1024 * 1024);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (true) {
            buffer.clear();
            int size = extractor.readSampleData(buffer, 0);
            if (size < 0) break;
            info.offset = 0;
            info.size = size;
            info.presentationTimeUs = extractor.getSampleTime();
            int sampleFlags = extractor.getSampleFlags();
            if ((sampleFlags & MediaExtractor.SAMPLE_FLAG_ENCRYPTED) != 0) {
                throw new IOException("Encrypted YouTube tracks cannot be merged");
            }
            info.flags = 0;
            if ((sampleFlags & MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                info.flags |= MediaCodec.BUFFER_FLAG_KEY_FRAME;
            }
            if ((sampleFlags & MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME) != 0) {
                info.flags |= MediaCodec.BUFFER_FLAG_PARTIAL_FRAME;
            }
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

    public static String failureMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        if (current instanceof HttpStatusException status && status.statusCode() == 403) {
            return "YouTube stream URL expired. Re-open the video and retry.";
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onDestroy() {
        coordinator.shutdownNow();
        transfers.shutdownNow();
        segments.shutdownNow();
        super.onDestroy();
    }
}
