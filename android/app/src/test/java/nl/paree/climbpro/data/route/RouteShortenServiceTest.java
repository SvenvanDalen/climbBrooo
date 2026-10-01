package nl.paree.climbpro.data.route;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import nl.paree.climbpro.domain.climb.ClimbConstants;
import nl.paree.climbpro.domain.climb.ClimbDetector;
import nl.paree.climbpro.domain.route.CumulativeDistance;
import nl.paree.climbpro.domain.route.RoutePoint;
import nl.paree.climbpro.domain.route.RouteShortener;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Issue #205: a chosen shortcut becomes a new, re-detected route. */
@RunWith(RobolectricTestRunner.class)
public class RouteShortenServiceTest {

    private static final double M_PER_DEG = 111_195.0;

    private RouteRepository repo;
    private RouteShortenService service;

    @Before
    public void setUp() {
        Application app = ApplicationProvider.getApplicationContext();
        SharedPreferences prefs = app.getSharedPreferences("route_repo", Context.MODE_PRIVATE);
        prefs.edit().putInt("segment_version", ClimbConstants.SEGMENT_VERSION).commit();
        repo = new RouteRepository(app, (lat, lon) -> null);
        service = new RouteShortenService(repo);
    }

    /** 3 km flat out-and-back east, then a 6 km out-and-back north with a 2 km climb at 6 %. */
    private static List<RoutePoint> twoLobes() {
        List<RoutePoint> raw = new ArrayList<>();
        double[][] legs = {{3000, 0, 0}, {0, 40, 0}, {-3000, 0, 0},
                {40, 0, 0}, {0, 2000, 0.06}, {0, 1000, 0}, {40, 0, 0}, {0, -1000, 0},
                {0, -2000, -0.06}};
        double x = 0, y = 0, ele = 100;
        raw.add(pt(x, y, ele));
        for (double[] leg : legs) {
            double len = Math.hypot(leg[0], leg[1]);
            int steps = Math.max(1, (int) Math.round(len / 50));
            for (int s = 1; s <= steps; s++) {
                x += leg[0] / steps;
                y += leg[1] / steps;
                ele += leg[2] * len / steps;
                raw.add(pt(x, y, ele));
            }
        }
        return CumulativeDistance.compute(raw);
    }

    private static RoutePoint pt(double xM, double yM, double ele) {
        return new RoutePoint(51.0 + yM / M_PER_DEG,
                5.0 + xM / (M_PER_DEG * Math.cos(Math.toRadians(51.0))), ele, 0);
    }

    private StoredRoute saveOriginal(String id) throws Exception {
        List<RoutePoint> pts = twoLobes();
        StoredRoute r = new StoredRoute();
        r.routeId = id;
        r.name = "Twee lussen";
        r.sourceHash = "hash";
        repo.saveRoute(r, pts, ClimbDetector.detect(pts));
        return repo.loadRoute(id);
    }

    @Test
    public void suggestionSkippingTheClimbCreatesRouteWithoutIt() throws Exception {
        StoredRoute original = saveOriginal("gpx_s");
        assertEquals(1, original.climbs.size());

        RouteShortener.Variant skipClimb = null;
        for (RouteShortener.Variant v : RouteShortenService.suggest(original)) {
            if (v.skippedClimbs.contains(0)) { skipClimb = v; break; }
        }
        assertTrue("expected a variant that skips the climb", skipClimb != null);

        RouteShortenService.Result res =
                service.create("gpx_s", skipClimb.fromIndex, skipClimb.toIndex);

        assertTrue(res.created);
        StoredRoute shortened = repo.loadRoute(res.routeId);
        assertEquals(0, shortened.climbs.size());
        assertEquals(0, res.climbCount);
        assertTrue(shortened.name.startsWith("Twee lussen (ingekort, "));
        assertEquals(skipClimb.newLengthM,
                shortened.distances[shortened.distances.length - 1], 1.0);
        // Original untouched.
        assertEquals(original.lats.length, repo.loadRoute("gpx_s").lats.length);
    }

    @Test
    public void sameShortcutTwiceReusesTheRoute() throws Exception {
        StoredRoute original = saveOriginal("gpx_t");
        RouteShortener.Variant v = RouteShortenService.suggest(original).get(0);
        RouteShortenService.Result first = service.create("gpx_t", v.fromIndex, v.toIndex);
        int size = repo.loadCatalog().size();

        RouteShortenService.Result again = service.create("gpx_t", v.fromIndex, v.toIndex);

        assertFalse(again.created);
        assertEquals(first.routeId, again.routeId);
        assertEquals(size, repo.loadCatalog().size());
    }

    @Test
    public void shortenedNameFallsBackForBlankNames() {
        assertEquals("Route (ingekort, 42 km)", RouteShortenService.shortenedName("  ", 42_000));
        assertEquals("Rit (ingekort, 7 km)", RouteShortenService.shortenedName("Rit", 7_200));
    }
}
