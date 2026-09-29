package nl.paree.climbpro.domain.training;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import nl.paree.climbpro.data.ride.StoredRide;
import nl.paree.climbpro.data.route.StoredClimbAttempt;

import org.junit.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class TrainingLoadCalendarTest {

    private static final ZoneId ZONE = ZoneOffset.UTC;
    /** A Saturday. */
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 26);

    private static StoredRide ride(long id, LocalDate day, int movingSec, Integer normWatts) {
        StoredRide r = new StoredRide();
        r.activityId = id;
        r.type = "Ride";
        r.startEpochSec = day.atTime(8, 0).toEpochSecond(ZoneOffset.UTC);
        r.movingTimeSec = movingSec;
        r.elevationGainM = 500;
        if (normWatts != null) {
            r.deviceWatts = true;
            r.weightedAvgWatts = normWatts;
        }
        return r;
    }

    private static StoredClimbAttempt attempt(long activityId, LocalDate day, int elapsedSec) {
        StoredClimbAttempt a = new StoredClimbAttempt();
        a.climbId = "c" + activityId + "-" + elapsedSec;
        a.activityId = activityId;
        a.dateEpochSec = day.atTime(9, 0).toEpochSecond(ZoneOffset.UTC);
        a.elapsedSec = elapsedSec;
        return a;
    }

    private static TrainingLoadCalendar.Day dayOf(TrainingLoadCalendar.Result r, LocalDate d) {
        for (TrainingLoadCalendar.Day day : r.days) if (day.date.equals(d)) return day;
        return null;
    }

    @Test
    public void gridStartsOnMondayAndEndsToday() {
        TrainingLoadCalendar.Result r = TrainingLoadCalendar.compute(
                Collections.emptyList(), Collections.emptyList(), 250, TODAY, ZONE, 4);

        assertEquals(DayOfWeek.MONDAY, r.firstDay.getDayOfWeek());
        // 3 full weeks before this week + Monday..Saturday of this week.
        assertEquals(LocalDate.of(2026, 8, 31), r.firstDay);
        assertEquals(3 * 7 + 6, r.days.size());
        assertEquals(TODAY, r.days.get(r.days.size() - 1).date);
        assertEquals(4, r.weeks);
        assertEquals(0, r.activeDays);
        assertEquals(0, r.totalLoad, 1e-9);
        assertNull(r.busiestWeekStart);
    }

    @Test
    public void rideLoadUsesTrainingLoadAndSumsPerDay() {
        LocalDate d = TODAY.minusDays(2);
        List<StoredRide> rides = Arrays.asList(
                ride(1, d, 3600, 250),   // 1 h at FTP = 100
                ride(2, d, 1800, 250));  // 0.5 h at FTP = 50
        TrainingLoadCalendar.Result r = TrainingLoadCalendar.compute(
                rides, Collections.emptyList(), 250, TODAY, ZONE, 4);

        TrainingLoadCalendar.Day day = dayOf(r, d);
        assertEquals(150, day.load, 1e-9);
        assertEquals(2, day.rideCount);
        assertEquals(1000, day.elevationGainM, 1e-3);
        assertEquals(TrainingLoadCalendar.Level.HARD, day.level);
        assertEquals(1, r.activeDays);
        assertEquals(150, r.totalLoad, 1e-9);
    }

    @Test
    public void levelThresholds() {
        assertEquals(TrainingLoadCalendar.Level.NONE, TrainingLoadCalendar.levelOf(0));
        assertEquals(TrainingLoadCalendar.Level.LIGHT, TrainingLoadCalendar.levelOf(0.1));
        assertEquals(TrainingLoadCalendar.Level.LIGHT, TrainingLoadCalendar.levelOf(49.9));
        assertEquals(TrainingLoadCalendar.Level.MODERATE, TrainingLoadCalendar.levelOf(50));
        assertEquals(TrainingLoadCalendar.Level.HARD, TrainingLoadCalendar.levelOf(100));
        assertEquals(TrainingLoadCalendar.Level.VERY_HARD, TrainingLoadCalendar.levelOf(200));
    }

    @Test
    public void ridesOutsideWindowOrInFutureAreIgnored() {
        List<StoredRide> rides = Arrays.asList(
                ride(1, LocalDate.of(2026, 8, 30), 3600, 250), // Sunday before the grid
                ride(2, TODAY.plusDays(1), 3600, 250),
                ride(3, TODAY, 3600, 250));
        StoredRide noDate = ride(4, TODAY, 3600, 250);
        noDate.startEpochSec = 0;
        List<StoredRide> all = new ArrayList<>(rides);
        all.add(noDate);
        all.add(null);
        TrainingLoadCalendar.Result r = TrainingLoadCalendar.compute(
                all, null, 250, TODAY, ZONE, 4);

        assertEquals(1, r.activeDays);
        assertEquals(100, r.totalLoad, 1e-9);
    }

    @Test
    public void attemptsCountClimbsButDoNotDoubleCountArchivedRides() {
        LocalDate d = TODAY.minusDays(1);
        TrainingLoadCalendar.Result r = TrainingLoadCalendar.compute(
                Collections.singletonList(ride(1, d, 3600, 250)),
                Arrays.asList(attempt(1, d, 600), attempt(1, d, 900)),
                250, TODAY, ZONE, 4);

        TrainingLoadCalendar.Day day = dayOf(r, d);
        assertEquals(2, day.climbCount);
        assertEquals(100, day.load, 1e-9);
    }

    @Test
    public void attemptsWithoutArchivedRideAddClimbingLoad() {
        LocalDate d = TODAY.minusDays(3);
        // Two climbs of 30 min each in an activity that is not in the ride archive: 1 h at the
        // assumed climbing intensity.
        TrainingLoadCalendar.Result r = TrainingLoadCalendar.compute(
                Collections.emptyList(),
                Arrays.asList(attempt(77, d, 1800), attempt(77, d, 1800)),
                250, TODAY, ZONE, 4);

        TrainingLoadCalendar.Day day = dayOf(r, d);
        double i = TrainingLoadCalendar.CLIMB_INTENSITY;
        assertEquals(i * i * 100, day.load, 1e-9);
        assertEquals(0, day.rideCount);
        assertEquals(2, day.climbCount);
        assertEquals(1, r.activeDays);
    }

    @Test
    public void busiestWeekAndLongestStreak() {
        LocalDate monday = LocalDate.of(2026, 9, 14);
        List<StoredRide> rides = Arrays.asList(
                ride(1, monday, 3600, 250),
                ride(2, monday.plusDays(1), 3600, 250),
                ride(3, monday.plusDays(2), 3600, 250),
                ride(4, TODAY, 1800, 250),
                ride(5, TODAY.minusDays(1), 1800, 250));
        TrainingLoadCalendar.Result r = TrainingLoadCalendar.compute(
                rides, Collections.emptyList(), 250, TODAY, ZONE, 4);

        assertEquals(monday, r.busiestWeekStart);
        assertEquals(300, r.busiestWeekLoad, 1e-9);
        assertEquals(3, r.longestStreakDays);
        assertEquals(5, r.activeDays);
    }

    @Test
    public void weeksClampedToAtLeastOne() {
        TrainingLoadCalendar.Result r = TrainingLoadCalendar.compute(
                Collections.emptyList(), Collections.emptyList(), 0, TODAY, ZONE, 0);
        assertEquals(1, r.weeks);
        assertEquals(LocalDate.of(2026, 9, 21), r.firstDay);
        assertEquals(6, r.days.size());
    }

    @Test
    public void usesLocalDayOfRideStart() {
        ZoneId amsterdam = ZoneId.of("Europe/Amsterdam");
        StoredRide lateUtc = ride(1, TODAY.minusDays(1), 3600, 250);
        // 23:30 UTC on Friday is 01:30 on Saturday in Amsterdam (CEST).
        lateUtc.startEpochSec = TODAY.minusDays(1).atTime(23, 30).toEpochSecond(ZoneOffset.UTC);
        TrainingLoadCalendar.Result r = TrainingLoadCalendar.compute(
                Collections.singletonList(lateUtc), Collections.emptyList(), 250, TODAY,
                amsterdam, 4);
        assertEquals(100, dayOf(r, TODAY).load, 1e-9);
    }
}
