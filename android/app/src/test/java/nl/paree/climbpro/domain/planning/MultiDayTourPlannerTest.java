package nl.paree.climbpro.domain.planning;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import nl.paree.climbpro.domain.planning.MultiDayTourPlanner.BalanceMetric;
import nl.paree.climbpro.domain.planning.MultiDayTourPlanner.Request;
import nl.paree.climbpro.domain.route.CumulativeDistance;

/** Pure JUnit — MultiDayTourPlanner has no Android dependencies (issue #67). */
public class MultiDayTourPlannerTest {

    private static final double LAT = 45.0;

    /** A point-like climb on a west→east line; lon step 0.01° ≈ 786 m at 45°N. */
    private static TourStop at(String key, double lon, int hm) {
        return at(key, lon, hm, null);
    }

    private static TourStop at(String key, double lon, int hm, Integer seconds) {
        return new TourStop(key, "Klim " + key, LAT, lon, LAT, lon, hm, 5000, seconds);
    }

    private static Request req(int days) {
        return new Request(days, 0, null, null, BalanceMetric.ELEVATION);
    }

    private static List<TourStop> flatten(MultiDayTourPlan plan) {
        List<TourStop> out = new ArrayList<>();
        for (TourDay d : plan.days) out.addAll(d.stops);
        return out;
    }

    private static List<String> keys(MultiDayTourPlan plan) {
        List<String> out = new ArrayList<>();
        for (TourStop s : flatten(plan)) out.add(s.key);
        return out;
    }

    private static int maxDayHm(MultiDayTourPlan plan) {
        int max = 0;
        for (TourDay d : plan.days) max = Math.max(max, d.elevationGainM);
        return max;
    }

    /** Brute-force minimal heaviest day over all contiguous splits into k non-empty groups. */
    private static long bruteMinMax(long[] loads, int from, int k) {
        if (k == 1) {
            long s = 0;
            for (int i = from; i < loads.length; i++) s += loads[i];
            return s;
        }
        long best = Long.MAX_VALUE;
        long head = 0;
        for (int end = from; end <= loads.length - k; end++) {
            head += loads[end];
            best = Math.min(best, Math.max(head, bruteMinMax(loads, end + 1, k - 1)));
        }
        return best;
    }

    @Test
    public void emptyInput_givesEmptyPlan() {
        MultiDayTourPlan plan = MultiDayTourPlanner.plan(Collections.emptyList(), req(3));
        assertTrue(plan.isEmpty());
        assertEquals(3, plan.requestedDays);
        assertEquals(0, plan.totalElevationGainM);
        assertFalse(plan.fewerStopsThanDays());

        assertTrue(MultiDayTourPlanner.plan(null, req(2)).isEmpty());
    }

    @Test
    public void oneDay_putsEverythingInDayOne() {
        List<TourStop> stops = Arrays.asList(at("a", 6.00, 400), at("b", 6.02, 700), at("c", 6.01, 300));
        MultiDayTourPlan plan = MultiDayTourPlanner.plan(stops, req(1));
        assertEquals(1, plan.days.size());
        assertEquals(3, plan.days.get(0).stops.size());
        assertEquals(1400, plan.days.get(0).elevationGainM);
        assertEquals(1400, plan.totalElevationGainM);
        assertEquals(15000, plan.days.get(0).climbLengthM);
    }

    @Test
    public void moreDaysThanClimbs_capsToOneClimbPerDay() {
        List<TourStop> stops = Arrays.asList(at("a", 6.00, 400), at("b", 6.05, 700));
        MultiDayTourPlan plan = MultiDayTourPlanner.plan(stops, req(5));
        assertEquals(2, plan.days.size());
        assertEquals(5, plan.requestedDays);
        assertTrue(plan.fewerStopsThanDays());
        for (TourDay d : plan.days) assertEquals(1, d.stops.size());
        assertEquals(1, plan.days.get(0).dayNumber);
        assertEquals(2, plan.days.get(1).dayNumber);
    }

    @Test
    public void equalClimbs_splitEvenly() {
        List<TourStop> stops = new ArrayList<>();
        for (int i = 0; i < 6; i++) stops.add(at("k" + i, 6.0 + i * 0.05, 500));
        MultiDayTourPlan plan = MultiDayTourPlanner.plan(stops, req(3));
        assertEquals(3, plan.days.size());
        for (TourDay d : plan.days) {
            assertEquals(2, d.stops.size());
            assertEquals(1000, d.elevationGainM);
        }
    }

