package zm.co.codelabs.adm.engine.model;

import java.util.Map;

public record TransferRequest(String url, Map<String, String> headers, ByteRange range, String ifRange) {
    public TransferRequest {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }
}
