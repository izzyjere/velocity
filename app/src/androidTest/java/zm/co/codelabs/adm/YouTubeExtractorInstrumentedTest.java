package zm.co.codelabs.adm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.Test;
import org.junit.runner.RunWith;
import zm.co.codelabs.adm.data.db.entity.DownloadEntity;
import zm.co.codelabs.adm.data.repository.DownloadRepository;
import zm.co.codelabs.adm.engine.DownloadCoordinator;
import zm.co.codelabs.adm.media.YouTubeExtractor;
import zm.co.codelabs.adm.media.YouTubePoTokenProvider;
import zm.co.codelabs.adm.engine.DownloadPlanner;
import zm.co.codelabs.adm.engine.model.ByteRange;
import zm.co.codelabs.adm.engine.model.DownloadRequest;
import zm.co.codelabs.adm.engine.model.DownloadState;
import zm.co.codelabs.adm.engine.model.ProbeResult;
import zm.co.codelabs.adm.engine.model.TransferRequest;
import zm.co.codelabs.adm.transport.FallbackTransport;
import zm.co.codelabs.adm.transport.TransportCall;
import zm.co.codelabs.adm.transport.cronet.CronetTransport;
import zm.co.codelabs.adm.transport.okhttp.OkHttpTransport;

@RunWith(AndroidJUnit4.class)
public final class YouTubeExtractorInstrumentedTest {
    @Test public void generatesVideoBoundPoTokenInWebView() throws Exception {
        try (YouTubePoTokenProvider provider = new YouTubePoTokenProvider(
                InstrumentationRegistry.getInstrumentation().getTargetContext())) {
            String token = provider.tokenFor("Q3XMFsrl4ws");
            assertFalse("expected a non-empty video-bound PO token", token.isBlank());
        }
    }

    @Test public void resolvesDirectStreamsFromSharedLink() throws Exception {
        YouTubeExtractor.Resolution resolution = new YouTubeExtractor().resolve("https://youtu.be/dQw4w9WgXcQ");
        List<YouTubeExtractor.Stream> streams = resolution.streams();
        assertFalse("expected at least one downloadable stream", streams.isEmpty());
        for (YouTubeExtractor.Stream stream : streams) {
            assertTrue("stream url must be a direct googlevideo link",
                    stream.url().startsWith("https://") && stream.url().contains("googlevideo.com"));
            assertFalse("each format must preserve its User-Agent",
                    stream.requestHeaders().getOrDefault("User-Agent", "").isBlank());
            assertTrue("each format must preserve its YouTube origin",
                    "https://www.youtube.com".equals(stream.requestHeaders().get("Origin")));
        }

        YouTubeExtractor.Stream selected = null;
        for (YouTubeExtractor.Stream stream : streams) {
            if (stream.progressive() || stream.audioOnly()) {
                selected = stream;
                break;
            }
        }
        assertTrue("expected a directly downloadable video or audio format", selected != null);
        assertStreamAcceptsContext(selected);
    }

    @Test public void resolvesReportedMusicVideoWithAdaptiveMp4Pair() throws Exception {
        String pageUrl = "https://youtu.be/8P2LiCqQwnw?si=XJS_LoomkI8y3gPV";
        String videoId = YouTubeExtractor.videoId(pageUrl);
        String playerPoToken;
        try (YouTubePoTokenProvider provider = new YouTubePoTokenProvider(
                InstrumentationRegistry.getInstrumentation().getTargetContext())) {
            playerPoToken = provider.tokenFor(videoId);
        }
        YouTubeExtractor.VideoInfo info = new YouTubeExtractor().extract(pageUrl,
                YouTubeExtractor.BrowserSession.empty(), playerPoToken, Map.of());
        YouTubeExtractor.Stream video = null;
        YouTubeExtractor.Stream progressive = null;
        for (YouTubeExtractor.Stream stream : info.selectableFormats()) {
            if (progressive == null && stream.progressive()) progressive = stream;
            if (stream.videoOnly()) {
                video = stream;
                break;
            }
        }
        assertTrue("expected a safely mergeable MP4 video format", video != null);
        YouTubeExtractor.Stream audio = info.bestAudioFor(video);
        assertTrue("expected an automatically selected M4A audio format", audio != null);
        assertStreamAcceptsContext(video);
        assertStreamAcceptsContext(audio);
        assertTrue("expected a progressive fallback", progressive != null);
        assertVelocityMultipartRequests(progressive);
    }

