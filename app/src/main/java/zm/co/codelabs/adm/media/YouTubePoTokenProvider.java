package zm.co.codelabs.adm.media;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.annotation.MainThread;
import androidx.javascriptengine.JavaScriptIsolate;
import androidx.javascriptengine.JavaScriptSandbox;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.json.JSONArray;
import org.json.JSONObject;

/** Generates short-lived, video-bound Web Proof-of-Origin tokens using BotGuard. */
public final class YouTubePoTokenProvider implements AutoCloseable {
    private static final String HOME_URL = "https://www.youtube.com/";
    private static final String INTEGRITY_URL = "https://www.youtube.com/api/jnn/v1/GenerateIT";
    private static final String API_KEY = "AIzaSyDyT5W0Jh49F30Pqqtyfdf7pDLFKLJoAnw";
    private static final String REQUEST_KEY = "O43z0dpjhgX20SCx4KAo";
    private static final String INTERFACE = "VelocityPoToken";
    private static final String IFRAME_API_URL = "https://www.youtube.com/iframe_api";
    private static final Pattern PLAYER_ID = Pattern.compile("player\\\\/([A-Za-z0-9_-]+)\\\\/");
    private static final String ATTESTATION_USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36(KHTML, like Gecko)";
    private static final String PLAYER_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36";
    private static final MediaType JSON = MediaType.get("application/json+protobuf");

    private final Context context;
    private final String userAgent;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService network = Executors.newSingleThreadExecutor(r ->
            new Thread(r, "youtube-pot"));
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build();
    private final Map<String, CompletableFuture<String>> pendingTokens = new ConcurrentHashMap<>();
    private final Map<String, String> nTransformCache = new ConcurrentHashMap<>();
    private final Map<String, String> playerJavaScriptCache = new ConcurrentHashMap<>();
    private final Object nTransformLock = new Object();
    private final Object initializationLock = new Object();
    private volatile CompletableFuture<Void> initialization;
    private volatile Instant expiresAt = Instant.EPOCH;
    private volatile WebView webView;
    private volatile boolean closed;

    public YouTubePoTokenProvider(Context context) {
        this.context = context.getApplicationContext();
        this.userAgent = ATTESTATION_USER_AGENT;
    }

