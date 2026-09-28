package zm.co.codelabs.adm.storage;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

public final class FilenameSanitizer {
    private static final int MAX_BYTES = 180;
    private FilenameSanitizer() { }

    public static String sanitize(String raw) {
        String value = raw == null ? "download" : raw.trim();
        value = value.replaceAll("[\\x00-\\x1f\\x7f/\\\\:*?\"<>|]", "_").replaceAll("[. ]+$", "");
        if (value.isBlank() || value.equals(".") || value.equals("..")) value = "download";
        String upper = value.toUpperCase(Locale.ROOT);
        if (upper.matches("CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9]")) value = "_" + value;
        while (value.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES && value.length() > 1) value = value.substring(0, value.length() - 1);
        return value;
    }
}
