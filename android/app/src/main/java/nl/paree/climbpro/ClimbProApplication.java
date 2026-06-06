package nl.paree.climbpro;

import android.app.Application;
import org.osmdroid.config.Configuration;
import java.io.File;

public final class ClimbProApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        Configuration.getInstance().setUserAgentValue("ClimbPro/1.0");
        Configuration.getInstance().setOsmdroidTileCache(
                new File(getCacheDir(), "osmdroid"));
    }
}
