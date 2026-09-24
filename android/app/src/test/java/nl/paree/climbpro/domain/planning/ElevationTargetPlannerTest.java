package nl.paree.climbpro.domain.planning;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import nl.paree.climbpro.domain.planning.ElevationTargetPlanner.Candidate;
import nl.paree.climbpro.domain.planning.ElevationTargetPlanner.Request;
import nl.paree.climbpro.domain.planning.ElevationTargetPlanner.Status;
import nl.paree.climbpro.domain.planning.ElevationTargetPlanner.Suggestion;

/** Pure JUnit test for the hoogtemeter-doel planner (issue #68). */
public class ElevationTargetPlannerTest {

    private static final double LAT0 = 45.0;
    private static final double LON0 = 6.0;
    /** ~1 km of latitude in degrees. */
    private static final double KM = 1.0 / 111.195;

    /** Climb whose start lies {@code northKm} north / {@code eastKm} east of the start point. */
    private static Candidate climb(String id, double northKm, double eastKm, int gain) {
        double lat = LAT0 + northKm * KM;
        double lon = LON0 + eastKm * KM / Math.cos(Math.toRadians(LAT0));
        // Short climbs whose top is ~500 m further north: keeps the tour geometry obvious.
        return new Candidate(id, "route-" + id, 0, "Klim " + id,
                lat, lon, lat + 0.5 * KM, lon, gain, 5000);
    }

    private static Request req(int target, double radiusKm, int maxClimbs) {
        return new Request(LAT0, LON0, target, radiusKm * 1000.0, maxClimbs);
    }

    private static Set<String> ids(Suggestion s) {
        Set<String> out = new HashSet<>();
        for (Candidate c : s.climbs) out.add(c.climbId);
        return out;
    }

    private static List<String> orderedIds(Suggestion s) {
        List<String> out = new ArrayList<>();
        for (Candidate c : s.climbs) out.add(c.climbId);
        return out;
    }

    @Test
    public void exactHitIsChosen() {
        List<Candidate> cands = Arrays.asList(
                climb("a", 2, 0, 500),
                climb("b", 4, 0, 700),
                climb("c", 6, 0, 800),
                climb("d", 8, 0, 300));
        Suggestion s = ElevationTargetPlanner.plan(req(1500, 30, 0), cands);
        assertEquals(Status.OK, s.status);
        assertEquals(new HashSet<>(Arrays.asList("b", "c")), ids(s));
        assertEquals(1500, s.totalGainM);
        assertEquals(0, s.deltaM);
    }

    @Test
    public void bestApproximationWhenNoExactHit() {
        List<Candidate> cands = Arrays.asList(
                climb("a", 2, 0, 400),
                climb("b", 3, 0, 650),
                climb("c", 5, 0, 900));
        Suggestion s = ElevationTargetPlanner.plan(req(1500, 30, 0), cands);
        assertEquals(new HashSet<>(Arrays.asList("b", "c")), ids(s));
        assertEquals(1550, s.totalGainM);
        assertEquals(50, s.deltaM);
    }

    @Test
    public void radiusFiltersFarClimbs() {
        List<Candidate> cands = Arrays.asList(
                climb("far", 50, 0, 1500),
                climb("near", 5, 0, 1000));
        Suggestion s = ElevationTargetPlanner.plan(req(1500, 20, 0), cands);
        assertEquals(Status.OK, s.status);
        assertEquals(Collections.singleton("near"), ids(s));
        assertEquals(1, s.candidatesInRadius);
        assertEquals(-500, s.deltaM);
    }

    @Test
    public void sameClimbFromTwoRoutesIsUsedOnce() {
        Candidate dupA = new Candidate("x", "route-1", 2, "Col X",
                LAT0 + 3 * KM, LON0, LAT0 + 4 * KM, LON0, 750, 8000);
        Candidate dupB = new Candidate("x", "route-2", 0, "Col X (andere route)",
                LAT0 + 3 * KM, LON0, LAT0 + 4 * KM, LON0, 750, 8000);
        Candidate other = climb("y", 6, 0, 700);
        Suggestion s = ElevationTargetPlanner.plan(req(1500, 30, 0), Arrays.asList(dupB, other, dupA));
        assertEquals(2, s.candidatesInRadius);
        assertEquals(2, s.climbs.size());
        assertEquals(new HashSet<>(Arrays.asList("x", "y")), ids(s));
        // Deterministic representative regardless of input order: smallest (routeId, index).
        for (Candidate c : s.climbs) {
            if ("x".equals(c.climbId)) assertEquals("route-1", c.routeId);
        }
    }

    @Test
    public void orderIsNearestNeighbourFromStart() {
        List<Candidate> cands = Arrays.asList(
                climb("c15", 15, 0, 500),
                climb("c5", 5, 0, 500),
                climb("c10", 10, 0, 500));
        Suggestion s = ElevationTargetPlanner.plan(req(1500, 30, 0), cands);
        assertEquals(Arrays.asList("c5", "c10", "c15"), orderedIds(s));
        assertEquals(4, s.legsM.length); // 3 arrivals + return leg
        // start->5 km, 5.5->10, 10.5->15, 15.5->start  =  5 + 4.5 + 4.5 + 15.5 = 29.5 km
        assertEquals(29_500, s.straightLineConnectM, 150);
        assertEquals(15_000, s.climbLengthM);
    }

