package zm.co.codelabs.adm.transport;

import java.io.IOException;
import java.io.FilterInputStream;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import zm.co.codelabs.adm.engine.model.DownloadRequest;
import zm.co.codelabs.adm.engine.model.ProbeResult;
import zm.co.codelabs.adm.engine.model.TransferRequest;

public final class FallbackTransport implements TransportClient {
    private final TransportClient primary, fallback;
    private final Set<String> fallbackOrigins = ConcurrentHashMap.newKeySet();
    public FallbackTransport(TransportClient primary, TransportClient fallback) { this.primary = primary; this.fallback = fallback; }
    @Override public ProbeResult probe(DownloadRequest request) throws IOException { try { return primary.probe(request); } catch (IOException | RuntimeException e) { return fallback.probe(request); } }
    @Override public TransportCall open(TransferRequest request) throws IOException {
        String origin = origin(request.url());
        if (fallbackOrigins.contains(origin)) return fallback.open(request);
        try { return new FailureAwareCall(primary.open(request), () -> fallbackOrigins.add(origin)); }
        catch (IOException | RuntimeException e) { fallbackOrigins.add(origin); return fallback.open(request); }
    }
    @Override public void close() throws IOException { try { primary.close(); } finally { fallback.close(); } }

    private static String origin(String value) {
        try { URL url = new URL(value); return url.getProtocol().toLowerCase(java.util.Locale.ROOT) + "://" + url.getHost().toLowerCase(java.util.Locale.ROOT) + ":" + (url.getPort() >= 0 ? url.getPort() : url.getDefaultPort()); }
        catch (MalformedURLException e) { return value; }
    }

    private static final class FailureAwareCall implements TransportCall {
        private final TransportCall delegate;
        private final Runnable onFailure;
        private InputStream body;
        FailureAwareCall(TransportCall delegate, Runnable onFailure) { this.delegate = delegate; this.onFailure = onFailure; }
        @Override public int statusCode() { return delegate.statusCode(); }
        @Override public long contentLength() { return delegate.contentLength(); }
        @Override public String finalUrl() { return delegate.finalUrl(); }
        @Override public String protocol() { return delegate.protocol(); }
        @Override public Map<String, List<String>> headers() { return delegate.headers(); }
        @Override public synchronized InputStream body() {
            if (body == null) body = new FilterInputStream(delegate.body()) {
                @Override public int read() throws IOException { try { return super.read(); } catch (IOException e) { onFailure.run(); throw e; } }
                @Override public int read(byte[] bytes, int offset, int length) throws IOException { try { return super.read(bytes, offset, length); } catch (IOException e) { onFailure.run(); throw e; } }
            };
            return body;
        }
        @Override public void cancel() { delegate.cancel(); }
        @Override public void close() throws IOException { delegate.close(); }
    }
}
