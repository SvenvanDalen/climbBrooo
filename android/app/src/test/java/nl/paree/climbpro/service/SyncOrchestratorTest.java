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

    @Test
    public void buildThrows_marksBuildFailed() {
        SyncOrchestrator orch = new SyncOrchestrator(
                true, () -> 1, new FakeWatch(true, true),
                new SyncOrchestrator.PayloadJob() {
                    public byte[] build() throws java.io.IOException {
                        throw new java.io.IOException("disk error");
                    }
                    public void onSent() {}
                },
                0, 0);

        SyncOrchestrator.Result r = orch.run((c, ok) -> {});

        assertTrue(r.buildFailed);
        assertFalse(r.sendAttempted);
    }

    @Test
    public void sendThrows_countsAsFailedSendAndSkipsCommit() {
        FakePayloadJob job = new FakePayloadJob(PAYLOAD);
        SyncOrchestrator orch = new SyncOrchestrator(true, () -> 0,
                new SyncOrchestrator.WatchSender() {
                    public boolean awaitConnected(long t) { return true; }
                    public boolean send(byte[] p, long t) throws IOException {
                        throw new IOException("bluetooth dropped mid-send");
                    }
                }, job, 0, 0);

        SyncOrchestrator.Result r = orch.run(null);

        assertTrue(r.sendAttempted);
        assertFalse(r.sendSucceeded);
        assertFalse(job.sentCalled);
    }

    @Test
    public void sendReturnsFalse_doesNotCommit() {
        FakePayloadJob job = new FakePayloadJob(PAYLOAD);
        SyncOrchestrator.Result r = new SyncOrchestrator(
                false, () -> 0, new FakeWatch(true, false), job, 0, 0).run(null);
        assertTrue(r.sendAttempted);
        assertFalse(r.sendSucceeded);
        assertFalse(job.sentCalled);
    }

    @Test
    public void commitFailureAfterDeliveryStillReportsSent() {
        SyncOrchestrator.Result r = new SyncOrchestrator(false, () -> 0,
                new FakeWatch(true, true),
                new SyncOrchestrator.PayloadJob() {
                    public byte[] build() { return PAYLOAD; }
                    public void onSent() throws IOException { throw new IOException("disk"); }
                }, 0, 0).run(null);
        assertTrue(r.sendSucceeded);
        assertFalse(r.buildFailed);
    }

    @Test
    public void notAuthorised_skipsPullButReportsProgress() {
        AtomicInteger pulls = new AtomicInteger();
        boolean[] reportedOk = {true};
        AtomicInteger reports = new AtomicInteger();
        SyncOrchestrator.Result r = new SyncOrchestrator(false,
                () -> { pulls.incrementAndGet(); return 5; },
                new FakeWatch(true, true), new FakePayloadJob(PAYLOAD), 0, 0)
                .run((changed, ok) -> { reports.incrementAndGet(); reportedOk[0] = ok; });
        assertEquals(0, pulls.get());
        assertFalse(r.pullAttempted);
        assertFalse(r.pullSucceeded);
        assertEquals(0, r.routesChanged);
        assertEquals(1, reports.get());
        assertFalse(reportedOk[0]);
        assertTrue(r.sendSucceeded);
    }

    @Test
    public void timeoutsArePassedToTheWatch() {
        long[] seen = new long[2];
        new SyncOrchestrator(false, () -> 0, new SyncOrchestrator.WatchSender() {
            public boolean awaitConnected(long t) { seen[0] = t; return true; }
            public boolean send(byte[] p, long t) { seen[1] = t; return true; }
        }, new FakePayloadJob(PAYLOAD), 5_000, 10_000).run(null);
        assertEquals(5_000, seen[0]);
        assertEquals(10_000, seen[1]);
    }

    @Test
    public void watchUnavailable_neverBuildsThePayload() {
        AtomicInteger builds = new AtomicInteger();
        SyncOrchestrator.Result r = new SyncOrchestrator(false, () -> 0,
                new FakeWatch(false, true),
                new SyncOrchestrator.PayloadJob() {
                    public byte[] build() { builds.incrementAndGet(); return PAYLOAD; }
                    public void onSent() {}
                }, 0, 0).run(null);
        assertEquals(0, builds.get());
        assertFalse(r.buildFailed);
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
