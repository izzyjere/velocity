package zm.co.codelabs.adm.transport.cronet;

import android.content.Context;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.chromium.net.CronetEngine;
import org.chromium.net.CronetException;
import org.chromium.net.UrlRequest;
import org.chromium.net.UrlResponseInfo;
import zm.co.codelabs.adm.engine.model.ByteRange;
import zm.co.codelabs.adm.engine.model.DownloadRequest;
import zm.co.codelabs.adm.engine.model.ProbeResult;
import zm.co.codelabs.adm.engine.model.TransferRequest;
import zm.co.codelabs.adm.transport.TransportCall;
import zm.co.codelabs.adm.transport.TransportClient;
import zm.co.codelabs.adm.transport.HttpStatusException;
import zm.co.codelabs.adm.transport.RangeResponseException;

public final class CronetTransport implements TransportClient {
    private final CronetEngine engine;
    private final ExecutorService callbacks = Executors.newFixedThreadPool(Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors())));
    public CronetTransport(Context context) {
        engine = new CronetEngine.Builder(context.getApplicationContext()).enableHttp2(true).enableQuic(true).enableBrotli(false).build();
    }
    @Override public ProbeResult probe(DownloadRequest request) throws IOException {
        try (TransportCall call = open(new TransferRequest(request.url(), request.headers(), new ByteRange(0, 0), null))) {
            String range = header(call.headers(), "content-range");
            boolean supported = call.statusCode() == 206 && validRange(range);
            long total = supported ? rangeTotal(range) : call.contentLength();
            return new ProbeResult(call.finalUrl(), call.statusCode(), total, supported, header(call.headers(), "etag"),
                    header(call.headers(), "last-modified"), header(call.headers(), "content-type"),
                    header(call.headers(), "content-disposition"), header(call.headers(), "content-encoding"), call.protocol());
        }
    }
    @Override public TransportCall open(TransferRequest request) throws IOException {
        boolean sensitive = request.headers().keySet().stream().anyMatch(name -> name.equalsIgnoreCase("Authorization") || name.equalsIgnoreCase("Cookie") || name.equalsIgnoreCase("Proxy-Authorization"));
        CronetCall callback = new CronetCall(request.url(), sensitive);
        UrlRequest.Builder builder = engine.newUrlRequestBuilder(request.url(), callback, callbacks).setHttpMethod("GET");
        request.headers().forEach((k, v) -> { if (!k.equalsIgnoreCase("Host") && !k.equalsIgnoreCase("Content-Length")) builder.addHeader(k, v); });
        builder.addHeader("Accept-Encoding", "identity");
        if (request.range() != null) builder.addHeader("Range", request.range().headerValue());
        if (request.ifRange() != null && !request.ifRange().isBlank()) builder.addHeader("If-Range", request.ifRange());
        callback.attach(builder.build());
        callback.startAndAwaitHeaders();
        if (callback.statusCode() >= 400) { HttpStatusException error = new HttpStatusException(callback.statusCode(), header(callback.headers(), "retry-after")); callback.close(); throw error; }
        if (request.range() != null && (callback.statusCode() != 206 || !rangeMatches(header(callback.headers(), "content-range"), request.range()))) {
            int code = callback.statusCode(); callback.close(); throw new RangeResponseException(code);
        }
        return callback;
    }
    private static String header(Map<String, List<String>> headers, String name) {
        for (Map.Entry<String, List<String>> e : headers.entrySet()) if (e.getKey().equalsIgnoreCase(name) && !e.getValue().isEmpty()) return e.getValue().get(0);
        return null;
    }
    private static boolean validRange(String value) { return value != null && value.matches("bytes 0-0/\\d+"); }
    private static long rangeTotal(String value) { try { return Long.parseLong(value.substring(value.indexOf('/') + 1)); } catch (RuntimeException e) { return -1; } }
    private static boolean rangeMatches(String value, ByteRange expected) { return value != null && value.startsWith("bytes " + expected.start() + "-" + expected.endInclusive() + "/"); }
    @Override public void close() { engine.shutdown(); callbacks.shutdown(); }

    private static final class CronetCall extends UrlRequest.Callback implements TransportCall {
        private static final Object END = new Object();
        private final String initialUrl;
        private final boolean hasSensitiveHeaders;
        private final CountDownLatch headersReady = new CountDownLatch(1);
        private final BlockingQueue<Object> chunks = new LinkedBlockingQueue<>(16);
        private volatile UrlRequest request;
        private volatile UrlResponseInfo info;
        private volatile IOException failure;
        private volatile boolean closed;
        CronetCall(String initialUrl, boolean hasSensitiveHeaders) { this.initialUrl = initialUrl; this.hasSensitiveHeaders = hasSensitiveHeaders; }
        void attach(UrlRequest value) { request = value; }
        void startAndAwaitHeaders() throws IOException {
            request.start();
            try { if (!headersReady.await(30, TimeUnit.SECONDS)) { request.cancel(); throw new IOException("Cronet response timeout"); } }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); request.cancel(); throw new IOException("Interrupted", e); }
            if (failure != null) throw failure;
        }
        @Override public void onRedirectReceived(UrlRequest req, UrlResponseInfo response, String newLocationUrl) {
            try {
                // URL intentionally accepts common server-generated paths (for example raw '[' and ']')
                // that strict java.net.URI rejects. Cronet itself can safely follow these redirects.
                URL oldUrl = new URL(response.getUrl()), next = new URL(newLocationUrl);
                if ("https".equalsIgnoreCase(oldUrl.getProtocol()) && !"https".equalsIgnoreCase(next.getProtocol())) {
                    rejectRedirect(req, new IOException("Refusing HTTPS downgrade redirect")); return;
                }
                boolean same = oldUrl.getProtocol().equalsIgnoreCase(next.getProtocol())
                        && oldUrl.getHost().equalsIgnoreCase(next.getHost()) && port(oldUrl) == port(next);
                if (same || !hasSensitiveHeaders) req.followRedirect();
                else rejectRedirect(req, new IOException("Cross-origin redirect requires sanitized fallback"));
            } catch (MalformedURLException | RuntimeException e) {
                rejectRedirect(req, new IOException("Invalid redirect location", e));
            }
        }
        private void rejectRedirect(UrlRequest req, IOException error) { failure = error; headersReady.countDown(); req.cancel(); }
        @Override public void onResponseStarted(UrlRequest req, UrlResponseInfo response) { info = response; headersReady.countDown(); req.read(ByteBuffer.allocateDirect(128 * 1024)); }
        @Override public void onReadCompleted(UrlRequest req, UrlResponseInfo response, ByteBuffer buffer) {
            buffer.flip(); byte[] data = new byte[buffer.remaining()]; buffer.get(data);
            try { chunks.put(data); } catch (InterruptedException e) { Thread.currentThread().interrupt(); req.cancel(); return; }
            buffer.clear(); req.read(buffer);
        }
        @Override public void onSucceeded(UrlRequest req, UrlResponseInfo response) { chunks.offer(END); }
        @Override public void onFailed(UrlRequest req, UrlResponseInfo response, CronetException error) { failure = new IOException(error); headersReady.countDown(); chunks.offer(END); }
        @Override public void onCanceled(UrlRequest req, UrlResponseInfo response) { headersReady.countDown(); chunks.offer(END); }
        @Override public int statusCode() { return info.getHttpStatusCode(); }
        @Override public long contentLength() { String v = header(headers(), "content-length"); try { return v == null ? -1 : Long.parseLong(v); } catch (NumberFormatException e) { return -1; } }
        @Override public String finalUrl() { return info == null ? initialUrl : info.getUrl(); }
        @Override public String protocol() { return info == null ? "unknown" : info.getNegotiatedProtocol(); }
        @Override public Map<String, List<String>> headers() { return info == null ? Map.of() : info.getAllHeaders(); }
        @Override public InputStream body() { return new QueueInputStream(); }
        @Override public void cancel() { request.cancel(); }
        @Override public void close() { closed = true; request.cancel(); chunks.offer(END); }
        private final class QueueInputStream extends InputStream {
            private byte[] current; private int offset;
            @Override public int read() throws IOException { byte[] one = new byte[1]; int n = read(one, 0, 1); return n < 0 ? -1 : one[0] & 0xff; }
            @Override public int read(byte[] b, int off, int len) throws IOException {
                while (current == null || offset >= current.length) {
                    if (closed) return -1;
                    Object next; try { next = chunks.take(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("Interrupted", e); }
                    if (next == END) { if (failure != null) throw failure; return -1; }
                    current = (byte[]) next; offset = 0;
                }
                int count = Math.min(len, current.length - offset); System.arraycopy(current, offset, b, off, count); offset += count; return count;
            }
        }
        private static int port(URL value) { return value.getPort() >= 0 ? value.getPort() : value.getDefaultPort(); }
    }
}
