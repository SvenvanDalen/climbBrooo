package nl.paree.climbpro.ui.routes;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ResolveInfo;
import android.net.Uri;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.domain.route.GpxParser;
import nl.paree.climbpro.domain.route.RoutePoint;

/**
 * TDD for the navigation-handoff: tapping "Open in Garmin Connect" must send an
 * ACTION_SEND intent that actually carries the route as a GPX attachment (the old
 * code attached nothing, so Garmin Connect opened empty).
 */
@RunWith(RobolectricTestRunner.class)
public class GarminHandoffTest {

    private static StoredRoute ardennenRoute() {
        StoredRoute r = new StoredRoute();
        r.routeId = "ardennen";
        r.name = "La Redoute";
        r.lats = new double[] {50.5001, 50.5010, 50.5025};
        r.lons = new double[] {5.8001, 5.8009, 5.8020};
        r.elevations = new double[] {120.0, 155.5, 190.0};
        return r;
    }

    private Application ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    @Test
    public void buildShareIntent_attachesGpxUriAsExtraStream() {
        Uri gpx = Uri.parse("content://nl.paree.climbpro.fileprovider/shared_routes/route.gpx");
        Intent i = GarminHandoff.buildShareIntent(ctx(), gpx);

        assertEquals(gpx, i.getParcelableExtra(Intent.EXTRA_STREAM));
    }

    @Test
    public void buildShareIntent_usesSendActionAndGpxMime() {
        Intent i = GarminHandoff.buildShareIntent(ctx(), Uri.parse("content://x/route.gpx"));
        assertEquals(Intent.ACTION_SEND, i.getAction());
        assertEquals("application/gpx+xml", i.getType());
    }

    @Test
    public void buildShareIntent_grantsReadUriPermission() {
        Intent i = GarminHandoff.buildShareIntent(ctx(), Uri.parse("content://x/route.gpx"));
        assertTrue((i.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0);
    }

    @Test
    public void buildShareIntent_garminNotInstalled_clearsPackageForChooser() {
        // Default Robolectric env has no Garmin Connect installed.
        Intent i = GarminHandoff.buildShareIntent(ctx(), Uri.parse("content://x/route.gpx"));
        assertNull(i.getPackage());
    }

    @Test
    public void buildShareIntent_garminInstalled_targetsGarminPackage() {
        Intent probe = new Intent(Intent.ACTION_SEND)
                .setType("application/gpx+xml")
                .setPackage(GarminHandoff.GARMIN_PACKAGE);
        ResolveInfo ri = new ResolveInfo();
        ri.activityInfo = new ActivityInfo();
        ri.activityInfo.packageName = GarminHandoff.GARMIN_PACKAGE;
        ri.activityInfo.name = "GarminShareActivity";
        shadowOf(ctx().getPackageManager()).addResolveInfoForIntent(probe, ri);

        Intent i = GarminHandoff.buildShareIntent(ctx(), Uri.parse("content://x/route.gpx"));
        assertEquals(GarminHandoff.GARMIN_PACKAGE, i.getPackage());
    }

    @Test
    public void writeRouteGpx_writesParseableGpxFile() throws IOException {
        File f = GarminHandoff.writeRouteGpx(ctx(), ardennenRoute());
        assertNotNull(f);
        assertTrue(f.exists());

        String content = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        List<RoutePoint> back = parse(content);
        assertEquals(3, back.size());
        assertEquals(50.5001, back.get(0).lat, 1e-6);
        assertEquals(190.0, back.get(2).elevation, 1e-3);
    }

    private static List<RoutePoint> parse(String gpx) {
        try {
            return GpxParser.parse(new ByteArrayInputStream(gpx.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
