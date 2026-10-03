package zm.co.codelabs.adm.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
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

    @Test public void reusesPlayableBrowserResponseWithoutAnotherPlayerRequest() throws Exception {
        String json = "{\"videoDetails\":{\"title\":\"Browser title\"},"
                + "\"streamingData\":{\"formats\":[{\"itag\":18,"
                + "\"url\":\"https://rr1.googlevideo.com/videoplayback?itag=18\","
                + "\"mimeType\":\"video/mp4\",\"qualityLabel\":\"360p\"}]}}";
        YouTubeExtractor.BrowserSession session = new YouTubeExtractor.BrowserSession(
                "VISITOR_INFO1_LIVE=value", "visitor", json, "Browser UA");

        YouTubeExtractor.Resolution result = new YouTubeExtractor()
                .resolve("https://www.youtube.com/watch?v=dQw4w9WgXcQ", session);

        assertEquals("Browser title", result.title());
        assertEquals(1, result.streams().size());
        assertEquals("Browser UA", result.streamUserAgent());
    }

    @Test public void protectsMwebStreamsWithVideoBoundPoToken() throws Exception {
        String json = "{\"videoDetails\":{\"title\":\"Protected audio\"},"
                + "\"streamingData\":{\"adaptiveFormats\":[{\"itag\":140,"
                + "\"url\":\"https://rr1.googlevideo.com/videoplayback?c=MWEB&itag=140\","
                + "\"mimeType\":\"audio/mp4\",\"contentLength\":\"6261568\"}]}}";
        YouTubeExtractor.BrowserSession session = new YouTubeExtractor.BrowserSession(
                null, "visitor", json.replace("&itag=140", "&n=raw-value&itag=140"),
                "Browser UA", "transformed-value");

        assertTrue(YouTubeExtractor.requiresWebPoToken(session));
        YouTubeExtractor.Resolution result = new YouTubeExtractor().resolve(
                "https://m.youtube.com/watch?v=Q3XMFsrl4ws", session, "proof-token");

        assertEquals(1, result.streams().size());
        assertTrue(result.streams().get(0).url().contains("pot=proof-token"));
        assertTrue(result.streams().get(0).url().contains("n=transformed-value"));
        assertEquals("Browser UA", result.streamUserAgent());
    }

    @Test public void transformsEachDistinctStreamNValueIndependently() throws Exception {
        String json = "{\"videoDetails\":{\"title\":\"Distinct signatures\"},"
                + "\"streamingData\":{\"adaptiveFormats\":["
                + "{\"itag\":140,\"url\":\"https://rr1.googlevideo.com/videoplayback?c=MWEB&n=audio-raw\",\"mimeType\":\"audio/mp4\"},"
                + "{\"itag\":137,\"url\":\"https://rr1.googlevideo.com/videoplayback?c=MWEB&n=video-raw\",\"mimeType\":\"video/mp4\"}]}}";
        YouTubeExtractor.BrowserSession session = new YouTubeExtractor.BrowserSession(
                null, "visitor", json, "Browser UA");

        assertEquals(Set.of("audio-raw", "video-raw"),
                YouTubeExtractor.rawNValues(session));
        YouTubeExtractor.Resolution result = new YouTubeExtractor().resolve(
                "https://m.youtube.com/watch?v=Q3XMFsrl4ws", session, "proof-token",
                Map.of("audio-raw", "audio-signed", "video-raw", "video-signed"));

        assertTrue(result.streams().get(0).url().contains("n=audio-signed"));
        assertTrue(result.streams().get(1).url().contains("n=video-signed"));
    }

    @Test public void createsYouTubeSessionAuthorizationWithoutExposingCookieValue() {
        String authorization = YouTubeExtractor.authorizationHeader(
                "PREF=x; SAPISID=secret; SID=y", 123L);
        assertEquals("SAPISIDHASH 123_30a006a7c5a295bee1489c54c5b7a28857edecfb",
                authorization);
        assertFalse(authorization.contains("secret"));
    }
    @Test public void attachesPerFormatRequestContext() throws Exception {
        String json = "{\"videoDetails\":{\"title\":\"Browser title\"},"
                + "\"streamingData\":{\"formats\":[{\"itag\":18,"
                + "\"url\":\"https://rr1.googlevideo.com/videoplayback?itag=18\","
                + "\"mimeType\":\"video/mp4\",\"qualityLabel\":\"360p\"}]}}";
        YouTubeExtractor.BrowserSession session = new YouTubeExtractor.BrowserSession(
                null, "visitor", json, "Browser UA");

        YouTubeExtractor.Resolution result = new YouTubeExtractor().resolve(
                "https://www.youtube.com/watch?v=dQw4w9WgXcQ", session);

        Map<String, String> headers = result.streams().get(0).requestHeaders();
        assertEquals("Browser UA", headers.get("User-Agent"));
        assertEquals("https://www.youtube.com", headers.get("Origin"));
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", headers.get("Referer"));
        assertEquals("*/*", headers.get("Accept"));
    }

}
