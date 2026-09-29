package zm.co.codelabs.adm.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

public final class YouTubeExtractorTest {
    @Test public void extractsVideoIdFromEverySharedForm() {
        assertEquals("dQw4w9WgXcQ", YouTubeExtractor.videoId("https://youtu.be/dQw4w9WgXcQ"));
        assertEquals("dQw4w9WgXcQ", YouTubeExtractor.videoId("https://youtu.be/dQw4w9WgXcQ?t=42"));
        assertEquals("dQw4w9WgXcQ", YouTubeExtractor.videoId("https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=abc"));
        assertEquals("dQw4w9WgXcQ", YouTubeExtractor.videoId("https://m.youtube.com/shorts/dQw4w9WgXcQ"));
        assertEquals("dQw4w9WgXcQ", YouTubeExtractor.videoId("https://www.youtube.com/embed/dQw4w9WgXcQ"));
        assertNull(YouTubeExtractor.videoId("https://vimeo.com/12345678"));
        assertNull(YouTubeExtractor.videoId(null));
    }

    @Test public void parsesProgressiveAndAdaptiveStreamsWithDirectUrls() {
        String json = "{" +
                "\"videoDetails\":{\"title\":\"Never Gonna Give You Up\"}," +
                "\"streamingData\":{" +
                "\"formats\":[{\"itag\":18,\"url\":\"https://rr1.googlevideo.com/videoplayback?itag=18\",\"mimeType\":\"video/mp4; codecs=\\\"avc1\\\"\",\"qualityLabel\":\"360p\",\"contentLength\":\"1048576\"}]," +
                "\"adaptiveFormats\":[" +
                "{\"itag\":140,\"url\":\"https://rr1.googlevideo.com/videoplayback?itag=140\",\"mimeType\":\"audio/mp4; codecs=\\\"mp4a\\\"\",\"audioQuality\":\"AUDIO_QUALITY_MEDIUM\",\"contentLength\":\"524288\"}," +
                "{\"itag\":251,\"signatureCipher\":\"s=abc\",\"mimeType\":\"audio/webm\"}" +
                "]}}";
        List<YouTubeExtractor.Stream> streams = YouTubeExtractor.parsePlayerResponse(json);
        assertEquals(2, streams.size());
        YouTubeExtractor.Stream video = streams.get(0);
        assertEquals(18, video.itag());
        assertFalse(video.audioOnly());
        assertTrue(video.label().contains("360p"));
        assertTrue(video.suggestedFileName("Never Gonna Give You Up").endsWith(".mp4"));
        YouTubeExtractor.Stream audio = streams.get(1);
        assertEquals(140, audio.itag());
        assertTrue(audio.audioOnly());
        assertEquals("Never Gonna Give You Up", YouTubeExtractor.parseTitle(json));
    }

    @Test public void returnsNoStreamsForEmptyOrCipheredResponses() {
        assertTrue(YouTubeExtractor.parsePlayerResponse("{}").isEmpty());
        assertTrue(YouTubeExtractor.parsePlayerResponse("not json").isEmpty());
        String ciphered = "{\"streamingData\":{\"adaptiveFormats\":[{\"itag\":251,\"signatureCipher\":\"s=abc\"}]}}";
        assertTrue(YouTubeExtractor.parsePlayerResponse(ciphered).isEmpty());
    }
}
