package nl.paree.climbpro.service;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;
import androidx.work.Data;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.fasterxml.jackson.databind.ObjectMapper;

import nl.paree.climbpro.connectiq.ConnectIqClient;
import nl.paree.climbpro.data.route.ClimbAttemptRepository;
import nl.paree.climbpro.data.route.RouteRepository;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.data.strava.StravaAuthRepository;
import nl.paree.climbpro.data.strava.StravaRoutesRepository;
import nl.paree.climbpro.data.sync.SyncState;
import nl.paree.climbpro.data.sync.SyncStateRepository;

import java.io.IOException;

/**
 * WorkManager {@link Worker} that:
 *   1. Pulls latest Strava routes.
 *   2. Builds and sends the appropriate payload to the watch.
 *
 * Registered in SyncScheduler with CHARGING + UNMETERED constraints and a
 * periodic interval of 6 hours; also triggered manually from SettingsViewModel.
 */
public final class RouteSyncWorker extends Worker {

    private static final String TAG          = "RouteSyncWorker";
    public  static final String PREF_MODE    = "sync_mode";
    public  static final String MODE_ROUTE   = "route";
    public  static final String MODE_RADIUS  = "radius";
    public  static final String PREF_ROUTE_ID = "active_route_id";
    public  static final String PREF_RADIUS_M = "radius_metres";
    public  static final String PREF_LAST_LAT = "last_lat";
    public  static final String PREF_LAST_LON = "last_lon";

    public  static final String KEY_PULL_DONE  = "pull_done";
    public  static final String KEY_CHANGED    = "routes_changed";
    public  static final String KEY_WATCH_SENT = "watch_sent";

    private static final int DEFAULT_RADIUS_M = 30_000;

    public RouteSyncWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context ctx = getApplicationContext();

        RouteRepository      routeRepo      = new RouteRepository(ctx);
        ClimbAttemptRepository attemptRepo  = new ClimbAttemptRepository(ctx);
        SyncStateRepository  syncStateRepo  = new SyncStateRepository(ctx);
        StravaAuthRepository authRepo        = new StravaAuthRepository(ctx);
        ObjectMapper         mapper          = new ObjectMapper();
        ClimbPayloadBuilder  payloadBuilder  = new ClimbPayloadBuilder(mapper);
        ConnectIqClient      ciqClient       =
                ((nl.paree.climbpro.ClimbProApplication) ctx).connectIqClient();
        SharedPreferences    prefs           = PreferenceManager.getDefaultSharedPreferences(ctx);

        nl.paree.climbpro.data.rider.RiderProfileRepository riderRepo =
                new nl.paree.climbpro.data.rider.RiderProfileRepository(ctx);
        nl.paree.climbpro.domain.power.RiderProfile profile = riderRepo.load();

        boolean authorised = authRepo.isAuthorised();

        SyncOrchestrator.RouteSource pull = () ->
                authorised
                        ? new StravaRoutesRepository(authRepo, routeRepo).syncRoutes()
                        : 0;

        SyncOrchestrator.WatchSender sender = new SyncOrchestrator.WatchSender() {
            @Override public boolean awaitConnected(long timeoutMs) {
                long deadline = System.currentTimeMillis() + timeoutMs;
                while (!ciqClient.isConnected() && System.currentTimeMillis() < deadline) {
                    try { Thread.sleep(250); } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
                return ciqClient.isConnected();
            }
            @Override public boolean send(byte[] payload, long timeoutMs) {
                return ciqClient.sendPayloadBlocking(payload, timeoutMs);
            }
        };

        SyncOrchestrator.PayloadJob job = buildPayloadJob(
                prefs, routeRepo, syncStateRepo, payloadBuilder, profile, attemptRepo);

        SyncOrchestrator orchestrator = new SyncOrchestrator(
                authorised, pull, sender, job,
                /* connectTimeoutMs */ 5_000, /* sendTimeoutMs */ 10_000);

        SyncOrchestrator.Result r = orchestrator.run((changed, ok) ->
                setProgressAsync(new Data.Builder()
                        .putBoolean(KEY_PULL_DONE, true)
                        .putInt(KEY_CHANGED, changed)
                        .build()));

        Data output = new Data.Builder()
                .putBoolean(KEY_PULL_DONE, true)
                .putInt(KEY_CHANGED, r.routesChanged)
                .putBoolean(KEY_WATCH_SENT, r.sendSucceeded)
                .build();

        boolean shouldRetry = (r.pullAttempted && !r.pullSucceeded)
                || (r.sendAttempted && !r.sendSucceeded)
                || r.buildFailed;
        if (shouldRetry) {
            Log.w(TAG, "Sync incomplete — will retry (pull=" + r.pullSucceeded
                    + ", sendAttempted=" + r.sendAttempted + ", sent=" + r.sendSucceeded + ")");
            return Result.retry();
        }
        return Result.success(output);
    }

