package zm.co.codelabs.adm.transport.okhttp;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import okhttp3.Call;
import okhttp3.ConnectionPool;
import okhttp3.Headers;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import zm.co.codelabs.adm.engine.model.ByteRange;
import zm.co.codelabs.adm.engine.model.DownloadRequest;
import zm.co.codelabs.adm.engine.model.ProbeResult;
import zm.co.codelabs.adm.engine.model.TransferRequest;
import zm.co.codelabs.adm.transport.TransportCall;
import zm.co.codelabs.adm.transport.TransportClient;
import zm.co.codelabs.adm.transport.HttpStatusException;
import zm.co.codelabs.adm.transport.RangeResponseException;

public final class OkHttpTransport implements TransportClient {
    private final OkHttpClient client;
    public OkHttpTransport() {
        client = new OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true).connectionPool(new ConnectionPool(12, 5, TimeUnit.MINUTES)).followRedirects(false).build();
    }
    public OkHttpTransport(OkHttpClient client) { this.client = client.newBuilder().followRedirects(false).followSslRedirects(false).build(); }

    @Override public ProbeResult probe(DownloadRequest input) throws IOException {
        try (TransportCall call = openInternal(new TransferRequest(input.url(), input.headers(), new ByteRange(0, 0), null), false)) {
            String contentRange = first(call.headers(), "Content-Range");
            boolean ranged = call.statusCode() == 206 && validContentRange(contentRange, new ByteRange(0, 0));
            long total = ranged ? totalFromRange(contentRange) : call.contentLength();
            return new ProbeResult(call.finalUrl(), call.statusCode(), total, ranged,
                    first(call.headers(), "ETag"), first(call.headers(), "Last-Modified"), first(call.headers(), "Content-Type"),
                    first(call.headers(), "Content-Disposition"), first(call.headers(), "Content-Encoding"), call.protocol());
        }
    }
    @Override public TransportCall open(TransferRequest input) throws IOException {
        return openInternal(input, true);
    }
    private TransportCall openInternal(TransferRequest input, boolean validateRange) throws IOException {
        String url = input.url(); Map<String, String> headers = new LinkedHashMap<>(input.headers());
        for (int redirects = 0; redirects <= 10; redirects++) {
            Request.Builder builder = requestBuilder(url, headers).header("Accept-Encoding", "identity");
            if (input.range() != null) builder.header("Range", input.range().headerValue());
            if (input.ifRange() != null && !input.ifRange().isBlank()) builder.header("If-Range", input.ifRange());
            Call call = client.newCall(builder.get().build()); Response response = call.execute();
            if (response.isRedirect()) {
                String location = response.header("Location"); okhttp3.HttpUrl next = location == null ? null : response.request().url().resolve(location);
                if (next == null) { response.close(); throw new IOException("Invalid redirect location"); }
                if (response.request().url().isHttps() && !next.isHttps()) { response.close(); throw new IOException("Refusing HTTPS downgrade redirect"); }
                if (!sameOrigin(response.request().url().toString(), next.toString())) headers.entrySet().removeIf(e -> sensitive(e.getKey()));
                url = next.toString(); response.close(); continue;
            }
            if (response.code() >= 400) { HttpStatusException error = new HttpStatusException(response.code(), response.header("Retry-After")); response.close(); throw error; }
            if (validateRange && input.range() != null && (response.code() != 206 || !validContentRange(response.header("Content-Range"), input.range()))) {
                int code = response.code(); response.close(); throw new RangeResponseException(code);
            }
            return new ResponseCall(call, response);
        }
        throw new IOException("Too many redirects");
    }
    private static boolean sensitive(String name) { return name.equalsIgnoreCase("Authorization") || name.equalsIgnoreCase("Proxy-Authorization") || name.equalsIgnoreCase("Cookie"); }
    private static boolean sameOrigin(String first, String second) { URI a = URI.create(first), b = URI.create(second); return a.getScheme().equalsIgnoreCase(b.getScheme()) && a.getHost().equalsIgnoreCase(b.getHost()) && effectivePort(a) == effectivePort(b); }
    private static int effectivePort(URI uri) { return uri.getPort() >= 0 ? uri.getPort() : ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80); }
    private static String first(Map<String, List<String>> headers, String name) { for (Map.Entry<String, List<String>> e : headers.entrySet()) if (e.getKey().equalsIgnoreCase(name) && !e.getValue().isEmpty()) return e.getValue().get(0); return null; }
    private static long totalFromRange(String value) { try { return Long.parseLong(value.substring(value.indexOf('/') + 1)); } catch (RuntimeException e) { return -1; } }
    private static Request.Builder requestBuilder(String url, Map<String, String> headers) {
        Request.Builder builder = new Request.Builder().url(url);
        URI origin = URI.create(url);
        headers.forEach((name, value) -> {
            if (!name.equalsIgnoreCase("Host") && !name.equalsIgnoreCase("Content-Length") && origin.getHost() != null) builder.header(name, value);
        });
        return builder;
    }
    static boolean validContentRange(String value, ByteRange expected) {
        if (value == null || !value.startsWith("bytes ")) return false;
        int dash = value.indexOf('-', 6), slash = value.indexOf('/', dash + 1);
        try { return dash > 6 && slash > dash && Long.parseLong(value.substring(6, dash)) == expected.start() && Long.parseLong(value.substring(dash + 1, slash)) == expected.endInclusive(); }
        catch (NumberFormatException e) { return false; }
    }
    private static long totalLength(Response response, boolean ranged) {
        if (ranged) { String value = response.header("Content-Range"); int slash = value == null ? -1 : value.indexOf('/'); if (slash > 0) try { return Long.parseLong(value.substring(slash + 1)); } catch (NumberFormatException ignored) { } }
        ResponseBody body = response.body(); return body == null ? -1 : body.contentLength();
    }
    private record ResponseCall(Call call, Response response) implements TransportCall {
        @Override public int statusCode() { return response.code(); }
        @Override public long contentLength() { return response.body() == null ? -1 : response.body().contentLength(); }
        @Override public String finalUrl() { return response.request().url().toString(); }
        @Override public String protocol() { return response.protocol().toString(); }
        @Override public Map<String, List<String>> headers() { return response.headers().toMultimap(); }
        @Override public InputStream body() { if (response.body() == null) throw new IllegalStateException("Response has no body"); return response.body().byteStream(); }
        @Override public void cancel() { call.cancel(); }
        @Override public void close() { response.close(); }
    }
}
