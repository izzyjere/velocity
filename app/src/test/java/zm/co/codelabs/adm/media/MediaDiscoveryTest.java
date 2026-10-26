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
        assertFalse(MediaDiscovery.isDirectMediaUrl("https://video.fna.fbcdn.net/o1/v/track.mp4?_nc_cat=1&bytestart=0&byteend=1024"));
    }

    @Test public void routesKnownMediaPagesAndRecognizesAuthorizedMediaRequest() {
        assertTrue(MediaDiscovery.isMediaPageUrl("https://www.youtube.com/watch?v=abc"));
        assertFalse(MediaDiscovery.isRestrictedPlatformPage("https://youtu.be/abc"));
        assertFalse(MediaDiscovery.isRestrictedPlatformPage("https://www.youtube.com/watch?v=abc"));
        String media = "https://rr1.googlevideo.com/videoplayback?mime=video%2Fmp4&itag=18&clen=1234";
        assertTrue(MediaDiscovery.isDirectMediaUrl(media));
        assertEquals("Video 360p · rr1.googlevideo.com", MediaDiscovery.label(media));
    }

    @Test public void acceptsFullSizeContentImages() {
        assertTrue(MediaDiscovery.isDownloadableImage("https://cdn.example/uploads/2026/beach.jpg", 1920, 1080, "Sunset over the beach article-photo", false));
        assertTrue(MediaDiscovery.isDownloadableImage("https://images.example/photo-123?w=2400&q=80", 2400, 1600, "", false));
        assertTrue(MediaDiscovery.isDownloadableImage("https://cdn.example/gallery/silicon-wafer.webp", 800, 800, "gallery-item", false));
        assertEquals("beach.jpg · 1920×1080 · cdn.example", MediaDiscovery.imageLabel("https://cdn.example/uploads/2026/beach.jpg", 1920, 1080));
        assertEquals("Lion_(Panthera_leo).jpg · 500×354 · thumb.example", MediaDiscovery.imageLabel("https://thumb.example/Lion_%28Panthera_leo%29.jpg", 500, 354));
        assertEquals("Image · 2400×1600 · images.example", MediaDiscovery.imageLabel("https://images.example/photo-123?w=2400", 2400, 1600));
    }

    @Test public void rejectsSmallImagesBannersLogosAndAds() {
        assertFalse(MediaDiscovery.isDownloadableImage("https://cdn.example/thumbs/a.jpg", 150, 150, "", false));
        assertFalse(MediaDiscovery.isDownloadableImage("https://cdn.example/pixel?id=1", 1, 1, "", false));
        assertFalse(MediaDiscovery.isDownloadableImage("https://cdn.example/strip.jpg", 1600, 300, "", false));
        assertFalse(MediaDiscovery.isDownloadableImage("https://cdn.example/creative.jpg", 300, 600, "", false));
        assertFalse(MediaDiscovery.isDownloadableImage("https://cdn.example/brand/site-logo.png", 600, 600, "", false));
        assertFalse(MediaDiscovery.isDownloadableImage("https://cdn.example/a.png", 600, 600, "headerBanner", false));
        assertFalse(MediaDiscovery.isDownloadableImage("https://cdn.example/a.png", 600, 600, "Acme logo", false));
        assertFalse(MediaDiscovery.isDownloadableImage("https://cdn.example/a.png", 600, 600, "ad-slot container", false));
        assertFalse(MediaDiscovery.isDownloadableImage("https://cdn.example/hero.jpg", 1200, 800, "", true));
        assertFalse(MediaDiscovery.isDownloadableImage("https://cdn.example/art.svg", 1200, 800, "", false));
        assertFalse(MediaDiscovery.isDownloadableImage("https://tpc.googlesyndication.com/simgad/1", 600, 500, "", false));
        assertFalse(MediaDiscovery.isDownloadableImage("data:image/png;base64,AAAA", 600, 600, "", false));
    }

    @Test public void recognizesImageRequests() {
        assertTrue(MediaDiscovery.looksLikeImageRequest("https://cdn.example/a.webp", null));
        assertTrue(MediaDiscovery.looksLikeImageRequest("https://cdn.example/img?id=1", "image/avif,image/webp,*/*"));
        assertFalse(MediaDiscovery.looksLikeImageRequest("https://cdn.example/app.js", "*/*"));
    }

    @Test public void recognizesYouTubePages() {
        assertTrue(MediaDiscovery.isYouTubeUrl("https://youtu.be/dQw4w9WgXcQ"));
        assertTrue(MediaDiscovery.isYouTubeUrl("https://www.youtube.com/watch?v=dQw4w9WgXcQ"));
        assertFalse(MediaDiscovery.isYouTubeUrl("https://vimeo.com/12345"));
    }
}
