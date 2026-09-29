package zm.co.codelabs.adm;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import zm.co.codelabs.adm.media.YouTubeExtractor;

@RunWith(AndroidJUnit4.class)
public final class YouTubeExtractorInstrumentedTest {
    @Test public void resolvesDirectStreamsFromSharedLink() throws Exception {
        YouTubeExtractor.Resolution resolution = new YouTubeExtractor().resolve("https://youtu.be/dQw4w9WgXcQ");
        List<YouTubeExtractor.Stream> streams = resolution.streams();
        assertFalse("expected at least one downloadable stream", streams.isEmpty());
        for (YouTubeExtractor.Stream stream : streams) {
            assertTrue("stream url must be a direct googlevideo link",
                    stream.url().startsWith("https://") && stream.url().contains("googlevideo.com"));
        }
    }
}
