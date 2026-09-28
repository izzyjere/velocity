package zm.co.codelabs.adm.engine;

import java.util.concurrent.locks.LockSupport;

public final class TokenBucket {
    private volatile long bytesPerSecond;
    private double tokens;
    private long lastNanos = System.nanoTime();
    public TokenBucket(long bytesPerSecond) { setRate(bytesPerSecond); }
    public synchronized void setRate(long rate) { bytesPerSecond = Math.max(0, rate); tokens = rate; lastNanos = System.nanoTime(); }
    public void acquire(int bytes) {
        long rate = bytesPerSecond;
        if (rate == 0) return;
        while (true) {
            long wait;
            synchronized (this) {
                long now = System.nanoTime();
                tokens = Math.min(rate, tokens + (now - lastNanos) * rate / 1_000_000_000d); lastNanos = now;
                if (tokens >= bytes) { tokens -= bytes; return; }
                wait = (long) ((bytes - tokens) * 1_000_000_000d / rate);
            }
            LockSupport.parkNanos(Math.min(wait, 100_000_000L));
        }
    }
}
