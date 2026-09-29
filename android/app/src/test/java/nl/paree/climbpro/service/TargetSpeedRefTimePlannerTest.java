package nl.paree.climbpro.service;

import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.data.route.StoredSegment;
import nl.paree.climbpro.domain.power.GhostTarget;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * Issue #31: virtual constant-speed / constant-VAM ghost for climbs without history. Per
 * segment seconds come from segment length / target speed (or gain / target VAM).
 */
public class TargetSpeedRefTimePlannerTest {

    private static StoredSegment seg(int distance, int gain) {
        StoredSegment s = new StoredSegment();
        s.distance = distance;
        s.elevationGain = gain;
        s.gradient = distance > 0 ? gain / (double) distance : 0;
        return s;
    }

    private static StoredClimb climb(StoredSegment... segs) {
        StoredClimb c = new StoredClimb();
        c.segments = new ArrayList<>(Arrays.asList(segs));
        int len = 0, gain = 0;
        for (StoredSegment s : segs) { len += s.distance; gain += s.elevationGain; }
        c.length = len;
        c.endDistance = len;
        c.elevationGain = gain;
        c.avgGradient = len > 0 ? gain / (double) len : 0;
        return c;
    }

    private static int sum(int[] a) {
        int s = 0;
        for (int v : a) s += v;
        return s;
    }

    @Test
    public void speedOnly_segmentSecondsAreLengthOverSpeed() {
        // 18 km/h = 5 m/s → 500 m = 100 s.
        StoredClimb c = climb(seg(500, 20), seg(500, 40), seg(500, 30), seg(500, 10));
        int[] secs = TargetSpeedRefTimePlanner.segmentSeconds(c, new GhostTarget(18, 0));
        assertArrayEquals(new int[]{100, 100, 100, 100}, secs);
    }

    @Test
    public void speedOnly_unevenSegmentsKeepExactCumulativeTotal() {
        // 10 km/h: 333 m = 119.88 s each; total 999 m = 359.64 s → 360.
        StoredClimb c = climb(seg(333, 10), seg(333, 10), seg(333, 10));
        int[] secs = TargetSpeedRefTimePlanner.segmentSeconds(c, new GhostTarget(10, 0));
        assertEquals(3, secs.length);
        assertEquals(360, sum(secs));
        for (int s : secs) assertEquals(120, s, 1);
    }

    @Test
    public void vamOnly_segmentSecondsAreGainOverVam() {
        // 900 m/h: 30 m = 120 s, 45 m = 180 s.
        StoredClimb c = climb(seg(500, 30), seg(500, 45));
        int[] secs = TargetSpeedRefTimePlanner.segmentSeconds(c, new GhostTarget(0, 900));
        assertArrayEquals(new int[]{120, 180}, secs);
    }

    @Test
    public void vamOnly_flatSegmentUsesClimbAverageImpliedSpeed() {
        // Climb: 1500 m, 60 m gain → avg 4 %. VAM 900 m/h → implied speed = 900/0.04
        // = 22.5 km/h = 6.25 m/s. The flat 500 m segment then takes 80 s.
        StoredClimb c = climb(seg(500, 30), seg(500, 0), seg(500, 30));
        int[] secs = TargetSpeedRefTimePlanner.segmentSeconds(c, new GhostTarget(0, 900));
        assertArrayEquals(new int[]{120, 80, 120}, secs);
    }

    @Test
    public void vamOnly_climbWithoutAnyGainYieldsNull() {
        StoredClimb c = climb(seg(500, 0), seg(500, 0));
        assertNull(TargetSpeedRefTimePlanner.segmentSeconds(c, new GhostTarget(0, 900)));
    }

    @Test
    public void speedAndVam_slowerOfTheTwoWinsPerSegment() {
        // 18 km/h → 500 m = 100 s. VAM 900 → 10 m = 40 s, 50 m = 200 s.
        StoredClimb c = climb(seg(500, 10), seg(500, 50));
        int[] secs = TargetSpeedRefTimePlanner.segmentSeconds(c, new GhostTarget(18, 900));
        assertArrayEquals(new int[]{100, 200}, secs);
    }

    @Test
    public void segmentGainFallsBackToGradientWhenGainMissing() {
        StoredSegment s = new StoredSegment();
        s.distance = 500;
        s.elevationGain = 0;
        s.gradient = 0.06; // 30 m
        StoredClimb c = climb(s);
        c.elevationGain = 30;
        int[] secs = TargetSpeedRefTimePlanner.segmentSeconds(c, new GhostTarget(0, 900));
        assertArrayEquals(new int[]{120}, secs);
    }

    @Test
    public void unsetTarget_isNull() {
        StoredClimb c = climb(seg(500, 20));
        assertNull(TargetSpeedRefTimePlanner.segmentSeconds(c, GhostTarget.NONE));
        assertNull(TargetSpeedRefTimePlanner.segmentSeconds(c, null));
    }

    @Test
    public void climbWithoutSegments_isNull() {
        StoredClimb c = new StoredClimb();
        c.length = 1000;
        assertNull(TargetSpeedRefTimePlanner.segmentSeconds(c, new GhostTarget(15, 0)));
        c.segments = Collections.emptyList();
        assertNull(TargetSpeedRefTimePlanner.segmentSeconds(c, new GhostTarget(15, 0)));
    }

    @Test
    public void plan_isIndexedByClimbPosition() {
        StoredRoute route = new StoredRoute();
        StoredClimb empty = new StoredClimb();
        route.climbs = new ArrayList<>(Arrays.asList(
                climb(seg(500, 20), seg(500, 20)), empty));
        int[][] plan = TargetSpeedRefTimePlanner.plan(route, new GhostTarget(18, 0));
        assertEquals(2, plan.length);
        assertArrayEquals(new int[]{100, 100}, plan[0]);
        assertNull(plan[1]);
    }

    @Test
    public void plan_nullWhenRouteHasNoClimbsOrTargetUnset() {
        StoredRoute route = new StoredRoute();
        assertNull(TargetSpeedRefTimePlanner.plan(route, new GhostTarget(18, 0)));
        assertNull(TargetSpeedRefTimePlanner.plan(null, new GhostTarget(18, 0)));
        route.climbs = new ArrayList<>(Arrays.asList(climb(seg(500, 20))));
        assertNull(TargetSpeedRefTimePlanner.plan(route, GhostTarget.NONE));
    }
}
