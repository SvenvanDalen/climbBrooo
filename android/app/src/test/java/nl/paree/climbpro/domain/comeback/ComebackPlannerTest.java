package nl.paree.climbpro.domain.comeback;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import nl.paree.climbpro.data.ride.StoredRide;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ComebackPlannerTest {

    private static final long DAY = ComebackPlanner.DAY;
    private static final long WEEK = ComebackPlanner.WEEK;
    private static final long T0 = 1_700_000_000L;

    private static StoredRide ride(long start, double km, double hours) {
        StoredRide r = new StoredRide();
        r.activityId = start;
        r.type = "Ride";
        r.startEpochSec = start;
        r.distanceM = (float) (km * 1000);
        r.movingTimeSec = (int) Math.round(hours * 3600);
        return r;
    }

    /** 8 weeks of 3 rides/week (40 km, 1.5 h each; the last one 100 km, 4 h), ending at T0. */
    private static List<StoredRide> steadyHistory() {
        List<StoredRide> rides = new ArrayList<>();
        for (int w = 0; w < 8; w++) {
            for (int d = 0; d < 3; d++) {
                long start = T0 - w * WEEK - d * 2 * DAY;
                if (start == T0) rides.add(ride(start, 100, 4));
                else rides.add(ride(start, 40, 1.5));
            }
        }
        return rides;
    }

    @Test
    public void daysSinceLastRide_ignoresFutureAndMissing() {
        List<StoredRide> rides = new ArrayList<>(steadyHistory());
        rides.add(ride(T0 + 100 * DAY, 10, 1)); // "future" relative to now
        assertEquals(20, ComebackPlanner.daysSinceLastRide(rides, T0 + 20 * DAY + 5));
        assertEquals(-1, ComebackPlanner.daysSinceLastRide(Collections.emptyList(), T0));
    }

    @Test
    public void baseline_fromEightWeeksBeforeBreak() {
        ComebackPlanner.Baseline b = ComebackPlanner.baseline(steadyHistory(), T0);
        assertFalse(b.fallback);
        assertEquals((23 * 40 + 100) / 8.0, b.weeklyKm, 1e-6);
        assertEquals((23 * 1.5 + 4) / 8.0, b.weeklyHours, 1e-6);
        assertEquals(3, b.ridesPerWeek);
        assertEquals(100, b.longestKm, 1e-6);
    }

    @Test
    public void baseline_fallsBackWithTooFewRides() {
        ComebackPlanner.Baseline b = ComebackPlanner.baseline(
                Collections.singletonList(ride(T0, 50, 2)), T0);
        assertTrue(b.fallback);
        assertEquals(ComebackPlanner.FALLBACK_WEEKLY_HOURS, b.weeklyHours, 1e-9);
    }

    @Test
    public void longerBreakAndInjury_startLowerAndLastLonger() {
        assertEquals(2, ComebackPlanner.weeksFor(15, false));
        assertEquals(3, ComebackPlanner.weeksFor(30, false));
        assertEquals(4, ComebackPlanner.weeksFor(60, false));
        assertEquals(6, ComebackPlanner.weeksFor(120, false));
        assertEquals(7, ComebackPlanner.weeksFor(120, true));
        assertEquals(0.6, ComebackPlanner.startPct(15, false), 1e-9);
        assertEquals(0.3, ComebackPlanner.startPct(120, false), 1e-9);
        assertEquals(0.2, ComebackPlanner.startPct(120, true), 1e-9);
    }

    @Test
    public void plan_rampsUpAndTracksActualRides() {
        List<StoredRide> rides = new ArrayList<>(steadyHistory());
        long planStart = T0 + 30 * DAY; // 30-day break → 3 weeks from 50 %
        rides.add(ride(planStart + DAY, 30, 1));
        rides.add(ride(planStart + 3 * DAY, 30, 1));
        rides.add(ride(planStart + WEEK + DAY, 80, 5)); // way over week 2's target

        ComebackPlanner.Plan p = ComebackPlanner.plan(rides, T0, planStart, false);
        assertEquals(30, p.breakDays);
        assertEquals(3, p.weeks.size());
        assertEquals(0.5, p.weeks.get(0).pct, 1e-9);
        assertEquals(0.5 + 0.5 / 3, p.weeks.get(1).pct, 1e-9);
        assertTrue(p.weeks.get(2).pct < 1.0);

        ComebackPlanner.Week w1 = p.weeks.get(0);
        assertEquals(p.baseline.weeklyHours * 0.5, w1.targetHours, 1e-9);
        assertEquals(3, w1.targetRides);
        assertEquals(50, w1.maxRideKm, 1e-9);
        assertEquals(2, w1.actualRides);
        assertEquals(60, w1.actualKm, 1e-6);
        assertFalse(w1.overloaded());
        assertTrue(p.weeks.get(1).overloaded());

        assertEquals(-1, p.currentWeek(planStart - 1));
        assertEquals(1, p.currentWeek(planStart + WEEK + 2 * DAY));
        assertEquals(3, p.currentWeek(planStart + 10 * WEEK));
    }

    @Test
    public void texts() {
        List<StoredRide> rides = new ArrayList<>(steadyHistory());
        long planStart = T0 + 30 * DAY;
        rides.add(ride(planStart + DAY, 30, 1));
        ComebackPlanner.Plan p = ComebackPlanner.plan(rides, T0, planStart, false);

        assertEquals("Vóór je pauze reed je gemiddeld 4,8 uur en 128 km per week in 3 ritten "
                + "(langste rit 100 km).", ComebackPlanner.baselineText(p));
        String week1 = ComebackPlanner.weekText(p.weeks.get(0), 0);
        assertTrue(week1, week1.startsWith("Week 1 (50%): 2,4 uur, ± 64 km in 3 ritten, "
                + "langste rit max. 50 km."));
        assertTrue(week1, week1.contains("Alleen rustig"));
        assertTrue(week1, week1.contains("Gereden: 1,0 uur, 30 km in 1 rit."));
        // A future week shows no "Gereden" line.
        assertFalse(ComebackPlanner.weekText(p.weeks.get(2), 0).contains("Gereden"));
    }
}
