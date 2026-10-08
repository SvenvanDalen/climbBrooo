package nl.paree.climbpro.ui.routes;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;

import androidx.core.content.FileProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import nl.paree.climbpro.data.route.RouteRepository;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.testsupport.DeviceState;
import nl.paree.climbpro.testsupport.UiTestData;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Navigation handoff: real FileProvider URI and real package resolution. */
@RunWith(AndroidJUnit4.class)
public class GarminHandoffInstrumentedTest {

    private Context app;
    private StoredRoute route;

    @Before
    public void setUp() throws Exception {
        DeviceState.seed();
        app = DeviceState.app();
        route = new RouteRepository(app).loadRoute(UiTestData.ROUTE_ID);
    }

    @Test
    public void gpxIsReadableThroughTheFileProvider() throws Exception {
        File gpx = GarminHandoff.writeRouteGpx(app, route);
        Uri uri = FileProvider.getUriForFile(app, app.getPackageName() + ".fileprovider", gpx);
        assertEquals("content", uri.getScheme());

        String body;
        try (InputStream in = app.getContentResolver().openInputStream(uri)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            body = out.toString(StandardCharsets.UTF_8.name());
        }
        assertTrue(body.contains("<gpx"));
        assertTrue(body.contains("<trkpt"));
    }

    @Test
    public void shareIntentTargetsGarminOnlyWhenInstalled() throws Exception {
        File gpx = GarminHandoff.writeRouteGpx(app, route);
        Uri uri = FileProvider.getUriForFile(app, app.getPackageName() + ".fileprovider", gpx);
        Intent share = GarminHandoff.buildShareIntent(app, uri);

        assertEquals(Intent.ACTION_SEND, share.getAction());
        assertEquals(GarminHandoff.GPX_MIME, share.getType());
        assertEquals(uri, share.getParcelableExtra(Intent.EXTRA_STREAM));
        assertTrue((share.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0);
        if (garminInstalled()) {
            assertEquals(GarminHandoff.GARMIN_PACKAGE, share.getPackage());
        } else {
            assertNull(share.getPackage());
        }
    }

    private boolean garminInstalled() {
        try {
            app.getPackageManager().getPackageInfo(GarminHandoff.GARMIN_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }
}
