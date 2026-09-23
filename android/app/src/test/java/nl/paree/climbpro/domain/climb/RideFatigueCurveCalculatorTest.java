package nl.paree.climbpro.domain.climb;

import nl.paree.climbpro.data.route.StoredClimbAttempt;
import nl.paree.climbpro.domain.climb.RideFatigueCurveCalculator.ClimbRef;
import nl.paree.climbpro.domain.climb.RideFatigueCurveCalculator.FatiguePoint;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class RideFatigueCurveCalculatorTest {

    private static StoredClimbAttempt attempt(String climbId, long activityId, int elapsedSec, int passIndex) {
        StoredClimbAttempt a = new StoredClimbAttempt();
        a.climbId = climbId;
        a.activityId = activityId;
        a.dateEpochSec = 1_700_000_000L; // same for every attempt of one activity, by design
        a.elapsedSec = elapsedSec;
        a.passIndex = passIndex;
        return a;
    }

    private static Map<String, ClimbRef> refs(Object... climbIdThenRef) {
        Map<String, ClimbRef> map = new HashMap<>();
        for (int i = 0; i < climbIdThenRef.length; i += 2) {
            map.put((String) climbIdThenRef[i], (ClimbRef) climbIdThenRef[i + 1]);
        }
        return map;
    }

    @Test
    public void groupByActivity_groupsAttemptsByActivityId() {
        List<StoredClimbAttempt> attempts = new ArrayList<>();
        attempts.add(attempt("c1", 111L, 300, 0));
        attempts.add(attempt("c2", 111L, 400, 0));
        attempts.add(attempt("c1", 222L, 320, 0));

        Map<Long, List<StoredClimbAttempt>> grouped = RideFatigueCurveCalculator.groupByActivity(attempts);

        assertEquals(2, grouped.size());
        assertEquals(2, grouped.get(111L).size());
        assertEquals(1, grouped.get(222L).size());
    }

    @Test
    public void groupByActivity_emptyInput_returnsEmptyMap() {
        assertTrue(RideFatigueCurveCalculator.groupByActivity(Collections.emptyList()).isEmpty());
        assertTrue(RideFatigueCurveCalculator.groupByActivity(null).isEmpty());
    }

    @Test
    public void computeForActivity_noAttempts_returnsEmpty() {
        assertTrue(RideFatigueCurveCalculator.computeForActivity(Collections.emptyList(), refs()).isEmpty());
        assertTrue(RideFatigueCurveCalculator.computeForActivity(null, refs()).isEmpty());
    }

    @Test
    public void computeForActivity_singleClimb_returnsEmpty() {
        List<StoredClimbAttempt> attempts = Collections.singletonList(attempt("c1", 111L, 300, 0));
        Map<String, ClimbRef> refs = refs("c1", new ClimbRef("Alpe", 500, "route1", 0));

        assertTrue(RideFatigueCurveCalculator.computeForActivity(attempts, refs).isEmpty());
    }

    @Test
    public void computeForActivity_degradingPace_showsDecreasingVamAcrossClimbs() {
        // Same climb-sized elevation gain, but each successive climb takes longer -> VAM drops,
        // which is exactly the fatigue signal the issue asks to visualise.
        List<StoredClimbAttempt> attempts = new ArrayList<>();
        attempts.add(attempt("c1", 111L, 600, 0));  // 500m / 600s -> fastest
        attempts.add(attempt("c2", 111L, 700, 0));  // slower
        attempts.add(attempt("c3", 111L, 900, 0));  // slowest

        Map<String, ClimbRef> refs = refs(
                "c1", new ClimbRef("Climb 1", 500, "route1", 0),
                "c2", new ClimbRef("Climb 2", 500, "route1", 1),
                "c3", new ClimbRef("Climb 3", 500, "route1", 2));

        List<FatiguePoint> curve = RideFatigueCurveCalculator.computeForActivity(attempts, refs);

        assertEquals(3, curve.size());
        assertEquals(1, curve.get(0).ordinal);
        assertEquals(2, curve.get(1).ordinal);
        assertEquals(3, curve.get(2).ordinal);
        assertEquals("Climb 1", curve.get(0).label);
        assertEquals("Climb 2", curve.get(1).label);
        assertEquals("Climb 3", curve.get(2).label);

        // Strictly decreasing VAM across the ride.
        assertTrue(curve.get(0).vamMPerHour > curve.get(1).vamMPerHour);
        assertTrue(curve.get(1).vamMPerHour > curve.get(2).vamMPerHour);

        // First point is always the 100% baseline; later points fall below it.
        assertEquals(100.0, curve.get(0).relativeToFirstPct, 0.001);
        assertTrue(curve.get(1).relativeToFirstPct < 100.0);
        assertTrue(curve.get(2).relativeToFirstPct < 100.0);
        assertTrue(curve.get(1).relativeToFirstPct > curve.get(2).relativeToFirstPct);
    }

    @Test
    public void computeForActivity_ordersByRouteClimbIndexThenPassIndex_notInputOrder() {
        // Fed out of order; orderIndex + passIndex must still recover ride order.
        List<StoredClimbAttempt> attempts = new ArrayList<>();
        attempts.add(attempt("c3", 111L, 500, 0));
        attempts.add(attempt("c1", 111L, 300, 1)); // second pass of c1
        attempts.add(attempt("c1", 111L, 280, 0)); // first pass of c1
        attempts.add(attempt("c2", 111L, 400, 0));

        Map<String, ClimbRef> refs = refs(
                "c1", new ClimbRef("Climb 1", 500, "route1", 0),
                "c2", new ClimbRef("Climb 2", 500, "route1", 1),
                "c3", new ClimbRef("Climb 3", 500, "route1", 2));

        List<FatiguePoint> curve = RideFatigueCurveCalculator.computeForActivity(attempts, refs);

        assertEquals(4, curve.size());
        assertEquals("Climb 1", curve.get(0).label);
        assertEquals(280, curve.get(0).elapsedSec);
        assertEquals("Climb 1", curve.get(1).label);
        assertEquals(300, curve.get(1).elapsedSec);
        assertEquals("Climb 2", curve.get(2).label);
        assertEquals("Climb 3", curve.get(3).label);
    }

    @Test
    public void computeForActivity_skipsAttemptsWithoutUsableData() {
        List<StoredClimbAttempt> attempts = new ArrayList<>();
        attempts.add(attempt("c1", 111L, 300, 0));
        attempts.add(attempt("unknown", 111L, 300, 0)); // no ClimbRef -> skipped
        attempts.add(attempt("c3", 111L, 0, 0));         // zero elapsed -> skipped (undefined VAM)

        Map<String, ClimbRef> refs = refs(
                "c1", new ClimbRef("Climb 1", 500, "route1", 0),
                "c2", new ClimbRef("Climb 2", 500, "route1", 1),
                "c3", new ClimbRef("Climb 3", 500, "route1", 2));

        // Only c1 has usable data -> fewer than 2 usable points -> empty.
        assertTrue(RideFatigueCurveCalculator.computeForActivity(attempts, refs).isEmpty());
    }

    @Test
    public void startOffsets_orderRideChronologically_evenAgainstRouteDirection() {
        java.util.Map<String, RideFatigueCurveCalculator.ClimbRef> refs = new java.util.HashMap<>();
        refs.put("first", new RideFatigueCurveCalculator.ClimbRef("Eerste", 200, "r1", 5));
        refs.put("second", new RideFatigueCurveCalculator.ClimbRef("Tweede", 200, "r1", 0));
        // Route stores "second" before "first" (index 0 vs 5): a reversed ride.
        StoredClimbAttempt later = new StoredClimbAttempt();
        later.climbId = "second"; later.activityId = 7; later.elapsedSec = 800; later.startOffsetSec = 5400;
        StoredClimbAttempt earlier = new StoredClimbAttempt();
        earlier.climbId = "first"; earlier.activityId = 7; earlier.elapsedSec = 600; earlier.startOffsetSec = 1200;
        java.util.List<StoredClimbAttempt> attempts = java.util.Arrays.asList(later, earlier);

        java.util.List<RideFatigueCurveCalculator.FatiguePoint> curve =
                RideFatigueCurveCalculator.computeForActivity(attempts, refs);

        org.junit.Assert.assertTrue(RideFatigueCurveCalculator.isChronological(attempts, refs));
        org.junit.Assert.assertEquals("Eerste", curve.get(0).label);
        org.junit.Assert.assertEquals("Tweede", curve.get(1).label);
    }

    @Test
    public void legacyAttemptWithoutOffset_fallsBackToRouteOrder() {
        java.util.Map<String, RideFatigueCurveCalculator.ClimbRef> refs = new java.util.HashMap<>();
        refs.put("a", new RideFatigueCurveCalculator.ClimbRef("A", 200, "r1", 0));
        refs.put("b", new RideFatigueCurveCalculator.ClimbRef("B", 200, "r1", 1));
        StoredClimbAttempt a = new StoredClimbAttempt();
        a.climbId = "a"; a.activityId = 7; a.elapsedSec = 600; // startOffsetSec stays -1
        StoredClimbAttempt b = new StoredClimbAttempt();
        b.climbId = "b"; b.activityId = 7; b.elapsedSec = 600; b.startOffsetSec = 10;
        java.util.List<StoredClimbAttempt> attempts = java.util.Arrays.asList(b, a);

        org.junit.Assert.assertFalse(RideFatigueCurveCalculator.isChronological(attempts, refs));
        org.junit.Assert.assertEquals("A",
                RideFatigueCurveCalculator.computeForActivity(attempts, refs).get(0).label);
    }
}
