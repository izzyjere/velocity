package zm.co.codelabs.adm.platform.logging;

import android.content.Context;
import androidx.annotation.NonNull;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** Bounded, application-private diagnostics with credential and URL redaction. */
public final class AppLogStore implements AutoCloseable {
    private static final long MAX_FILE_BYTES = 256L * 1024L;
    private static final int MAX_EVENT_CHARS = 16 * 1024;
    private static final Pattern URL = Pattern.compile("(?i)\\bhttps?://\\S+");
    private static final Pattern HEADER_SECRET = Pattern.compile("(?i)(authorization|proxy-authorization|cookie|set-cookie)\\s*[:=]\\s*[^\\r\\n,;]+");
    private static final Pattern QUERY_SECRET = Pattern.compile("(?i)(token|access_token|refresh_token|sig|signature|key|api_key|auth|exp)=([^&\\s]+)");

    private final Object fileLock = new Object();
    private final File current;
    private final File previous;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> new Thread(r, "diagnostic-log"));

    public AppLogStore(@NonNull Context context) {
        this(new File(context.getFilesDir(), "diagnostics"));
    }

    AppLogStore(@NonNull File directory) {
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IllegalStateException("Cannot create diagnostics directory");
        }
        current = new File(directory, "velocity.log");
        previous = new File(directory, "velocity.log.1");
    }

    public void info(String area, String message) { enqueue("INFO", area, message, null); }
    public void warning(String area, String message) { enqueue("WARN", area, message, null); }
    public void error(String area, Throwable error) { enqueue("ERROR", area, error == null ? "Unknown failure" : error.toString(), error); }

    /** Used by the uncaught-exception handler, where asynchronous work may never run. */
    public void errorNow(String area, Throwable error) {
        append(format("ERROR", area, error == null ? "Unknown failure" : error.toString(), error));
    }

    private void enqueue(String level, String area, String message, Throwable error) {
        String event = format(level, area, message, error);
        try { writer.execute(() -> append(event)); } catch (RuntimeException ignored) { }
    }

    private static String format(String level, String area, String message, Throwable error) {
        StringBuilder value = new StringBuilder(1024)
                .append(DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(OffsetDateTime.now()))
                .append(' ').append(level).append(" [").append(clean(area)).append("] ")
                .append(clean(message)).append('\n');
        if (error != null) {
            Throwable cursor = error;
            int causes = 0;
            while (cursor != null && causes++ < 4 && value.length() < MAX_EVENT_CHARS) {
                if (causes > 1) value.append("Caused by: ").append(clean(cursor.toString())).append('\n');
                StackTraceElement[] trace = cursor.getStackTrace();
                for (int i = 0; i < trace.length && i < 24 && value.length() < MAX_EVENT_CHARS; i++) {
                    value.append("  at ").append(clean(trace[i].toString())).append('\n');
                }
                cursor = cursor.getCause();
            }
        }
        if (value.length() > MAX_EVENT_CHARS) value.setLength(MAX_EVENT_CHARS);
        return value.append('\n').toString();
    }

    static String clean(String value) {
        if (value == null) return "";
        String sanitized = HEADER_SECRET.matcher(value).replaceAll("$1: [redacted]");
        sanitized = QUERY_SECRET.matcher(sanitized).replaceAll("$1=[redacted]");
        return URL.matcher(sanitized).replaceAll("[redacted-url]");
    }

    private void append(String event) {
        synchronized (fileLock) {
            try {
                if (current.length() + event.length() * 2L > MAX_FILE_BYTES) rotate();
                try (BufferedWriter output = new BufferedWriter(new FileWriter(current, true))) { output.write(event); }
            } catch (IOException ignored) { }
        }
    }

    private void rotate() throws IOException {
        if (previous.exists() && !previous.delete()) throw new IOException("Cannot remove old diagnostic log");
        if (current.exists() && !current.renameTo(previous)) throw new IOException("Cannot rotate diagnostic log");
    }

    public String read() {
        flush();
        synchronized (fileLock) {
            StringBuilder result = new StringBuilder();
            readInto(previous, result);
            readInto(current, result);
            return result.toString();
        }
    }

    public void clear() {
        flush();
        synchronized (fileLock) {
            if (current.exists()) current.delete();
            if (previous.exists()) previous.delete();
        }
    }

    public void flush() {
        try {
            Future<?> barrier = writer.submit(() -> { });
            barrier.get(5, TimeUnit.SECONDS);
        } catch (Exception ignored) { }
    }

    private static void readInto(File file, StringBuilder result) {
        if (!file.isFile()) return;
        try (BufferedReader input = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = input.readLine()) != null) result.append(line).append('\n');
        } catch (IOException ignored) { }
    }

    @Override public void close() { flush(); writer.shutdown(); }
}
