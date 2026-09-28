package zm.co.codelabs.adm.transport;

import java.io.Closeable;
import java.io.IOException;
import zm.co.codelabs.adm.engine.model.DownloadRequest;
import zm.co.codelabs.adm.engine.model.ProbeResult;
import zm.co.codelabs.adm.engine.model.TransferRequest;

public interface TransportClient extends Closeable {
    ProbeResult probe(DownloadRequest request) throws IOException;
    TransportCall open(TransferRequest request) throws IOException;
    @Override default void close() throws IOException { }
}
