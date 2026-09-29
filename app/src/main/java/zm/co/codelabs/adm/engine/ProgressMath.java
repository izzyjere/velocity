package zm.co.codelabs.adm.engine;

public final class ProgressMath {
    private ProgressMath() { }

    public static int permille(long completedBytes, long totalBytes, boolean completed) {
        if (totalBytes <= 0) return 0;
        long bounded = Math.max(0, Math.min(completedBytes, totalBytes));
        int value = (int) Math.min(1000, Math.floor((double) bounded * 1000d / (double) totalBytes));
        return completed ? value : Math.min(999, value);
    }
}
