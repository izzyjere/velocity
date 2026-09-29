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
            "facebook.com", "instagram.com", "soundcloud.com", "bandcamp.com", "peertube.tv",
            "music.apple.com", "deezer.com", "tidal.com", "audiomack.com", "mixcloud.com"
    };
    private static final String[] RESTRICTED_HOSTS = {
            "netflix.com", "spotify.com", "music.apple.com", "deezer.com", "tidal.com"
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

    public static boolean isDirectMediaUrl(String value) {
        URL url = parse(value);
        if (url == null || isRestrictedPlatformPage(value)) return false;
        String query = url.getQuery();
        String decoded = query == null ? "" : decode(query).toLowerCase(Locale.ROOT);
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
