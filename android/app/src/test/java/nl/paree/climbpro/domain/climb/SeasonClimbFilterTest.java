package nl.paree.climbpro.domain.climb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;

import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredClimbAttempt;
import nl.paree.climbpro.data.route.StoredRoute;

/**
 * TDD for the period filter behind the batch/season GPX export (issue #91): which
 * (route, climb) pairs had at least one attempt in a chosen period.
 */
public class SeasonClimbFilterTest {

    private static long epochSec(int year, int month, int day) {
        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        cal.clear();
        cal.set(year, month - 1, day, 12, 0, 0);
        return cal.getTimeInMillis() / 1000L;
    }

    private static StoredClimb climb(double lat, double lon, int length) {
        StoredClimb c = new StoredClimb();
        c.startLat = lat;
        c.startLon = lon;
        c.length = length;
        c.startDistance = 0;
        c.endDistance = length;
        return c;
    }

    private static StoredRoute routeWith(String routeId, StoredClimb... climbs) {
        StoredRoute r = new StoredRoute();
        r.routeId = routeId;
        r.climbs = new ArrayList<>();
        for (StoredClimb c : climbs) r.climbs.add(c);
        return r;
    }

    private static StoredClimbAttempt attempt(String climbId, long dateEpochSec) {
        StoredClimbAttempt a = new StoredClimbAttempt();
        a.climbId = climbId;
        a.dateEpochSec = dateEpochSec;
        a.elapsedSec = 300;
        return a;
    }

    @Test
    public void includesClimbWithAttemptInsidePeriod() {
        StoredClimb c = climb(50.500, 5.500, 1200);
        StoredRoute route = routeWith("r1", c);
        String climbId = ClimbIdentity.of(c.startLat, c.startLon, c.length);

        List<StoredClimbAttempt> attempts =
                java.util.Collections.singletonList(attempt(climbId, epochSec(2026, 6, 15)));
        long[] range = SeasonClimbFilter.yearRange(2026);

        List<SeasonClimbFilter.Match> matches = SeasonClimbFilter.climbsInPeriod(
                java.util.Collections.singletonList(route), attempts, range[0], range[1]);

        assertEquals(1, matches.size());
        assertEquals(route, matches.get(0).route);
        assertEquals(c, matches.get(0).climb);
    }

    @Test
    public void excludesClimbWithNoAttemptInPeriod() {
        StoredClimb c = climb(50.500, 5.500, 1200);
        StoredRoute route = routeWith("r1", c);
        String climbId = ClimbIdentity.of(c.startLat, c.startLon, c.length);

        // Attempt exists, but in a different year.
        List<StoredClimbAttempt> attempts =
                java.util.Collections.singletonList(attempt(climbId, epochSec(2025, 6, 15)));
        long[] range = SeasonClimbFilter.yearRange(2026);

        List<SeasonClimbFilter.Match> matches = SeasonClimbFilter.climbsInPeriod(
                java.util.Collections.singletonList(route), attempts, range[0], range[1]);

        assertTrue(matches.isEmpty());
    }

    @Test
    public void excludesClimbWithNoAttemptsAtAll() {
        StoredClimb ridden = climb(50.500, 5.500, 1200);
        StoredClimb neverRidden = climb(51.500, 6.500, 900);
        StoredRoute route = routeWith("r1", ridden, neverRidden);
        String riddenId = ClimbIdentity.of(ridden.startLat, ridden.startLon, ridden.length);

        List<StoredClimbAttempt> attempts =
                java.util.Collections.singletonList(attempt(riddenId, epochSec(2026, 3, 1)));
        long[] range = SeasonClimbFilter.yearRange(2026);

        List<SeasonClimbFilter.Match> matches = SeasonClimbFilter.climbsInPeriod(
                java.util.Collections.singletonList(route), attempts, range[0], range[1]);

        assertEquals(1, matches.size());
        assertEquals(ridden, matches.get(0).climb);
    }

    @Test
    public void periodEndIsExclusive() {
        StoredClimb c = climb(50.500, 5.500, 1200);
        StoredRoute route = routeWith("r1", c);
        String climbId = ClimbIdentity.of(c.startLat, c.startLon, c.length);
        long[] range = SeasonClimbFilter.yearRange(2026);

        // Exactly at the start of the next year -> not in 2026.
        List<StoredClimbAttempt> attempts =
                java.util.Collections.singletonList(attempt(climbId, range[1]));

        List<SeasonClimbFilter.Match> matches = SeasonClimbFilter.climbsInPeriod(
                java.util.Collections.singletonList(route), attempts, range[0], range[1]);

        assertTrue(matches.isEmpty());
    }

    @Test
    public void matchesMultipleRoutesInOrder() {
        StoredClimb c1 = climb(50.500, 5.500, 1200);
        StoredClimb c2 = climb(51.500, 6.500, 900);
        StoredRoute r1 = routeWith("r1", c1);
        StoredRoute r2 = routeWith("r2", c2);
        String id1 = ClimbIdentity.of(c1.startLat, c1.startLon, c1.length);
        String id2 = ClimbIdentity.of(c2.startLat, c2.startLon, c2.length);

        List<StoredClimbAttempt> attempts = new ArrayList<>();
        attempts.add(attempt(id1, epochSec(2026, 4, 1)));
        attempts.add(attempt(id2, epochSec(2026, 8, 1)));
        long[] range = SeasonClimbFilter.yearRange(2026);

        List<SeasonClimbFilter.Match> matches = SeasonClimbFilter.climbsInPeriod(
                java.util.Arrays.asList(r1, r2), attempts, range[0], range[1]);

        assertEquals(2, matches.size());
        assertEquals(r1, matches.get(0).route);
        assertEquals(r2, matches.get(1).route);
    }

    @Test
    public void yearRangeSpansExactlyOneCalendarYear() {
        long[] range2026 = SeasonClimbFilter.yearRange(2026);
        assertEquals(epochSec(2026, 1, 1) - 12 * 3600, range2026[0]); // midnight, not noon
        assertEquals(range2026[1], SeasonClimbFilter.yearRange(2027)[0]);
    }

    @Test
    public void yearsWithAttemptsReturnsDistinctYearsNewestFirst() {
        List<StoredClimbAttempt> attempts = new ArrayList<>();
        attempts.add(attempt("c1", epochSec(2024, 5, 1)));
        attempts.add(attempt("c2", epochSec(2026, 5, 1)));
        attempts.add(attempt("c3", epochSec(2025, 5, 1)));
        attempts.add(attempt("c4", epochSec(2025, 9, 1))); // duplicate year

        List<Integer> years = SeasonClimbFilter.yearsWithAttempts(attempts);

        assertEquals(java.util.Arrays.asList(2026, 2025, 2024), years);
    }

    @Test
    public void yearsWithAttemptsEmptyWhenNoAttempts() {
        assertTrue(SeasonClimbFilter.yearsWithAttempts(new ArrayList<>()).isEmpty());
        assertTrue(SeasonClimbFilter.yearsWithAttempts(null).isEmpty());
    }
}
