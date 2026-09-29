package zm.co.codelabs.adm.platform.logging;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class AppLogStoreTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test public void redactsUrlsAndCredentialsFromPersistedFailures() throws Exception {
        File directory = temporary.newFolder("diagnostics");
        try (AppLogStore store = new AppLogStore(directory)) {
            store.error("Download #42", new IllegalStateException(
                    "GET https://example.test/file?token=top-secret&exp=123 Authorization: Bearer-secret"));

            String content = store.read();

            assertTrue(content.contains("Download #42"));
            assertTrue(content.contains("IllegalStateException"));
            assertTrue(content.contains("[redacted-url]"));
            assertFalse(content.contains("example.test"));
            assertFalse(content.contains("top-secret"));
            assertFalse(content.contains("Bearer-secret"));
        }
    }

    @Test public void clearRemovesCurrentAndRotatedHistory() throws Exception {
        File directory = temporary.newFolder("diagnostics");
        try (AppLogStore store = new AppLogStore(directory)) {
            String large = "x".repeat(15_000);
            for (int i = 0; i < 40; i++) store.info("Rotation", i + large);
            assertFalse(store.read().isBlank());

            store.clear();

            assertTrue(store.read().isBlank());
        }
    }

    @Test public void notifiesVisibleConsumersAfterAnEventIsPersisted() throws Exception {
        File directory = temporary.newFolder("streaming");
        try (AppLogStore store = new AppLogStore(directory)) {
            CountDownLatch event = new CountDownLatch(1);
            store.addListener(event::countDown);

            store.info("Streaming", "new event");

            assertTrue(event.await(2, TimeUnit.SECONDS));
            assertTrue(store.read().contains("new event"));
        }
    }
}
