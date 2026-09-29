package zm.co.codelabs.adm.media;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public final class MediaDiscoveryTest {
    @Test public void recognizesDirectMediaFilesAndTypedResources() {
        assertTrue(MediaDiscovery.isDirectMediaUrl("https://cdn.example/video/movie.mp4?token=private"));
        assertTrue(MediaDiscovery.isDirectMediaUrl("https://cdn.example/resource?id=7&mime=video%2Fwebm"));
        assertTrue(MediaDiscovery.isDirectMediaUrl("https://audio.example/play?id=9&type=audio%2Fmpeg"));
        assertTrue(MediaDiscovery.isDirectMediaUrl("https://audio.example/album/song.flac"));
        assertFalse(MediaDiscovery.isDirectMediaUrl("https://example.com/watch/7"));
        assertFalse(MediaDiscovery.isDirectMediaUrl("blob:https://example.com/identifier"));
    }

    @Test public void routesKnownMediaPagesAndRecognizesAuthorizedMediaRequest() {
        assertTrue(MediaDiscovery.isMediaPageUrl("https://www.youtube.com/watch?v=abc"));
        assertFalse(MediaDiscovery.isRestrictedPlatformPage("https://youtu.be/abc"));
        assertFalse(MediaDiscovery.isRestrictedPlatformPage("https://www.youtube.com/watch?v=abc"));
        String media = "https://rr1.googlevideo.com/videoplayback?mime=video%2Fmp4&itag=18&clen=1234";
        assertTrue(MediaDiscovery.isDirectMediaUrl(media));
        assertEquals("Video 360p · rr1.googlevideo.com", MediaDiscovery.label(media));
        assertFalse(MediaDiscovery.isDirectMediaUrl(media + "&drm=widevine"));
    }
}