    @Test public void downloadsReportedProgressiveThroughVelocityEngine() throws Exception {
        String pageUrl = "https://youtu.be/8P2LiCqQwnw?si=XJS_LoomkI8y3gPV";
        YouTubeExtractor.VideoInfo info = new YouTubeExtractor().extract(pageUrl,
                YouTubeExtractor.BrowserSession.empty(), null, Map.of());
        YouTubeExtractor.Stream progressive = null;
        for (YouTubeExtractor.Stream stream : info.selectableFormats()) {
            if (stream.progressive()) {
                progressive = stream;
                break;
            }
        }
        assertNotNull("expected a progressive format for the real engine test", progressive);

        App app = (App) InstrumentationRegistry.getInstrumentation()
                .getTargetContext().getApplicationContext();
        DownloadRepository repository = app.repository();
        DownloadCoordinator coordinator = app.coordinator();
        String fileName = "velocity-youtube-e2e-" + System.currentTimeMillis() + ".mp4";
        File destination = app.destinations().uniqueFile(fileName);
        DownloadEntity item = new DownloadEntity();
        item.canonicalUrl = progressive.url();
        item.fileName = fileName;
        item.destination = destination.getPath();
        item.mimeType = progressive.mimeType();
        item.maxConnections = 8;
        item.queuePosition = System.currentTimeMillis();
        item.createdAt = item.queuePosition;
        item.updatedAt = item.queuePosition;
        item.encryptedHeaders = repository.encryptHeaders(progressive.requestHeaders());
        long id = repository.create(item);

        CountDownLatch terminal = new CountDownLatch(1);
        AtomicReference<DownloadState> terminalState = new AtomicReference<>();
        DownloadCoordinator.Observer observer = new DownloadCoordinator.Observer() {
            @Override public void onProgress(long downloadId, long bytes, long total,
                                             double bytesPerSecond) { }

            @Override public void onTerminal(long downloadId, DownloadState state) {
                if (downloadId == id) {
                    terminalState.set(state);
                    terminal.countDown();
                }
            }
        };
        coordinator.addObserver(observer);
        Uri published = null;
        try {
            coordinator.start(id);
            assertTrue("Velocity download did not finish within three minutes",
                    terminal.await(3, TimeUnit.MINUTES));
            DownloadEntity completed = repository.get(id);
            assertNotNull(completed);
            assertEquals(completed.errorMessage, DownloadState.COMPLETED, terminalState.get());
            assertEquals(DownloadState.COMPLETED.name(), completed.state);
            assertTrue("expected downloaded media bytes", completed.completedBytes > 1_000_000);
            assertTrue("publisher must replace the private path with a content URI",
                    completed.destination.startsWith("content://"));
            published = Uri.parse(completed.destination);
            assertPublishedFile(app.getContentResolver(), published, fileName,
                    completed.completedBytes);
            assertFalse("publisher must remove the private completed file", destination.exists());
            assertFalse("publisher must remove the private partial file",
                    new File(destination.getPath() + ".part").exists());
        } finally {
            coordinator.removeObserver(observer);
            DownloadEntity current = repository.get(id);
            if (published == null && current != null && current.destination != null
                    && current.destination.startsWith("content://")) {
                published = Uri.parse(current.destination);
            }
            if (published != null) app.getContentResolver().delete(published, null, null);
            repository.delete(id);
            destination.delete();
            new File(destination.getPath() + ".part").delete();
        }
    }

    private static void assertStreamAcceptsContext(YouTubeExtractor.Stream selected)
            throws Exception {
        Request.Builder request = new Request.Builder().url(selected.url())
                .header("Range", "bytes=0-0").header("Accept-Encoding", "identity");
        for (Map.Entry<String, String> header : selected.requestHeaders().entrySet()) {
            request.header(header.getKey(), header.getValue());
        }
        try (Response response = new OkHttpClient().newCall(request.build()).execute()) {
            assertTrue("resolved stream must accept its captured request context, HTTP "
                    + response.code(), response.code() == 200 || response.code() == 206);
        }
    }

    private static void assertVelocityMultipartRequests(YouTubeExtractor.Stream stream)
            throws Exception {
        android.content.Context context = InstrumentationRegistry.getInstrumentation()
                .getTargetContext();
        try (FallbackTransport transport = new FallbackTransport(
                new CronetTransport(context), new OkHttpTransport())) {
            ProbeResult probe = transport.probe(new DownloadRequest(
                    stream.url(), stream.requestHeaders()));
            assertTrue("googlevideo must validate byte ranges before multipart",
                    probe.safeForMultipart());
            String validator = probe.etag() != null && !probe.etag().startsWith("W/")
                    ? probe.etag() : probe.lastModified();
            List<ByteRange> ranges = new DownloadPlanner().plan(probe, 8);
            assertFalse("expected a multipart range plan", ranges.isEmpty());
            for (ByteRange range : ranges) {
                try (TransportCall call = transport.open(new TransferRequest(
                        probe.resolvedUrl(), stream.requestHeaders(), range, validator))) {
                    assertTrue("multipart request returned no data", call.body().read() >= 0);
                }
            }
        }
    }

    private static void assertPublishedFile(ContentResolver resolver, Uri uri,
                                            String expectedName, long expectedSize) {
        try (Cursor cursor = resolver.query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE},
                null, null, null)) {
            assertNotNull("published media must be queryable", cursor);
            assertTrue("published media row is missing", cursor.moveToFirst());
            assertEquals(expectedName, cursor.getString(0));
            assertEquals(expectedSize, cursor.getLong(1));
        }
    }
}
