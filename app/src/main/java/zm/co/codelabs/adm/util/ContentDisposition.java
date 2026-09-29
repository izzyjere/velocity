package zm.co.codelabs.adm.util;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ContentDisposition {
    private static final Pattern UTF8 = Pattern.compile("filename\\*\\s*=\\s*(?:UTF-8'')?([^;]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern PLAIN = Pattern.compile("filename\\s*=\\s*(?:\"([^\"]+)\"|([^;]+))", Pattern.CASE_INSENSITIVE);
    private ContentDisposition() { }
    public static String filename(String header) {
        if (header == null) return null;
        Matcher encoded = UTF8.matcher(header);
        if (encoded.find()) {
            try { return URLDecoder.decode(unquote(Objects.requireNonNull(encoded.group(1)).trim()), "UTF-8"); } catch (Exception ignored) { }
        }
        Matcher plain = PLAIN.matcher(header);
        if (plain.find()) return unquote((Objects.requireNonNull(plain.group(1) != null ? plain.group(1) : plain.group(2))).trim());
        return null;
    }
    private static String unquote(String value) { return value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"") ? value.substring(1, value.length() - 1) : value; }
}
