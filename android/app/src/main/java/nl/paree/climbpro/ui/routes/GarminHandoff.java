package nl.paree.climbpro.ui.routes;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.domain.route.GpxWriter;

/**
 * Navigation-handoff: turns a stored route into a GPX file and builds the share intent
 * that hands it to Garmin Connect, which imports it as a navigable course and pushes it
 * to the watch. We do not push courses ourselves (see {@code Documentation/ARCHITECTURE.md}).
 *
 * <p>Split into pure, testable steps: {@link #writeRouteGpx} serializes + persists the GPX,
 * {@link #buildShareIntent} assembles the {@code ACTION_SEND} intent carrying it.
 */
public final class GarminHandoff {

    public static final String GARMIN_PACKAGE = "com.garmin.android.apps.connectmobile";
    public static final String GPX_MIME = "application/gpx+xml";

    private GarminHandoff() {}

    /** Writes the route's GPX to a shareable file under the app cache and returns it. */
    public static File writeRouteGpx(Context ctx, StoredRoute route) throws IOException {
        File dir = new File(ctx.getCacheDir(), "shared_routes");
        dir.mkdirs();
        File file = new File(dir, "route.gpx");
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(GpxWriter.toGpx(route).getBytes(StandardCharsets.UTF_8));
        }
        return file;
    }

    /**
     * Builds the ACTION_SEND intent carrying {@code gpxUri}. Targets Garmin Connect when it
     * is installed; otherwise clears the package so the system chooser is shown.
     */
    public static Intent buildShareIntent(Context ctx, Uri gpxUri) {
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType(GPX_MIME);
        share.putExtra(Intent.EXTRA_SUBJECT, "ClimbPro route");
        share.putExtra(Intent.EXTRA_STREAM, gpxUri);
        share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        share.setPackage(GARMIN_PACKAGE);
        if (ctx.getPackageManager().resolveActivity(share, 0) == null) {
            share.setPackage(null);
        }
        return share;
    }
}
