package nl.paree.climbpro.domain.climb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import nl.paree.climbpro.data.route.StoredClimbAttempt;
import nl.paree.climbpro.domain.climb.ElevationGoalCalculator.Period;
import nl.paree.climbpro.domain.climb.ElevationGoalCalculator.Progress;

import org.junit.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ElevationGoalCalculatorTest {

    private static final ZoneId ZONE = ZoneOffset.UTC;

    private static StoredClimbAttempt at(String climbId, LocalDate day) {
        StoredClimbAttempt a = new StoredClimbAttempt();
        a.climbId = climbId;
        a.dateEpochSec = day.atStartOfDay(ZONE).toEpochSecond();
        a.elapsedSec = 600;
        return a;
    }

    @Test
    public void noAttempts_zeroGain_noCrash() {
        int gain = ElevationGoalCalculator.cumulativeGainM(
                Collections.emptyList(), new HashMap<>(), Period.WEEK, ZONE, LocalDate.of(2026, 9, 22));
        assertEquals(0, gain);
    }

    @Test
    public void nullElevationMap_zeroGain_noCrash() {
        List<StoredClimbAttempt> attempts = Arrays.asList(at("k1", LocalDate.of(2026, 9, 22)));
        int gain = ElevationGoalCalculator.cumulativeGainM(
                attempts, null, Period.WEEK, ZONE, LocalDate.of(2026, 9, 22));
        assertEquals(0, gain);
    }

    @Test
    public void weekWindow_includesOnlyMondayToSunday() {
        // 2026-09-22 is a Tuesday; the week is Mon 2026-09-21 .. Sun 2026-09-27.
        LocalDate today = LocalDate.of(2026, 9, 22);
        Map<String, Integer> elev = new HashMap<>();
        elev.put("k1", 500);
        elev.put("k2", 300);
        elev.put("k3", 900);

        List<StoredClimbAttempt> attempts = Arrays.asList(
                at("k1", LocalDate.of(2026, 9, 21)),  // Monday: inside
                at("k2", LocalDate.of(2026, 9, 22)),  // Tuesday (today): inside
                at("k3", LocalDate.of(2026, 9, 20)),  // Sunday before: outside
                at("k3", LocalDate.of(2026, 9, 28)));  // Monday after: outside

        int gain = ElevationGoalCalculator.cumulativeGainM(attempts, elev, Period.WEEK, ZONE, today);

        assertEquals(500 + 300, gain);
    }

    @Test
    public void monthWindow_includesOnlyCurrentCalendarMonth() {
        LocalDate today = LocalDate.of(2026, 9, 22);
        Map<String, Integer> elev = new HashMap<>();
        elev.put("k1", 500);
        elev.put("k2", 300);
        elev.put("k3", 900);

        List<StoredClimbAttempt> attempts = Arrays.asList(
                at("k1", LocalDate.of(2026, 9, 1)),   // first of month: inside
                at("k2", LocalDate.of(2026, 9, 30)),  // last of month: inside
                at("k3", LocalDate.of(2026, 8, 31)),  // previous month: outside
                at("k3", LocalDate.of(2026, 10, 1)));  // next month: outside

        int gain = ElevationGoalCalculator.cumulativeGainM(attempts, elev, Period.MONTH, ZONE, today);

        assertEquals(500 + 300, gain);
    }

    @Test
    public void cumulativeSum_addsAcrossMultipleAttemptsAndClimbs() {
        LocalDate today = LocalDate.of(2026, 9, 22);
        Map<String, Integer> elev = new HashMap<>();
        elev.put("k1", 400);
        elev.put("k2", 250);

        List<StoredClimbAttempt> attempts = Arrays.asList(
                at("k1", LocalDate.of(2026, 9, 21)),
                at("k1", LocalDate.of(2026, 9, 22)),
                at("k2", LocalDate.of(2026, 9, 22)));

        int gain = ElevationGoalCalculator.cumulativeGainM(attempts, elev, Period.WEEK, ZONE, today);

        assertEquals(400 + 400 + 250, gain);
    }

    @Test
    public void unresolvableClimbId_skippedNotCrashed() {
        LocalDate today = LocalDate.of(2026, 9, 22);
        Map<String, Integer> elev = new HashMap<>();
        elev.put("known", 500);

        List<StoredClimbAttempt> attempts = Arrays.asList(
                at("known", today),
                at("deleted-route-climb", today));

        int gain = ElevationGoalCalculator.cumulativeGainM(attempts, elev, Period.WEEK, ZONE, today);

        assertEquals(500, gain);
    }

    @Test
    public void progress_fraction_zeroWhenGoalNotSet() {
        Progress p = new Progress(1200, 0);
        assertEquals(0.0, p.fraction(), 0.0001);
    }

    @Test
    public void progress_fraction_computesRatio() {
        Progress p = new Progress(1500, 3000);
        assertEquals(0.5, p.fraction(), 0.0001);
    }

    @Test
    public void progress_fraction_canExceedOneWhenGoalMet() {
        Progress p = new Progress(4000, 3000);
        assertTrue(p.fraction() > 1.0);
    }

    @Test
    public void monthlyGoalScalesWithMonthLength() {
        assertEquals(4000, ElevationGoalCalculator.monthlyGoalFromWeekly(1000,
                java.time.LocalDate.of(2026, 2, 10)));  // 28 days = exactly 4 weeks
        assertEquals(4429, ElevationGoalCalculator.monthlyGoalFromWeekly(1000,
                java.time.LocalDate.of(2026, 7, 10)));  // 31 days
        assertEquals(0, ElevationGoalCalculator.monthlyGoalFromWeekly(0,
                java.time.LocalDate.of(2026, 7, 10)));
    }

    private static nl.paree.climbpro.data.ride.StoredRide ride(LocalDate day, float gainM) {
        nl.paree.climbpro.data.ride.StoredRide r = new nl.paree.climbpro.data.ride.StoredRide();
        r.startEpochSec = day.atTime(10, 0).atZone(ZONE).toEpochSecond();
        r.elevationGainM = gainM;
        return r;
    }

    @Test
    public void rideGain_sumsWholeRidesInPeriod() {
        LocalDate wed = LocalDate.of(2026, 9, 23);
        List<nl.paree.climbpro.data.ride.StoredRide> rides = Arrays.asList(
                ride(LocalDate.of(2026, 9, 21), 650),   // Monday: in week and month
                ride(wed, 420.4f),                      // in week and month
                ride(LocalDate.of(2026, 9, 20), 900),   // Sunday before: month only
                ride(LocalDate.of(2026, 8, 31), 1000)); // previous month
        assertEquals(1070, ElevationGoalCalculator.cumulativeRideGainM(rides, Period.WEEK, ZONE, wed));
        assertEquals(1970, ElevationGoalCalculator.cumulativeRideGainM(rides, Period.MONTH, ZONE, wed));
    }

    @Test
    public void rideGain_skipsUndatedAndEmpty() {
        nl.paree.climbpro.data.ride.StoredRide undated = ride(LocalDate.of(2026, 9, 23), 500);
        undated.startEpochSec = 0;
        assertEquals(0, ElevationGoalCalculator.cumulativeRideGainM(
                Collections.singletonList(undated), Period.WEEK, ZONE, LocalDate.of(2026, 9, 23)));
        assertEquals(0, ElevationGoalCalculator.cumulativeRideGainM(
                null, Period.WEEK, ZONE, LocalDate.of(2026, 9, 23)));
    }
}
