package zm.co.codelabs.adm;

import static org.junit.Assert.*;
import java.io.File;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okio.Buffer;
import org.junit.Test;
import zm.co.codelabs.adm.engine.IntegrityVerifier;
import zm.co.codelabs.adm.engine.SegmentScheduler;
import zm.co.codelabs.adm.engine.model.ByteRange;
import zm.co.codelabs.adm.engine.model.DownloadRequest;
import zm.co.codelabs.adm.engine.model.ProbeResult;
import zm.co.codelabs.adm.engine.model.TransferRequest;
import zm.co.codelabs.adm.storage.PositionedFileWriter;
import zm.co.codelabs.adm.transport.TransportCall;
import zm.co.codelabs.adm.transport.okhttp.OkHttpTransport;
import zm.co.codelabs.adm.transport.RangeResponseException;

public class HttpEngineIntegrationTest {
    @Test public void fallsBackToSingleStreamWhenOriginIgnoresRanges() throws Exception {
        try (MockWebServer server = new MockWebServer(); OkHttpTransport transport = new OkHttpTransport(new OkHttpClient())) {
            server.enqueue(new MockResponse().setResponseCode(200).setBody("complete body")); server.start();
            ProbeResult result = transport.probe(new DownloadRequest(server.url("/no-range").toString(), Map.of()));
            assertFalse(result.rangeSupported()); assertEquals(13, result.contentLength());
        }
    }
    @Test public void rejectsMalformedRangeInsteadOfSplicingBytes() throws Exception {
        try (MockWebServer server = new MockWebServer(); OkHttpTransport transport = new OkHttpTransport(new OkHttpClient())) {
            server.enqueue(new MockResponse().setResponseCode(206).addHeader("Content-Range", "bytes 2-5/10").setBody("xxxx")); server.start();
            try { transport.open(new TransferRequest(server.url("/bad-range").toString(), Map.of(), new ByteRange(0, 3), "\"etag\"")); fail("Expected range rejection"); }
            catch (RangeResponseException expected) { assertTrue(expected.getMessage().contains("did not honor")); }
        }
    }
    @Test public void stripsCookieOnCrossOriginRedirect() throws Exception {
        try (MockWebServer redirect = new MockWebServer(); MockWebServer target = new MockWebServer(); OkHttpTransport transport = new OkHttpTransport(new OkHttpClient())) {
            target.enqueue(new MockResponse().setResponseCode(206).addHeader("Content-Range", "bytes 0-0/1").setBody("x")); target.start();
            redirect.enqueue(new MockResponse().setResponseCode(302).addHeader("Location", target.url("/final"))); redirect.start();
            ProbeResult result = transport.probe(new DownloadRequest(redirect.url("/start").toString(), Map.of("Cookie", "secret=yes")));
            assertTrue(result.rangeSupported()); assertNull(target.takeRequest().getHeader("Cookie"));
        }
    }
    @Test public void probeAndParallelPositionedDownloadProduceExactBytes() throws Exception {
        byte[] source = new byte[8 * 1024 * 1024]; new Random(7).nextBytes(source);
        try (MockWebServer server = rangeServer(source); OkHttpTransport transport = new OkHttpTransport(new OkHttpClient())) {
            String url = server.url("/payload.bin").toString(); ProbeResult probe = transport.probe(new DownloadRequest(url, Map.of()));
            assertTrue(probe.rangeSupported()); assertEquals(source.length, probe.contentLength());
            File baseline = File.createTempFile("velocity-single", ".bin"); baseline.deleteOnExit(); long baselineStart = System.nanoTime();
            try (PositionedFileWriter writer = new PositionedFileWriter(baseline, source.length); TransportCall call = transport.open(new TransferRequest(url, Map.of(), null, null))) { copy(call.body(), writer, 0); writer.force(false); }
            long baselineElapsed = System.nanoTime() - baselineStart;
            File output = File.createTempFile("velocity-parts", ".bin"); output.deleteOnExit(); long started = System.nanoTime();
            List<ByteRange> ranges = SegmentScheduler.partition(source.length, 4); ExecutorService pool = Executors.newFixedThreadPool(4);
            try (PositionedFileWriter writer = new PositionedFileWriter(output, source.length)) {
                List<Future<?>> futures = new ArrayList<>();
                for (ByteRange range : ranges) futures.add(pool.submit(() -> { try (TransportCall call = transport.open(new TransferRequest(url, Map.of(), range, probe.etag()))) { copy(call.body(), writer, range.start()); } catch (Exception e) { throw new RuntimeException(e); } }));
                for (Future<?> future : futures) future.get(); writer.force(false);
            } finally { pool.shutdownNow(); }
            long elapsed = System.nanoTime() - started; byte[] actual = java.nio.file.Files.readAllBytes(output.toPath()); assertEquals(source.length, actual.length); assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(source), MessageDigest.getInstance("SHA-256").digest(actual));
            assertTrue(new IntegrityVerifier().sizeMatches(output, source.length));
            System.out.printf("BENCHMARK single-stream size=%d bytes time=%.3fs throughput=%.2f MiB/s%n", source.length, baselineElapsed / 1e9, source.length / (baselineElapsed / 1e9) / 1024 / 1024);
            System.out.printf("BENCHMARK adaptive-multipart size=%d bytes segments=4 time=%.3fs throughput=%.2f MiB/s speedup=%.2fx%n", source.length, elapsed / 1e9, source.length / (elapsed / 1e9) / 1024 / 1024, (double) baselineElapsed / elapsed);
        }
    }
    private static MockWebServer rangeServer(byte[] source) throws Exception {
        MockWebServer server = new MockWebServer(); server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                String header = request.getHeader("Range"); int start = 0, end = source.length - 1; int code = 200;
                if (header != null && header.startsWith("bytes=")) { String[] values = header.substring(6).split("-"); start = Integer.parseInt(values[0]); end = values.length > 1 && !values[1].isBlank() ? Math.min(end, Integer.parseInt(values[1])) : end; code = 206; }
                byte[] body = java.util.Arrays.copyOfRange(source, start, end + 1); MockResponse response = new MockResponse().setResponseCode(code).addHeader("ETag", "\"stable\"").addHeader("Accept-Ranges", "bytes").addHeader("Content-Length", body.length).setBody(new Buffer().write(body)).throttleBody(64 * 1024, 2, TimeUnit.MILLISECONDS);
                if (code == 206) response.addHeader("Content-Range", "bytes " + start + "-" + end + "/" + source.length); return response;
            }
        }); server.start(); return server;
    }
    private static void copy(InputStream in, PositionedFileWriter writer, long offset) throws Exception { byte[] buffer = new byte[128 * 1024]; int n; long position = offset; while ((n = in.read(buffer)) != -1) { writer.write(position, buffer, 0, n); position += n; } }
}
