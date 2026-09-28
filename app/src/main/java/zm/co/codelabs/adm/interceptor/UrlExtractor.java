package zm.co.codelabs.adm.interceptor;

import android.net.Uri;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class UrlExtractor {
    private static final Pattern URL = Pattern.compile("https?://[^\\s<>\"]+", Pattern.CASE_INSENSITIVE);
    private UrlExtractor() { }
    public static List<String> extract(String text) {
        if (text == null) return List.of(); LinkedHashSet<String> result = new LinkedHashSet<>(); Matcher matcher = URL.matcher(text);
        while (matcher.find()) { String candidate = matcher.group().replaceAll("[),.;!?]+$", ""); Uri uri = Uri.parse(candidate); if (uri.getHost() != null && ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))) result.add(uri.toString()); }
        return List.copyOf(result);
    }
}
