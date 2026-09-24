package nl.paree.climbpro.domain.matching;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredRoute;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;

public class NextClimbFinderTest {

    /** ~111 m per 0.001° latitude: a straight northbound route of 10 km along lon 5.0. */
    private static final double M_PER_DEG = 111_195.0;

    private static StoredClimb climb(String name, int start, int end, double gradient) {
        StoredClimb c = new StoredClimb();
        c.name = name;
        c.startDistance = start;
        c.endDistance = end;
        c.length = end - start;
        c.avgGradient = gradient;
        return c;
    }

    private static StoredRoute route(StoredClimb... climbs) {
        StoredRoute r = new StoredRoute();
        int n = 101;
        r.lats = new double[n];
        r.lons = new double[n];
        r.distances = new double[n];
        for (int i = 0; i < n; i++) {
            r.distances[i] = i * 100.0;
            r.lats[i] = 50.0 + r.distances[i] / M_PER_DEG;
            r.lons[i] = 5.0;
        }
        r.climbs = new ArrayList<>(Arrays.asList(climbs));
        return r;
    }

    private static double latAt(double metres) { return 50.0 + metres / M_PER_DEG; }

    @Test
    public void find_nextClimbAheadWithDistance() {
        StoredClimb a = climb("Keutenberg", 2000, 3000, 0.058);
        StoredClimb b = climb("Camerig", 6000, 8000, 0.041);
        NextClimbFinder.Answer ans = NextClimbFinder.find(route(a, b), latAt(3500), 5.0);

        assertSame(b, ans.climb);
        assertTrue(ans.onRoute);
        assertFalse(ans.onClimb);
        assertEquals(2500, ans.metresToGo, 5);
        assertEquals("De volgende klim is Camerig over 2,5 kilometer, 2,0 kilometer lang, "
                + "gemiddeld 4,1 procent.", ans.toSpeech());
    }

    @Test
    public void find_onClimbReportsDistanceToTop() {
        StoredClimb a = climb("Cauberg", 2000, 3000, 0.058);
        a.userDisplayName = "Cauberg Valkenburg";
        NextClimbFinder.Answer ans = NextClimbFinder.find(route(a), latAt(2600), 5.0);

        assertTrue(ans.onClimb);
        assertEquals(400, ans.metresToGo, 5);
        assertEquals("Je zit op Cauberg Valkenburg. Nog 400 meter tot de top.", ans.toSpeech());
    }

    @Test
    public void find_offRouteOrUnknownPositionCountsFromStart() {
        StoredClimb a = climb("Eyserbosweg", 2000, 3000, 0.08);
        NextClimbFinder.Answer far = NextClimbFinder.find(route(a), latAt(5000), 5.1); // ~7 km east
        NextClimbFinder.Answer none = NextClimbFinder.find(route(a), null, null);

        for (NextClimbFinder.Answer ans : new NextClimbFinder.Answer[]{far, none}) {
            assertFalse(ans.onRoute);
            assertSame(a, ans.climb);
            assertEquals(2000, ans.metresToGo);
            assertTrue(ans.toSpeech().startsWith("Ik weet niet waar je bent op de route."));
        }
    }

    @Test
    public void find_noClimbsLeft() {
        NextClimbFinder.Answer ans = NextClimbFinder.find(
                route(climb("A", 1000, 2000, 0.05)), latAt(9000), 5.0);
        assertNull(ans.climb);
        assertEquals("Er komen geen klimmen meer op deze route.", ans.toSpeech());
        assertEquals("Deze route heeft geen klimmen.",
                NextClimbFinder.find(route(), null, null).toSpeech());
    }

    @Test
    public void find_unorderedClimbListStillPicksNearestAhead() {
        StoredClimb late = climb("Laat", 7000, 8000, 0.05);
        StoredClimb early = climb("Vroeg", 4000, 5000, 0.05);
        assertSame(early, NextClimbFinder.find(route(late, early), latAt(1000), 5.0).climb);
    }

    @Test
    public void distance_roundsForSpeech() {
        assertEquals("850 meter", NextClimbFinder.distance(846));
        assertEquals("1,0 kilometer", NextClimbFinder.distance(1000));
        assertEquals("1,0 kilometer", NextClimbFinder.distance(995)); // not "1000 meter"
        assertEquals("12,3 kilometer", NextClimbFinder.distance(12_345));
    }
}
