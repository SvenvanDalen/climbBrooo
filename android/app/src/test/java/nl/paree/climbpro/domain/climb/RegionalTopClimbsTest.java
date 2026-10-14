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
        return new Candidate.Builder()
                .climbId(id)
                .routeId("route-" + id)
                .climbIndex(0)
                .name("Klim " + id)
                .startLat(LAT0 + northKm * KM)
                .startLon(LON0)
                .elevationGainM(gain)
                .avgGradient(gradient)
                .lengthM(5000)
                .build();
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
        Candidate a = new Candidate.Builder()
                .climbId("same")
                .routeId("route-1")
                .climbIndex(2)
                .name("Klim uit route 1")
                .startLat(LAT0 + KM)
                .startLon(LON0)
                .elevationGainM(400)
                .avgGradient(0.07)
                .lengthM(5000)
                .build();
        Candidate b = new Candidate.Builder()
                .climbId("same")
                .routeId("route-2")
                .climbIndex(0)
                .name("Klim uit route 2")
                .startLat(LAT0 + KM)
                .startLon(LON0)
                .elevationGainM(400)
                .avgGradient(0.07)
                .lengthM(5000)
                .build();
        List<Ranked> top = RegionalTopClimbs.top(LAT0, LON0, 50_000,
                Arrays.asList(a, b, climb("other", 2, 100, 0.04)), 10);
        assertEquals(Arrays.asList("same", "other"), ids(top));
        // First occurrence wins so the choice is deterministic.
        assertEquals("route-1", top.get(0).candidate.routeId);
    }

    @Test
    public void duplicateKeepsHardestVersion() {
        Candidate weaker = new Candidate.Builder()
                .climbId("same")
                .routeId("route-1")
                .climbIndex(0)
                .name("Oud")
                .startLat(LAT0 + KM)
                .startLon(LON0)
                .elevationGainM(300)
                .avgGradient(0.06)
                .lengthM(5000)
                .build();
        Candidate stronger = new Candidate.Builder()
                .climbId("same")
                .routeId("route-2")
                .climbIndex(0)
                .name("Nieuw")
                .startLat(LAT0 + KM)
                .startLon(LON0)
                .elevationGainM(320)
                .avgGradient(0.065)
                .lengthM(5000)
                .build();
        List<Ranked> top = RegionalTopClimbs.top(LAT0, LON0, 50_000,
                Arrays.asList(weaker, stronger), 10);
        assertEquals(1, top.size());
        assertEquals("route-2", top.get(0).candidate.routeId);
    }

    @Test
    public void nullClimbIdFallsBackToRouteAndIndex() {
        Candidate a = new Candidate.Builder()
                .climbId(null)
                .routeId("route-1")
                .climbIndex(0)
                .name("A")
                .startLat(LAT0 + KM)
                .startLon(LON0)
                .elevationGainM(300)
                .avgGradient(0.06)
                .lengthM(5000)
                .build();
        Candidate b = new Candidate.Builder()
                .climbId(null)
                .routeId("route-1")
                .climbIndex(1)
                .name("B")
                .startLat(LAT0 + KM)
                .startLon(LON0)
                .elevationGainM(300)
                .avgGradient(0.06)
                .lengthM(5000)
                .build();
        assertEquals(2, RegionalTopClimbs.top(LAT0, LON0, 50_000, Arrays.asList(a, b), 10).size());
    }

    @Test
    public void equalScoreTieBreaksOnDistanceThenName() {
        List<Candidate> cands = Arrays.asList(
                climb("far", 8, 200, 0.05),
                climb("near", 2, 200, 0.05),
                new Candidate.Builder()
                        .climbId("b")
                        .routeId("r")
                        .climbIndex(0)
                        .name("B")
                        .startLat(LAT0 + 2 * KM)
                        .startLon(LON0)
                        .elevationGainM(200)
                        .avgGradient(0.05)
                        .lengthM(5000)
                        .build(),
                new Candidate.Builder()
                        .climbId("a")
                        .routeId("r")
                        .climbIndex(1)
                        .name("A")
                        .startLat(LAT0 + 2 * KM)
                        .startLon(LON0)
                        .elevationGainM(200)
                        .avgGradient(0.05)
                        .lengthM(5000)
                        .build());
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
        Candidate broken = new Candidate.Builder()
                .climbId("nan")
                .routeId("r")
                .climbIndex(0)
                .name("NaN")
                .startLat(Double.NaN)
                .startLon(LON0)
                .elevationGainM(900)
                .avgGradient(0.1)
                .lengthM(5000)
                .build();
        List<Candidate> cands = Arrays.asList(null, broken, climb("ok", 1, 100, 0.05));
        assertEquals(Collections.singletonList("ok"),
                ids(RegionalTopClimbs.top(LAT0, LON0, 50_000, cands, 10)));
    }
}
