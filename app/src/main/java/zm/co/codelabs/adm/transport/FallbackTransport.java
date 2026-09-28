package zm.co.codelabs.adm.transport;

import java.io.IOException;
import zm.co.codelabs.adm.engine.model.DownloadRequest;
import zm.co.codelabs.adm.engine.model.ProbeResult;
import zm.co.codelabs.adm.engine.model.TransferRequest;

public final class FallbackTransport implements TransportClient {
    private final TransportClient primary, fallback;
    public FallbackTransport(TransportClient primary, TransportClient fallback) { this.primary = primary; this.fallback = fallback; }
    @Override public ProbeResult probe(DownloadRequest request) throws IOException { try { return primary.probe(request); } catch (IOException | RuntimeException e) { return fallback.probe(request); } }
    @Override public TransportCall open(TransferRequest request) throws IOException { try { return primary.open(request); } catch (IOException | RuntimeException e) { return fallback.open(request); } }
    @Override public void close() throws IOException { try { primary.close(); } finally { fallback.close(); } }
}
