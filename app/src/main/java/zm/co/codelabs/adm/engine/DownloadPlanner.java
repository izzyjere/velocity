package zm.co.codelabs.adm.engine;

import java.util.List;
import zm.co.codelabs.adm.engine.model.ByteRange;
import zm.co.codelabs.adm.engine.model.ProbeResult;

public final class DownloadPlanner {
    private static final long MULTIPART_THRESHOLD = 8L * 1024 * 1024;
    public List<ByteRange> plan(ProbeResult probe, int connectionCeiling) {
        if (!probe.safeForMultipart() || probe.contentLength() < MULTIPART_THRESHOLD) return probe.hasKnownLength() ? List.of(new ByteRange(0, probe.contentLength() - 1)) : List.of();
        int ceiling = Math.max(1, Math.min(16, connectionCeiling));
        int initial = Math.min(ceiling, AdaptiveParallelism.initialForSize(probe.contentLength()));
        int usefulChunks = (int) Math.max(1, probe.contentLength() / (8L * 1024 * 1024));
        int chunks = Math.max(initial, Math.min(ceiling * 2, usefulChunks));
        return SegmentScheduler.partition(probe.contentLength(), chunks);
    }
}
