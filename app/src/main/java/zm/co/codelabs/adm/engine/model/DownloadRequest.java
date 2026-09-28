package zm.co.codelabs.adm.engine.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record DownloadRequest(String url, Map<String, String> headers) {
    public DownloadRequest {
        if (url == null || !(url.startsWith("https://") || url.startsWith("http://"))) throw new IllegalArgumentException("HTTP(S) URL required");
        headers = headers == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(headers));
    }
}
