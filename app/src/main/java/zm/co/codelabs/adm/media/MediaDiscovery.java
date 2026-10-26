package zm.co.codelabs.adm.media;

import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLDecoder;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Classifies only direct, unprotected media resources that the download engine can store intact.
 */
public final class MediaDiscovery {
    private static final Set<String> MEDIA_EXTENSIONS = Set.of(
            "mp4", "webm", "mkv", "mov", "m4v", "avi", "mp3", "m4a", "aac", "ogg", "oga", "opus",
            "wav", "flac", "alac", "ape", "amr", "wma", "mid", "midi");
    private static final String[] MEDIA_PAGE_HOSTS = {
            "youtube.com", "youtu.be", "vimeo.com", "dailymotion.com", "tiktok.com", "twitch.tv",
            "facebook.com", "fb.watch", "instagram.com", "soundcloud.com", "bandcamp.com", "peertube.tv",
            "music.apple.com", "deezer.com", "tidal.com", "audiomack.com", "mixcloud.com"
    };
    private static final String[] RESTRICTED_HOSTS = {
            "netflix.com", "spotify.com", "music.apple.com", "deezer.com", "tidal.com"
    };
    private static final String[] YOUTUBE_HOSTS = {"youtube.com", "youtu.be", "youtube-nocookie.com"};
    /** Raster formats worth saving; SVG and ICO are almost always logos, icons, or UI chrome. */
    private static final Set<String> IMAGE_EXTENSIONS = Set.of(
            "jpg", "jpeg", "jfif", "png", "webp", "avif", "gif", "bmp", "heic", "heif", "tif", "tiff");
    private static final Set<String> NON_CONTENT_IMAGE_EXTENSIONS = Set.of("svg", "svgz", "ico", "cur");
    /** Images smaller than this on either side are thumbnails, icons, tracking pixels, or spacers. */
    static final int MIN_IMAGE_SIDE = 300;
    /** Strips wider or taller than this ratio are banners, dividers, or skyscraper ads. */
    private static final double MAX_IMAGE_ASPECT_RATIO = 3.2;
    /** Standard IAB ad slot sizes that pass the size filter but are never page content. */
    private static final Set<String> AD_SLOT_SIZES = Set.of(
            "300x250", "336x280", "300x600", "300x1050", "970x250", "970x90", "728x90", "468x60",
            "320x480", "480x320", "250x250", "200x200", "160x600", "120x600");
    /** Matched anywhere in a lowercase hint, so "site-logo" and "headerBanner" are both caught. */
    private static final String[] JUNK_IMAGE_FRAGMENTS = {
            "logo", "banner", "favicon", "sprite", "avatar", "placeholder", "spacer", "watermark",
            "advert", "sponsor", "doubleclick", "tracking", "pixel.gif", "blank.gif", "loading.gif",
            "apple-touch-icon", "emoji", "emoticon", "masthead", "brandmark"
    };
    /** Matched only as whole words to avoid false hits like "uploads" or "silicon". */
    private static final Set<String> JUNK_IMAGE_WORDS = Set.of(
            "ad", "ads", "adv", "icon", "icons", "badge", "badges", "btn", "button", "beacon",
            "brand", "branding", "userpic", "flag", "flags", "rating", "stars");
    private static final String[] AD_HOSTS = {
            "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.com",
            "amazon-adsystem.com", "adnxs.com", "taboola.com", "outbrain.com", "criteo.com", "criteo.net",
            "scorecardresearch.com", "pubmatic.com", "rubiconproject.com", "moatads.com", "adsrvr.org",
            "facebook.com/tr", "gravatar.com"
    };

    private MediaDiscovery() {
    }

    public static boolean isMediaPageUrl(String value) {
        return checkIfExists(value, MEDIA_PAGE_HOSTS);
    }

    private static boolean checkIfExists(String value, String[] arr) {
        URL url = parse(value);
        if (url == null) return false;
        String host = url.getHost().toLowerCase(Locale.ROOT);
        for (String known : arr) if (host.equals(known) || host.endsWith("." + known)) return true;
        return false;
    }

    public static boolean isRestrictedPlatformPage(String value) {
        return checkIfExists(value, RESTRICTED_HOSTS);
    }

    public static boolean isYouTubeUrl(String value) {
        return checkIfExists(value, YOUTUBE_HOSTS);
    }

    public static boolean isDirectMediaUrl(String value) {
        URL url = parse(value);
        if (url == null || isRestrictedPlatformPage(value)) return false;
        String query = url.getQuery();
        String decoded = query == null ? "" : decode(query).toLowerCase(Locale.ROOT);
        if (isRangeFragment(query)) return false;
        String path = url.getPath().toLowerCase(Locale.ROOT);
        int dot = path.lastIndexOf('.');
        if (dot >= 0 && MEDIA_EXTENSIONS.contains(path.substring(dot + 1))) return true;
        if (query == null) return false;
        return decoded.contains("mime=video/") || decoded.contains("mime=audio/")
                || decoded.contains("content_type=video/") || decoded.contains("content_type=audio/")
                || decoded.contains("type=video/") || decoded.contains("type=audio/")
                || decoded.contains("response-content-type=video/") || decoded.contains("response-content-type=audio/")
                || hasMediaFormat(query);
    }
    public static String label(String value) {
        URL url = parse(value);
        if (url == null) return "Media file";
        Map<String, String> query = queryParameters(url.getQuery());
        String type = query.get("mime");
        String itag = query.get("itag");
        if (type != null) {
            String kind = type.toLowerCase(Locale.ROOT).startsWith("audio/") ? "Audio" : "Video";
            String quality = qualityForItag(itag);
            return quality == null ? kind + " · " + url.getHost() : kind + " " + quality + " · " + url.getHost();
        }
        String path = url.getPath();
        int slash = path.lastIndexOf('/');
        String name = slash >= 0 ? path.substring(slash + 1) : path;
        if (name.isBlank()) name = "Media file";
        if (name.length() > 54) name = name.substring(0, 51) + "…";
        return name + " · " + url.getHost();
    }

