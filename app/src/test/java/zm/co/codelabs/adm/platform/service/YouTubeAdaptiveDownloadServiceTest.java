package zm.co.codelabs.adm.platform.service;

import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.util.concurrent.ExecutionException;
import org.junit.Test;
import zm.co.codelabs.adm.transport.HttpStatusException;

public final class YouTubeAdaptiveDownloadServiceTest {
    @Test public void translatesForbiddenStreamIntoExpiredUrlMessage() {
        Exception failure = new ExecutionException(
                new RuntimeException(new HttpStatusException(403, null)));

        assertEquals("YouTube stream URL expired. Re-open the video and retry.",
                YouTubeAdaptiveDownloadService.failureMessage(failure));
    }

    @Test public void preservesUsefulNonHttpFailureMessage() {
        assertEquals("mux failed",
                YouTubeAdaptiveDownloadService.failureMessage(new IOException("mux failed")));
    }
}
