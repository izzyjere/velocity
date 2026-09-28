package zm.co.codelabs.adm;

import static org.junit.Assert.*;
import org.junit.Test;
import zm.co.codelabs.adm.engine.SpeedEstimator;

public class SpeedEstimatorTest {
    @Test public void smoothsSpeedAndComputesEta() { SpeedEstimator s = new SpeedEstimator(); s.update(0, 1_000_000_000L); double speed = s.update(1_000_000, 2_000_000_000L); assertEquals(1_000_000d, speed, 1); assertEquals(4, s.etaSeconds(4_000_000)); }
}