    @Test
    public void unevenClimbs_heaviestDayIsOptimalForTheOrder() {
        int[] hms = {1000, 200, 300, 500, 400, 600, 900, 150};
        List<TourStop> stops = new ArrayList<>();
        for (int i = 0; i < hms.length; i++) stops.add(at("k" + i, 6.0 + i * 0.05, hms[i]));
        for (int days = 1; days <= hms.length; days++) {
            MultiDayTourPlan plan = MultiDayTourPlanner.plan(stops, req(days));
            List<TourStop> ordered = flatten(plan);
            long[] loads = new long[ordered.size()];
            for (int i = 0; i < loads.length; i++) loads[i] = ordered.get(i).elevationGainM;
            assertEquals("days=" + days, bruteMinMax(loads, 0, days), maxDayHm(plan));
            assertEquals(days, plan.days.size());
        }
    }

    @Test
    public void partition_prefersEvenSplitAmongEqualMaxima() {
        // Heaviest day is 100 either way; [100 | 10,10 | 10,10] is more even than [100 | 10 | 10,10,10].
        int[] starts = MultiDayTourPlanner.partition(new long[]{100, 10, 10, 10, 10}, 3);
        assertArrayEquals(new int[]{0, 1, 3}, starts);
    }

    @Test
    public void ordering_followsGeographyAndKeepsDaysContiguous() {
        List<TourStop> stops = new ArrayList<>();
        for (int i = 0; i < 9; i++) stops.add(at(String.format("k%02d", i), 6.0 + i * 0.03, 300 + 50 * i));
        Collections.shuffle(stops, new Random(7));
        MultiDayTourPlan plan = MultiDayTourPlanner.plan(stops, req(3));
        List<TourStop> ordered = flatten(plan);
        assertEquals(9, ordered.size());
        boolean increasing = ordered.get(1).startLon > ordered.get(0).startLon;
        for (int i = 1; i < ordered.size(); i++) {
            double delta = ordered.get(i).startLon - ordered.get(i - 1).startLon;
            assertTrue("stop order must be monotonic along the line", increasing ? delta > 0 : delta < 0);
        }
    }

    @Test
    public void startPoint_anchorsTheOrder() {
        List<TourStop> stops = Arrays.asList(at("a", 6.10, 300), at("b", 6.00, 300), at("c", 6.05, 300));
        Request fromEast = new Request(1, 0, LAT, 6.2, BalanceMetric.ELEVATION);
        assertEquals(Arrays.asList("a", "c", "b"), keys(MultiDayTourPlanner.plan(stops, fromEast)));
        Request fromWest = new Request(1, 0, LAT, 5.9, BalanceMetric.ELEVATION);
        assertEquals(Arrays.asList("b", "c", "a"), keys(MultiDayTourPlanner.plan(stops, fromWest)));
    }

    @Test
    public void ordering_respectsClimbDirection() {
        // Chain: z runs 6.00→6.10, y runs 6.10→6.20, x runs 6.20→6.30. Keys sort x,y,z so the
        // canonical order is reversed; asymmetric top→foot costs must still yield z, y, x.
        TourStop x = new TourStop("x", "x", LAT, 6.20, LAT, 6.30, 500, 8000, null);
        TourStop y = new TourStop("y", "y", LAT, 6.10, LAT, 6.20, 500, 8000, null);
        TourStop z = new TourStop("z", "z", LAT, 6.00, LAT, 6.10, 500, 8000, null);
        MultiDayTourPlan plan = MultiDayTourPlanner.plan(Arrays.asList(x, y, z), req(1));
        assertEquals(Arrays.asList("z", "y", "x"), keys(plan));
        assertEquals(0, plan.days.get(0).transferMetersHemelsbreed);
    }

