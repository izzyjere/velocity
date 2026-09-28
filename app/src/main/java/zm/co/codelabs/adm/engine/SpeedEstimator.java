package zm.co.codelabs.adm.engine;

public final class SpeedEstimator {
    private double smoothedBytesPerSecond;
    private long lastBytes;
    private long lastNanos;
    public synchronized double update(long totalBytes, long nowNanos) {
        if (lastNanos != 0 && nowNanos > lastNanos && totalBytes >= lastBytes) {
            double instant = (totalBytes - lastBytes) * 1_000_000_000d / (nowNanos - lastNanos);
            smoothedBytesPerSecond = smoothedBytesPerSecond == 0 ? instant : smoothedBytesPerSecond * 0.72 + instant * 0.28;
        }
        lastBytes = totalBytes; lastNanos = nowNanos;
        return smoothedBytesPerSecond;
    }
    public synchronized long etaSeconds(long remainingBytes) {
        return smoothedBytesPerSecond < 1 ? -1 : (long) Math.ceil(remainingBytes / smoothedBytesPerSecond);
    }
}
