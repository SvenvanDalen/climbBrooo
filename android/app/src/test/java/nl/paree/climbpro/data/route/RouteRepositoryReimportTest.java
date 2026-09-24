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
    public void saveRoutePreservesClimbShapeOverrideAcrossReimport() throws Exception {
        RouteRepository repo = new RouteRepository(app);
        repo.saveRoute(routeShell("r1"), points(), climbs());
        repo.setClimbShapeOverride("r1", 0,
                nl.paree.climbpro.domain.climb.ClimbShape.IRREGULAR.name());

        // Re-import: fresh climbs at the SAME startDistance, no shapeOverride set.
        repo.saveRoute(routeShell("r1"), points(), climbs());

        StoredRoute reloaded = repo.loadRoute("r1");
        assertEquals("climb shape override must survive re-import",
                nl.paree.climbpro.domain.climb.ClimbShape.IRREGULAR.name(),
                reloaded.climbs.get(0).shapeOverride);
    }

    @Test
    public void setClimbShapeOverrideNullClearsOverride() throws Exception {
        RouteRepository repo = new RouteRepository(app);
        repo.saveRoute(routeShell("r1"), points(), climbs());
        repo.setClimbShapeOverride("r1", 0,
                nl.paree.climbpro.domain.climb.ClimbShape.STEEP_FINISH.name());
        repo.setClimbShapeOverride("r1", 0, null);

        StoredRoute reloaded = repo.loadRoute("r1");
        assertEquals("null clears the override back to auto",
                null, reloaded.climbs.get(0).shapeOverride);
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
    public void manualSegmentTargetDroppedWhenGridMovesWithSameSegmentCount() throws Exception {
        RouteRepository repo = new RouteRepository(app);
        repo.saveRoute(routeShell("r1"), points(), climbs());
        repo.setSegmentManualTargetSec("r1", 0, 1, 95);

        // Same start and still two segments, but the climb got longer: the boundary moved.
        List<Segment> segs = new ArrayList<>();
        segs.add(new Segment(600, 36, 0.06, 3));
        segs.add(new Segment(600, 36, 0.06, 3));
        List<Climb> longer = new ArrayList<>();
        longer.add(Climb.builder()
                .startDistance(0).endDistance(1200)
                .length(1200).elevationGain(72).avgGradient(0.06)
                .startLat(51.0).startLon(5.0)
                .segments(segs)
                .build());
        repo.saveRoute(routeShell("r1"), points(), longer);

        assertEquals("a target must not move onto a different stretch of road",
                null, repo.loadRoute("r1").climbs.get(0).segments.get(1).manualTargetSec);
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
    // -------------------------------------------------------------------------
    // Issue #87: user data follows the road when segment boundaries move
    // -------------------------------------------------------------------------

    /** Same road as {@link #points()}, but the route now starts 500 m earlier. */
    private static List<RoutePoint> shiftedPoints() {
        List<RoutePoint> pts = new ArrayList<>();
        pts.add(new RoutePoint(50.995, 5.0, 100, 0));
        pts.add(new RoutePoint(51.00, 5.0, 100, 500));
        pts.add(new RoutePoint(51.01, 5.0, 130, 1000));
        pts.add(new RoutePoint(51.02, 5.0, 160, 1500));
        pts.add(new RoutePoint(51.03, 5.0, 160, 2000));
        return pts;
    }

    private static List<Climb> climbAt(int start, int... segLens) {
        return climbAt(start, 51.0, segLens);
    }

    private static List<Climb> climbAt(int start, double startLat, int... segLens) {
        List<Segment> segs = new ArrayList<>();
        int len = 0;
        for (int l : segLens) {
            segs.add(new Segment(l, 30, 0.06, 3));
            len += l;
        }
        Climb c = Climb.builder()
                .startDistance(start).endDistance(start + len)
                .length(len).elevationGain(60).avgGradient(0.06)
                .startLat(startLat).startLon(5.0)
                .segments(segs)
                .build();
        List<Climb> out = new ArrayList<>();
        out.add(c);
        return out;
    }

    @Test
    public void shiftedRouteStartKeepsRenameSurfaceAndFlatData() throws Exception {
        RouteRepository repo = new RouteRepository(app);
        repo.saveRoute(routeShell("r1"), points(), climbs());
        repo.renameClimb("r1", 0, "Mortirolo");
        repo.setSegmentSurfaceType("r1", 0, 1, SurfaceType.COBBLESTONE);
        repo.setFlatSegmentSurfaceType("r1", 1000, SurfaceType.GRAVEL);

        // Same road, every distance offset by +500 m.
        repo.saveRoute(routeShell("r1"), shiftedPoints(), climbAt(500, 500, 500));

        StoredRoute reloaded = repo.loadRoute("r1");
        StoredClimb c = reloaded.climbs.get(0);
        assertEquals(500, c.startDistance);
        assertEquals("rename must survive a shifted route start", "Mortirolo", c.userDisplayName);
        assertEquals(SurfaceType.UNKNOWN, c.segments.get(0).surfaceType);
        assertEquals("surface stays on the same stretch of road",
                SurfaceType.COBBLESTONE, c.segments.get(1).surfaceType);

        StoredFlatSegment movedFlat = null;
        for (StoredFlatSegment f : reloaded.flatSegments) {
            if (f.startDistance == 1500) movedFlat = f;
            else assertEquals(SurfaceType.UNKNOWN, f.surfaceType);
        }
        assertNotNull(movedFlat);
        assertEquals("flat surface follows the route shift", SurfaceType.GRAVEL, movedFlat.surfaceType);
    }

    @Test
    public void changedGridCarriesSurfaceByOverlapNotByIndex() throws Exception {
        RouteRepository repo = new RouteRepository(app);
        repo.saveRoute(routeShell("r1"), points(), climbs()); // [0,500] [500,1000]
        repo.setSegmentSurfaceType("r1", 0, 0, SurfaceType.COBBLESTONE);
        repo.setSegmentSurfaceType("r1", 0, 1, SurfaceType.GRAVEL);

        // Climb extended to 1500 m: third segment covers road that had no surface set.
        repo.saveRoute(routeShell("r1"), points(), climbAt(0, 500, 500, 500));

        List<StoredSegment> segs = repo.loadRoute("r1").climbs.get(0).segments;
        assertEquals(3, segs.size());
        assertEquals(SurfaceType.COBBLESTONE, segs.get(0).surfaceType);
        assertEquals(SurfaceType.GRAVEL, segs.get(1).surfaceType);
        assertEquals("no overlap with the old climb -> default",
                SurfaceType.UNKNOWN, segs.get(2).surfaceType);
    }

    @Test
    public void movedClimbStartSameSegmentCountDoesNotCopyByIndex() throws Exception {
        RouteRepository repo = new RouteRepository(app);
        repo.saveRoute(routeShell("r1"), points(), climbs()); // [0,500] [500,1000]
        repo.renameClimb("r1", 0, "Mortirolo");
        repo.setSegmentSurfaceType("r1", 0, 0, SurfaceType.COBBLESTONE);
        repo.setSegmentSurfaceType("r1", 0, 1, SurfaceType.GRAVEL);

        // Re-detection now starts the climb 500 m later ([500,1000] [1000,1500]): still two
        // segments, but the old index copy would paint the cobbles onto the gravel stretch.
        repo.saveRoute(routeShell("r1"), points(), climbAt(500, 51.01, 500, 500));

        StoredClimb c = repo.loadRoute("r1").climbs.get(0);
        assertEquals("matched by range overlap", "Mortirolo", c.userDisplayName);
        assertEquals(SurfaceType.GRAVEL, c.segments.get(0).surfaceType);
        assertEquals(SurfaceType.UNKNOWN, c.segments.get(1).surfaceType);
    }
}
