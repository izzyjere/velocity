package zm.co.codelabs.adm.media;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.json.JSONObject;

/**
 * Resolves a Facebook video, reel or watch page into its progressive (muxed audio+video) MP4 files.
 *
 * <p>Facebook's player streams DASH fragments ({@code bytestart}/{@code byteend} ranges of
 * video-only and audio-only tracks) which cannot be saved as playable files. The page markup,
 * however, carries {@code browser_native_sd_url}/{@code browser_native_hd_url} progressive files
 * next to the owning video id, plus an {@code og:video} fallback on the mobile site.</p>
 */
public final class FacebookExtractor {
    private static final String DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/130.0.0.0 Safari/537.36";
    private static final String REFERER = "https://www.facebook.com/";
    private static final String[] HOSTS = {"facebook.com", "fb.watch", "fb.com"};
    private static final Pattern[] VIDEO_ID_PATTERNS = {
            Pattern.compile("/reels?/(\\d{5,})"),
            Pattern.compile("/videos/(?:[^/?#]+/)*(\\d{5,})"),
            Pattern.compile("[?&](?:v|video_id)=(\\d{5,})"),
            Pattern.compile("/watch/?\\?(?:.*&)?v=(\\d{5,})")
    };
    private static final Pattern NATIVE_URL = Pattern.compile(
            "\"(browser_native_(?:sd|hd)_url|playable_url(?:_quality_hd)?)\":(\"(?:[^\"\\\\]|\\\\.)*\")");
    private static final Pattern OWNER_ID = Pattern.compile("\"(?:id|video_id)\":\"(\\d{5,})\"");
    private static final Pattern META = Pattern.compile(
            "<meta\\s+(?:property|name)=\"([^\"]+)\"\\s+content=\"([^\"]*)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern STATS_PREFIX = Pattern.compile(
            "^(?:[\\d.,]+[KMB]?\\s+(?:views?|reactions?|plays?|comments?|shares?)\\s*·?\\s*)+\\|\\s*",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern HEIGHT_TAG = Pattern.compile("(\\d{3,4})p(?:\\b|_|$)");

    private final OkHttpClient client;

    public FacebookExtractor() {
        this(new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .build());
    }

    public FacebookExtractor(OkHttpClient client) {
        this.client = client;
    }

    /** A single progressive Facebook rendition. */
    public record Stream(String url, String quality, int height) {
        public String label() {
            return height > 0 ? "Video " + height + "p (" + quality + ") · MP4" : "Video " + quality + " · MP4";
        }

        public Map<String, String> requestHeaders() {
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Referer", REFERER);
            return headers;
        }

        public String suggestedFileName(String title, String videoId) {
            String base = title == null || title.isBlank()
                    ? "facebook-" + (videoId == null ? "video" : videoId) : title;
            return base + (height > 0 ? " " + height + "p" : "") + ".mp4";
        }
    }

    /** Title and renditions for one Facebook video, best quality first. */
    public record VideoInfo(String videoId, String title, List<Stream> streams) {
        public VideoInfo {
            streams = streams == null ? List.of() : List.copyOf(streams);
        }
    }

    public static boolean isFacebookUrl(String url) {
        if (url == null) return false;
        try {
            String host = new java.net.URL(url).getHost().toLowerCase(Locale.ROOT);
            for (String known : HOSTS) if (host.equals(known) || host.endsWith("." + known)) return true;
        } catch (Exception ignored) {
        }
        return false;
    }

    /** Numeric video id from reel, video and watch URLs; {@code null} for share links. */
    public static String videoId(String url) {
        if (url == null || !isFacebookUrl(url)) return null;
        for (Pattern pattern : VIDEO_ID_PATTERNS) {
            Matcher matcher = pattern.matcher(url);
            if (matcher.find()) return matcher.group(1);
        }
        return null;
    }

    /**
     * Resolves the page, preferring the already-rendered WebView markup and then fetching the page
     * with the browser's cookies so private/group videos visible to the user resolve too.
     */
    public VideoInfo extract(String pageUrl, String renderedHtml, String cookies, String userAgent)
            throws IOException {
        String videoId = videoId(pageUrl);
        VideoInfo info = parsePage(renderedHtml, videoId);
        if (hasHd(info)) return info;
        IOException failure = null;
        // The desktop markup carries both SD and HD; the mobile markup usually only og:video SD.
        for (String agent : new String[]{DESKTOP_USER_AGENT, userAgent}) {
            if (agent == null) continue;
            try {
                VideoInfo fetched = parsePage(fetch(pageUrl, cookies, agent), videoId);
                info = better(info, fetched);
                if (hasHd(info)) return info;
            } catch (IOException e) {
                failure = e;
            }
        }
        if (info != null && !info.streams().isEmpty()) return info;
        if (failure != null) throw failure;
        throw new IOException("No downloadable Facebook video was found on this page");
    }

    private String fetch(String pageUrl, String cookies, String userAgent) throws IOException {
        Request.Builder request = new Request.Builder().url(pageUrl)
                .header("User-Agent", userAgent)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Sec-Fetch-Mode", "navigate")
                .header("Sec-Fetch-Dest", "document")
                .header("Sec-Fetch-Site", "none")
                .header("Upgrade-Insecure-Requests", "1");
        if (cookies != null && !cookies.isBlank()) request.header("Cookie", cookies);
        try (Response response = client.newCall(request.build()).execute()) {
            ResponseBody body = response.body();
            if (!response.isSuccessful() || body == null) {
                throw new IOException("Facebook returned HTTP " + response.code());
            }
            return body.string();
        }
    }

    /** Parses page markup into renditions for {@code videoId} (or the page's main video). */
    public static VideoInfo parsePage(String html, String videoId) {
        if (html == null || html.isEmpty()) return null;
        Map<String, String> meta = metaTags(html);
        if (videoId == null) videoId = videoId(meta.get("og:url"));
        Map<String, Stream> byUrl = new LinkedHashMap<>();
        String firstOwner = null;
        Matcher matcher = NATIVE_URL.matcher(html);
        while (matcher.find()) {
            String url = unescapeJson(matcher.group(2));
            if (url == null || !url.startsWith("http")) continue;
            String owner = ownerAfter(html, matcher.end());
            if (firstOwner == null) firstOwner = owner;
            String wanted = videoId != null ? videoId : firstOwner;
            if (wanted != null && owner != null && !wanted.equals(owner)) continue;
            String key = matcher.group(1);
            String quality = key.contains("hd") ? "HD" : "SD";
            byUrl.putIfAbsent(url, new Stream(url, quality, heightOf(url)));
        }
        if (byUrl.isEmpty()) {
            String og = meta.get("og:video:secure_url");
            if (og == null) og = meta.get("og:video:url");
            if (og == null) og = meta.get("og:video");
            if (og != null && og.startsWith("http")) byUrl.put(og, new Stream(og, "SD", heightOf(og)));
        }
        List<Stream> streams = new ArrayList<>(byUrl.values());
        streams.sort((a, b) -> Integer.compare(rank(b), rank(a)));
        String title = meta.get("og:title");
        if (title == null) title = titleTag(html);
        return new VideoInfo(videoId != null ? videoId : firstOwner, cleanTitle(title), streams);
    }

    private static int rank(Stream stream) {
        return stream.height() > 0 ? stream.height() : "HD".equals(stream.quality()) ? 720 : 360;
    }

    private static boolean hasHd(VideoInfo info) {
        if (info == null) return false;
        for (Stream stream : info.streams()) if ("HD".equals(stream.quality())) return true;
        return false;
    }

    private static VideoInfo better(VideoInfo current, VideoInfo candidate) {
        if (candidate == null || candidate.streams().isEmpty()) return current;
        if (current == null || current.streams().isEmpty()) return candidate;
        return candidate.streams().size() > current.streams().size() ? candidate : current;
    }

    /** The id of the JSON object holding the URL: the first sibling {@code "id"} after it. */
    private static String ownerAfter(String html, int from) {
        int end = Math.min(html.length(), from + 4_000);
        Matcher matcher = OWNER_ID.matcher(html).region(from, end);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static int heightOf(String url) {
        int tag = url.indexOf("tag=");
        if (tag < 0) return 0;
        Matcher matcher = HEIGHT_TAG.matcher(url.substring(tag));
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
    }

    private static Map<String, String> metaTags(String html) {
        Map<String, String> result = new LinkedHashMap<>();
        Matcher matcher = META.matcher(html);
        while (matcher.find()) {
            result.putIfAbsent(matcher.group(1).toLowerCase(Locale.ROOT), decodeEntities(matcher.group(2)));
        }
        return result;
    }

    private static String titleTag(String html) {
        Matcher matcher = Pattern.compile("<title[^>]*>([^<]*)</title>", Pattern.CASE_INSENSITIVE).matcher(html);
        return matcher.find() ? decodeEntities(matcher.group(1)) : null;
    }

    private static String cleanTitle(String title) {
        if (title == null) return null;
        String cleaned = title.replaceAll("\\s+", " ").trim();
        int pipe = cleaned.lastIndexOf(" | ");
        if (pipe > 0 && cleaned.endsWith("Facebook")) cleaned = cleaned.substring(0, pipe).trim();
        // Desktop titles lead with engagement counts: "8.3K views · 2.4K reactions | <caption>".
        cleaned = STATS_PREFIX.matcher(cleaned).replaceFirst("").trim();
        if (cleaned.isEmpty() || cleaned.equalsIgnoreCase("Facebook")) return null;
        if (cleaned.length() > 80) cleaned = cleaned.substring(0, 80).trim();
        return cleaned;
    }

    private static String unescapeJson(String quoted) {
        try {
            return new JSONObject("{\"v\":" + quoted + "}").getString("v");
        } catch (Exception e) {
            return null;
        }
    }

    static String decodeEntities(String value) {
        StringBuilder out = new StringBuilder(value.length());
        int i = 0;
        while (i < value.length()) {
            char c = value.charAt(i);
            int semi = c == '&' ? value.indexOf(';', i) : -1;
            if (semi > i && semi - i <= 10) {
                String entity = value.substring(i + 1, semi);
                String decoded = switch (entity) {
                    case "amp" -> "&";
                    case "lt" -> "<";
                    case "gt" -> ">";
                    case "quot" -> "\"";
                    case "apos" -> "'";
                    default -> numericEntity(entity);
                };
                if (decoded != null) {
                    out.append(decoded);
                    i = semi + 1;
                    continue;
                }
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    private static String numericEntity(String entity) {
        try {
            if (entity.startsWith("#x") || entity.startsWith("#X")) {
                return new String(Character.toChars(Integer.parseInt(entity.substring(2), 16)));
            }
            if (entity.startsWith("#")) return new String(Character.toChars(Integer.parseInt(entity.substring(1))));
        } catch (RuntimeException ignored) {
        }
        return null;
    }
}