    @Test
    public void twoOptFixesCrossingTour() {
        // Four climbs on the corners of a square; NN from a start in the middle-left can
        // produce a crossing order. The final tour must be no longer than any permutation
        // that starts with the same first climb, i.e. it must be free of obvious crossings.
        List<Candidate> cands = Arrays.asList(
                climb("ne", 10, 10, 300),
                climb("nw", 10, 0.5, 300),
                climb("se", -10, 10, 300),
                climb("sw", -10, 0.5, 300));
        Suggestion s = ElevationTargetPlanner.plan(req(1200, 30, 0), cands);
        assertEquals(4, s.climbs.size());
        double best = Double.POSITIVE_INFINITY;
        for (List<Candidate> perm : permutations(new ArrayList<>(s.climbs))) {
            best = Math.min(best, ElevationTargetPlanner.tourCost(LAT0, LON0, perm));
        }
        assertEquals(best, s.straightLineConnectM, 1.0);
    }

    @Test
    public void noClimbsAndInvalidInputs() {
        assertEquals(Status.NO_CLIMBS_IN_RADIUS,
                ElevationTargetPlanner.plan(req(1500, 30, 0), Collections.emptyList()).status);
        assertEquals(Status.NO_CLIMBS_IN_RADIUS,
                ElevationTargetPlanner.plan(req(1500, 30, 0), null).status);
        assertEquals(Status.INVALID_TARGET,
                ElevationTargetPlanner.plan(req(0, 30, 0), Arrays.asList(climb("a", 1, 0, 500))).status);
        assertEquals(Status.INVALID_TARGET,
                ElevationTargetPlanner.plan(req(-10, 30, 0), Arrays.asList(climb("a", 1, 0, 500))).status);
        assertEquals(Status.INVALID_RADIUS,
                ElevationTargetPlanner.plan(req(1500, 0, 0), Arrays.asList(climb("a", 1, 0, 500))).status);
        Suggestion none = ElevationTargetPlanner.plan(req(1500, 30, 0), Collections.emptyList());
        assertTrue(none.climbs.isEmpty());
        assertEquals(0, none.legsM.length);
    }

    @Test
    public void zeroGainClimbsAreIgnored() {
        Suggestion s = ElevationTargetPlanner.plan(req(500, 30, 0),
                Arrays.asList(climb("flat", 1, 0, 0)));
        assertEquals(Status.NO_CLIMBS_IN_RADIUS, s.status);
    }

    @Test
    public void maxClimbsIsRespected() {
        List<Candidate> cands = new ArrayList<>();
        for (int i = 0; i < 6; i++) cands.add(climb("c" + i, 1 + i, 0, 300));
        Suggestion s = ElevationTargetPlanner.plan(req(1500, 30, 3), cands);
        assertEquals(3, s.climbs.size());
        assertEquals(900, s.totalGainM);
        // The three nearest should be preferred (distance penalty).
        assertEquals(new HashSet<>(Arrays.asList("c0", "c1", "c2")), ids(s));
    }

    @Test
    public void tinyTargetStillSuggestsOneClimb() {
        Suggestion s = ElevationTargetPlanner.plan(req(50, 30, 0),
                Arrays.asList(climb("big", 3, 0, 800)));
        assertEquals(Status.OK, s.status);
        assertEquals(1, s.climbs.size());
        assertEquals(750, s.deltaM);
    }

    @Test
    public void fewerClimbsPreferredOnEqualFit() {
        List<Candidate> cands = Arrays.asList(
                climb("half1", 2, 0, 500),
                climb("half2", 2.5, 0, 500),
                climb("whole", 3, 0, 1000));
        Suggestion s = ElevationTargetPlanner.plan(req(1000, 30, 0), cands);
        assertEquals(Collections.singleton("whole"), ids(s));
    }

    @Test
    public void resultIsDeterministicRegardlessOfInputOrder() {
        List<Candidate> cands = new ArrayList<>();
        Random geo = new Random(7);
        for (int i = 0; i < 25; i++) {
            cands.add(climb("c" + i, geo.nextDouble() * 40 - 20, geo.nextDouble() * 40 - 20,
                    150 + geo.nextInt(700)));
        }
        List<String> expected = orderedIds(ElevationTargetPlanner.plan(req(2300, 25, 0), cands));
        assertTrue(!expected.isEmpty());
        Random shuffler = new Random(42);
        for (int round = 0; round < 10; round++) {
            List<Candidate> shuffled = new ArrayList<>(cands);
            Collections.shuffle(shuffled, shuffler);
            assertEquals(expected, orderedIds(ElevationTargetPlanner.plan(req(2300, 25, 0), shuffled)));
        }
    }

    private static List<List<Candidate>> permutations(List<Candidate> items) {
        List<List<Candidate>> out = new ArrayList<>();
        permute(items, 0, out);
        return out;
    }

    private static void permute(List<Candidate> items, int k, List<List<Candidate>> out) {
        if (k == items.size()) { out.add(new ArrayList<>(items)); return; }
        for (int i = k; i < items.size(); i++) {
            Collections.swap(items, k, i);
            permute(items, k + 1, out);
            Collections.swap(items, k, i);
        }
    }
}
