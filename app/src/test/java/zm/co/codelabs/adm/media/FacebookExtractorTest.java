package zm.co.codelabs.adm.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

public final class FacebookExtractorTest {
    private static final String SD = "https:\\/\\/video.fna.fbcdn.net\\/o1\\/v\\/sd.mp4?_nc_cat=1&bitrate=314721&tag=progressive_h264-basic-gen2_360p";
    private static final String HD = "https:\\/\\/video.fna.fbcdn.net\\/o1\\/v\\/hd.mp4?_nc_cat=1&bitrate=1019735&tag=progressive_h264-basic-gen2_720p";
    private static final String OTHER = "https:\\/\\/video.fna.fbcdn.net\\/o1\\/v\\/other.mp4?tag=dash_baseline_3_v1";

    @Test public void extractsVideoIdsFromSupportedUrls() {
        assertEquals("1424981898987786", FacebookExtractor.videoId("https://www.facebook.com/reel/1424981898987786/?rdid=x"));
        assertEquals("1424981898987786", FacebookExtractor.videoId("https://web.facebook.com/reel/1424981898987786"));
        assertEquals("10153231379946729", FacebookExtractor.videoId("https://www.facebook.com/somepage/videos/10153231379946729/"));
        assertEquals("10153231379946729", FacebookExtractor.videoId("https://m.facebook.com/watch/?v=10153231379946729&ref=sharing"));
        assertNull(FacebookExtractor.videoId("https://www.facebook.com/share/r/14vAJsKvJ9x/"));
        assertNull(FacebookExtractor.videoId("https://www.youtube.com/watch?v=10153231379946729"));
        assertTrue(FacebookExtractor.isFacebookUrl("https://fb.watch/abc/"));
        assertFalse(FacebookExtractor.isFacebookUrl("https://notfacebook.com/reel/1"));
    }

    @Test public void picksProgressiveFilesOwnedByTheRequestedVideo() {
        String html = "<script>{\"dash_manifest_url\":null,\"browser_native_sd_url\":\"" + OTHER
                + "\",\"browser_native_hd_url\":null,\"id\":\"999999999\"}"
                + "{\"browser_native_sd_url\":\"" + SD + "\",\"browser_native_hd_url\":\"" + HD
                + "\",\"id\":\"1424981898987786\",\"is_gaming_video\":false}</script>";
        FacebookExtractor.VideoInfo info = FacebookExtractor.parsePage(html, "1424981898987786");
        List<FacebookExtractor.Stream> streams = info.streams();
        assertEquals(2, streams.size());
        assertEquals("https://video.fna.fbcdn.net/o1/v/hd.mp4?_nc_cat=1&bitrate=1019735&tag=progressive_h264-basic-gen2_720p", streams.get(0).url());
        assertEquals(720, streams.get(0).height());
        assertEquals("HD", streams.get(0).quality());
        assertEquals(360, streams.get(1).height());
        assertEquals("Video 720p (HD) · MP4", streams.get(0).label());
    }

    @Test public void fallsBackToOpenGraphVideoAndTitle() {
        String html = "<head><title>Ignored</title><meta property=\"og:title\" content=\"I&#x2019;m tired &amp; done | Workday Struggles | Facebook\" />"
                + "<meta property=\"og:url\" content=\"https://www.facebook.com/reel/1424981898987786/\" />"
                + "<meta property=\"og:video\" content=\"https://video.fna.fbcdn.net/sd.mp4?a=1&amp;tag=progressive_h264-basic-gen2_360p\" /></head>";
        FacebookExtractor.VideoInfo info = FacebookExtractor.parsePage(html, null);
        assertEquals("1424981898987786", info.videoId());
        assertEquals("I’m tired & done | Workday Struggles", info.title());
        assertEquals(1, info.streams().size());
        assertEquals("https://video.fna.fbcdn.net/sd.mp4?a=1&tag=progressive_h264-basic-gen2_360p", info.streams().get(0).url());
        assertEquals("I’m tired & done | Workday Struggles 360p.mp4",
                info.streams().get(0).suggestedFileName(info.title(), info.videoId()));
    }

    @Test public void stripsDesktopEngagementPrefixFromTitle() {
        String html = "<meta property=\"og:title\" content=\"8.3K views &#xb7; 2.4K reactions | Pay my bills | Workday Struggles | Facebook\" />";
        assertEquals("Pay my bills | Workday Struggles", FacebookExtractor.parsePage(html, null).title());
    }

    @Test public void emptyMarkupYieldsNoStreams() {
        assertNull(FacebookExtractor.parsePage(null, "1"));
        assertTrue(FacebookExtractor.parsePage("<html></html>", "1").streams().isEmpty());
    }
}
