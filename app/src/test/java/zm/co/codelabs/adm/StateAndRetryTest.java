package zm.co.codelabs.adm;

import static org.junit.Assert.*;
import java.net.UnknownHostException;
import org.junit.Test;
import zm.co.codelabs.adm.engine.RetryPolicy;
import zm.co.codelabs.adm.engine.model.DownloadState;
import zm.co.codelabs.adm.engine.model.ErrorCode;

public class StateAndRetryTest {
    @Test public void stateMachineRejectsUnsafeTransitions() { assertTrue(DownloadState.RUNNING.canTransitionTo(DownloadState.PAUSING)); assertFalse(DownloadState.RUNNING.canTransitionTo(DownloadState.COMPLETED)); }
    @Test public void errorsAreClassifiedDeterministically() { assertEquals(ErrorCode.DNS, RetryPolicy.classify(new UnknownHostException())); assertEquals(ErrorCode.AUTH_REQUIRED, RetryPolicy.classifyHttp(403)); assertEquals(ErrorCode.HTTP_SERVER_TRANSIENT, RetryPolicy.classifyHttp(503)); }
}
