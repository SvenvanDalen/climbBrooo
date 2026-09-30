package nl.paree.climbpro.domain.ride;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import nl.paree.climbpro.data.ride.StoredRide;
import nl.paree.climbpro.domain.ride.YearlyDistanceGoalCalculator.Pace;
import nl.paree.climbpro.domain.ride.YearlyDistanceGoalCalculator.Progress;

import org.junit.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class YearlyDistanceGoalCalculatorTest {

    private static final ZoneId AMS = ZoneId.of("Europe/Amsterdam");
    private static final double EPS = 1e-9;

    private static StoredRide ride(String type, float distanceM, long startEpochSec) {
        StoredRide r = new StoredRide();
        r.type = type;
        r.distanceM = distanceM;
        r.startEpochSec = startEpochSec;
        return r;
    }

    private static long epoch(int y, int mo, int d, int h, int mi, ZoneId zone) {
        return LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toEpochSecond();
    }

    // --- year boundaries -----------------------------------------------------------------

    @Test
    public void yearBoundary_usesLocalZoneNotUtc() {
        // 31 Dec 2025 23:30 UTC = 1 Jan 2026 00:30 in Amsterdam → counts for 2026 there.
        long newYearsNightUtc = epoch(2025, 12, 31, 23, 30, ZoneId.of("UTC"));
        List<StoredRide> rides = Collections.singletonList(ride("Ride", 20_000, newYearsNightUtc));

        assertEquals(20_000, YearlyDistanceGoalCalculator.distanceInYearM(rides, 2026, AMS), EPS);
        assertEquals(0, YearlyDistanceGoalCalculator.distanceInYearM(rides, 2025, AMS), EPS);
        assertEquals(0, YearlyDistanceGoalCalculator.distanceInYearM(
                rides, 2026, ZoneId.of("UTC")), EPS);
    }

    @Test
    public void yearBoundary_lastSecondOfYearAndFirstSecondOfNextYear() {
        List<StoredRide> rides = Arrays.asList(
                ride("Ride", 10_000, epoch(2026, 1, 1, 0, 0, AMS)),       // first second of 2026
                ride("Ride", 30_000, epoch(2026, 12, 31, 23, 59, AMS)),   // last minute of 2026
                ride("Ride", 50_000, epoch(2027, 1, 1, 0, 0, AMS)),       // 2027
                ride("Ride", 70_000, epoch(2025, 12, 31, 23, 59, AMS)));  // 2025
        assertEquals(40_000, YearlyDistanceGoalCalculator.distanceInYearM(rides, 2026, AMS), EPS);
    }

    // --- which rides count ---------------------------------------------------------------

    @Test
    public void undatedAndNullRides_areSkipped() {
        List<StoredRide> rides = new ArrayList<>();
        rides.add(ride("Ride", 25_000, 0));      // start date failed to parse
        rides.add(ride("Ride", 25_000, -5));
        rides.add(null);
        rides.add(ride("Ride", 12_000, epoch(2026, 5, 1, 9, 0, AMS)));
        assertEquals(12_000, YearlyDistanceGoalCalculator.distanceInYearM(rides, 2026, AMS), EPS);
    }

    @Test
    public void virtualRides_count() {
        List<StoredRide> rides = Arrays.asList(
                ride("VirtualRide", 30_000, epoch(2026, 2, 1, 19, 0, AMS)),
                ride("GravelRide", 40_000, epoch(2026, 3, 1, 10, 0, AMS)));
        assertEquals(70_000, YearlyDistanceGoalCalculator.distanceInYearM(rides, 2026, AMS), EPS);
    }

    @Test
    public void nullRideList_isZero() {
        Progress p = YearlyDistanceGoalCalculator.compute(null, 5000,
                LocalDate.of(2026, 6, 1), AMS);
        assertEquals(0, p.riddenKm, EPS);
        assertEquals(Pace.BEHIND_SCHEDULE, p.pace);
    }

    // --- expected / pace -----------------------------------------------------------------

    @Test
    public void expected_nonLeapYear() {
        // 2026 has 365 days; 1 July = day 182.
        assertEquals(3650 * 182 / 365.0,
                YearlyDistanceGoalCalculator.expectedKm(3650, LocalDate.of(2026, 7, 1)), EPS);
        assertEquals(3650, YearlyDistanceGoalCalculator.expectedKm(
                3650, LocalDate.of(2026, 12, 31)), EPS);
        assertEquals(10, YearlyDistanceGoalCalculator.expectedKm(
                3650, LocalDate.of(2026, 1, 1)), EPS);
    }

    @Test
    public void expected_leapYear_uses366Days() {
        // 2028 is a leap year; 1 July = day 183 of 366.
        assertEquals(3660 * 183 / 366.0,
                YearlyDistanceGoalCalculator.expectedKm(3660, LocalDate.of(2028, 7, 1)), EPS);
        assertEquals(3660, YearlyDistanceGoalCalculator.expectedKm(
                3660, LocalDate.of(2028, 12, 31)), EPS);
        // 29 Feb = day 60 of 366 → 60 km per 366 km of goal.
        assertEquals(60, YearlyDistanceGoalCalculator.expectedKm(
                366, LocalDate.of(2028, 2, 29)), EPS);
    }

    @Test
    public void pace_behind_onSchedule_reached() {
        LocalDate today = LocalDate.of(2026, 7, 1); // expected = 3650*182/365 = 1820 km
        long t = epoch(2026, 3, 1, 10, 0, AMS);

        Progress behind = YearlyDistanceGoalCalculator.compute(
                Collections.singletonList(ride("Ride", 1_000_000, t)), 3650, today, AMS);
        assertEquals(Pace.BEHIND_SCHEDULE, behind.pace);
        assertEquals(1820, behind.expectedKm, EPS);
        assertEquals(-820, behind.scheduleDeltaKm(), EPS);
        assertEquals(1000 / 3650.0, behind.fraction, EPS);

        Progress onSchedule = YearlyDistanceGoalCalculator.compute(
                Collections.singletonList(ride("Ride", 1_820_000, t)), 3650, today, AMS);
        assertEquals(Pace.ON_SCHEDULE, onSchedule.pace);

        Progress reached = YearlyDistanceGoalCalculator.compute(
                Collections.singletonList(ride("Ride", 4_000_000, t)), 3650, today, AMS);
        assertEquals(Pace.GOAL_REACHED, reached.pace);
        assertEquals(1.0, reached.fraction, EPS); // clamped
    }

    @Test
    public void compute_usesYearOfToday() {
        List<StoredRide> rides = Arrays.asList(
                ride("Ride", 100_000, epoch(2025, 6, 1, 10, 0, AMS)),
                ride("Ride", 200_000, epoch(2026, 6, 1, 10, 0, AMS)));
        Progress p = YearlyDistanceGoalCalculator.compute(rides, 5000,
                LocalDate.of(2026, 9, 23), AMS);
        assertEquals(2026, p.year);
        assertEquals(200, p.riddenKm, EPS);
    }

    // --- no goal ---------------------------------------------------------------------------

    @Test
    public void noGoal_zeroOrNegative() {
        List<StoredRide> rides = Collections.singletonList(
                ride("Ride", 1_234_000, epoch(2026, 4, 1, 10, 0, AMS)));
        for (int goal : new int[]{0, -100}) {
            Progress p = YearlyDistanceGoalCalculator.compute(rides, goal,
                    LocalDate.of(2026, 9, 23), AMS);
            assertFalse(p.hasGoal());
            assertEquals(Pace.NO_GOAL, p.pace);
            assertEquals(0, p.goalKm);
            assertEquals(0, p.fraction, EPS);
            assertEquals(0, p.expectedKm, EPS);
            assertEquals(1234, p.riddenKm, EPS);
            assertEquals("1.234 km in 2026", YearlyDistanceGoalCalculator.headline(p));
            assertEquals("", YearlyDistanceGoalCalculator.paceHint(p));
        }
    }

    // --- formatting ------------------------------------------------------------------------

    @Test
    public void headlineAndHints_dutchFormatting() {
        LocalDate today = LocalDate.of(2026, 7, 1); // expected 1820 of 3650
        long t = epoch(2026, 3, 1, 10, 0, AMS);

        Progress behind = YearlyDistanceGoalCalculator.compute(
                Collections.singletonList(ride("Ride", 1_234_900, t)), 5000, today, AMS);
        assertTrue(behind.hasGoal());
        assertEquals("1.234 / 5.000 km in 2026", YearlyDistanceGoalCalculator.headline(behind));

        Progress behind2 = YearlyDistanceGoalCalculator.compute(
                Collections.singletonList(ride("Ride", 1_000_000, t)), 3650, today, AMS);
        assertEquals("820 km achter op schema", YearlyDistanceGoalCalculator.paceHint(behind2));

        Progress ahead = YearlyDistanceGoalCalculator.compute(
                Collections.singletonList(ride("Ride", 2_000_000, t)), 3650, today, AMS);
        assertEquals("Op schema (180 km voor)", YearlyDistanceGoalCalculator.paceHint(ahead));

        Progress exact = YearlyDistanceGoalCalculator.compute(
                Collections.singletonList(ride("Ride", 1_820_000, t)), 3650, today, AMS);
        assertEquals("Op schema", YearlyDistanceGoalCalculator.paceHint(exact));

        Progress reached = YearlyDistanceGoalCalculator.compute(
                Collections.singletonList(ride("Ride", 3_650_000, t)), 3650, today, AMS);
        assertEquals("Doel gehaald!", YearlyDistanceGoalCalculator.paceHint(reached));
    }
}