    /**
     * Decides whether an image rendered on a page is real content rather than a logo, banner, ad,
     * icon, or low-resolution decoration.
     *
     * @param width  intrinsic pixel width of the decoded image
     * @param height intrinsic pixel height of the decoded image
     * @param hints  alt text, class names, ids, and other element/ancestor descriptors, or null
     * @param inPageChrome whether the element sits in a header, nav, footer, or aside region
     */
    public static boolean isDownloadableImage(String value, int width, int height, String hints, boolean inPageChrome) {
        URL url = parse(value);
        if (url == null || inPageChrome || isRestrictedPlatformPage(value)) return false;
        if (width < MIN_IMAGE_SIDE || height < MIN_IMAGE_SIDE) return false;
        double ratio = (double) Math.max(width, height) / Math.min(width, height);
        if (ratio > MAX_IMAGE_ASPECT_RATIO) return false;
        if (AD_SLOT_SIZES.contains(width + "x" + height)) return false;
        String path = url.getPath().toLowerCase(Locale.ROOT);
        String extension = extension(path);
        if (NON_CONTENT_IMAGE_EXTENSIONS.contains(extension)) return false;
        String hostAndPath = url.getHost().toLowerCase(Locale.ROOT) + path;
        for (String host : AD_HOSTS) if (hostAndPath.contains(host)) return false;
        return !looksLikeJunk(path) && !looksLikeJunk(hints);
    }

    /** Cheap pre-filter for intercepted requests, used only to decide when to rescan the page's images. */
    public static boolean looksLikeImageRequest(String value, String accept) {
        URL url = parse(value);
        if (url == null) return false;
        if (accept != null && accept.toLowerCase(Locale.ROOT).startsWith("image/")) return true;
        return IMAGE_EXTENSIONS.contains(extension(url.getPath().toLowerCase(Locale.ROOT)));
    }

    public static String imageLabel(String value, int width, int height) {
        URL url = parse(value);
        if (url == null) return "Image";
        String path = url.getPath();
        String name = decode(path.substring(path.lastIndexOf('/') + 1).replace("+", "%2B"));
        if (name.isBlank() || !IMAGE_EXTENSIONS.contains(extension(name.toLowerCase(Locale.ROOT)))) name = "Image";
        if (name.length() > 40) name = name.substring(0, 37) + "…";
        return name + " · " + width + "×" + height + " · " + url.getHost();
    }

    private static boolean looksLikeJunk(String hint) {
        if (hint == null || hint.isBlank()) return false;
        // Split camelCase before lowercasing so "siteLogoImg" yields the word "logo".
        String spaced = hint.replaceAll("([a-z])([A-Z])", "$1 $2").toLowerCase(Locale.ROOT);
        for (String fragment : JUNK_IMAGE_FRAGMENTS) if (spaced.contains(fragment)) return true;
        for (String word : spaced.split("[^a-z0-9]+")) if (JUNK_IMAGE_WORDS.contains(word)) return true;
        return false;
    }

    private static String extension(String path) {
        int slash = path.lastIndexOf('/');
        int dot = path.lastIndexOf('.');
        return dot > slash ? path.substring(dot + 1) : "";
    }

    /** DASH players (e.g. Facebook) request byte slices of a track; a slice is never a playable file. */
    private static boolean isRangeFragment(String rawQuery) {
        Map<String, String> values = queryParameters(rawQuery);
        return values.containsKey("bytestart") || values.containsKey("byteend");
    }

    private static boolean hasMediaFormat(String rawQuery) {
        Map<String, String> values = queryParameters(rawQuery);
        String format = values.get("format");
        if (format == null) format = values.get("ext");
        return format != null && MEDIA_EXTENSIONS.contains(format.toLowerCase(Locale.ROOT));
    }

    private static Map<String, String> queryParameters(String rawQuery) {
        Map<String, String> result = new LinkedHashMap<>();
        if (rawQuery == null) return result;
        for (String pair : rawQuery.split("&")) {
            int separator = pair.indexOf('=');
            String key = decode(separator < 0 ? pair : pair.substring(0, separator)).toLowerCase(Locale.ROOT);
            String value = decode(separator < 0 ? "" : pair.substring(separator + 1));
            result.putIfAbsent(key, value);
        }
        return result;
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, "UTF-8");
        } catch (Exception e) {
            return value;
        }
    }

    private static String qualityForItag(String itag) {
        if (itag == null) return null;
        return switch (itag) {
            case "17" -> "144p";
            case "18" -> "360p";
            case "22" -> "720p";
            case "37" -> "1080p";
            default -> null;
        };
    }

    private static URL parse(String value) {
        if (value == null || value.length() > 16_384) return null;
        try {
            URL url = new URL(value);
            return ("https".equalsIgnoreCase(url.getProtocol()) || "http".equalsIgnoreCase(url.getProtocol())) && !url.getHost().isBlank() ? url : null;
        } catch (MalformedURLException | RuntimeException e) {
            return null;
        }
    }
}
