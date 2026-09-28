package zm.co.codelabs.adm;

import static org.junit.Assert.*;
import org.junit.Test;
import zm.co.codelabs.adm.engine.AdaptiveParallelism;

public class AdaptiveParallelismTest {
    @Test public void scalesWithHysteresisAndBacksOffOnThrottle() {
        AdaptiveParallelism auto = new AdaptiveParallelism(100L * 1024 * 1024, 8);
        assertEquals(4, auto.current()); auto.sample(10, 0, false, 0); assertEquals(4, auto.current());
        auto.sample(12, 0, false, 0); assertEquals(5, auto.current());
        auto.sample(12, .2, true, 1); assertEquals(4, auto.current());
    }
}
