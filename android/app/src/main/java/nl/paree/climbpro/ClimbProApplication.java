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
        ciqClient.connect();
    }

    /** App-scoped Connect IQ client. Reused by RouteSyncWorker — never construct your own. */
    public ConnectIqClient connectIqClient() {
        return ciqClient;
    }
}
