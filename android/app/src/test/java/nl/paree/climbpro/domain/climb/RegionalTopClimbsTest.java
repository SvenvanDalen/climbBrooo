package nl.paree.climbpro.domain.climb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import nl.paree.climbpro.domain.climb.RegionalTopClimbs.Candidate;
import nl.paree.climbpro.domain.climb.RegionalTopClimbs.Ranked;

/** Pure JUnit test for the "Top 10 zwaarste klimmen in je regio" ranking (issue #211). */
public class RegionalTopClimbsTest {

    private static final double LAT0 = 45.0;
    private static final double LON0 = 6.0;
    /** ~1 km of latitude in degrees. */
    private static final double KM = 1.0 / 111.195;

    private static Candidate climb(String id, double northKm, int gain, double gradient) {
        return new Candidate(id, "route-" + id, 0, "Klim " + id,
                LAT0 + northKm * KM, LON0, gain, gradient, 5000);
    }

    private static List<String> ids(List<Ranked> ranked) {
        List<String> out = new ArrayList<>();
        for (Ranked r : ranked) out.add(r.candidate.climbId);
        return out;
    }

    @Test
    public void sortsByDifficultyScoreHardestFirst() {
        List<Candidate> cands = Arrays.asList(
                climb("easy", 1, 100, 0.04),    // 4
                climb("hard", 2, 500, 0.08),    // 40
                climb("medium", 3, 300, 0.06)); // 18
        List<Ranked> top = RegionalTopClimbs.top(LAT0, LON0, 50_000, cands, 10);
        assertEquals(Arrays.asList("hard", "medium", "easy"), ids(top));
        assertEquals(DifficultyScoreCalculator.score(500, 0.08, 0), top.get(0).score, 1e-9);
    }

    @Test
    public void excludesClimbsOutsideRadius() {
        List<Candidate> cands = Arrays.asList(
                climb("near", 5, 100, 0.05),
                climb("far", 30, 900, 0.10));
        List<Ranked> top = RegionalTopClimbs.top(LAT0, LON0, 10_000, cands, 10);
        assertEquals(Collections.singletonList("near"), ids(top));
        assertEquals(5000, top.get(0).distanceM, 50);
    }

    @Test
    public void truncatesToLimit() {
        List<Candidate> cands = new ArrayList<>();
        for (int i = 0; i < 15; i++) cands.add(climb("c" + i, 1, 100 + i * 10, 0.05));
        List<Ranked> top = RegionalTopClimbs.top(LAT0, LON0, 50_000, cands, 10);
        assertEquals(10, top.size());
        assertEquals("c14", top.get(0).candidate.climbId);
        assertEquals("c5", top.get(9).candidate.climbId);
    }

    @Test
    public void nonPositiveLimitUsesDefault() {
        List<Candidate> cands = new ArrayList<>();
        for (int i = 0; i < 15; i++) cands.add(climb("c" + i, 1, 100 + i, 0.05));
        assertEquals(RegionalTopClimbs.DEFAULT_LIMIT,
                RegionalTopClimbs.top(LAT0, LON0, 50_000, cands, 0).size());
    }

    @Test
    public void sameClimbFromSeveralRoutesAppearsOnce() {
        Candidate a = new Candidate("same", "route-1", 2, "Klim uit route 1",
                LAT0 + KM, LON0, 400, 0.07, 5000);
        Candidate b = new Candidate("same", "route-2", 0, "Klim uit route 2",
                LAT0 + KM, LON0, 400, 0.07, 5000);
        List<Ranked> top = RegionalTopClimbs.top(LAT0, LON0, 50_000,
                Arrays.asList(a, b, climb("other", 2, 100, 0.04)), 10);
        assertEquals(Arrays.asList("same", "other"), ids(top));
        // First occurrence wins so the choice is deterministic.
        assertEquals("route-1", top.get(0).candidate.routeId);
    }

    @Test
    public void duplicateKeepsHardestVersion() {
        Candidate weaker = new Candidate("same", "route-1", 0, "Oud", LAT0 + KM, LON0,
                300, 0.06, 5000);
        Candidate stronger = new Candidate("same", "route-2", 0, "Nieuw", LAT0 + KM, LON0,
                320, 0.065, 5000);
        List<Ranked> top = RegionalTopClimbs.top(LAT0, LON0, 50_000,
                Arrays.asList(weaker, stronger), 10);
        assertEquals(1, top.size());
        assertEquals("route-2", top.get(0).candidate.routeId);
    }

    @Test
    public void nullClimbIdFallsBackToRouteAndIndex() {
        Candidate a = new Candidate(null, "route-1", 0, "A", LAT0 + KM, LON0, 300, 0.06, 5000);
        Candidate b = new Candidate(null, "route-1", 1, "B", LAT0 + KM, LON0, 300, 0.06, 5000);
        assertEquals(2, RegionalTopClimbs.top(LAT0, LON0, 50_000, Arrays.asList(a, b), 10).size());
    }

    @Test
    public void equalScoreTieBreaksOnDistanceThenName() {
        List<Candidate> cands = Arrays.asList(
                climb("far", 8, 200, 0.05),
                climb("near", 2, 200, 0.05),
                new Candidate("b", "r", 0, "B", LAT0 + 2 * KM, LON0, 200, 0.05, 5000),
                new Candidate("a", "r", 1, "A", LAT0 + 2 * KM, LON0, 200, 0.05, 5000));
        List<Ranked> top = RegionalTopClimbs.top(LAT0, LON0, 50_000, cands, 10);
        assertEquals(Arrays.asList("a", "b", "near", "far"), ids(top));
    }

    @Test
    public void invalidInputYieldsEmptyList() {
        List<Candidate> cands = Collections.singletonList(climb("x", 1, 100, 0.05));
        assertTrue(RegionalTopClimbs.top(LAT0, LON0, 0, cands, 10).isEmpty());
        assertTrue(RegionalTopClimbs.top(LAT0, LON0, -5, cands, 10).isEmpty());
        assertTrue(RegionalTopClimbs.top(Double.NaN, LON0, 50_000, cands, 10).isEmpty());
        assertTrue(RegionalTopClimbs.top(LAT0, LON0, 50_000, null, 10).isEmpty());
    }

    @Test
    public void skipsNullCandidatesAndInvalidCoordinates() {
        Candidate broken = new Candidate("nan", "r", 0, "NaN", Double.NaN, LON0,
                900, 0.1, 5000);
        List<Candidate> cands = Arrays.asList(null, broken, climb("ok", 1, 100, 0.05));
        assertEquals(Collections.singletonList("ok"),
                ids(RegionalTopClimbs.top(LAT0, LON0, 50_000, cands, 10)));
    }
}
