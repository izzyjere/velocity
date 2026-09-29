package zm.co.codelabs.adm.media;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
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
import zm.co.codelabs.adm.util.CommonUtils;

/**
 * Resolves a YouTube watch/share URL into directly downloadable progressive and adaptive streams.
 *
 * <p>The extractor talks to YouTube's public Innertube {@code player} endpoint using the ANDROID_VR
 * client context, which returns stream descriptors that already contain a plain {@code url} field
 * (no signature ciphering or PoToken required). This keeps the download engine unchanged: it simply
 * receives a direct {@code googlevideo.com} URL like any other media resource.</p>
 */
public final class YouTubeExtractor {
    /**
     * Publicly known Innertube API key. Paired with the ANDROID_VR client below, which is one of the
     * few Innertube clients that still returns unciphered, PoToken-free stream URLs.
     */
    private static final String INNERTUBE_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8";
    private static final String PLAYER_ENDPOINT =
            "https://www.youtube.com/youtubei/v1/player?key=" + INNERTUBE_KEY;
    private static final String CLIENT_NAME = "ANDROID_VR";
    private static final String CLIENT_VERSION = "1.60.19";
    private static final String USER_AGENT =
            "com.google.android.apps.youtube.vr.oculus/" + CLIENT_VERSION
                    + " (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip";
    /** User-Agent that must accompany downloads of the resolved ANDROID-client stream URLs. */
    public static final String STREAM_USER_AGENT = USER_AGENT;
    private static final String VISITOR_ID_ENDPOINT =
            "https://www.youtube.com/youtubei/v1/visitor_id?key=" + INNERTUBE_KEY;
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final Pattern VIDEO_ID = Pattern.compile("[A-Za-z0-9_-]{11}");

    private final OkHttpClient client;
    private volatile String visitorData;

    public YouTubeExtractor() {
        this(new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .build());
    }

    public YouTubeExtractor(OkHttpClient client) {
        this.client = client;
    }

    /** Browser state captured from the currently visible YouTube page. */
    public record BrowserSession(String cookies, String visitorData, String playerResponse,
                                 String userAgent) {
        public BrowserSession {
            cookies = clean(cookies);
            visitorData = clean(visitorData);
            playerResponse = clean(playerResponse);
            userAgent = clean(userAgent);
        }

        public static BrowserSession empty() {
            return new BrowserSession(null, null, null, null);
        }

        private static String clean(String value) {
            return value == null || value.isBlank() ? null : value;
        }
    }

    /** The resolved title and streams for a YouTube video. */
    public record Resolution(String title, List<Stream> streams, String streamUserAgent) {
        public Resolution(String title, List<Stream> streams) {
            this(title, streams, STREAM_USER_AGENT);
        }
    }

    /** A single downloadable YouTube stream. */
    public record Stream(String url, int itag, String mimeType, String qualityLabel,
                         boolean audioOnly, long contentLength) {
        public String label() {
            String kind = audioOnly ? "Audio" : "Video";
            String quality = qualityLabel == null || qualityLabel.isBlank() ? "" : " " + qualityLabel;
            String container = containerExtension(mimeType);
            String size = contentLength > 0 ? " · " + CommonUtils.humanSize(contentLength) : "";
            return kind + quality + " (" + container + ")" + size;
        }

        public String suggestedFileName(String title) {
            String base = title == null || title.isBlank() ? "youtube-" + itag : title;
            return base + "." + containerExtension(mimeType);
        }
    }

    /** Extracts the 11-character video id from any supported YouTube URL form. */
    public static String videoId(String url) {
        if (url == null) return null;
        String cleaned = url.trim();
        String lower = cleaned.toLowerCase(Locale.ROOT);
        if (!lower.contains("youtu")) return null;
        String[] markers = {"v=", "/shorts/", "/embed/", "/live/", "youtu.be/", "/v/"};
        for (String marker : markers) {
            int index = cleaned.indexOf(marker);
            if (index >= 0) {
                String candidate = cleaned.substring(index + marker.length());
                candidate = firstToken(candidate);
                if (candidate.length() >= 11) candidate = candidate.substring(0, 11);
                if (VIDEO_ID.matcher(candidate).matches()) return candidate;
            }
        }
        return null;
    }

