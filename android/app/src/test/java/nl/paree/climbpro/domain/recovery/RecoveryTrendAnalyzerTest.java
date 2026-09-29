package nl.paree.climbpro.domain.recovery;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import nl.paree.climbpro.data.recovery.RecoveryCheck;
import nl.paree.climbpro.data.ride.StoredRide;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class RecoveryTrendAnalyzerTest {

    private static StoredRide ride(long id, long start, float km, int movingSec) {
        StoredRide r = new StoredRide();
        r.activityId = id;
        r.startEpochSec = start;
        r.distanceM = km * 1000f;
        r.movingTimeSec = movingSec;
        r.avgSpeedMps = movingSec > 0 ? (km * 1000f) / movingSec : 0f;
        return r;
    }

    private static RecoveryCheck check(long rideId, int rpe, int sleep, Float hours) {
        RecoveryCheck c = new RecoveryCheck();
        c.rideActivityId = rideId;
        c.rpe = rpe;
        c.sleepQuality = sleep;
        c.sleepHours = hours;
        return c;
    }

    @Test
    public void clamps() {
        assertEquals(1, RecoveryTrendAnalyzer.clampRpe(0));
        assertEquals(10, RecoveryTrendAnalyzer.clampRpe(14));
        assertEquals(7, RecoveryTrendAnalyzer.clampRpe(7));
        assertEquals(1, RecoveryTrendAnalyzer.clampSleepQuality(-3));
        assertEquals(5, RecoveryTrendAnalyzer.clampSleepQuality(9));
        assertNull(RecoveryTrendAnalyzer.clampSleepHours(null));
        assertNull(RecoveryTrendAnalyzer.clampSleepHours(0f));
        assertNull(RecoveryTrendAnalyzer.clampSleepHours(-1f));
        assertNull(RecoveryTrendAnalyzer.clampSleepHours(Float.NaN));
        assertEquals(24f, RecoveryTrendAnalyzer.clampSleepHours(30f), 0.0001f);
        assertEquals(7.5f, RecoveryTrendAnalyzer.clampSleepHours(7.54f), 0.0001f);
    }

    @Test
    public void sessionLoad_isRpeTimesMovingMinutes() {
        assertEquals(420, RecoveryTrendAnalyzer.sessionLoad(7, 3600));
        assertEquals(0, RecoveryTrendAnalyzer.sessionLoad(7, 0));
        assertEquals(15, RecoveryTrendAnalyzer.sessionLoad(10, 89)); // 10 × 89 s / 60 = 14.8 → 15
    }

    @Test
    public void emptyInput_givesEmptyTrend() {
        RecoveryTrendAnalyzer.Trend t = RecoveryTrendAnalyzer.analyze(null, null);
        assertTrue(t.points.isEmpty());
        assertFalse(t.hasComparison());
        assertEquals(RecoveryTrendAnalyzer.Direction.NONE, t.rpeDirection);
        assertEquals(RecoveryTrendAnalyzer.Direction.NONE, t.sleepDirection);
        assertFalse(t.recoveryWarning);
    }

    @Test
    public void points_joinRideData_sortedOldestFirst_skipUnknownRides() {
        List<StoredRide> rides = Arrays.asList(
                ride(1, 2000, 40f, 5400), ride(2, 1000, 20f, 3600));
        List<RecoveryCheck> checks = Arrays.asList(
                check(1, 6, 4, 8f), check(2, 3, 3, null), check(99, 9, 1, null), null);

        RecoveryTrendAnalyzer.Trend t = RecoveryTrendAnalyzer.analyze(checks, rides);

        assertEquals(2, t.points.size());
        RecoveryTrendAnalyzer.Point first = t.points.get(0);
        assertEquals(2, first.rideActivityId);
        assertEquals(1000, first.startEpochSec);
        assertEquals(3, first.rpe);
        assertEquals(20.0, first.distanceKm, 0.001);
        assertEquals(20.0, first.avgSpeedKmh, 0.01);
        assertEquals(180, first.sessionLoad);
        assertNull(first.sleepHours);
        RecoveryTrendAnalyzer.Point second = t.points.get(1);
        assertEquals(1, second.rideActivityId);
        assertEquals(540, second.sessionLoad);
        assertEquals(8f, second.sleepHours, 0.001f);
    }

    @Test
    public void points_clampStoredOutOfRangeValues() {
        RecoveryTrendAnalyzer.Trend t = RecoveryTrendAnalyzer.analyze(
                Collections.singletonList(check(1, 42, 0, 50f)),
                Collections.singletonList(ride(1, 1000, 10f, 1800)));
        assertEquals(10, t.points.get(0).rpe);
        assertEquals(1, t.points.get(0).sleepQuality);
        assertEquals(24f, t.points.get(0).sleepHours, 0.001f);
    }

    @Test
    public void fewerThanFourPoints_noComparison() {
        List<StoredRide> rides = new ArrayList<>();
        List<RecoveryCheck> checks = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            rides.add(ride(i, i * 1000L, 30f, 3600));
            checks.add(check(i, 5, 3, null));
        }
        RecoveryTrendAnalyzer.Trend t = RecoveryTrendAnalyzer.analyze(checks, rides);
        assertEquals(3, t.points.size());
        assertFalse(t.hasComparison());
        assertEquals(RecoveryTrendAnalyzer.Direction.NONE, t.rpeDirection);
        assertEquals(5.0, t.rpeAvgRecent, 0.001);
    }

    @Test
    public void comparison_lastWindowAgainstWindowBefore() {
        // 12 rides: the oldest 2 fall outside both windows, then 5 easy + well slept,
        // then 5 hard + badly slept.
        List<StoredRide> rides = new ArrayList<>();
        List<RecoveryCheck> checks = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            rides.add(ride(i, i * 86_400L, 30f, 3600));
            boolean recent = i > 7;
            boolean outside = i <= 2;
            checks.add(check(i, outside ? 10 : recent ? 8 : 4, outside ? 1 : recent ? 2 : 4, null));
        }
        RecoveryTrendAnalyzer.Trend t = RecoveryTrendAnalyzer.analyze(checks, rides);

        assertTrue(t.hasComparison());
        assertEquals(5, t.windowSize);
        assertEquals(8.0, t.rpeAvgRecent, 0.001);
        assertEquals(4.0, t.rpeAvgPrevious, 0.001);
        assertEquals(2.0, t.sleepAvgRecent, 0.001);
        assertEquals(4.0, t.sleepAvgPrevious, 0.001);
        assertEquals(RecoveryTrendAnalyzer.Direction.UP, t.rpeDirection);
        assertEquals(RecoveryTrendAnalyzer.Direction.DOWN, t.sleepDirection);
        assertTrue(t.recoveryWarning);
    }

    @Test
    public void comparison_smallWindowWhenFewPoints_andStableWithinThreshold() {
        List<StoredRide> rides = new ArrayList<>();
        List<RecoveryCheck> checks = new ArrayList<>();
        int[] rpes = {5, 6, 6, 5, 6, 5}; // previous avg 5.67, recent 5.33
        for (int i = 0; i < rpes.length; i++) {
            rides.add(ride(i + 1, (i + 1) * 1000L, 30f, 3600));
            checks.add(check(i + 1, rpes[i], 3, null));
        }
        RecoveryTrendAnalyzer.Trend t = RecoveryTrendAnalyzer.analyze(checks, rides);

        assertEquals(3, t.windowSize);
        assertEquals(RecoveryTrendAnalyzer.Direction.STABLE, t.rpeDirection);
        assertEquals(RecoveryTrendAnalyzer.Direction.STABLE, t.sleepDirection);
        assertFalse(t.recoveryWarning);
    }

    @Test
    public void harderButSleepingBetter_noWarning() {
        List<StoredRide> rides = new ArrayList<>();
        List<RecoveryCheck> checks = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            rides.add(ride(i, i * 1000L, 30f, 3600));
            checks.add(check(i, i <= 2 ? 3 : 8, i <= 2 ? 2 : 5, null));
        }
        RecoveryTrendAnalyzer.Trend t = RecoveryTrendAnalyzer.analyze(checks, rides);
        assertEquals(RecoveryTrendAnalyzer.Direction.UP, t.rpeDirection);
        assertEquals(RecoveryTrendAnalyzer.Direction.UP, t.sleepDirection);
        assertFalse(t.recoveryWarning);
    }

    @Test
    public void duplicateChecksForOneRide_lastWins() {
        RecoveryTrendAnalyzer.Trend t = RecoveryTrendAnalyzer.analyze(
                Arrays.asList(check(1, 3, 3, null), check(1, 9, 2, null)),
                Collections.singletonList(ride(1, 1000, 10f, 1800)));
        assertEquals(1, t.points.size());
        assertEquals(9, t.points.get(0).rpe);
    }
}
