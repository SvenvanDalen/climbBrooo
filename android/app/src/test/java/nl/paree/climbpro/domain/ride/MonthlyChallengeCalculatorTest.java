package nl.paree.climbpro.domain.ride;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import nl.paree.climbpro.data.ride.StoredRide;
import nl.paree.climbpro.data.route.StoredClimbAttempt;
import nl.paree.climbpro.domain.ride.MonthlyChallengeCalculator.Progress;
import nl.paree.climbpro.domain.ride.MonthlyChallengeCalculator.Type;

import org.junit.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class MonthlyChallengeCalculatorTest {

    private static final ZoneId AMS = ZoneId.of("Europe/Amsterdam");
    private static final YearMonth SEP = YearMonth.of(2026, 9);

    private static long epoch(int y, int mo, int d) {
        return LocalDateTime.of(y, mo, d, 10, 0).atZone(AMS).toEpochSecond();
    }

    private static StoredRide ride(long start, float distanceM, float gainM) {
        StoredRide r = new StoredRide();
        r.startEpochSec = start;
        r.distanceM = distanceM;
        r.elevationGainM = gainM;
        return r;
    }

    private static StoredClimbAttempt attempt(String climbId, long date) {
        StoredClimbAttempt a = new StoredClimbAttempt();
        a.climbId = climbId;
        a.dateEpochSec = date;
        return a;
    }

    @Test
    public void distinctClimbs_countsEachClimbOnceWithinMonth() {
        List<StoredClimbAttempt> attempts = Arrays.asList(
                attempt("a", epoch(2026, 9, 1)),
                attempt("a", epoch(2026, 9, 5)),
                attempt("b", epoch(2026, 9, 30)),
                attempt("c", epoch(2026, 8, 31)),
                attempt("d", epoch(2026, 10, 1)));
        assertEquals(2, MonthlyChallengeCalculator.valueInMonth(
                Type.DISTINCT_CLIMBS, null, attempts, SEP, AMS));
    }

    @Test
    public void monthBoundary_usesLocalZone() {
        // 31 Aug 22:30 UTC = 1 Sep 00:30 in Amsterdam.
        long utcLateAugust = LocalDateTime.of(2026, 8, 31, 22, 30)
                .atZone(ZoneId.of("UTC")).toEpochSecond();
        List<StoredRide> rides = Collections.singletonList(ride(utcLateAugust, 10_000, 0));
        assertEquals(1, MonthlyChallengeCalculator.valueInMonth(Type.RIDES, rides, null, SEP, AMS));
        assertEquals(0, MonthlyChallengeCalculator.valueInMonth(
                Type.RIDES, rides, null, SEP, ZoneId.of("UTC")));
    }

    @Test
    public void rideMetrics_sumAndFloor() {
        List<StoredRide> rides = Arrays.asList(
                ride(epoch(2026, 9, 2), 40_600, 350.7f),
                ride(epoch(2026, 9, 3), 20_700, 100.6f),
                ride(0, 99_000, 999),           // unparsed start: skipped
                null);
        assertEquals(61, MonthlyChallengeCalculator.valueInMonth(
                Type.DISTANCE_KM, rides, null, SEP, AMS));
        assertEquals(451, MonthlyChallengeCalculator.valueInMonth(
                Type.ELEVATION_M, rides, null, SEP, AMS));
        assertEquals(2, MonthlyChallengeCalculator.valueInMonth(Type.RIDES, rides, null, SEP, AMS));
    }

    @Test
    public void progress_midMonthBehindAndAhead() {
        List<StoredRide> rides = Collections.singletonList(ride(epoch(2026, 9, 2), 0, 0));
        Progress p = MonthlyChallengeCalculator.progress(Type.RIDES, 4, rides, null, SEP,
                LocalDate.of(2026, 9, 15), AMS);
        assertEquals(1, p.current);
        assertEquals(2.0, p.expected, 1e-9);
        assertEquals(15, p.daysLeft);
        assertFalse(p.reached());
        assertEquals(0.25, p.fraction(), 1e-9);
        assertEquals("Nog 3 ritten in 15 dagen (achter op schema)",
                MonthlyChallengeCalculator.hint(p));

        Progress early = MonthlyChallengeCalculator.progress(Type.RIDES, 4, rides, null, SEP,
                LocalDate.of(2026, 9, 3), AMS);
        assertEquals("Nog 3 ritten in 27 dagen (op schema)",
                MonthlyChallengeCalculator.hint(early));
    }

    @Test
    public void progress_reachedAndLastDay() {
        List<StoredRide> rides = new ArrayList<>();
        for (int d = 1; d <= 4; d++) rides.add(ride(epoch(2026, 9, d), 0, 0));
        Progress done = MonthlyChallengeCalculator.progress(Type.RIDES, 4, rides, null, SEP,
                LocalDate.of(2026, 9, 10), AMS);
        assertTrue(done.reached());
        assertEquals(1.0, done.fraction(), 1e-9);
        assertEquals("Uitdaging gehaald!", MonthlyChallengeCalculator.hint(done));

        Progress last = MonthlyChallengeCalculator.progress(Type.RIDES, 10, rides, null, SEP,
                LocalDate.of(2026, 9, 30), AMS);
        assertEquals(0, last.daysLeft);
        assertEquals("Nog 6 ritten — laatste dag!", MonthlyChallengeCalculator.hint(last));
    }

    @Test
    public void suggestTarget_averagesPreviousMonthsWithStretch() {
        // Jun 300, Jul 600, Aug 900 m → avg 600 → +10% = 660 → rounded up to 700, but floor 1000.
        List<StoredRide> rides = Arrays.asList(
                ride(epoch(2026, 6, 10), 0, 300),
                ride(epoch(2026, 7, 10), 0, 600),
                ride(epoch(2026, 8, 10), 0, 900));
        assertEquals(1_000, MonthlyChallengeCalculator.suggestTarget(
                Type.ELEVATION_M, rides, null, SEP, AMS));

        // 3000 / 6000 / 9000 → avg 6000 → 6600 → multiple of 100 stays 6600.
        List<StoredRide> big = Arrays.asList(
                ride(epoch(2026, 6, 10), 0, 3000),
                ride(epoch(2026, 7, 10), 0, 6000),
                ride(epoch(2026, 8, 10), 0, 9000),
                ride(epoch(2026, 9, 1), 0, 50_000)); // current month ignored
        assertEquals(6_600, MonthlyChallengeCalculator.suggestTarget(
                Type.ELEVATION_M, big, null, SEP, AMS));
    }

    @Test
    public void suggestTarget_withoutHistoryReturnsFloor() {
        assertEquals(5, MonthlyChallengeCalculator.suggestTarget(
                Type.DISTINCT_CLIMBS, null, null, SEP, AMS));
        assertEquals(200, MonthlyChallengeCalculator.suggestTarget(
                Type.DISTANCE_KM, Collections.emptyList(), null, SEP, AMS));
    }

    @Test
    public void suggestTarget_distinctClimbsRoundsUpToWholeClimb() {
        List<StoredClimbAttempt> attempts = new ArrayList<>();
        // 6 distinct climbs per month over Jun–Aug → avg 6 → 6.6 → 7.
        for (int m = 6; m <= 8; m++) {
            for (int c = 0; c < 6; c++) attempts.add(attempt("c" + c, epoch(2026, m, 5)));
        }
        assertEquals(7, MonthlyChallengeCalculator.suggestTarget(
                Type.DISTINCT_CLIMBS, null, attempts, SEP, AMS));
    }

    @Test
    public void textHelpers() {
        assertEquals("2.000 hoogtemeters in september",
                MonthlyChallengeCalculator.title(Type.ELEVATION_M, 2000, SEP));
        Progress p = MonthlyChallengeCalculator.progress(Type.DISTINCT_CLIMBS, 10, null,
                Collections.singletonList(attempt("a", epoch(2026, 9, 1))), SEP,
                LocalDate.of(2026, 9, 29), AMS);
        assertEquals("1 / 10 klimmen", MonthlyChallengeCalculator.progressLine(p));
        assertEquals("Nog 9 klimmen in 1 dag (achter op schema)",
                MonthlyChallengeCalculator.hint(p));
    }

    @Test
    public void typeFromKey() {
        assertEquals(Type.RIDES, Type.fromKey("RIDES"));
        assertNull(Type.fromKey("NOPE"));
        assertNull(Type.fromKey(null));
    }
}
