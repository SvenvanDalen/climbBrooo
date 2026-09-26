package nl.paree.climbpro.domain.goal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import nl.paree.climbpro.data.goal.GoalEvent;
import nl.paree.climbpro.data.ride.StoredRide;

import org.junit.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class GoalEventProgressTest {

    private static final ZoneId ZONE = ZoneOffset.UTC;
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 26); // a Saturday

    private static GoalEvent event(LocalDate date, int km, int hm) {
        GoalEvent e = new GoalEvent();
        e.name = "Marmotte";
        e.date = date.toString();
        e.distanceKm = km;
        e.elevationM = hm;
        return e;
    }

    private static StoredRide ride(LocalDate day, float km, float hm, String type) {
        StoredRide r = new StoredRide();
        r.activityId = day.toEpochDay() * 10 + (long) km;
        r.type = type;
        r.startEpochSec = day.atTime(9, 0).toEpochSecond(ZoneOffset.UTC);
        r.distanceM = km * 1000;
        r.elevationGainM = hm;
        r.movingTimeSec = (int) (km * 120);
        return r;
    }

    @Test
    public void countsDaysAndPicksPhase() {
        assertEquals(100, GoalEventProgress.compute(event(TODAY.plusDays(100), 170, 5000),
                Collections.emptyList(), TODAY, ZONE).daysLeft);
        assertEquals(GoalEventProgress.Phase.BASE, GoalEventProgress.phaseFor(100));
        assertEquals(GoalEventProgress.Phase.BUILD, GoalEventProgress.phaseFor(84));
        assertEquals(GoalEventProgress.Phase.BUILD, GoalEventProgress.phaseFor(15));
        assertEquals(GoalEventProgress.Phase.TAPER, GoalEventProgress.phaseFor(14));
        assertEquals(GoalEventProgress.Phase.TAPER, GoalEventProgress.phaseFor(1));
        assertEquals(GoalEventProgress.Phase.EVENT_DAY, GoalEventProgress.phaseFor(0));
        assertEquals(GoalEventProgress.Phase.PAST, GoalEventProgress.phaseFor(-1));
    }

    @Test
    public void longestRideAndMostClimbingOfTheLastFourWeeks() {
        List<StoredRide> rides = Arrays.asList(
                ride(TODAY.minusDays(3), 90, 1500, "Ride"),
                ride(TODAY.minusDays(10), 120, 900, "Ride"),
                ride(TODAY.minusDays(40), 200, 4000, "Ride"),    // too long ago
                ride(TODAY.minusDays(5), 150, 3000, "EBikeRide")); // doesn't count
        GoalEventProgress.Result r = GoalEventProgress.compute(
                event(TODAY.plusDays(60), 160, 3000), rides, TODAY, ZONE);

        assertEquals(120, r.longestRideKm, 1e-3);
        assertEquals(1500, r.mostElevationM, 1e-3);
        assertEquals(0.75, r.distanceReadiness, 1e-9);
        assertEquals(0.5, r.elevationReadiness, 1e-9);
    }

    @Test
    public void readinessIsCappedAtOne() {
        GoalEventProgress.Result r = GoalEventProgress.compute(event(TODAY.plusDays(30), 100, 1000),
                Collections.singletonList(ride(TODAY.minusDays(2), 150, 2500, "Ride")), TODAY, ZONE);
        assertEquals(1.0, r.distanceReadiness, 1e-9);
        assertEquals(1.0, r.elevationReadiness, 1e-9);
    }

    @Test
    public void weeklyVolumeCoversTheLastSixWeeksNewestFirst() {
        List<StoredRide> rides = new ArrayList<>();
        rides.add(ride(TODAY, 40, 300, "Ride"));                 // this week (Mon 21 Sep)
        rides.add(ride(TODAY.minusDays(6), 60, 500, "Ride"));    // Sun 20 Sep: previous week
        rides.add(ride(TODAY.minusDays(7), 30, 100, "VirtualRide"));
        GoalEventProgress.Result r = GoalEventProgress.compute(
                event(TODAY.plusDays(60), 160, 3000), rides, TODAY, ZONE);

        assertEquals(GoalEventProgress.WEEKS_SHOWN, r.weeks.size());
        assertEquals(LocalDate.of(2026, 9, 21), r.weeks.get(0).weekStart);
        assertEquals(40, r.weeks.get(0).km, 1e-3);
        assertEquals(90, r.weeks.get(1).km, 1e-3);
        assertEquals(600, r.weeks.get(1).elevationM, 1e-3);
        assertEquals(2, r.weeks.get(1).rides);
        assertEquals(0, r.weeks.get(5).km, 1e-3);
    }

    @Test
    public void adviceFollowsPhaseAndReadiness() {
        GoalEventProgress.Result base = GoalEventProgress.compute(
                event(TODAY.plusDays(120), 160, 3000), Collections.emptyList(), TODAY, ZONE);
        assertFalse(base.advice.isEmpty());

        GoalEventProgress.Result taper = GoalEventProgress.compute(
                event(TODAY.plusDays(7), 160, 3000), Collections.emptyList(), TODAY, ZONE);
        assertTrue(taper.advice, taper.advice.contains("rust"));

        GoalEventProgress.Result buildBehind = GoalEventProgress.compute(
                event(TODAY.plusDays(40), 160, 3000),
                Collections.singletonList(ride(TODAY.minusDays(2), 60, 500, "Ride")), TODAY, ZONE);
        assertTrue(buildBehind.advice, buildBehind.advice.contains("langste rit"));
    }

    @Test
    public void unparseableDateIsPast() {
        GoalEvent e = event(TODAY, 100, 1000);
        e.date = "gisteren";
        assertEquals(GoalEventProgress.Phase.PAST,
                GoalEventProgress.compute(e, Collections.emptyList(), TODAY, ZONE).phase);
    }
}
