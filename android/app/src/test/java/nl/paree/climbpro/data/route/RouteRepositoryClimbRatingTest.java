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

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Climb ratings (issue #244): set, clear, propagate across routes, survive resync. */
@RunWith(RobolectricTestRunner.class)
public class RouteRepositoryClimbRatingTest {

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
                .startDistance(0).endDistance(1000).length(1000)
                .elevationGain(100).avgGradient(0.10)
                .startLat(51.0000).startLon(5.0)
                .name("Test climb")
                .build();
    }

    private void save(String routeId) throws Exception {
        StoredRoute r = new StoredRoute();
        r.routeId = routeId; r.name = "R " + routeId;
        repo.saveRoute(r, points(), Collections.singletonList(climb()));
    }

    @Test
    public void setClimbRating_storesNormalizedValues() throws Exception {
        save("r1");
        repo.setClimbRating("r1", 0, 5, 9, 4, "  Mooi  ");

        StoredClimb c = repo.loadRoute("r1").climbs.get(0);
        assertEquals(Integer.valueOf(5), c.ratingRoad);
        assertNull(c.ratingTraffic); // 9 is out of range
        assertEquals(Integer.valueOf(4), c.ratingView);
        assertEquals("Mooi", c.ratingNote);
    }

    @Test
    public void setClimbRating_allNullClears() throws Exception {
        save("r1");
        repo.setClimbRating("r1", 0, 5, 4, 3, "x");
        repo.setClimbRating("r1", 0, null, null, null, " ");

        StoredClimb c = repo.loadRoute("r1").climbs.get(0);
        assertNull(c.ratingRoad);
        assertNull(c.ratingTraffic);
        assertNull(c.ratingView);
        assertNull(c.ratingNote);
    }

    @Test
    public void setClimbRating_outOfRangeIndexIsNoOp() throws Exception {
        save("r1");
        repo.setClimbRating("r1", 3, 5, 5, 5, "x");
        repo.setClimbRating("r1", -1, 5, 5, 5, "x");

        assertNull(repo.loadRoute("r1").climbs.get(0).ratingRoad);
    }

    @Test
    public void setClimbRating_propagatesToSameClimbInOtherRoutes() throws Exception {
        save("r1");
        save("r2");

        repo.setClimbRating("r1", 0, 4, 3, 5, "Top");

        StoredClimb other = repo.loadRoute("r2").climbs.get(0);
        assertEquals(Integer.valueOf(4), other.ratingRoad);
        assertEquals(Integer.valueOf(3), other.ratingTraffic);
        assertEquals(Integer.valueOf(5), other.ratingView);
        assertEquals("Top", other.ratingNote);
    }

    @Test
    public void saveRoute_resyncPreservesRating() throws Exception {
        save("r1");
        repo.setClimbRating("r1", 0, 2, 1, 5, "Druk");

        save("r1"); // resync: same climb freshly re-detected, no rating on it

        StoredClimb c = repo.loadRoute("r1").climbs.get(0);
        assertEquals(Integer.valueOf(2), c.ratingRoad);
        assertEquals(Integer.valueOf(1), c.ratingTraffic);
        assertEquals(Integer.valueOf(5), c.ratingView);
        assertEquals("Druk", c.ratingNote);
    }
}
