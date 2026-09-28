package zm.co.codelabs.adm.engine.model;

public record ProbeResult(String resolvedUrl, int statusCode, long contentLength, boolean rangeSupported,
                          String etag, String lastModified, String mimeType, String contentDisposition,
                          String contentEncoding, String protocol) {
    public boolean hasKnownLength() { return contentLength >= 0; }
    public boolean safeForMultipart() {
        return rangeSupported && hasKnownLength() && (contentEncoding == null || contentEncoding.isBlank() || "identity".equalsIgnoreCase(contentEncoding));
    }
}
