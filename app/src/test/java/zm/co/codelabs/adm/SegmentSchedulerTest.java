package zm.co.codelabs.adm;

import static org.junit.Assert.*;
import java.util.List;
import org.junit.Test;
import zm.co.codelabs.adm.engine.SegmentScheduler;
import zm.co.codelabs.adm.engine.model.ByteRange;

public class SegmentSchedulerTest {
    @Test public void partitionsEveryByteExactlyOnce() {
        for (long size : new long[]{1, 2, 7, 1000, 1_000_003}) for (int parts = 1; parts <= 16; parts++) {
            List<ByteRange> ranges = SegmentScheduler.partition(size, parts); long cursor = 0, covered = 0;
            for (ByteRange r : ranges) { assertEquals(cursor, r.start()); covered += r.length(); cursor = r.endInclusive() + 1; }
            assertEquals(size, covered); assertEquals(size, cursor);
        }
    }
    @Test public void splitsOnlyLargeUnconsumedTail() {
        List<ByteRange> split = SegmentScheduler.splitRemaining(new ByteRange(0, 20L * 1024 * 1024 - 1), 4L * 1024 * 1024);
        assertEquals(2, split.size()); assertEquals(4L * 1024 * 1024, split.get(0).start());
        assertEquals(split.get(0).endInclusive() + 1, split.get(1).start());
    }
}
