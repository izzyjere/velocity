package zm.co.codelabs.adm.transport;

import java.io.IOException;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

public final class HttpStatusException extends IOException {
    private final int statusCode;
    private final long retryAfterMillis;
    public HttpStatusException(int statusCode, String retryAfter) { super("HTTP " + statusCode); this.statusCode = statusCode; this.retryAfterMillis = parseRetryAfter(retryAfter); }
    public int statusCode() { return statusCode; }
    public long retryAfterMillis() { return retryAfterMillis; }
    private static long parseRetryAfter(String value) {
        if (value == null) return -1;
        try { return Math.max(0, Long.parseLong(value.trim()) * 1000); } catch (NumberFormatException ignored) { }
        try { return Math.max(0, ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - System.currentTimeMillis()); } catch (RuntimeException ignored) { return -1; }
    }
}
