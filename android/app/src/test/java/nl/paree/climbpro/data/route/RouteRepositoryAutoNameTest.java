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
import nl.paree.climbpro.domain.climb.ClimbNameSuggester;
import nl.paree.climbpro.domain.route.RoutePoint;
import nl.paree.climbpro.domain.segment.Segment;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * {@link RouteRepository#saveRoute} fills in a climb's auto-suggested {@code name} via
 * {@link ClimbNameSuggester} on first detection only, and never overwrites a name the user
 * (or a matched Strava segment) already gave the climb (backlog #109).
 */
@RunWith(RobolectricTestRunner.class)
public class RouteRepositoryAutoNameTest {

    private Application app;

    @Before
    public void setUp() {
        app = ApplicationProvider.getApplicationContext();
        // Skip migrateIfNeeded()'s wipe of route files.
        SharedPreferences prefs = app.getSharedPreferences("route_repo", Context.MODE_PRIVATE);
        prefs.edit().putInt("segment_version", ClimbConstants.SEGMENT_VERSION).commit();
    }

    private static StoredRoute routeShell(String routeId) {
        StoredRoute r = new StoredRoute();
        r.routeId = routeId;
        r.name = "Test";
        return r;
    }

    private static List<RoutePoint> points() {
        List<RoutePoint> pts = new ArrayList<>();
        pts.add(new RoutePoint(51.00, 5.0, 100, 0));
        pts.add(new RoutePoint(51.01, 5.0, 130, 500));
        pts.add(new RoutePoint(51.02, 5.0, 160, 1000));
        pts.add(new RoutePoint(51.03, 5.0, 160, 1500));
        return pts;
    }

    private static List<Climb> climbWithName(String name) {
        List<Segment> segs = new ArrayList<>();
        segs.add(new Segment(500, 30, 0.06, 3));
        segs.add(new Segment(500, 30, 0.06, 3));
        Climb.Builder b = Climb.builder()
                .startDistance(0).endDistance(1000)
                .length(1000).elevationGain(60).avgGradient(0.06)
                .startLat(51.0).startLon(5.0)
                .segments(segs);
        if (name != null) b.name(name);
        List<Climb> out = new ArrayList<>();
        out.add(b.build());
        return out;
    }

    /** Fake suggester that always returns a fixed name and counts how often it's called. */
    private static final class FixedSuggester implements ClimbNameSuggester {
        final AtomicInteger calls = new AtomicInteger();
        final String result;
        FixedSuggester(String result) { this.result = result; }
        @Override public String suggestName(double lat, double lon) {
            calls.incrementAndGet();
            return result;
        }
    }

    @Test
    public void saveRoute_fillsMissingNameFromSuggester() throws Exception {
        FixedSuggester suggester = new FixedSuggester("Klim bij Voeren");
        RouteRepository repo = new RouteRepository(app, suggester);

        repo.saveRoute(routeShell("r1"), points(), climbWithName(null));

        StoredRoute reloaded = repo.loadRoute("r1");
        assertEquals("Klim bij Voeren", reloaded.climbs.get(0).name);
        assertEquals(1, suggester.calls.get());
    }

    @Test
    public void saveRoute_doesNotOverwriteExistingName() throws Exception {
        FixedSuggester suggester = new FixedSuggester("Klim bij Voeren");
        RouteRepository repo = new RouteRepository(app, suggester);

        // e.g. a name already matched from a starred Strava segment.
        repo.saveRoute(routeShell("r1"), points(), climbWithName("Mont Ventoux"));

        StoredRoute reloaded = repo.loadRoute("r1");
        assertEquals("Mont Ventoux", reloaded.climbs.get(0).name);
        assertEquals(0, suggester.calls.get());
    }

    @Test
    public void saveRoute_doesNotOverwriteUserRename() throws Exception {
        FixedSuggester suggester = new FixedSuggester("Klim bij Voeren");
        RouteRepository repo = new RouteRepository(app, suggester);

        repo.saveRoute(routeShell("r1"), points(), climbWithName(null));
        repo.renameClimb("r1", 0, "Mijn favoriete klim");

        StoredRoute reloaded = repo.loadRoute("r1");
        assertEquals("Mijn favoriete klim", reloaded.climbs.get(0).userDisplayName);
        assertEquals("Klim bij Voeren", reloaded.climbs.get(0).name);
    }

    @Test
    public void saveRoute_suggesterFailure_leavesNameNull() throws Exception {
        ClimbNameSuggester failing = (lat, lon) -> { throw new RuntimeException("no network"); };
        RouteRepository repo = new RouteRepository(app, failing);

        repo.saveRoute(routeShell("r1"), points(), climbWithName(null));

        StoredRoute reloaded = repo.loadRoute("r1");
        assertNull(reloaded.climbs.get(0).name);
    }

    @Test
    public void saveRoute_doesNotResuggestOnReimport() throws Exception {
        FixedSuggester suggester = new FixedSuggester("Klim bij Voeren");
        RouteRepository repo = new RouteRepository(app, suggester);

        repo.saveRoute(routeShell("r1"), points(), climbWithName(null));
        assertEquals(1, suggester.calls.get());

        // Re-import: fresh climb at the SAME startDistance, name null again.
        repo.saveRoute(routeShell("r1"), points(), climbWithName(null));

        StoredRoute reloaded = repo.loadRoute("r1");
        assertEquals("Klim bij Voeren", reloaded.climbs.get(0).name);
        assertEquals("suggester must not be called again for an already-named climb",
                1, suggester.calls.get());
    }
}
