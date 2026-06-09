package nl.paree.climbpro.service;

import java.io.IOException;

/**
 * Pure, framework-free orchestration of a single sync round.
 *
 * Order (offline-first):
 *   1. Strava pull (if authorised) — independent of the watch.
 *   2. Report the pull result via {@link ProgressListener} so the UI can refresh immediately.
 *   3. Opportunistic payload send to the watch: may fail/skip without undoing the pull.
 *
 * Contains no Android types, so it is fully unit-testable.
 */
public final class SyncOrchestrator {

    /** Supplies routes (e.g. {@code StravaRoutesRepository::syncRoutes}). */
    public interface RouteSource {
        /** @return number of newly created or updated routes (0 if none). */
        int syncRoutes() throws IOException;
    }

    /** Connects to and sends to the watch. */
    public interface WatchSender {
        boolean awaitConnected(long timeoutMs);
        boolean send(byte[] payload, long timeoutMs) throws IOException;
    }

    /** Builds the payload for the active mode and commits after a send. */
    public interface PayloadJob {
        /** @return payload bytes, or {@code null} if there is nothing to send. */
        byte[] build() throws IOException;
        /** Called after a successful send (e.g. markSynced). */
        void onSent() throws IOException;
    }

    /** Invoked once the pull is complete (before the send). */
    public interface ProgressListener {
        void onPullComplete(int routesChanged, boolean success);
    }

    public static final class Result {
        public final boolean pullAttempted;
        public final boolean pullSucceeded;
        public final int     routesChanged;
        public final boolean watchAvailable;
        public final boolean sendAttempted;
        public final boolean sendSucceeded;
        public final boolean buildFailed;

        Result(boolean pullAttempted, boolean pullSucceeded, int routesChanged,
               boolean watchAvailable, boolean sendAttempted, boolean sendSucceeded,
               boolean buildFailed) {
            this.pullAttempted  = pullAttempted;
            this.pullSucceeded  = pullSucceeded;
            this.routesChanged  = routesChanged;
            this.watchAvailable = watchAvailable;
            this.sendAttempted  = sendAttempted;
            this.sendSucceeded  = sendSucceeded;
            this.buildFailed    = buildFailed;
        }
    }

    private final boolean     stravaAuthorised;
    private final RouteSource routeSource;
    private final WatchSender watch;
    private final PayloadJob  payloadJob;
    private final long        connectTimeoutMs;
    private final long        sendTimeoutMs;

    public SyncOrchestrator(boolean stravaAuthorised, RouteSource routeSource,
                            WatchSender watch, PayloadJob payloadJob,
                            long connectTimeoutMs, long sendTimeoutMs) {
        this.stravaAuthorised = stravaAuthorised;
        this.routeSource      = routeSource;
        this.watch            = watch;
        this.payloadJob       = payloadJob;
        this.connectTimeoutMs = connectTimeoutMs;
        this.sendTimeoutMs    = sendTimeoutMs;
    }

    public Result run(ProgressListener progress) {
        boolean pullAttempted = stravaAuthorised;
        boolean pullSucceeded = false;
        int     changed       = 0;

        if (stravaAuthorised) {
            try {
                changed = routeSource.syncRoutes();
                pullSucceeded = true;
            } catch (IOException e) {
                pullSucceeded = false; // not fatal — we still try to send
            }
        }
        if (progress != null) progress.onPullComplete(changed, pullSucceeded);

        boolean watchAvailable = watch.awaitConnected(connectTimeoutMs);
        if (!watchAvailable) {
            return new Result(pullAttempted, pullSucceeded, changed, false, false, false, false);
        }

        byte[] payload;
        try {
            payload = payloadJob.build();
        } catch (IOException e) {
            return new Result(pullAttempted, pullSucceeded, changed, true, false, false, true);
        }
        if (payload == null) {
            return new Result(pullAttempted, pullSucceeded, changed, true, false, false, false);
        }

        boolean sent;
        try {
            sent = watch.send(payload, sendTimeoutMs);
        } catch (IOException e) {
            sent = false;
        }
        if (sent) {
            try { payloadJob.onSent(); } catch (IOException ignored) { /* not fatal */ }
        }
        return new Result(pullAttempted, pullSucceeded, changed, true, true, sent, false);
    }
}
