package nl.paree.climbpro.data.route;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;

import nl.paree.climbpro.domain.climb.Climb;
import nl.paree.climbpro.domain.climb.ClimbConstants;
import nl.paree.climbpro.domain.route.RoutePoint;
import nl.paree.climbpro.domain.segment.SurfaceType;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
public class RouteRepositoryStarredSegmentTest {

    private RouteRepository repo;

    @Before
    public void setUp() {
        Application app = ApplicationProvider.getApplicationContext();
        SharedPreferences prefs = app.getSharedPreferences("route_repo", Context.MODE_PRIVATE);
        prefs.edit().putInt("segment_version", ClimbConstants.SEGMENT_VERSION).commit();
        repo = new RouteRepository(app);
    }

    private static List<RoutePoint> points() {
        return new ArrayList<>(Arrays.asList(
                new RoutePoint(51.0000, 5.0, 0.0,   0),
                new RoutePoint(51.0018, 5.0, 0.5, 200),
                new RoutePoint(51.0036, 5.0, 1.0, 400)));
    }

    private static StoredStarredSegment seg(long id, int surface, String userName) {
        StoredStarredSegment s = new StoredStarredSegment();
        s.stravaId = id;
        s.startDistance = 0; s.endDistance = 400; s.length = 400;
        s.startLat = 51.0; s.startLon = 5.0; s.endLat = 51.0036; s.endLon = 5.0;
        s.avgGradient = 0.0025;
        s.name = "Vlak ster";
        s.surfaceType = surface;
        s.userDisplayName = userName;
        return s;
    }

    @Test
    public void saveRoute_persistsStarredSegments() throws Exception {
        StoredRoute r = new StoredRoute();
        r.routeId = "strava_1"; r.name = "R";
        repo.saveRoute(r, points(), Collections.<Climb>emptyList(),
                Arrays.asList(seg(7L, SurfaceType.UNKNOWN, null)));

        StoredRoute loaded = repo.loadRoute("strava_1");
        assertEquals(1, loaded.starredSegments.size());
        assertEquals(7L, loaded.starredSegments.get(0).stravaId);
        assertEquals(SurfaceType.UNKNOWN, loaded.starredSegments.get(0).surfaceType);
    }

    @Test
    public void saveRoute_resyncPreservesSurfaceAndNameByStravaId() throws Exception {
        StoredRoute r1 = new StoredRoute();
        r1.routeId = "strava_1"; r1.name = "R";
        // Simulate a prior user edit: surface + rename already on the stored segment.
        repo.saveRoute(r1, points(), Collections.<Climb>emptyList(),
                Arrays.asList(seg(7L, SurfaceType.GRAVEL, "Mijn gravel")));

        // Resync: Strava re-supplies the same segment with no surface / no rename.
        StoredRoute r2 = new StoredRoute();
        r2.routeId = "strava_1"; r2.name = "R";
        repo.saveRoute(r2, points(), Collections.<Climb>emptyList(),
                Arrays.asList(seg(7L, SurfaceType.UNKNOWN, null)));

        StoredRoute loaded = repo.loadRoute("strava_1");
        assertEquals(SurfaceType.GRAVEL, loaded.starredSegments.get(0).surfaceType);
        assertEquals("Mijn gravel", loaded.starredSegments.get(0).userDisplayName);
    }

    @Test
    public void saveRoute_threeArgOverload_leavesEmptyStarredList() throws Exception {
        StoredRoute r = new StoredRoute();
        r.routeId = "strava_1"; r.name = "R";
        repo.saveRoute(r, points(), Collections.<Climb>emptyList());

        StoredRoute loaded = repo.loadRoute("strava_1");
        org.junit.Assert.assertEquals(0,
                loaded.starredSegments == null ? 0 : loaded.starredSegments.size());
    }

    @Test
    public void updateStarredSegment_setsSurfaceAndName() throws Exception {
        StoredRoute r = new StoredRoute();
        r.routeId = "strava_1"; r.name = "R";
        repo.saveRoute(r, points(), Collections.<Climb>emptyList(),
                Arrays.asList(seg(7L, SurfaceType.UNKNOWN, null)));

        repo.updateStarredSegment("strava_1", 7L, SurfaceType.DIRT, "  Bospad  ");

        StoredStarredSegment s = repo.loadRoute("strava_1").starredSegments.get(0);
        assertEquals(SurfaceType.DIRT, s.surfaceType);
        assertEquals("Bospad", s.userDisplayName);
    }

    @Test
    public void updateStarredSegment_blankNameClears() throws Exception {
        StoredRoute r = new StoredRoute();
        r.routeId = "strava_1"; r.name = "R";
        repo.saveRoute(r, points(), Collections.<Climb>emptyList(),
                Arrays.asList(seg(7L, SurfaceType.GRAVEL, "Oud")));

        repo.updateStarredSegment("strava_1", 7L, SurfaceType.GRAVEL, "   ");

        assertNull(repo.loadRoute("strava_1").starredSegments.get(0).userDisplayName);
    }
}