    private static String firstToken(String value) {
        int end = value.length();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '&' || c == '?' || c == '/' || c == '#') { end = i; break; }
        }
        return value.substring(0, end);
    }

    /** Resolves the title and streams available for the given YouTube page URL. */
    public Resolution resolve(String pageUrl) throws IOException {
        return resolve(pageUrl, BrowserSession.empty());
    }

    /** Resolves a page using its live WebView session before falling back to Innertube. */
    public Resolution resolve(String pageUrl, BrowserSession session) throws IOException {
        String videoId = videoId(pageUrl);
        if (videoId == null) throw new IOException("Not a recognizable YouTube link");

        BrowserSession browser = session == null ? BrowserSession.empty() : session;
        List<Stream> pageStreams = parsePlayerResponse(browser.playerResponse());
        if (!pageStreams.isEmpty()) {
            String agent = browser.userAgent() == null ? STREAM_USER_AGENT : browser.userAgent();
            return new Resolution(parseTitle(browser.playerResponse()), pageStreams, agent);
        }

        String visitor = browser.visitorData() == null
                ? ensureVisitorData(false, browser.cookies()) : browser.visitorData();
        String json = requestPlayer(videoId, visitor, browser);
        List<Stream> streams = parsePlayerResponse(json);
        if (streams.isEmpty() && isBotCheck(json)) {
            json = requestPlayer(videoId, ensureVisitorData(true, browser.cookies()), browser);
            streams = parsePlayerResponse(json);
        }
        if (streams.isEmpty()) throw new IOException(playabilityMessage(json));
        return new Resolution(parseTitle(json), streams, STREAM_USER_AGENT);
    }

    private String requestPlayer(String videoId, String visitor, BrowserSession session) throws IOException {
        JSONObject body = requestBody(videoId, visitor);
        Request.Builder request = new Request.Builder()
                .url(PLAYER_ENDPOINT)
                .header("User-Agent", USER_AGENT)
                .header("X-YouTube-Client-Name", "28")
                .header("X-YouTube-Client-Version", CLIENT_VERSION)
                .header("Origin", "https://www.youtube.com")
                .header("Referer", "https://www.youtube.com/");
        addSessionHeaders(request, visitor, session);
        try (Response response = client.newCall(request
                .post(RequestBody.create(body.toString(), JSON)).build()).execute()) {
            if (!response.isSuccessful()) throw new IOException("YouTube responded " + response.code());
            ResponseBody payload = response.body();
            return payload == null ? "" : payload.string();
        }
    }

    private String ensureVisitorData(boolean forceRefresh, String cookies) {
        String cached = visitorData;
        if (cached != null && !forceRefresh) return cached;
        try {
            JSONObject context = new JSONObject().put("client", clientContext());
            JSONObject body = new JSONObject().put("context", context);
            Request.Builder request = new Request.Builder()
                    .url(VISITOR_ID_ENDPOINT)
                    .header("User-Agent", USER_AGENT)
                    .header("Origin", "https://www.youtube.com");
            addSessionHeaders(request, null,
                    new BrowserSession(cookies, null, null, null));
            try (Response response = client.newCall(request
                    .post(RequestBody.create(body.toString(), JSON)).build()).execute()) {
                ResponseBody payload = response.body();
                String json = payload == null ? "" : payload.string();
                String token = new JSONObject(json)
                        .optJSONObject("responseContext")
                        .optString("visitorData", null);
                if (token != null && !token.isBlank()) visitorData = token;
            }
        } catch (Exception ignored) {
        }
        return visitorData;
    }

    private static void addSessionHeaders(Request.Builder request, String visitor,
                                          BrowserSession session) {
        if (visitor != null && !visitor.isBlank()) request.header("X-Goog-Visitor-Id", visitor);
        if (session == null || session.cookies() == null) return;
        request.header("Cookie", session.cookies());
        String authorization = authorizationHeader(session.cookies(), System.currentTimeMillis() / 1000L);
        if (authorization != null) {
            request.header("Authorization", authorization);
            request.header("X-Goog-AuthUser", "0");
            request.header("X-Origin", "https://www.youtube.com");
        }
    }

    static String authorizationHeader(String cookies, long epochSeconds) {
        String sapisid = cookieValue(cookies, "SAPISID");
        if (sapisid == null) sapisid = cookieValue(cookies, "__Secure-3PAPISID");
        if (sapisid == null) return null;
        try {
            String input = epochSeconds + " " + sapisid + " https://www.youtube.com";
            byte[] digest = MessageDigest.getInstance("SHA-1")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) hex.append(String.format(Locale.ROOT, "%02x", value & 0xff));
            return "SAPISIDHASH " + epochSeconds + "_" + hex;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String cookieValue(String cookies, String name) {
        if (cookies == null) return null;
        for (String pair : cookies.split(";")) {
            String value = pair.trim();
            int separator = value.indexOf('=');
            if (separator > 0 && name.equals(value.substring(0, separator))) {
                String result = value.substring(separator + 1);
                return result.isBlank() ? null : result;
            }
        }
        return null;
    }

    private static boolean isBotCheck(String json) {
        try {
            JSONObject status = new JSONObject(json).optJSONObject("playabilityStatus");
            if (status == null) return false;
            String value = (status.optString("status", "") + " " + status.optString("reason", ""))
                    .toLowerCase(Locale.ROOT);
            return value.contains("login_required") || value.contains("not a bot")
                    || value.contains("sign in");
        } catch (Exception ignored) {
            return false;
        }
    }

    /** Extracts the video title from an Innertube player response. Package-visible for testing. */
    public static String parseTitle(String json) {
        try {
            JSONObject details = new JSONObject(json).optJSONObject("videoDetails");
            if (details != null) {
                String title = details.optString("title", "");
                if (!title.isBlank()) return title;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static JSONObject clientContext() throws org.json.JSONException {
        return new JSONObject()
                .put("clientName", CLIENT_NAME)
                .put("clientVersion", CLIENT_VERSION)
                .put("deviceModel", "Quest 3")
                .put("androidSdkVersion", 32)
                .put("hl", "en")
                .put("gl", "US");
    }

    private static JSONObject requestBody(String videoId, String visitor) {
        try {
            JSONObject clientContext = clientContext();
            if (visitor != null && !visitor.isBlank()) clientContext.put("visitorData", visitor);
            JSONObject context = new JSONObject().put("client", clientContext);
            return new JSONObject()
                    .put("context", context)
                    .put("videoId", videoId)
                    .put("contentCheckOk", true)
                    .put("racyCheckOk", true);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Parses the streaming descriptors from an Innertube player response. Package-visible for testing. */
    public static List<Stream> parsePlayerResponse(String json) {
        List<Stream> streams = new ArrayList<>();
        try {
            JSONObject root = new JSONObject(json);
            JSONObject streamingData = root.optJSONObject("streamingData");
            if (streamingData == null) return streams;
            collect(streams, streamingData.optJSONArray("formats"));
            collect(streams, streamingData.optJSONArray("adaptiveFormats"));
        } catch (Exception ignored) {
        }
        return streams;
    }

    private static void collect(List<Stream> streams, JSONArray formats) {
        if (formats == null) return;
        for (int i = 0; i < formats.length(); i++) {
            JSONObject format = formats.optJSONObject(i);
            if (format == null) continue;
            String url = format.optString("url", null);
            if (url == null || url.isBlank()) continue; // ciphered formats are skipped
            String mime = format.optString("mimeType", "");
            boolean audioOnly = mime.startsWith("audio/");
            String quality = format.optString("qualityLabel",
                    audioOnly ? format.optString("audioQuality", "") : format.optString("quality", ""));
            long length = parseLong(format.optString("contentLength", ""));
            streams.add(new Stream(url, format.optInt("itag", 0), mime, quality, audioOnly, length));
        }
    }

    private static String playabilityMessage(String json) {
        if (isBotCheck(json)) {
            return "YouTube could not verify this browser session. Start the video and retry.";
        }
        try {
            JSONObject status = new JSONObject(json).optJSONObject("playabilityStatus");
            if (status != null) {
                String reason = status.optString("reason", "");
                if (!reason.isBlank()) return reason;
            }
        } catch (Exception ignored) {
        }
        return "No downloadable streams were found";
    }

    private static long parseLong(String value) {
        try {
            return value == null || value.isBlank() ? 0 : Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String containerExtension(String mimeType) {
        if (mimeType == null) return "bin";
        String lower = mimeType.toLowerCase(Locale.ROOT);
        if (lower.startsWith("audio/mp4")) return "m4a";
        if (lower.startsWith("audio/webm")) return "weba";
        if (lower.contains("webm")) return "webm";
        if (lower.contains("mp4")) return "mp4";
        if (lower.contains("3gpp")) return "3gp";
        Matcher matcher = Pattern.compile("/([a-z0-9]+)").matcher(lower);
        return matcher.find() ? matcher.group(1) : "bin";
    }

}
