package nl.paree.climbpro.domain.climb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredClimbAttempt;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.data.route.StoredSegment;
import nl.paree.climbpro.domain.power.RiderProfile;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class ClimbComparisonTest {

    /** A climb of {@code segs} equal segments of 100 m at the given gradients. */
    private static StoredClimb climb(String name, double lat, double... gradients) {
        StoredClimb c = new StoredClimb();
        c.name = name;
        c.startLat = lat;
        c.startLon = 6.0;
        c.segments = new ArrayList<>();
        double gain = 0;
        for (double g : gradients) {
            StoredSegment s = new StoredSegment();
            s.distance = 100;
            s.gradient = g;
            s.elevationGain = (int) Math.round(100 * g);
            c.segments.add(s);
            gain += 100 * g;
        }
        c.length = 100 * gradients.length;
        c.startDistance = 0;
        c.endDistance = c.length;
        c.elevationGain = (int) Math.round(gain);
        c.avgGradient = gain / c.length;
        return c;
    }

    private static StoredClimbAttempt attempt(StoredClimb c, int sec) {
        StoredClimbAttempt a = new StoredClimbAttempt();
        a.climbId = ClimbIdentity.of(c.startLat, c.startLon, c.length);
        a.elapsedSec = sec;
        return a;
    }

    @Test
    public void sideCollectsStatsHistoryAndProfile() {
        StoredClimb c = climb("Muur", 50.0, 0.05, 0.12, 0.08, 0.05);
        List<StoredClimbAttempt> attempts = Arrays.asList(attempt(c, 300), attempt(c, 280),
                attempt(climb("Ander", 51.0, 0.05, 0.05), 100));
        ClimbComparison.Side s = ClimbComparison.side("r", 2, c, attempts,
                new RiderProfile(250, 70, 8));

        assertEquals("Muur", s.name);
        assertEquals(400, s.lengthM);
        assertEquals(30, s.elevationGainM);
        assertEquals(0.12, s.maxSegmentGradient, 1e-9);
        assertEquals(2, s.attempts);
        assertEquals(Integer.valueOf(280), s.prSec);
        assertNotNull(s.estimateSec);
        assertTrue(s.estimateSec > 0);
        assertEquals(5, s.profileDist.length);
        assertEquals(400, s.profileDist[4], 1e-9);
        assertEquals(30, s.profileHeight[4], 1e-9);
        assertEquals(DifficultyScoreCalculator.score(30, 0.075, 0), s.difficulty, 1e-9);
    }

    @Test
    public void noProfileOrHistoryLeavesThoseEmpty() {
        ClimbComparison.Side s = ClimbComparison.side("r", 0, climb("Kort", 50.0, 0.06),
                Collections.emptyList(), new RiderProfile(0, 0, 0));
        assertNull(s.estimateSec);
        assertNull(s.prSec);
        assertEquals(0, s.attempts);
    }

    @Test
    public void verdictNamesTheHarderClimb() {
        RiderProfile none = new RiderProfile(0, 0, 0);
        ClimbComparison.Side big = ClimbComparison.side("a", 0,
                climb("Groot", 50, 0.08, 0.08, 0.08, 0.08), null, none);
        ClimbComparison.Side small = ClimbComparison.side("b", 0,
                climb("Klein", 51, 0.04, 0.04), null, none);
        // 32 m x 8 % against 8 m x 4 %: eight times as hard.
        assertEquals("Groot is 8,0× zo zwaar als Klein.",
                ClimbComparison.verdict(small, big));
        ClimbComparison.Side twin = ClimbComparison.side("c", 0,
                climb("Tweeling", 52, 0.08, 0.08, 0.08, 0.08), null, none);
        assertTrue(ClimbComparison.verdict(big, twin).contains("ongeveer even zwaar"));
    }

    @Test
    public void candidatesAreDedupedSortedAndExcludeTheFirstClimb() {
        StoredClimb a = climb("Alpe", 45.0, 0.08, 0.08);
        StoredClimb b = climb("Zoncolan", 46.0, 0.1, 0.1);
        StoredClimb bAgain = climb("Zoncolan", 46.0, 0.1, 0.1);
        StoredClimb c = climb("Bonette", 47.0, 0.07, 0.07);
        StoredRoute r1 = new StoredRoute();
        r1.routeId = "r1";
        r1.climbs = new ArrayList<>(Arrays.asList(a, b));
        StoredRoute r2 = new StoredRoute();
        r2.routeId = "r2";
        r2.climbs = new ArrayList<>(Arrays.asList(bAgain, c));

        List<ClimbComparison.Candidate> list = ClimbComparison.candidates(
                Arrays.asList(r1, r2), a);
        assertEquals(2, list.size());
        assertEquals("Bonette", list.get(0).name);
        assertEquals("r2", list.get(0).routeId);
        assertEquals(1, list.get(0).climbIndex);
        assertEquals("Zoncolan", list.get(1).name);
        assertEquals("r1", list.get(1).routeId);
    }
}
