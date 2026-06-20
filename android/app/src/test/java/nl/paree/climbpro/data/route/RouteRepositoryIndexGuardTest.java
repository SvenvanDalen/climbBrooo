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
import java.io.IOException;
import java.util.ArrayList;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

/**
 * Negative-index guards: out-of-range indices (including negatives) must surface as
 * the intended {@link IOException} for the throwing setters, and as a silent no-op for
 * {@link RouteRepository#renameClimb} — never as a raw {@code IndexOutOfBoundsException}.
 */
@RunWith(RobolectricTestRunner.class)
public class RouteRepositoryIndexGuardTest {

    private Application app;

    /** Writes a route with one climb that has two segments. */
    private void seedRouteWithClimb(String routeId) throws Exception {
        StoredRoute route = new StoredRoute();
        route.routeId    = routeId;
        route.name       = "Test";
        route.lats       = new double[]{51.0, 51.01, 51.02};
        route.lons       = new double[]{5.0, 5.01, 5.02};
        route.elevations = new double[]{100, 120, 140};
        route.distances  = new double[]{0, 500, 1000};

        StoredClimb climb = new StoredClimb();
        climb.startDistance = 0;
        climb.endDistance   = 1000;
        climb.length        = 1000;
        climb.name          = "Climb";
        climb.segments      = new ArrayList<>();
        StoredSegment s0 = new StoredSegment();
        s0.distance = 500;
        StoredSegment s1 = new StoredSegment();
        s1.distance = 500;
        climb.segments.add(s0);
        climb.segments.add(s1);

        route.climbs = new ArrayList<>();
        route.climbs.add(climb);

        File dir = new File(app.getFilesDir(), "routes");
        dir.mkdirs();
        new ObjectMapper().writeValue(new File(dir, routeId + ".json"), route);
    }

    @Before
    public void setUp() {
        app = ApplicationProvider.getApplicationContext();
        // Skip migrateIfNeeded()'s wipe of route files.
        SharedPreferences prefs = app.getSharedPreferences("route_repo", Context.MODE_PRIVATE);
        prefs.edit().putInt("segment_version", ClimbConstants.SEGMENT_VERSION).commit();
    }

    @Test
    public void setSegmentSurfaceType_negativeClimbIndex_throwsIOException() throws Exception {
        seedRouteWithClimb("r1");
        RouteRepository repo = new RouteRepository(app);

        assertThrows(IOException.class,
                () -> repo.setSegmentSurfaceType("r1", -1, 0, SurfaceType.GRAVEL));
    }

    @Test
    public void setSegmentSurfaceType_negativeSegmentIndex_throwsIOException() throws Exception {
        seedRouteWithClimb("r1");
        RouteRepository repo = new RouteRepository(app);

        assertThrows(IOException.class,
                () -> repo.setSegmentSurfaceType("r1", 0, -1, SurfaceType.GRAVEL));
    }

    @Test
    public void setBulkClimbSurfaceType_negativeClimbIndex_throwsIOException() throws Exception {
        seedRouteWithClimb("r1");
        RouteRepository repo = new RouteRepository(app);

        assertThrows(IOException.class,
                () -> repo.setBulkClimbSurfaceType("r1", -1, SurfaceType.GRAVEL));
    }

    @Test
    public void reSegmentClimb_negativeClimbIndex_throwsIOException() throws Exception {
        seedRouteWithClimb("r1");
        RouteRepository repo = new RouteRepository(app);

        assertThrows(IOException.class,
                () -> repo.reSegmentClimb("r1", -1, 13));
    }

    @Test
    public void renameClimb_negativeClimbIndex_isSilentNoOp() throws Exception {
        seedRouteWithClimb("r1");
        RouteRepository repo = new RouteRepository(app);

        repo.renameClimb("r1", -1, "ShouldNotApply"); // must not throw

        StoredRoute reloaded = repo.loadRoute("r1");
        assertEquals("Climb", reloaded.climbs.get(0).name);
        org.junit.Assert.assertNull(reloaded.climbs.get(0).userDisplayName);
    }
}
