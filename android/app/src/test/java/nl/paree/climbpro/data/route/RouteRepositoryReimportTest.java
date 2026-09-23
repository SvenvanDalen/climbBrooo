package nl.paree.climbpro.data.route;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import nl.paree.climbpro.domain.climb.Climb;
import nl.paree.climbpro.domain.climb.ClimbConstants;
import nl.paree.climbpro.domain.route.RoutePoint;
import nl.paree.climbpro.domain.segment.CalibrationPoint;
import nl.paree.climbpro.domain.segment.Segment;
import nl.paree.climbpro.domain.segment.SurfaceType;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

/**
 * Covers data that must survive a re-import/resync of the same routeId
 * (Strava bumps updatedAt → hash changes → full reprocess) and the
 * calibration-point pipeline on the normal save path.
 */
@RunWith(RobolectricTestRunner.class)
public class RouteRepositoryReimportTest {

    private Application app;

    @Before
    public void setUp() {
        app = ApplicationProvider.getApplicationContext();
        // Skip migrateIfNeeded()'s wipe of route files.
        SharedPreferences prefs = app.getSharedPreferences("route_repo", Context.MODE_PRIVATE);
        prefs.edit().putInt("segment_version", ClimbConstants.SEGMENT_VERSION).commit();
    }

    /** Fresh StoredRoute shell (id + name only), as a re-import would supply. */
    private static StoredRoute routeShell(String routeId) {
        StoredRoute r = new StoredRoute();
        r.routeId = routeId;
        r.name = "Test";
        return r;
    }

    /** Route geometry: a 1000 m climb on a 1500 m route. */
    private static List<RoutePoint> points() {
        List<RoutePoint> pts = new ArrayList<>();
        pts.add(new RoutePoint(51.00, 5.0, 100, 0));
        pts.add(new RoutePoint(51.01, 5.0, 130, 500));
        pts.add(new RoutePoint(51.02, 5.0, 160, 1000));
        pts.add(new RoutePoint(51.03, 5.0, 160, 1500));
        return pts;
    }

    /** One climb at startDistance 0, two segments, no calibration points. */
    private static List<Climb> climbs() {
        List<Segment> segs = new ArrayList<>();
        segs.add(new Segment(500, 30, 0.06, 3));
        segs.add(new Segment(500, 30, 0.06, 3));
        Climb c = Climb.builder()
                .startDistance(0).endDistance(1000)
                .length(1000).elevationGain(60).avgGradient(0.06)
                .startLat(51.0).startLon(5.0)
                .segments(segs)
                .build();
        List<Climb> out = new ArrayList<>();
        out.add(c);
        return out;
    }

    @Test
    public void saveRoutePreservesClimbRenameAcrossReimport() throws Exception {
        RouteRepository repo = new RouteRepository(app);
        repo.saveRoute(routeShell("r1"), points(), climbs());
        repo.renameClimb("r1", 0, "Mortirolo");

        // Re-import: fresh climbs at the SAME startDistance, no userDisplayName.
        repo.saveRoute(routeShell("r1"), points(), climbs());

        StoredRoute reloaded = repo.loadRoute("r1");
        assertEquals("climb rename must survive re-import",
                "Mortirolo", reloaded.climbs.get(0).userDisplayName);
    }

    @Test
    public void saveRoutePreservesClimbSegmentSurfaceAcrossReimport() throws Exception {
        RouteRepository repo = new RouteRepository(app);
        repo.saveRoute(routeShell("r1"), points(), climbs());
        repo.setSegmentSurfaceType("r1", 0, 0, SurfaceType.COBBLESTONE);

        repo.saveRoute(routeShell("r1"), points(), climbs());

        StoredRoute reloaded = repo.loadRoute("r1");
        assertEquals("per-segment surface must survive re-import",
                SurfaceType.COBBLESTONE,
                reloaded.climbs.get(0).segments.get(0).surfaceType);
    }

    @Test
    public void saveRoutePreservesSegmentManualTargetAcrossReimport() throws Exception {
        RouteRepository repo = new RouteRepository(app);
        repo.saveRoute(routeShell("r1"), points(), climbs());
        repo.setSegmentManualTargetSec("r1", 0, 1, 95);

        repo.saveRoute(routeShell("r1"), points(), climbs());

        StoredRoute reloaded = repo.loadRoute("r1");
        assertEquals("manual segment target must survive re-import",
                Integer.valueOf(95), reloaded.climbs.get(0).segments.get(1).manualTargetSec);
    }

    @Test
    public void saveRoutePersistsClimbCalibrationPoints() throws Exception {
        RouteRepository repo = new RouteRepository(app);

        List<Segment> segs = new ArrayList<>();
        segs.add(new Segment(500, 30, 0.06, 3));
        segs.add(new Segment(500, 30, 0.06, 3));
        List<CalibrationPoint> cps = new ArrayList<>();
        cps.add(new CalibrationPoint(250, 51.01, 5.0));
        cps.add(new CalibrationPoint(500, 51.02, 5.0));
        Climb c = Climb.builder()
                .startDistance(0).endDistance(1000)
                .length(1000).elevationGain(60).avgGradient(0.06)
                .startLat(51.0).startLon(5.0)
                .segments(segs)
                .calibrationPoints(cps)
                .build();
        List<Climb> withCalib = new ArrayList<>();
        withCalib.add(c);

        repo.saveRoute(routeShell("r1"), points(), withCalib);

        StoredRoute reloaded = repo.loadRoute("r1");
        assertNotNull("calibration points must be persisted",
                reloaded.climbs.get(0).calibrationPoints);
        assertFalse("calibration points must not be empty",
                reloaded.climbs.get(0).calibrationPoints.isEmpty());
        assertEquals(250,
                reloaded.climbs.get(0).calibrationPoints.get(0).distanceFromClimbStart);
    }
}
