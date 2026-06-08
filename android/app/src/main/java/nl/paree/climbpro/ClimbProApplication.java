package nl.paree.climbpro;

import android.app.Application;
import org.osmdroid.config.Configuration;
import java.io.File;
import nl.paree.climbpro.connectiq.ConnectIqClient;
import nl.paree.climbpro.connectiq.WatchRequestHandler;
import nl.paree.climbpro.data.route.RouteRepository;

public final class ClimbProApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        Configuration.getInstance().setUserAgentValue("ClimbPro/1.0");
        Configuration.getInstance().setOsmdroidTileCache(
                new File(getCacheDir(), "osmdroid"));

        ConnectIqClient ciqClient = new ConnectIqClient(this);
        RouteRepository routeRepo = new RouteRepository(this);
        ciqClient.setWatchRequestHandler(new WatchRequestHandler(routeRepo, ciqClient));
    }
}
