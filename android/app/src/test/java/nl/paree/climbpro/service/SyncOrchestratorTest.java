package nl.paree.climbpro.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

public class SyncOrchestratorTest {

    private static final byte[] PAYLOAD = {1, 2, 3};

    /** REGRESSION: without a connected watch the Strava pull must still run and be reported. */
    @Test
    public void watchUnavailable_pullStillRunsAndReports() {
        AtomicInteger reported = new AtomicInteger(-1);
        SyncOrchestrator orch = new SyncOrchestrator(
                /* stravaAuthorised */ true,
                /* routeSource */ () -> 3,
                /* watch */ new FakeWatch(false, false),
                /* payloadJob */ new FakePayloadJob(PAYLOAD),
                /* connectTimeoutMs */ 0, /* sendTimeoutMs */ 0);

        SyncOrchestrator.Result r = orch.run((changed, ok) -> reported.set(changed));

        assertTrue(r.pullSucceeded);
        assertEquals(3, r.routesChanged);
        assertEquals(3, reported.get());     // progress reported before the send attempt
        assertFalse(r.watchAvailable);
        assertFalse(r.sendAttempted);
    }

    @Test
    public void watchAvailable_buildsSendsAndMarksSent() {
        FakePayloadJob job = new FakePayloadJob(PAYLOAD);
        SyncOrchestrator orch = new SyncOrchestrator(
                true, () -> 1, new FakeWatch(true, true), job, 0, 0);

        SyncOrchestrator.Result r = orch.run((c, ok) -> {});

        assertTrue(r.sendAttempted);
        assertTrue(r.sendSucceeded);
        assertTrue(job.sentCalled);
    }

    @Test
    public void pullThrows_pullFailsButSendStillAttempted() {
        SyncOrchestrator orch = new SyncOrchestrator(
                true,
                () -> { throw new IOException("network down"); },
                new FakeWatch(true, true),
                new FakePayloadJob(PAYLOAD), 0, 0);

        SyncOrchestrator.Result r = orch.run((c, ok) -> {});

        assertFalse(r.pullSucceeded);
        assertTrue(r.sendAttempted);
    }

    @Test
    public void nothingToSend_buildReturnsNull_noSend() {
        SyncOrchestrator orch = new SyncOrchestrator(
                true, () -> 0, new FakeWatch(true, true),
                new FakePayloadJob(null), 0, 0);

        SyncOrchestrator.Result r = orch.run((c, ok) -> {});

        assertTrue(r.watchAvailable);
        assertFalse(r.sendAttempted);
    }

    // --- fakes ---

    private static final class FakeWatch implements SyncOrchestrator.WatchSender {
        private final boolean connected;
        private final boolean sendOk;
        FakeWatch(boolean connected, boolean sendOk) { this.connected = connected; this.sendOk = sendOk; }
        public boolean awaitConnected(long timeoutMs) { return connected; }
        public boolean send(byte[] payload, long timeoutMs) { return sendOk; }
    }

    private static final class FakePayloadJob implements SyncOrchestrator.PayloadJob {
        private final byte[] payload;
        boolean sentCalled = false;
        FakePayloadJob(byte[] payload) { this.payload = payload; }
        public byte[] build() { return payload; }
        public void onSent() { sentCalled = true; }
    }
}
