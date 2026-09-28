package zm.co.codelabs.adm.transport;

import java.io.IOException;

public final class RangeResponseException extends IOException {
    public RangeResponseException(int statusCode) { super("Server did not honor the requested byte range (HTTP " + statusCode + ")"); }
}