    @Test
    public void plan_isDeterministicForAnyInputOrder() {
        Random rnd = new Random(42);
        List<TourStop> stops = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            double lat = 45 + rnd.nextDouble() * 0.5;
            double lon = 6 + rnd.nextDouble() * 0.5;
            stops.add(new TourStop("s" + i, "s" + i, lat, lon, lat + 0.01, lon + 0.01,
                    200 + rnd.nextInt(1200), 6000, null));
        }
        MultiDayTourPlan reference = MultiDayTourPlanner.plan(stops, req(4));
        for (int seed = 0; seed < 10; seed++) {
            List<TourStop> shuffled = new ArrayList<>(stops);
            Collections.shuffle(shuffled, new Random(seed));
            MultiDayTourPlan plan = MultiDayTourPlanner.plan(shuffled, req(4));
            assertEquals(keys(reference), keys(plan));
            for (int d = 0; d < reference.days.size(); d++) {
                assertEquals(reference.days.get(d).stops.size(), plan.days.get(d).stops.size());
            }
        }
    }

    @Test
    public void transfers_sumToTheWholeHemelsbreedPath() {
        List<TourStop> stops = new ArrayList<>();
        for (int i = 0; i < 5; i++) stops.add(at("k" + i, 6.0 + i * 0.02, 400));
        MultiDayTourPlan plan = MultiDayTourPlanner.plan(stops, req(2));
        int total = 0;
        for (TourDay d : plan.days) total += d.transferMetersHemelsbreed;
        double expected = CumulativeDistance.haversine(LAT, 6.0, LAT, 6.08);
        assertEquals(expected, total, 3.0);
    }

    @Test
    public void maxHm_flagsOverloadedDaysAndReportsMinimumDays() {
        List<TourStop> stops = Arrays.asList(at("a", 6.0, 1500), at("b", 6.05, 1500), at("c", 6.10, 1500));
        MultiDayTourPlan plan = MultiDayTourPlanner.plan(stops,
                new Request(2, 2000, null, null, BalanceMetric.ELEVATION));
        assertTrue(plan.anyDayExceedsMaxElevation());
        assertEquals(3, plan.minDaysForMaxElevation);
        int exceeding = 0;
        for (TourDay d : plan.days) if (d.exceedsMaxElevation) exceeding++;
        assertEquals(1, exceeding);

        MultiDayTourPlan enough = MultiDayTourPlanner.plan(stops,
                new Request(3, 2000, null, null, BalanceMetric.ELEVATION));
        assertFalse(enough.anyDayExceedsMaxElevation());
    }

    @Test
    public void maxHm_singleClimbAboveLimitIsFlaggedButCountsAsOneDay() {
        List<TourStop> stops = Arrays.asList(at("a", 6.0, 2500), at("b", 6.05, 300));
        MultiDayTourPlan plan = MultiDayTourPlanner.plan(stops,
                new Request(2, 2000, null, null, BalanceMetric.ELEVATION));
        assertEquals(2, plan.minDaysForMaxElevation);
        assertTrue(plan.anyDayExceedsMaxElevation());
    }

    @Test
    public void noMaxHm_neverWarns() {
        List<TourStop> stops = Arrays.asList(at("a", 6.0, 3000), at("b", 6.05, 3000));
        MultiDayTourPlan plan = MultiDayTourPlanner.plan(stops, req(1));
        assertFalse(plan.anyDayExceedsMaxElevation());
        assertEquals(0, plan.minDaysForMaxElevation);
    }

    @Test
    public void climbTimeBalance_usedOnlyWhenEveryStopHasAnEstimate() {
        // hm says [1000 | 100,100,100]; time says the short steep one is quick.
        List<TourStop> timed = Arrays.asList(
                at("a", 6.00, 1000, 1200), at("b", 6.05, 100, 1200),
                at("c", 6.10, 100, 1200), at("d", 6.15, 100, 1200));
        Request onTime = new Request(2, 0, LAT, 5.9, BalanceMetric.CLIMB_TIME);
        MultiDayTourPlan plan = MultiDayTourPlanner.plan(timed, onTime);
        assertTrue(plan.balancedOnClimbTime);
        assertEquals(2, plan.days.get(0).stops.size());
        assertEquals(2400, plan.days.get(0).climbSeconds);
        assertTrue(plan.days.get(0).climbTimeComplete);

        List<TourStop> partial = new ArrayList<>(timed);
        partial.set(3, at("d", 6.15, 100, null));
        MultiDayTourPlan fallback = MultiDayTourPlanner.plan(partial, onTime);
        assertFalse(fallback.balancedOnClimbTime);
        assertEquals(1, fallback.days.get(0).stops.size());
        assertFalse(fallback.days.get(1).climbTimeComplete);
    }
}
