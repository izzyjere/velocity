package zm.co.codelabs.adm.engine.model;

public record ByteRange(long start, long endInclusive) {
    public ByteRange {
        if (start < 0 || endInclusive < start) throw new IllegalArgumentException("Invalid inclusive byte range");
    }
    public long length() { return endInclusive - start + 1; }
    public String headerValue() { return "bytes=" + start + "-" + endInclusive; }
}
