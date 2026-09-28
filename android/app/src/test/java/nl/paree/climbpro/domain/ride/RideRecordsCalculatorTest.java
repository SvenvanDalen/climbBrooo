package nl.paree.climbpro.domain.ride;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import nl.paree.climbpro.data.ride.StoredRide;
import nl.paree.climbpro.domain.ride.RideRecordsCalculator.Records;
import nl.paree.climbpro.domain.ride.RideRecordsCalculator.Streak;

import org.junit.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class RideRecordsCalculatorTest {

    private static final ZoneId AMS = ZoneId.of("Europe/Amsterdam");

    private static StoredRide ride(long id, String startLocal, float km, float kmh) {
        StoredRide r = new StoredRide();
        r.activityId = id;
        r.name = "Rit " + id;
        r.type = "Ride";
        r.startEpochSec = startLocal == null ? 0
                : LocalDateTime.parse(startLocal).atZone(AMS).toEpochSecond();
        r.distanceM = km * 1000f;
        r.avgSpeedMps = kmh / 3.6f;
        r.movingTimeSec = kmh > 0 ? Math.round(km / kmh * 3600f) : 0;
        return r;
    }

    private static Records compute(StoredRide... rides) {
        return RideRecordsCalculator.compute(Arrays.asList(rides), AMS);
    }

    @Test
    public void emptyAndNullList_haveNoRecords() {
        assertTrue(RideRecordsCalculator.compute(Collections.emptyList(), AMS).isEmpty());
        assertTrue(RideRecordsCalculator.compute(null, AMS).isEmpty());
    }

    @Test
    public void longestDistance_picksLongestRide() {
        StoredRide a = ride(1, "2026-05-01T10:00", 50, 25);
        StoredRide b = ride(2, "2026-05-03T10:00", 120, 25);
        StoredRide c = ride(3, "2026-05-05T10:00", 80, 25);
        assertSame(b, compute(a, b, c).longestDistance);
    }

    @Test
    public void mostElevationAndLongestMovingTime() {
        StoredRide a = ride(1, "2026-05-01T10:00", 60, 30);   // 2 h
        StoredRide b = ride(2, "2026-05-03T10:00", 50, 20);   // 2.5 h
        a.elevationGainM = 1500;
        b.elevationGainM = 400;
        Records rec = compute(a, b);
        assertSame(a, rec.mostElevation);
        assertSame(b, rec.longestMovingTime);
    }

    @Test
    public void zeroValues_neverHoldARecord() {
        StoredRide flat = ride(1, "2026-05-01T10:00", 0, 0);
        Records rec = compute(flat);
        assertNull(rec.longestDistance);
        assertNull(rec.fastestAvgSpeed);
        assertNull(rec.mostElevation);
        assertNull(rec.longestMovingTime);
    }

    @Test
    public void fastestSpeed_ignoresRidesBelowMinDistance() {
        StoredRide sprint = ride(1, "2026-05-01T10:00", 5, 40);
        StoredRide shortOfMin = ride(2, "2026-05-02T10:00", 19.9f, 38);
        StoredRide real = ride(3, "2026-05-03T10:00", 60, 31);
        assertSame(real, compute(sprint, shortOfMin, real).fastestAvgSpeed);
    }

    @Test
    public void fastestSpeed_exactlyMinDistanceQualifies() {
        StoredRide atMin = ride(1, "2026-05-01T10:00",
                (float) (RideRecordsCalculator.SPEED_MIN_DISTANCE_M / 1000), 33);
        assertSame(atMin, compute(atMin).fastestAvgSpeed);
    }

    @Test
    public void fastestSpeed_excludesVirtualRide_butOtherRecordsIncludeIt() {
        StoredRide zwift = ride(1, "2026-05-01T10:00", 150, 42);
        zwift.type = "VirtualRide";
        zwift.elevationGainM = 2000;
        StoredRide road = ride(2, "2026-05-02T10:00", 70, 29);
        Records rec = compute(zwift, road);
        assertSame(road, rec.fastestAvgSpeed);
        assertSame(zwift, rec.longestDistance);
        assertSame(zwift, rec.mostElevation);
    }

    @Test
    public void fastestSpeed_excludesEBikeRides_butOtherRecordsIncludeThem() {
        StoredRide ebike = ride(1, "2026-05-01T10:00", 90, 32);
        ebike.type = "EBikeRide";
        StoredRide emtb = ride(2, "2026-05-02T10:00", 30, 31);
        emtb.type = "EMountainBikeRide";
        StoredRide road = ride(3, "2026-05-03T10:00", 60, 28);
        Records rec = compute(ebike, emtb, road);
        assertSame(road, rec.fastestAvgSpeed);
        assertSame(ebike, rec.longestDistance);
    }

    @Test
    public void fastestSpeed_noneWhenOnlyShortOrVirtualRides() {
        StoredRide zwift = ride(1, "2026-05-01T10:00", 40, 38);
        zwift.type = "VirtualRide";
        assertNull(compute(zwift, ride(2, "2026-05-02T10:00", 10, 30)).fastestAvgSpeed);
    }

    @Test
    public void ties_earliestRideKeepsRecord_regardlessOfListOrder() {
        StoredRide later = ride(1, "2026-06-01T10:00", 100, 30);
        StoredRide earlier = ride(2, "2026-05-01T10:00", 100, 30);
        assertSame(earlier, compute(later, earlier).longestDistance);
        assertSame(earlier, compute(earlier, later).longestDistance);
        assertSame(earlier, compute(later, earlier).fastestAvgSpeed);
    }

    @Test
    public void ties_datedRideBeatsUndated() {
        StoredRide undated = ride(1, null, 100, 30);
        StoredRide dated = ride(2, "2026-05-01T10:00", 100, 30);
        assertSame(dated, compute(undated, dated).longestDistance);
    }

    @Test
    public void undatedRide_canHoldDistanceRecord_butIsSkippedForStreak() {
        StoredRide undated = ride(1, null, 200, 30);
        Records rec = compute(undated);
        assertSame(undated, rec.longestDistance);
        assertNull(rec.longestStreak);
    }

    @Test
    public void streak_singleRideIsOneDay() {
        Streak s = compute(ride(1, "2026-05-01T10:00", 30, 25)).longestStreak;
        assertEquals(1, s.days);
        assertEquals(LocalDate.of(2026, 5, 1), s.firstDay);
        assertEquals(LocalDate.of(2026, 5, 1), s.lastDay);
    }

    @Test
    public void streak_multipleRidesSameDayCountOnce() {
        Streak s = compute(
                ride(1, "2026-05-01T07:00", 10, 25),
                ride(2, "2026-05-01T17:30", 10, 25),
                ride(3, "2026-05-02T08:00", 30, 25),
                ride(4, "2026-05-02T19:00", 30, 25)).longestStreak;
        assertEquals(2, s.days);
    }

    @Test
    public void streak_acrossMonthAndYearBoundary() {
        Streak s = compute(
                ride(1, "2025-12-30T10:00", 30, 25),
                ride(2, "2025-12-31T10:00", 30, 25),
                ride(3, "2026-01-01T10:00", 30, 25),
                ride(4, "2026-01-02T10:00", 30, 25),
                ride(5, "2026-01-10T10:00", 30, 25)).longestStreak;
        assertEquals(4, s.days);
        assertEquals(LocalDate.of(2025, 12, 30), s.firstDay);
        assertEquals(LocalDate.of(2026, 1, 2), s.lastDay);
    }

    @Test
    public void streak_acrossMonthEndInLeapYear() {
        Streak s = compute(
                ride(1, "2028-02-28T10:00", 30, 25),
                ride(2, "2028-02-29T10:00", 30, 25),
                ride(3, "2028-03-01T10:00", 30, 25)).longestStreak;
        assertEquals(3, s.days);
    }

    @Test
    public void streak_gapBreaksRun_andLongestWins_regardlessOfOrder() {
        List<StoredRide> rides = new ArrayList<>(Arrays.asList(
                ride(1, "2026-05-01T10:00", 30, 25),
                ride(2, "2026-05-02T10:00", 30, 25),
                ride(3, "2026-05-10T10:00", 30, 25),
                ride(4, "2026-05-11T10:00", 30, 25),
                ride(5, "2026-05-12T10:00", 30, 25)));
        Collections.reverse(rides);
        Streak s = RideRecordsCalculator.compute(rides, AMS).longestStreak;
        assertEquals(3, s.days);
        assertEquals(LocalDate.of(2026, 5, 10), s.firstDay);
    }

    @Test
    public void streak_equalLength_earliestStreakReported() {
        Streak s = compute(
                ride(1, "2026-06-01T10:00", 30, 25),
                ride(2, "2026-06-02T10:00", 30, 25),
                ride(3, "2026-05-01T10:00", 30, 25),
                ride(4, "2026-05-02T10:00", 30, 25)).longestStreak;
        assertEquals(2, s.days);
        assertEquals(LocalDate.of(2026, 5, 1), s.firstDay);
    }

    @Test
    public void streak_usesLocalCalendarDay_notUtc() {
        // 23:30 and 00:30 Amsterdam time (CEST, UTC+2) on consecutive local days — both fall on
        // the same UTC day (21:30 and 22:30 UTC on 1 May).
        StoredRide lateEvening = ride(1, "2026-05-01T23:30", 20, 25);
        StoredRide afterMidnight = ride(2, "2026-05-02T00:30", 20, 25);

        Streak local = compute(lateEvening, afterMidnight).longestStreak;
        assertEquals(2, local.days);
        assertEquals(LocalDate.of(2026, 5, 1), local.firstDay);

        Streak utc = RideRecordsCalculator.compute(
                Arrays.asList(lateEvening, afterMidnight), ZoneOffset.UTC).longestStreak;
        assertEquals(1, utc.days);
    }

    @Test
    public void nullEntries_areIgnored() {
        StoredRide r = ride(1, "2026-05-01T10:00", 40, 28);
        Records rec = RideRecordsCalculator.compute(Arrays.asList(null, r, null), AMS);
        assertSame(r, rec.longestDistance);
        assertEquals(1, rec.longestStreak.days);
    }
}
