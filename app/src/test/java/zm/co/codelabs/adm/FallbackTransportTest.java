package zm.co.codelabs.adm;

import static org.junit.Assert.assertEquals;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import zm.co.codelabs.adm.engine.model.DownloadRequest;
import zm.co.codelabs.adm.engine.model.ProbeResult;
import zm.co.codelabs.adm.engine.model.TransferRequest;
import zm.co.codelabs.adm.transport.FallbackTransport;
import zm.co.codelabs.adm.transport.TransportCall;
import zm.co.codelabs.adm.transport.TransportClient;

public class FallbackTransportTest {
    @Test public void bodyFailureQuarantinesPrimaryForFollowingRangeRetry() throws Exception {
        AtomicInteger primaryOpens = new AtomicInteger(), fallbackOpens = new AtomicInteger();
        TransportClient primary = client(primaryOpens, true), fallback = client(fallbackOpens, false);
        try (FallbackTransport transport = new FallbackTransport(primary, fallback)) {
            TransferRequest request = new TransferRequest("https://cdn.example.test/File [2026].zip", Map.of(), null, null);
            try (TransportCall call = transport.open(request)) { call.body().read(); }
            catch (IOException expected) { assertEquals("simulated protocol failure", expected.getMessage()); }
            try (TransportCall call = transport.open(request)) { assertEquals(7, call.body().read()); }
        }
        assertEquals(1, primaryOpens.get()); assertEquals(1, fallbackOpens.get());
    }

    private static TransportClient client(AtomicInteger opens, boolean fail) {
        return new TransportClient() {
            @Override public ProbeResult probe(DownloadRequest request) { throw new UnsupportedOperationException(); }
            @Override public TransportCall open(TransferRequest request) { opens.incrementAndGet(); return call(fail); }
            @Override public void close() { }
        };
    }

    private static TransportCall call(boolean fail) {
        return new TransportCall() {
            @Override public int statusCode() { return 200; }
            @Override public long contentLength() { return 1; }
            @Override public String finalUrl() { return "https://cdn.example.test/file"; }
            @Override public String protocol() { return "h2"; }
            @Override public Map<String, List<String>> headers() { return Map.of(); }
            @Override public InputStream body() { return new InputStream() { @Override public int read() throws IOException { if (fail) throw new IOException("simulated protocol failure"); return 7; } }; }
            @Override public void cancel() { }
            @Override public void close() { }
        };
    }
}