    /** May be called from a worker thread. Tokens and challenge responses are never persisted. */
    public String tokenFor(String videoId) throws IOException {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw new IOException("PO token generation cannot block the main thread");
        }
        ensureInitialized();
        CompletableFuture<String> result = new CompletableFuture<>();
        CompletableFuture<String> existing = pendingTokens.putIfAbsent(videoId, result);
        if (existing != null) return await(existing, 15, "PO token generation timed out");
        byte[] identifier = videoId.getBytes(StandardCharsets.UTF_8);
        StringBuilder bytes = new StringBuilder("new Uint8Array([");
        for (int i = 0; i < identifier.length; i++) {
            if (i > 0) bytes.append(',');
            bytes.append(identifier[i] & 0xff);
        }
        bytes.append("]) ");
        String script = "mintPoToken(globalThis.velocityIntegrityToken," + bytes
                + ").then(function(value){" + INTERFACE + ".onToken("
                + JSONObject.quote(videoId) + ",value);},function(e){"
                + INTERFACE + ".onTokenError(" + JSONObject.quote(videoId)
                + ",String(e));});";
        if (!main.post(() -> evaluate(script))) {
            pendingTokens.remove(videoId);
            throw new IOException("Unable to schedule PO token generation");
        }
        return await(result, 15, "PO token generation timed out");
    }

    /** Applies the current YouTube player script's exact throttling transform to a raw n value. */
    public String transformN(String rawN) throws IOException {
        return transformN(rawN, null);
    }

    public String transformN(String rawN, String playerJsUrl) throws IOException {
        if (rawN == null || rawN.isBlank()) return null;
        String resolvedPlayerUrl = resolvePlayerUrl(playerJsUrl);
        String cacheKey = resolvedPlayerUrl + '\n' + rawN;
        String cached = nTransformCache.get(cacheKey);
        if (cached != null) return cached;
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw new IOException("YouTube URL transformation cannot block the main thread");
        }
        try {
            String player = playerJavaScriptCache.get(resolvedPlayerUrl);
            if (player == null) {
                player = get(resolvedPlayerUrl);
                playerJavaScriptCache.put(resolvedPlayerUrl, player);
            }
            String transformed = transformNInSandbox(player, rawN);
            if (transformed == null || transformed.isBlank()) {
                throw new IOException("YouTube URL transformation failed");
            }
            nTransformCache.put(cacheKey, transformed);
            return transformed;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("YouTube URL transformation failed", e);
        }
    }

    /** Executes untrusted player code without DOM or app-process access. */
    private String transformNInSandbox(String player, String rawN) throws Exception {
        synchronized (nTransformLock) {
            if (!JavaScriptSandbox.isSupported()) {
                throw new IOException("Secure JavaScript execution is unavailable");
            }
            try (JavaScriptSandbox sandbox = JavaScriptSandbox.createConnectedInstanceAsync(context)
                    .get(10, TimeUnit.SECONDS);
                 JavaScriptIsolate isolate = sandbox.createIsolate()) {
                if (!sandbox.isFeatureSupported(
                        JavaScriptSandbox.JS_FEATURE_PROVIDE_CONSUME_ARRAY_BUFFER)
                        || !sandbox.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_PROMISE_RETURN)) {
                    throw new IOException("Secure JavaScript data transfer is unavailable");
                }
                byte[] extractor;
                try (InputStream input = context.getAssets().open("youtube_nsig.js")) {
                    extractor = readAll(input);
                }
                isolate.provideNamedData("velocity-extractor", extractor);
                isolate.provideNamedData("velocity-player", player.getBytes(StandardCharsets.UTF_8));
                String script = "(async()=>{const d=b=>{const a=new Uint8Array(b);let s='',i=0,c,x;while(i<a.length){c=a[i++];"
                        + "if(c<128)x=c;else if(c<224)x=(c&31)<<6|a[i++]&63;else if(c<240)x=(c&15)<<12|(a[i++]&63)<<6|a[i++]&63;"
                        + "else x=(c&7)<<18|(a[i++]&63)<<12|(a[i++]&63)<<6|a[i++]&63;s+=String.fromCodePoint(x)}return s};"
                        + "globalThis.structuredClone=globalThis.structuredClone||function(v){return JSON.parse(JSON.stringify(v))};"
                        + "const e=d(await android.consumeNamedDataAsArrayBuffer('velocity-extractor'));"
                        + "const p=d(await android.consumeNamedDataAsArrayBuffer('velocity-player'));"
                        + "(0,eval)(e);return String(globalThis.velocityDecipherN(p,"
                        + JSONObject.quote(rawN) + "));})()";
                return isolate.evaluateJavaScriptAsync(script).get(30, TimeUnit.SECONDS);
            }
        }
    }

    private String resolvePlayerUrl(String supplied) throws IOException {
        String url = supplied;
        if (url == null || url.isBlank()) {
            String iframe = get(IFRAME_API_URL);
            Matcher match = PLAYER_ID.matcher(iframe);
            if (!match.find()) throw new IOException("YouTube player version was unavailable");
            url = "/s/player/" + match.group(1) + "/player_es6.vflset/en_US/base.js";
        }
        if (url.startsWith("//")) url = "https:" + url;
        else if (url.startsWith("/")) url = "https://www.youtube.com" + url;
        Uri uri = Uri.parse(url);
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || !"www.youtube.com".equalsIgnoreCase(uri.getHost())
                || uri.getPath() == null || !uri.getPath().startsWith("/s/player/")) {
            throw new IOException("Untrusted YouTube player URL");
        }
        return uri.toString();
    }

    private void ensureInitialized() throws IOException {
        CompletableFuture<Void> future;
        synchronized (initializationLock) {
            if (closed) throw new IOException("PO token provider is closed");
            if (initialization == null || initialization.isCompletedExceptionally()
                    || Instant.now().isAfter(expiresAt)) {
                closeWebView();
                initialization = new CompletableFuture<>();
                if (!main.post(this::createWebView)) {
                    initialization.completeExceptionally(new IOException("Unable to initialize PO token provider"));
                }
            }
            future = initialization;
        }
        await(future, 35, "PO token initialization timed out");
    }

    @SuppressWarnings("SetJavaScriptEnabled")
    @MainThread
    private void createWebView() {
        try {
            WebView view = new WebView(context);
            WebSettings settings = view.getSettings();
            settings.setJavaScriptEnabled(true);
            settings.setUserAgentString(userAgent);
            settings.setBlockNetworkLoads(true);
            settings.setAllowFileAccess(false);
            settings.setAllowContentAccess(false);
            view.setWebChromeClient(new WebChromeClient());
            view.setWebViewClient(new WebViewClient() {
                private boolean started;
                @Override public void onPageFinished(WebView ignored, String url) {
                    if (started || !"https://www.youtube.com/".equals(url)) return;
                    started = true;
                    network.execute(YouTubePoTokenProvider.this::initializeBotGuard);
                }
            });
            view.addJavascriptInterface(new Bridge(), INTERFACE);
            webView = view;
            String html;
            try (InputStream input = context.getAssets().open("youtube_po_token.html")) {
                html = new String(readAll(input), StandardCharsets.UTF_8);
            }
            String nsig;
            try (InputStream input = context.getAssets().open("youtube_nsig.js")) {
                nsig = new String(readAll(input), StandardCharsets.UTF_8);
            }
            html = html.replace("</head>", "<script>" + nsig + "</script></head>");
            view.loadDataWithBaseURL("https://www.youtube.com", html, "text/html", "utf-8", null);
        } catch (Exception e) {
            failInitialization(e);
        }
    }

    private void initializeBotGuard() {
        try {
            HomepageAttestation attestation = fetchHomepageAttestation();
            String script = "globalThis.yt={config_:{EVENT_ID:"
                    + JSONObject.quote(attestation.eventId()) + "}};runBotGuard("
                    + attestation.challenge() + ").then(function(value){"
                    + INTERFACE + ".onBotGuard(String(value));},function(error){"
                    + INTERFACE + ".onInitializationError(String(error));});";
            if (!main.post(() -> evaluate(script))) throw new IOException("Unable to run BotGuard");
        } catch (Exception e) {
            failInitialization(e);
        }
    }

    private HomepageAttestation fetchHomepageAttestation() throws Exception {
        Request request = new Request.Builder().url(HOME_URL)
                .header("User-Agent", userAgent)
                .header("Accept", "*/*")
                .header("Accept-Language", "en-US,en;q=0.7")
                .build();
        String html;
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("YouTube attestation page responded " + response.code());
            }
            ResponseBody body = response.body();
            if (body == null) throw new IOException("YouTube attestation page was empty");
            html = body.string();
        }

        String eventId = null;
        for (String object : objectArguments(html, "ytcfg.set(")) {
            try {
                String candidate = new JSONObject(object).optString("EVENT_ID", null);
                if (candidate != null && !candidate.isBlank()) {
                    eventId = candidate;
                    break;
                }
            } catch (Exception ignored) {
                // Continue to the next configuration emitted on the same page.
            }
        }
        if (eventId == null) throw new IOException("YouTube attestation event was unavailable");

        JSONObject challenge = null;
        for (String object : objectArguments(html, "window.ytAtN(")) {
            try {
                String responseJson = looseStringProperty(object, "R");
                if (responseJson == null) continue;
                JSONObject candidate = new JSONObject(responseJson).optJSONObject("bgChallenge");
                if (candidate != null) {
                    challenge = canonicalChallenge(candidate);
                    break;
                }
            } catch (Exception ignored) {
                // A page can contain multiple calls; only accept a complete, valid challenge.
            }
        }
        if (challenge == null) throw new IOException("YouTube attestation challenge was unavailable");
        return new HomepageAttestation(eventId, challenge);
    }

    private JSONObject canonicalChallenge(JSONObject source) throws Exception {
        JSONObject wrapped = source.getJSONObject("interpreterUrl");
        String path = wrapped.getString(
                "privateDoNotAccessOrElseTrustedResourceUrlWrappedValue");
        String url = path.startsWith("//") ? "https:" + path : path;
        Uri uri = Uri.parse(url);
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || !"www.google.com".equalsIgnoreCase(uri.getHost())) {
            throw new IOException("Untrusted BotGuard interpreter origin");
        }
        Request request = new Request.Builder().url(url)
                .header("User-Agent", userAgent).header("Referer", HOME_URL).build();
        String interpreter;
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("BotGuard interpreter responded " + response.code());
            }
            ResponseBody body = response.body();
            if (body == null) throw new IOException("BotGuard interpreter was empty");
            interpreter = body.string();
        }
        if (interpreter.isBlank()) throw new IOException("BotGuard interpreter was empty");
        return new JSONObject().put("interpreterJavascript", interpreter)
                .put("program", source.getString("program"))
                .put("globalName", source.getString("globalName"));
    }

    static List<String> objectArguments(String source, String marker) {
        List<String> values = new ArrayList<>();
        int searchFrom = 0;
        while (searchFrom < source.length()) {
            int markerAt = source.indexOf(marker, searchFrom);
            if (markerAt < 0) break;
            int start = source.indexOf('{', markerAt + marker.length());
            if (start < 0) break;
            int depth = 0;
            char quote = 0;
            boolean escaped = false;
            int end = -1;
            for (int i = start; i < source.length(); i++) {
                char value = source.charAt(i);
                if (quote != 0) {
                    if (escaped) escaped = false;
                    else if (value == '\\') escaped = true;
                    else if (value == quote) quote = 0;
                    continue;
                }
                if (value == '\'' || value == '"') quote = value;
                else if (value == '{') depth++;
                else if (value == '}' && --depth == 0) {
                    end = i + 1;
                    break;
                }
            }
            if (end > start) values.add(source.substring(start, end));
            searchFrom = end > start ? end : start + 1;
        }
        return values;
    }

    static String looseStringProperty(String object, String property) throws IOException {
        int colon = findPropertyColon(object, property);
        if (colon < 0) return null;
        int position = colon + 1;
        while (position < object.length() && Character.isWhitespace(object.charAt(position))) position++;
        if (position >= object.length() || (object.charAt(position) != '\''
                && object.charAt(position) != '"')) return null;
        char quote = object.charAt(position++);
        StringBuilder value = new StringBuilder();
        while (position < object.length()) {
            char current = object.charAt(position++);
            if (current == quote) return value.toString();
            if (current != '\\') {
                value.append(current);
                continue;
            }
            if (position >= object.length()) throw new IOException("Invalid JavaScript string escape");
            char escaped = object.charAt(position++);
            switch (escaped) {
                case 'b' -> value.append('\b');
                case 'f' -> value.append('\f');
                case 'n' -> value.append('\n');
                case 'r' -> value.append('\r');
                case 't' -> value.append('\t');
                case 'v' -> value.append('\u000b');
                case '\\', '/', '\'', '"' -> value.append(escaped);
                case 'x' -> value.append((char) parseHex(object, position, 2));
                case 'u' -> value.append((char) parseHex(object, position, 4));
                default -> throw new IOException("Unsupported JavaScript string escape");
            }
            if (escaped == 'x') position += 2;
            else if (escaped == 'u') position += 4;
        }
        throw new IOException("Unterminated JavaScript string");
    }

    private static int findPropertyColon(String object, String property) {
        String[] forms = {"'" + property + "'", "\"" + property + "\"", property};
        for (String form : forms) {
            int at = object.indexOf(form);
            if (at < 0) continue;
            int position = at + form.length();
            while (position < object.length() && Character.isWhitespace(object.charAt(position))) position++;
            if (position < object.length() && object.charAt(position) == ':') return position;
        }
        return -1;
    }

    private static int parseHex(String value, int offset, int length) throws IOException {
        if (offset + length > value.length()) throw new IOException("Truncated hexadecimal escape");
        try {
            return Integer.parseInt(value.substring(offset, offset + length), 16);
        } catch (NumberFormatException e) {
            throw new IOException("Invalid hexadecimal escape", e);
        }
    }

    private String post(String url, String body) throws IOException {
        Request.Builder request = new Request.Builder().url(url)
                .header("User-Agent", userAgent)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json+protobuf")
                .header("x-goog-api-key", API_KEY)
                .header("x-user-agent", "grpc-web-javascript/0.1")
                .post(RequestBody.create(body, JSON));
        try (Response response = client.newCall(request.build()).execute()) {
            if (!response.isSuccessful()) throw new IOException("PO service responded " + response.code());
            ResponseBody payload = response.body();
            if (payload == null) throw new IOException("PO service returned an empty response");
            return payload.string();
        }
    }

    private String get(String url) throws IOException {
        Request request = new Request.Builder().url(url).header("User-Agent", PLAYER_USER_AGENT).build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) throw new IOException("YouTube resource responded " + response.code());
            ResponseBody body = response.body();
            if (body == null) throw new IOException("YouTube resource was empty");
            return body.string();
        }
    }

    private static byte[] decodeWebSafe(String value) {
        String normalized = value.replace('-', '+').replace('_', '/').replace('.', '=');
        int remainder = normalized.length() % 4;
        if (remainder != 0) normalized += "=".repeat(4 - remainder);
        return Base64.decode(normalized, Base64.DEFAULT);
    }

    private static byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        int read;
        while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
        return output.toByteArray();
    }

    private void evaluate(String script) {
        WebView view = webView;
        if (!closed && view != null) view.evaluateJavascript(script, null);
    }

    private void failInitialization(Throwable error) {
        CompletableFuture<Void> future = initialization;
        if (future != null) future.completeExceptionally(error);
    }

    private static <T> T await(CompletableFuture<T> future, long seconds, String timeoutMessage)
            throws IOException {
        try {
            return future.get(seconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new IOException(timeoutMessage, e);
        } catch (Exception e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof IOException io) throw io;
            throw new IOException("Unable to generate YouTube PO token", cause);
        }
    }

    private final class Bridge {
        @JavascriptInterface public void onBotGuard(String botGuardResponse) {
            network.execute(() -> {
                try {
                    String body = post(INTEGRITY_URL, new JSONArray()
                            .put(REQUEST_KEY).put(botGuardResponse).toString());
                    JSONArray values = new JSONArray(body);
                    byte[] integrity = decodeWebSafe(values.getString(0));
                    long lifetime = values.optLong(1, 3600);
                    StringBuilder bytes = new StringBuilder("new Uint8Array([");
                    for (int i = 0; i < integrity.length; i++) {
                        if (i > 0) bytes.append(',');
                        bytes.append(integrity[i] & 0xff);
                    }
                    bytes.append("])");
                    expiresAt = Instant.now().plusSeconds(Math.max(60, lifetime - 600));
                    if (!main.post(() -> evaluate("globalThis.velocityIntegrityToken=" + bytes
                            + ";" + INTERFACE + ".onReady();"))) {
                        throw new IOException("Unable to finish PO token initialization");
                    }
                } catch (Exception e) {
                    failInitialization(e);
                }
            });
        }

        @JavascriptInterface public void onReady() {
            CompletableFuture<Void> future = initialization;
            if (future != null) future.complete(null);
        }

        @JavascriptInterface public void onInitializationError(String ignored) {
            failInitialization(new IOException("BotGuard initialization failed"));
        }

        @JavascriptInterface public void onToken(String identifier, String csv) {
            CompletableFuture<String> future = pendingTokens.remove(identifier);
            if (future == null) return;
            try {
                String[] values = csv.split(",");
                byte[] token = new byte[values.length];
                for (int i = 0; i < values.length; i++) token[i] = (byte) Integer.parseInt(values[i]);
                future.complete(Base64.encodeToString(token, Base64.URL_SAFE | Base64.NO_WRAP));
            } catch (Exception e) {
                future.completeExceptionally(new IOException("Invalid PO token result", e));
            }
        }

        @JavascriptInterface public void onTokenError(String identifier, String ignored) {
            CompletableFuture<String> future = pendingTokens.remove(identifier);
            if (future != null) future.completeExceptionally(new IOException("PO token minting failed"));
        }

    }

    private void closeWebView() {
        main.post(() -> {
            WebView view = webView;
            webView = null;
            if (view != null) {
                view.removeJavascriptInterface(INTERFACE);
                view.stopLoading();
                view.loadUrl("about:blank");
                view.removeAllViews();
                view.destroy();
            }
        });
    }

    @Override public void close() {
        closed = true;
        pendingTokens.values().forEach(future ->
                future.completeExceptionally(new IOException("PO token provider closed")));
        pendingTokens.clear();
        closeWebView();
        network.shutdownNow();
    }

    private record HomepageAttestation(String eventId, JSONObject challenge) {}
}
