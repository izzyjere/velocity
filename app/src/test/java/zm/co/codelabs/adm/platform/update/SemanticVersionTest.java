package zm.co.codelabs.adm.platform.update;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;
import org.junit.Test;

public final class SemanticVersionTest {
    @Test public void comparesReleaseVersionsNumerically() {
        assertTrue(SemanticVersion.parse("v1.10.0").compareTo(SemanticVersion.parse("1.9.9")) > 0);
        assertTrue(SemanticVersion.parse("2.0").compareTo(SemanticVersion.parse("1.99.99")) > 0);
        assertEquals(0, SemanticVersion.parse("v1.2").compareTo(SemanticVersion.parse("1.2.0")));
    }

    @Test public void rejectsNonReleaseTags() {
        assertThrows(IllegalArgumentException.class, () -> SemanticVersion.parse("v1.2.3-beta"));
        assertThrows(IllegalArgumentException.class, () -> SemanticVersion.parse("latest"));
    }
}
