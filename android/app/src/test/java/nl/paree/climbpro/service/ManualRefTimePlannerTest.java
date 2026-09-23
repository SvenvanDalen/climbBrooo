package nl.paree.climbpro.service;

import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.data.route.StoredSegment;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class ManualRefTimePlannerTest {

    private static List<StoredSegment> segmentsOfDistances(int... distances) {
        List<StoredSegment> segs = new ArrayList<>();
        for (int d : distances) {
            StoredSegment s = new StoredSegment();
            s.distance = d;
            segs.add(s);
        }
        return segs;
    }

    private static int sum(int[] arr) {
        int total = 0;
        for (int v : arr) total += v;
        return total;
    }

    @Test
    public void distribute_evenSegments_splitsEvenly() {
        List<StoredSegment> segs = segmentsOfDistances(100, 100, 100, 100);
        int[] result = ManualRefTimePlanner.distribute(2000, segs);
        assertArrayEquals(new int[]{500, 500, 500, 500}, result);
    }

    @Test
    public void distribute_unevenSegments_isDistanceWeightedAndSumsToTotal() {
        // 10%, 20%, 70% of distance.
        List<StoredSegment> segs = segmentsOfDistances(100, 200, 700);
        int total = 2235; // 37:15
        int[] result = ManualRefTimePlanner.distribute(total, segs);

        assertEquals(total, sum(result));
        // Roughly proportional: last (70%) segment gets the largest share.
        assertEquals(true, result[2] > result[1]);
        assertEquals(true, result[1] > result[0]);
    }

    @Test
    public void distribute_zeroTotalDistance_splitsEvenlyWithoutCrashing() {
        List<StoredSegment> segs = segmentsOfDistances(0, 0, 0);
        int[] result = ManualRefTimePlanner.distribute(90, segs);
        assertEquals(90, sum(result));
    }

    @Test
    public void distribute_manySegmentsWithRemainder_sumsExactlyToTotal() {
        List<StoredSegment> segs = segmentsOfDistances(
                77, 83, 91, 62, 58, 101, 47, 66, 73, 88, 59, 64);
        int total = 2235;
        int[] result = ManualRefTimePlanner.distribute(total, segs);
        assertEquals(total, sum(result));
    }

    @Test
    public void plan_usesManualRefSecWhenSet() {
        StoredClimb c = new StoredClimb();
        c.manualRefSec = 1000;
        c.segments = segmentsOfDistances(500, 500);
        StoredRoute route = new StoredRoute();
        route.climbs = new ArrayList<>(Arrays.asList(c));

        int[][] plan = ManualRefTimePlanner.plan(route);

        assertArrayEquals(new int[]{500, 500}, plan[0]);
    }

    @Test
    public void plan_climbWithoutManualRef_isNullEntry() {
        StoredClimb c = new StoredClimb();
        c.segments = segmentsOfDistances(500, 500);
        StoredRoute route = new StoredRoute();
        route.climbs = new ArrayList<>(Arrays.asList(c));

        int[][] plan = ManualRefTimePlanner.plan(route);

        assertNull(plan[0]);
    }

    @Test
    public void plan_climbWithManualRefButNoSegments_isNullEntry() {
        StoredClimb c = new StoredClimb();
        c.manualRefSec = 1000;
        c.segments = null;
        StoredRoute route = new StoredRoute();
        route.climbs = new ArrayList<>(Arrays.asList(c));

        int[][] plan = ManualRefTimePlanner.plan(route);

        assertNull(plan[0]);
    }

    @Test
    public void plan_emptyRoute_returnsNull() {
        StoredRoute route = new StoredRoute();
        route.climbs = new ArrayList<>();
        assertNull(ManualRefTimePlanner.plan(route));
        assertNull(ManualRefTimePlanner.plan(null));
    }
}
