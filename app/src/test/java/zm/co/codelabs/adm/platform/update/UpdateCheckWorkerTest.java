package zm.co.codelabs.adm.platform.update;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class UpdateCheckWorkerTest {
    @Test public void notifiesOnlyForNewerUnannouncedReleases() {
        assertTrue(UpdateCheckWorker.shouldNotify("0.0.5", "0.0.4", null));
        assertFalse(UpdateCheckWorker.shouldNotify("0.0.4", "0.0.4", null));
        assertFalse(UpdateCheckWorker.shouldNotify("0.0.3", "0.0.4", null));
        assertFalse(UpdateCheckWorker.shouldNotify("0.0.5", "0.0.4", "0.0.5"));
        assertTrue(UpdateCheckWorker.shouldNotify("0.0.6", "0.0.4", "0.0.5"));
        assertTrue(UpdateCheckWorker.shouldNotify("v1.0", "0.9.9", "garbage"));
    }
}
