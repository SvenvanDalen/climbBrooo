package nl.paree.climbpro.domain.share;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import nl.paree.climbpro.data.route.ClimbMembership;
import nl.paree.climbpro.data.route.RouteCollection;
import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredRoute;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ClimbShareExtractorTest {

    /** Route of 101 points every 100 m (0-10 km) with one climb per {start, end} pair. */
    private static StoredRoute route(String id, int... startEnd) {
        StoredRoute r = new StoredRoute();
        r.routeId = id;
        int n = 101;
        r.lats = new double[n];
        r.lons = new double[n];
        r.elevations = new double[n];
        r.distances = new double[n];
        for (int i = 0; i < n; i++) {
            r.lats[i] = 50 + i * 0.0009;
            r.lons[i] = 5;
            r.elevations[i] = i;
            r.distances[i] = i * 100;
        }
        r.climbs = new ArrayList<>();
        for (int k = 0; k + 1 < startEnd.length; k += 2) {
            StoredClimb c = new StoredClimb();
            c.startDistance = startEnd[k];
            c.endDistance = startEnd[k + 1];
            c.name = "Klim " + id + k / 2;
            r.climbs.add(c);
        }
        return r;
    }

    @Test
    public void extractAddsMarginAndOnePointBeyond() {
        StoredRoute r = route("a", 3000, 5000);
        SharedClimb c = ClimbShareExtractor.extract(r, 0);
        // Margin 200 m: 2800..5200 m, plus one point on each side: 2700..5300 m.
        assertEquals(27, c.size());
        assertEquals(r.lats[27], c.lats[0], 0);
        assertEquals(r.elevations[53], c.elevations[26], 0);
        assertEquals("Klim a0", c.name);
    }

    @Test
    public void extractClampsAtRouteEndsAndPrefersDisplayName() {
        StoredRoute r = route("a", 0, 1000);
        r.climbs.get(0).userDisplayName = "Mijn klim";
        SharedClimb c = ClimbShareExtractor.extract(r, 0);
        assertEquals(0, r.distances[0], 0);
        assertEquals(r.lats[0], c.lats[0], 0);
        assertEquals(14, c.size()); // 0..1200 m + one beyond the end
        assertEquals("Mijn klim", c.name);
    }

    @Test
    public void homeClimbsAndMissingGeometryAreNotShared() {
        StoredRoute r = route("a", 3000, 5000);
        r.climbs.get(0).isHome = true;
        assertNull(ClimbShareExtractor.extract(r, 0));
        StoredRoute noGeo = route("b", 3000, 5000);
        noGeo.lats = null;
        assertNull(ClimbShareExtractor.extract(noGeo, 0));
        assertNull(ClimbShareExtractor.extract(noGeo, 5));
    }

    @Test
    public void collectTakesClimbMembersThenRouteClimbsOnce() {
        StoredRoute a = route("a", 1000, 2000, 6000, 8000);
        StoredRoute b = route("b", 2000, 4000);
        b.climbs.get(0).isHome = true;
        Map<String, StoredRoute> byId = new HashMap<>();
        byId.put("a", a);
        byId.put("b", b);
        RouteCollection col = new RouteCollection();
        col.climbs = new ArrayList<>(Arrays.asList(new ClimbMembership("a", 1),
                new ClimbMembership("b", 0), new ClimbMembership("gone", 0)));
        col.routeIds = new ArrayList<>(Arrays.asList("a"));

        ClimbShareExtractor.Selection s = ClimbShareExtractor.collect(col, byId);
        assertEquals(2, s.climbs.size());
        assertEquals("Klim a1", s.climbs.get(0).name);
        assertEquals("Klim a0", s.climbs.get(1).name);
        assertEquals(1, s.skippedHome);
        assertEquals(0, s.skippedOverLimit);
    }

    @Test
    public void collectStopsAtTheCodeLimit() {
        List<String> ids = new ArrayList<>();
        Map<String, StoredRoute> byId = new HashMap<>();
        for (int i = 0; i < ClimbShareCode.MAX_CLIMBS + 3; i++) {
            ids.add("r" + i);
            byId.put("r" + i, route("r" + i, 1000, 3000));
        }
        RouteCollection col = new RouteCollection();
        col.routeIds = ids;
        ClimbShareExtractor.Selection s = ClimbShareExtractor.collect(col, byId);
        assertEquals(ClimbShareCode.MAX_CLIMBS, s.climbs.size());
        assertEquals(3, s.skippedOverLimit);
    }
}
