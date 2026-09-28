package zm.co.codelabs.adm.engine;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import javax.net.ssl.SSLException;
import zm.co.codelabs.adm.engine.model.ErrorCode;
import zm.co.codelabs.adm.transport.RangeResponseException;
import zm.co.codelabs.adm.storage.DiskFullException;

public final class RetryPolicy {
    private final int maxAttempts;
    public RetryPolicy(int maxAttempts) { this.maxAttempts = Math.max(1, maxAttempts); }
    public boolean shouldRetry(ErrorCode code, int attempt) {
        return attempt < maxAttempts && (code == ErrorCode.NETWORK_TRANSIENT || code == ErrorCode.DNS || code == ErrorCode.HTTP_SERVER_TRANSIENT);
    }
    public long delayMillis(int attempt, long retryAfterMillis) {
        if (retryAfterMillis >= 0) return Math.min(retryAfterMillis, 5 * 60_000L);
        long base = Math.min(60_000L, 1_000L << Math.min(6, Math.max(0, attempt - 1)));
        return base / 2 + (long) (Math.random() * base / 2);
    }
    public static ErrorCode classify(Throwable error) {
        if (error instanceof RangeResponseException) return ErrorCode.RESOURCE_CHANGED;
        if (error instanceof DiskFullException) return ErrorCode.DISK_FULL;
        if (error instanceof SecurityException) return ErrorCode.PERMISSION;
        if (error instanceof UnknownHostException) return ErrorCode.DNS;
        if (error instanceof SSLException) return ErrorCode.TLS;
        if (error instanceof SocketTimeoutException || error instanceof IOException) return ErrorCode.NETWORK_TRANSIENT;
        return ErrorCode.FILESYSTEM;
    }
    public static ErrorCode classifyHttp(int status) {
        if (status == 416) return ErrorCode.RESOURCE_CHANGED;
        if (status == 401 || status == 403) return ErrorCode.AUTH_REQUIRED;
        if (status == 429 || status >= 500) return ErrorCode.HTTP_SERVER_TRANSIENT;
        if (status >= 400) return ErrorCode.HTTP_CLIENT_FATAL;
        return ErrorCode.NONE;
    }
}