    /**
     * Builds the appropriate {@link SyncOrchestrator.PayloadJob} for the active mode.
     * {@link SyncOrchestrator.PayloadJob#build()} returns {@code null} when there is
     * nothing to send (no route selected, or unchanged).
     */
    private SyncOrchestrator.PayloadJob buildPayloadJob(
            SharedPreferences prefs, RouteRepository routeRepo,
            SyncStateRepository syncStateRepo, ClimbPayloadBuilder payloadBuilder,
            nl.paree.climbpro.domain.power.RiderProfile profile,
            ClimbAttemptRepository attemptRepo) {

        String mode = prefs.getString(PREF_MODE, MODE_ROUTE);

        if (MODE_RADIUS.equals(mode)) {
            return new SyncOrchestrator.PayloadJob() {
                @Override public byte[] build() throws IOException {
                    double lat = Double.longBitsToDouble(
                            prefs.getLong(PREF_LAST_LAT, Double.doubleToLongBits(0)));
                    double lon = Double.longBitsToDouble(
                            prefs.getLong(PREF_LAST_LON, Double.doubleToLongBits(0)));
                    double radiusM = prefs.getInt(PREF_RADIUS_M, DEFAULT_RADIUS_M);
                    RadiusModeAssembler assembler =
                            new RadiusModeAssembler(routeRepo, payloadBuilder);
                    byte[] payload = assembler.assemble(lat, lon, radiusM);
                    if (assembler.wasTruncated()) {
                        Log.w(TAG, "Radius payload was truncated — some climbs omitted");
                    }
                    return payload;
                }
                @Override public void onSent() { /* radius mode has no per-route sync state */ }
            };
        }

        return new SyncOrchestrator.PayloadJob() {
            @Override public byte[] build() throws IOException {
                String routeId = prefs.getString(PREF_ROUTE_ID, null);
                if (routeId == null) {
                    Log.i(TAG, "No active route selected — nothing to send");
                    return null;
                }
                SyncState state = syncStateRepo.get(routeId);
                StoredRoute route = routeRepo.loadRoute(routeId);
                String wantHash = route.sourceHash + "|" + profile.signature();
                if (SyncState.Status.SYNCED.equals(state.status)
                        && wantHash.equals(state.lastSyncedHash)) {
                    Log.i(TAG, "Route " + routeId + " unchanged (incl. profile), no re-sync needed");
                    return null;
                }
                int[][] plan = nl.paree.climbpro.service.RoutePacingPlanner.plan(route, profile);
                int[][] refPlan = nl.paree.climbpro.service.CombinedRefTimePlanner.plan(
                        route, attemptRepo.loadAll());
                byte[] payload = payloadBuilder.buildRoutePayload(route, plan, refPlan);
                if (payload.length > PayloadBudget.MAX_BYTES) {
                    Log.e(TAG, "Payload exceeds budget: " + payload.length + " bytes — skipping send");
                    return null;
                }
                return payload;
            }
            @Override public void onSent() throws IOException {
                String routeId = prefs.getString(PREF_ROUTE_ID, null);
                if (routeId != null) {
                    StoredRoute route = routeRepo.loadRoute(routeId);
                    syncStateRepo.markSynced(routeId, route.sourceHash + "|" + profile.signature());
                }
            }
        };
    }
}
