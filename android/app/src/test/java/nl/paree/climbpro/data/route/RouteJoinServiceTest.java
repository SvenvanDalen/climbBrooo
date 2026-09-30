package nl.paree.climbpro.data.route;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import nl.paree.climbpro.domain.climb.ClimbConstants;
import nl.paree.climbpro.domain.climb.ClimbDetector;
import nl.paree.climbpro.domain.route.CumulativeDistance;
import nl.paree.climbpro.domain.route.RoutePoint;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Issue #204: joining two stored routes saves a new route and leaves the originals alone. */
@RunWith(RobolectricTestRunner.class)
public class RouteJoinServiceTest {

    private RouteRepository repo;

    @Before
    public void setUp() {
        Application app = ApplicationProvider.getApplicationContext();
        SharedPreferences prefs = app.getSharedPreferences("route_repo", Context.MODE_PRIVATE);
        prefs.edit().putInt("segment_version", ClimbConstants.SEGMENT_VERSION).commit();
        repo = new RouteRepository(app);
    }

    /** Northbound line of {@code n} points ~111 m apart, climbing {@code elePerPoint} each. */
    private static List<RoutePoint> line(double lat0, int n, double ele0, double elePerPoint) {
        List<RoutePoint> pts = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            pts.add(new RoutePoint(lat0 + i * 0.001, 5.0, ele0 + i * elePerPoint, 0.0));
        }
        return CumulativeDistance.compute(pts);
    }

    private void store(String id, String name, List<RoutePoint> pts) throws IOException {
        StoredRoute r = new StoredRoute();
        r.routeId = id;
        r.name = name;
        repo.saveRoute(r, pts, ClimbDetector.detect(pts));
    }

    @Test
    public void savesJoinedRouteWithReDetectedClimbAndKeepsOriginals() throws IOException {
        // Each half is a 5 % ramp of ~666 m: too short alone, one climb once joined.
        List<RoutePoint> a = line(51.0, 7, 0, 5.55);
        List<RoutePoint> b = line(51.006, 7, a.get(6).elevation, 5.55);
        store("join_a", "Aanloop", a);
        store("join_b", "Lus", b);
        repo.renameRoute("join_b", "Mijn lus");

        RouteJoinService service = new RouteJoinService(repo);
        RouteJoinService.Prepared prepared = service.prepare("join_a", "join_b");
        assertEquals("Aanloop + Mijn lus", prepared.defaultName);
        assertFalse(prepared.joined.hasLargeGap());

        RouteJoinService.Saved saved = service.save(prepared);
        assertEquals(1, saved.climbCount);
        assertNotEquals("join_a", saved.routeId);

        StoredRoute joined = repo.loadRoute(saved.routeId);
        assertEquals("Aanloop + Mijn lus", joined.name);
        assertEquals(13, joined.lats.length); // 7 + 7 minus the shared joint point
        assertEquals(a.get(6).distance * 2, joined.distances[12], 1.0);

        assertEquals(7, repo.loadRoute("join_a").lats.length);
        assertEquals(0, repo.loadRoute("join_a").climbs.size());
        assertEquals(7, repo.loadRoute("join_b").lats.length);

        boolean inCatalog = false;
        for (RouteCatalogEntry e : repo.loadCatalog()) {
            if (e.routeId.equals(saved.routeId)) inCatalog = true;
        }
        assertTrue(inCatalog);
    }

    @Test
    public void flagsLargeGapBeforeSaving() throws IOException {
        store("gap_a", "A", line(51.0, 3, 0, 0));
        store("gap_b", "B", line(51.1, 3, 0, 0));
        RouteJoinService.Prepared prepared =
                new RouteJoinService(repo).prepare("gap_a", "gap_b");
        assertTrue(prepared.joined.hasLargeGap());
    }

    @Test
    public void refusesJoiningRouteWithItself() throws IOException {
        store("self", "A", line(51.0, 3, 0, 0));
        try {
            new RouteJoinService(repo).prepare("self", "self");
            fail("expected IOException");
        } catch (IOException expected) {
            // ok
        }
    }
}
