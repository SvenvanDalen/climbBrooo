package nl.paree.climbpro.service;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.fasterxml.jackson.databind.ObjectMapper;

import nl.paree.climbpro.connectiq.ConnectIqClient;
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

    private static final int DEFAULT_RADIUS_M = 30_000;

    public RouteSyncWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context ctx = getApplicationContext();

        RouteRepository     routeRepo    = new RouteRepository(ctx);
        SyncStateRepository syncStateRepo = new SyncStateRepository(ctx);
        StravaAuthRepository authRepo    = new StravaAuthRepository(ctx);
        ObjectMapper         mapper      = new ObjectMapper();
        ClimbPayloadBuilder  payloadBuilder = new ClimbPayloadBuilder(mapper);
        ConnectIqClient      ciqClient    =
                ((nl.paree.climbpro.ClimbProApplication) ctx).connectIqClient();

        // The CIQ connection is async; give it a moment if the app just started.
        for (int i = 0; i < 20 && !ciqClient.isConnected(); i++) {
            try { Thread.sleep(250); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Result.retry();
            }
        }
        if (!ciqClient.isConnected()) {
            Log.w(TAG, "Watch not connected — will retry");
            return Result.retry();
        }

        // Step 1: pull Strava routes (if signed in)
        if (authRepo.isAuthorised()) {
            try {
                new StravaRoutesRepository(authRepo, routeRepo).syncRoutes();
            } catch (IOException e) {
                Log.e(TAG, "Strava sync failed", e);
                // Not fatal — we may still be able to send cached data to the watch.
            }
        }

        // Step 2: build and send payload
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ctx);
        String mode = prefs.getString(PREF_MODE, MODE_ROUTE);

        try {
            byte[] payload;

            if (MODE_RADIUS.equals(mode)) {
                double lat = Double.longBitsToDouble(
                        prefs.getLong(PREF_LAST_LAT, Double.doubleToLongBits(0)));
                double lon = Double.longBitsToDouble(
                        prefs.getLong(PREF_LAST_LON, Double.doubleToLongBits(0)));
                double radiusM = prefs.getInt(PREF_RADIUS_M, DEFAULT_RADIUS_M);

                RadiusModeAssembler assembler =
                        new RadiusModeAssembler(routeRepo, payloadBuilder);
                payload = assembler.assemble(lat, lon, radiusM);

                if (assembler.wasTruncated()) {
                    Log.w(TAG, "Radius payload was truncated — some climbs omitted");
                }
            } else {
                String routeId = prefs.getString(PREF_ROUTE_ID, null);
                if (routeId == null) {
                    Log.i(TAG, "No active route selected — nothing to sync");
                    return Result.success();
                }

                SyncState state = syncStateRepo.get(routeId);
                StoredRoute route = routeRepo.loadRoute(routeId);

                if (SyncState.Status.SYNCED.equals(state.status)
                        && route.sourceHash.equals(state.lastSyncedHash)) {
                    Log.i(TAG, "Route " + routeId + " unchanged, no re-sync needed");
                    return Result.success();
                }

                payload = payloadBuilder.buildRoutePayload(route);

                if (payload.length > PayloadBudget.MAX_BYTES) {
                    Log.e(TAG, "Payload exceeds budget: " + payload.length + " bytes");
                    return Result.failure();
                }
            }

            boolean sent = ciqClient.sendPayloadBlocking(payload, 10_000);
            if (!sent) {
                Log.w(TAG, "Send failed or not acknowledged — will retry");
                return Result.retry();
            }

            if (!MODE_RADIUS.equals(mode)) {
                String routeId = prefs.getString(PREF_ROUTE_ID, null);
                if (routeId != null) {
                    StoredRoute route = routeRepo.loadRoute(routeId);
                    syncStateRepo.markSynced(routeId, route.sourceHash);
                }
            }

            return Result.success();

        } catch (IOException e) {
            Log.e(TAG, "Payload build/send failed", e);
            return Result.retry();
        }
    }
}
