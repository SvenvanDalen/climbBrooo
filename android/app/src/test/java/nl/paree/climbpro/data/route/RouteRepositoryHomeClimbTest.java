package nl.paree.climbpro.data.route;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;

import nl.paree.climbpro.domain.climb.Climb;
import nl.paree.climbpro.domain.climb.ClimbConstants;
import nl.paree.climbpro.domain.route.RoutePoint;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * TDD for the "thuisklim" (home climb) privacy flag (issue #92): marking/unmarking a climb via
 * {@link RouteRepository#setClimbHome} and preserving that flag across a resync, mirroring the
 * existing rename/surface-type preservation coverage.
 */
@RunWith(RobolectricTestRunner.class)
public class RouteRepositoryHomeClimbTest {

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
                new RoutePoint(51.0000, 5.0, 0.0, 0),
                new RoutePoint(51.0090, 5.0, 100.0, 1000)));
    }

    private static Climb climb() {
        return Climb.builder()
                .startDistance(0)
                .endDistance(1000)
                .length(1000)
                .elevationGain(100)
                .avgGradient(0.10)
                .startLat(51.0000)
                .startLon(5.0)
                .name("Test climb")
                .build();
    }

    @Test
    public void newClimb_isNotHomeByDefault() throws Exception {
        StoredRoute r = new StoredRoute();
        r.routeId = "r1"; r.name = "R";
        repo.saveRoute(r, points(), Collections.singletonList(climb()));

        assertFalse(repo.loadRoute("r1").climbs.get(0).isHome);
    }

    @Test
    public void setClimbHome_marksClimb() throws Exception {
        StoredRoute r = new StoredRoute();
        r.routeId = "r1"; r.name = "R";
        repo.saveRoute(r, points(), Collections.singletonList(climb()));

        repo.setClimbHome("r1", 0, true);

        assertTrue(repo.loadRoute("r1").climbs.get(0).isHome);
    }

    @Test
    public void setClimbHome_canUnmark() throws Exception {
        StoredRoute r = new StoredRoute();
        r.routeId = "r1"; r.name = "R";
        repo.saveRoute(r, points(), Collections.singletonList(climb()));
        repo.setClimbHome("r1", 0, true);

        repo.setClimbHome("r1", 0, false);

        assertFalse(repo.loadRoute("r1").climbs.get(0).isHome);
    }

    @Test
    public void saveRoute_resyncPreservesHomeFlag() throws Exception {
        StoredRoute r1 = new StoredRoute();
        r1.routeId = "r1"; r1.name = "R";
        repo.saveRoute(r1, points(), Collections.singletonList(climb()));
        repo.setClimbHome("r1", 0, true);

        // Resync: same climb geometry re-detected from a fresh import.
        StoredRoute r2 = new StoredRoute();
        r2.routeId = "r1"; r2.name = "R";
        repo.saveRoute(r2, points(), Collections.singletonList(climb()));

        assertTrue(repo.loadRoute("r1").climbs.get(0).isHome);
    }

    @Test
    public void setClimbPrivacyCentre_storesAndSurvivesResync() throws Exception {
        StoredRoute r1 = new StoredRoute();
        r1.routeId = "r1"; r1.name = "R";
        repo.saveRoute(r1, points(), Collections.singletonList(climb()));
        repo.setClimbHome("r1", 0, true);
        repo.setClimbPrivacyCentre("r1", 0, 50.0012, 5.0034);

        StoredRoute r2 = new StoredRoute();
        r2.routeId = "r1"; r2.name = "R";
        repo.saveRoute(r2, points(), Collections.singletonList(climb()));

        StoredClimb c = repo.loadRoute("r1").climbs.get(0);
        assertEquals(50.0012, c.privacyCentreLat, 0.0);
        assertEquals(5.0034, c.privacyCentreLon, 0.0);
    }

    @Test
    public void setClimbHome_outOfRangeIndexIsNoOp() throws Exception {
        StoredRoute r = new StoredRoute();
        r.routeId = "r1"; r.name = "R";
        repo.saveRoute(r, points(), Collections.singletonList(climb()));

        repo.setClimbHome("r1", 5, true); // should not throw
        repo.setClimbHome("r1", -1, true);

        assertFalse(repo.loadRoute("r1").climbs.get(0).isHome);
    }
}
