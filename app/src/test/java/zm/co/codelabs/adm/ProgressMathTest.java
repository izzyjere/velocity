package zm.co.codelabs.adm;

import static org.junit.Assert.assertEquals;
import org.junit.Test;
import zm.co.codelabs.adm.engine.ProgressMath;

public class ProgressMathTest {
    @Test public void activeTransferNeverLooksComplete() {
        assertEquals(999, ProgressMath.permille(100, 100, false));
        assertEquals(1000, ProgressMath.permille(100, 100, true));
    }

    @Test public void calculationDoesNotOverflowForLargeFiles() {
        assertEquals(500, ProgressMath.permille(Long.MAX_VALUE / 2, Long.MAX_VALUE - 1, false));
    }

    @Test public void progressIsBounded() {
        assertEquals(0, ProgressMath.permille(-10, 100, false));
        assertEquals(999, ProgressMath.permille(200, 100, false));
    }
}
