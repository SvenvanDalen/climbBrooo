package nl.paree.climbpro;

import android.app.Application;

import org.osmdroid.config.Configuration;

import java.io.File;

import nl.paree.climbpro.connectiq.ConnectIqClient;
import nl.paree.climbpro.connectiq.WatchRequestHandler;
import nl.paree.climbpro.data.route.RouteRepository;

public final class ClimbProApplication extends Application {

    private ConnectIqClient ciqClient;

    @Override
    public void onCreate() {
        super.onCreate();
        Configuration.getInstance().setUserAgentValue("ClimbPro/1.0");
        Configuration.getInstance().setOsmdroidTileCache(
                new File(getCacheDir(), "osmdroid"));

        RouteRepository routeRepo = new RouteRepository(this);
        ciqClient = new ConnectIqClient(this);
        ciqClient.setWatchRequestHandler(new WatchRequestHandler(
                routeRepo, ciqClient,
                new nl.paree.climbpro.data.rider.RiderProfileRepository(this)));
        // Force a clean GCM rebind on startup so a phone-only app update can't leave
        // the watch talking to a dead process. See ConnectIqClient#forceRebind.
        ciqClient.forceRebind();
        // Keep the GCM binder-service registration fresh even if the user never
        // opens the app (see RebindScheduler / CiqRebindWorker). On a device,
        // WorkManager is initialised (androidx.startup) before this runs; in
        // JVM/Robolectric tests it is not — there, scheduling is a no-op.
        try {
            nl.paree.climbpro.service.RebindScheduler.schedulePeriodicRebind(this);
        } catch (IllegalStateException e) {
            android.util.Log.w("ClimbProApplication",
                    "WorkManager not initialised — skipping rebind schedule", e);
        }
    }

    /** App-scoped Connect IQ client. Reused by RouteSyncWorker — never construct your own. */
    public ConnectIqClient connectIqClient() {
        return ciqClient;
    }
}
