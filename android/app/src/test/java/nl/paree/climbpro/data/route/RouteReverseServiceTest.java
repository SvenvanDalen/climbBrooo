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
import nl.paree.climbpro.domain.climb.ClimbDetector;
import nl.paree.climbpro.domain.route.CumulativeDistance;
import nl.paree.climbpro.domain.route.RoutePoint;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Issue #201: reversing a stored route creates a separate, re-detected route. */
@RunWith(RobolectricTestRunner.class)
public class RouteReverseServiceTest {

    private static final double STEP_DEG = 50.0 / 111_195.0;

    private RouteRepository repo;
    private RouteReverseService service;

    @Before
    public void setUp() {
        Application app = ApplicationProvider.getApplicationContext();
        SharedPreferences prefs = app.getSharedPreferences("route_repo", Context.MODE_PRIVATE);
        prefs.edit().putInt("segment_version", ClimbConstants.SEGMENT_VERSION).commit();
        repo = new RouteRepository(app, (lat, lon) -> null);
        service = new RouteReverseService(repo);
    }

    /** Flat 1 km, up 2 km @ 6 %, flat 1 km, down 1.5 km @ 5 %, flat 1 km. */
    private static List<RoutePoint> profile() {
        List<RoutePoint> raw = new ArrayList<>();
        double ele = 100;
        int idx = 0;
        double[][] stretches = {{20, 0.0}, {40, 0.06}, {20, 0.0}, {30, -0.05}, {20, 0.0}};
        for (double[] st : stretches) {
            for (int s = 0; s < (int) st[0]; s++) {
                raw.add(new RoutePoint(51.0 + idx * STEP_DEG, 5.0, ele + s * 50 * st[1], 0));
                idx++;
            }
            ele += st[0] * 50 * st[1];
        }
        raw.add(new RoutePoint(51.0 + idx * STEP_DEG, 5.0, ele, 0));
        return CumulativeDistance.compute(raw);
    }

    private StoredRoute saveOriginal(String id) throws Exception {
        List<RoutePoint> pts = profile();
        List<Climb> climbs = ClimbDetector.detect(pts);
        StoredRoute r = new StoredRoute();
        r.routeId = id;
        r.name = "Rondje";
        r.notes = "mijn notitie";
        r.sourceHash = "hash";
        repo.saveRoute(r, pts, climbs);
        repo.renameClimb(id, 0, "Mijn klim");
        return repo.loadRoute(id);
    }

    @Test
    public void createsNewReversedRouteWithReDetectedClimbs() throws Exception {
        StoredRoute before = saveOriginal("gpx_a");

        RouteReverseService.Result res = service.reverse("gpx_a");

        assertTrue(res.created);
        assertEquals("rev_gpx_a", res.routeId);
        StoredRoute rev = repo.loadRoute("rev_gpx_a");
        assertEquals("Rondje (omgekeerd)", rev.name);
        assertEquals(before.lats[0], rev.lats[rev.lats.length - 1], 0.0);
        assertEquals(0.0, rev.distances[0], 0.0);
        assertEquals(1, rev.climbs.size());
        assertEquals(0.05, rev.climbs.get(0).avgGradient, 0.005);
        assertEquals(res.climbCount, rev.climbs.size());
        // Different climbs: the original's climb rename must not carry over.
        assertFalse("Mijn klim".equals(rev.climbs.get(0).userDisplayName));
        assertNull(rev.notes);
        assertNotNull(catalogEntry("rev_gpx_a"));
    }

    @Test
    public void leavesOriginalUntouched() throws Exception {
        StoredRoute before = saveOriginal("gpx_b");
        service.reverse("gpx_b");
        StoredRoute after = repo.loadRoute("gpx_b");
        assertEquals(before.name, after.name);
        assertEquals(before.lats[0], after.lats[0], 0.0);
        assertEquals(before.lastModifiedMs, after.lastModifiedMs);
        assertEquals("Mijn klim", after.climbs.get(0).userDisplayName);
        assertEquals(0.06, after.climbs.get(0).avgGradient, 0.005);
    }

    @Test
    public void secondReverseReusesExistingRouteInsteadOfDuplicating() throws Exception {
        saveOriginal("gpx_c");
        service.reverse("gpx_c");
        repo.renameRoute("rev_gpx_c", "Andersom");
        int catalogSize = repo.loadCatalog().size();

        RouteReverseService.Result again = service.reverse("gpx_c");

        assertFalse(again.created);
        assertEquals("rev_gpx_c", again.routeId);
        assertEquals(catalogSize, repo.loadCatalog().size());
        assertEquals("Andersom", repo.loadRoute("rev_gpx_c").userDisplayName);
    }

    @Test
    public void reversingTheReversedRouteOpensTheOriginal() throws Exception {
        saveOriginal("gpx_d");
        service.reverse("gpx_d");
        int catalogSize = repo.loadCatalog().size();

        RouteReverseService.Result back = service.reverse("rev_gpx_d");

        assertFalse(back.created);
        assertEquals("gpx_d", back.routeId);
        assertEquals(catalogSize, repo.loadCatalog().size());
    }

    @Test
    public void usesUserDisplayNameAsBaseName() throws Exception {
        saveOriginal("gpx_e");
        repo.renameRoute("gpx_e", "Heuvelland");
        service.reverse("gpx_e");
        assertEquals("Heuvelland (omgekeerd)", repo.loadRoute("rev_gpx_e").name);
    }

    @Test(expected = java.io.IOException.class)
    public void missingRouteThrows() throws Exception {
        service.reverse("does_not_exist");
    }

    private RouteCatalogEntry catalogEntry(String routeId) {
        for (RouteCatalogEntry e : repo.loadCatalog()) {
            if (e.routeId.equals(routeId)) return e;
        }
        return null;
    }
}
