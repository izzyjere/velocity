package zm.co.codelabs.adm.transport;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

public interface TransportCall extends Closeable {
    int statusCode();
    long contentLength();
    String finalUrl();
    String protocol();
    Map<String, List<String>> headers();
    InputStream body();
    void cancel();
    @Override void close() throws IOException;
}
