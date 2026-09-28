package zm.co.codelabs.adm.engine;

import java.util.ArrayList;
import java.util.List;
import zm.co.codelabs.adm.engine.model.ByteRange;

public final class SegmentScheduler {
    private static final long MIN_SPLIT_TAIL = 2L * 1024 * 1024;
    private SegmentScheduler() { }

    public static List<ByteRange> partition(long totalBytes, int parts) {
        if (totalBytes <= 0 || parts <= 0) throw new IllegalArgumentException();
        int actual = (int) Math.min(totalBytes, parts);
        long base = totalBytes / actual, remainder = totalBytes % actual, cursor = 0;
        List<ByteRange> result = new ArrayList<>(actual);
        for (int i = 0; i < actual; i++) {
            long length = base + (i < remainder ? 1 : 0);
            result.add(new ByteRange(cursor, cursor + length - 1));
            cursor += length;
        }
        return List.copyOf(result);
    }

    public static List<ByteRange> splitRemaining(ByteRange original, long completedBytes) {
        long remainingStart = original.start() + completedBytes;
        long remaining = original.endInclusive() - remainingStart + 1;
        if (remaining < MIN_SPLIT_TAIL * 2) return List.of(new ByteRange(remainingStart, original.endInclusive()));
        long firstEnd = remainingStart + remaining / 2 - 1;
        return List.of(new ByteRange(remainingStart, firstEnd), new ByteRange(firstEnd + 1, original.endInclusive()));
    }
}
