package zm.co.codelabs.adm.engine;

public final class AdaptiveParallelism {
    private int current;
    private final int ceiling;
    private double bestThroughput;
    private int positiveWindows;
    private int weakWindows;

    public AdaptiveParallelism(long totalBytes, int ceiling) {
        this.ceiling = Math.max(1, Math.min(16, ceiling));
        this.current = Math.min(this.ceiling, initialForSize(totalBytes));
    }

    public static int initialForSize(long bytes) {
        if (bytes < 8L * 1024 * 1024) return 1;
        if (bytes < 64L * 1024 * 1024) return 2;
        if (bytes < 512L * 1024 * 1024) return 4;
        return 6;
    }

    public synchronized int sample(double bytesPerSecond, double failureRate, boolean throttled, int stalledWorkers) {
        if (throttled || failureRate >= 0.15 || stalledWorkers > Math.max(1, current / 3)) {
            weakWindows = 0; positiveWindows = 0; current = Math.max(1, current - 1); return current;
        }
        if (bestThroughput == 0 || bytesPerSecond > bestThroughput * 1.08) {
            bestThroughput = Math.max(bestThroughput, bytesPerSecond);
            positiveWindows++;
            weakWindows = 0;
            if (positiveWindows >= 2 && current < ceiling) { current++; positiveWindows = 0; }
        } else if (bytesPerSecond < bestThroughput * 0.92) {
            weakWindows++;
            positiveWindows = 0;
            if (weakWindows >= 2 && current > 1) { current--; weakWindows = 0; }
        }
        return current;
    }
    public synchronized int current() { return current; }
}
