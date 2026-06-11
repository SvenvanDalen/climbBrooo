package nl.paree.climbpro.data.route;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import nl.paree.climbpro.domain.climb.ClimbConstants;
import nl.paree.climbpro.domain.segment.SurfaceType;

import java.io.File;

import static org.junit.Assert.assertEquals;

@RunWith(RobolectricTestRunner.class)
public class FlatSegmentNameRepositoryTest {

    private Application app;

    /** Writes a route with one flat segment 1000..2000 m and a 0..5000 m axis. */
    private void seedRouteWithFlat(String routeId) throws Exception {
        StoredRoute route = new StoredRoute();
        route.routeId    = routeId;
        route.name       = "Test";
        route.lats       = new double[]{51.0, 51.01, 51.02};
        route.lons       = new double[]{5.0, 5.01, 5.02};
        route.elevations = new double[]{100, 120, 140};
        route.distances  = new double[]{0, 2500, 5000};

        StoredFlatSegment fs = new StoredFlatSegment();
        fs.startDistance = 1000;
        fs.endDistance   = 2000;
        fs.length        = 1000;
        route.flatSegments = new java.util.ArrayList<>();
        route.flatSegments.add(fs);

        File dir = new File(app.getFilesDir(), "routes");
        dir.mkdirs();
        new ObjectMapper().writeValue(new File(dir, routeId + ".json"), route);
    }

    @Before
    public void setUp() {
        app = ApplicationProvider.getApplicationContext();
        SharedPreferences prefs = app.getSharedPreferences("route_repo", Context.MODE_PRIVATE);
        prefs.edit().putInt("segment_version", ClimbConstants.SEGMENT_VERSION).commit();
    }

    @Test
    public void updateFlatSegment_persistsSurfaceAndName() throws Exception {
        seedRouteWithFlat("r1");
        RouteRepository repo = new RouteRepository(app);

        repo.updateFlatSegment("r1", 1000, SurfaceType.GRAVEL, "Bospad");

        StoredFlatSegment fs = repo.loadRoute("r1").flatSegments.get(0);
        assertEquals(SurfaceType.GRAVEL, fs.surfaceType);
        assertEquals("Bospad", fs.name);
    }

    @Test
    public void flatSegmentName_survivesReimport() throws Exception {
        // Seed a route with no climbs so the detector produces one flat 0..3000.
        StoredRoute route = new StoredRoute();
        route.routeId    = "r2";
        route.name       = "Test";
        route.lats       = new double[]{51.0, 51.01};
        route.lons       = new double[]{5.0, 5.01};
        route.elevations = new double[]{100, 120};
        route.distances  = new double[]{0, 3000};
        StoredFlatSegment fs = new StoredFlatSegment();
        fs.startDistance = 0; fs.endDistance = 3000; fs.length = 3000;
        route.flatSegments = new java.util.ArrayList<>();
        route.flatSegments.add(fs);
        File dir = new File(app.getFilesDir(), "routes");
        dir.mkdirs();
        new ObjectMapper().writeValue(new File(dir, "r2.json"), route);

        RouteRepository repo = new RouteRepository(app);
        repo.updateFlatSegment("r2", 0, SurfaceType.GRAVEL, "Bospad");

        // Re-import with same length and no climbs — detector re-emits flat 0..3000.
        StoredRoute fresh = new StoredRoute();
        fresh.routeId = "r2";
        fresh.name    = "Test";
        java.util.List<nl.paree.climbpro.domain.route.RoutePoint> points = new java.util.ArrayList<>();
        points.add(new nl.paree.climbpro.domain.route.RoutePoint(51.0, 5.0, 100, 0));
        points.add(new nl.paree.climbpro.domain.route.RoutePoint(51.01, 5.01, 120, 3000));
        repo.saveRoute(fresh, points, java.util.Collections.emptyList());

        StoredFlatSegment loaded = repo.loadRoute("r2").flatSegments.get(0);
        assertEquals("name must survive re-import", "Bospad", loaded.name);
        assertEquals(SurfaceType.GRAVEL, loaded.surfaceType);
    }
}
