package nl.paree.climbpro.service;

import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredClimbAttempt;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.data.route.StoredSegment;
import nl.paree.climbpro.domain.climb.ClimbIdentity;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;

/**
 * Manual WR/pro reference (issue #59) takes priority over the rider's own PR per climb,
 * falling back to the PR when no manual reference is set, and to null when neither exists.
 */
public class CombinedRefTimePlannerTest {

    private static StoredClimb climbWithSegments(double startLat, double startLon, int len, int segN) {
        StoredClimb c = new StoredClimb();
        c.startLat = startLat;
        c.startLon = startLon;
        c.startDistance = 0;
        c.endDistance = len;
        c.length = len;
        c.segments = new ArrayList<>();
        for (int i = 0; i < segN; i++) {
            StoredSegment s = new StoredSegment();
            s.distance = len / segN;
            c.segments.add(s);
        }
        return c;
    }

    private static StoredClimbAttempt attempt(String climbId, int[] splits) {
        StoredClimbAttempt a = new StoredClimbAttempt();
        a.climbId = climbId;
        a.segSplitSec = splits;
        return a;
    }

    @Test
    public void manualReference_winsOverOwnPr() {
        StoredClimb c = climbWithSegments(51.0, 5.0, 2000, 4);
        c.manualRefSec = 2000;
        StoredRoute route = new StoredRoute();
        route.climbs = new ArrayList<>(Arrays.asList(c));

        String climbId = ClimbIdentity.of(51.0, 5.0, 2000);
        List<StoredClimbAttempt> attempts = Arrays.asList(attempt(climbId, new int[]{60, 60, 60, 60}));

        int[][] plan = CombinedRefTimePlanner.plan(route, attempts);

        assertArrayEquals(new int[]{500, 500, 500, 500}, plan[0]);
    }

    @Test
    public void noManualReference_fallsBackToOwnPr() {
        StoredClimb c = climbWithSegments(51.0, 5.0, 2000, 4);
        StoredRoute route = new StoredRoute();
        route.climbs = new ArrayList<>(Arrays.asList(c));

        String climbId = ClimbIdentity.of(51.0, 5.0, 2000);
        List<StoredClimbAttempt> attempts = Arrays.asList(attempt(climbId, new int[]{60, 60, 60, 60}));

        int[][] plan = CombinedRefTimePlanner.plan(route, attempts);

        assertArrayEquals(new int[]{60, 60, 60, 60}, plan[0]);
    }

    @Test
    public void neitherSource_isNullEntry() {
        StoredClimb c = climbWithSegments(51.0, 5.0, 2000, 4);
        StoredRoute route = new StoredRoute();
        route.climbs = new ArrayList<>(Arrays.asList(c));

        int[][] plan = CombinedRefTimePlanner.plan(route, Collections.emptyList());

        assertNull(plan[0]);
    }

    @Test
    public void mixedClimbs_eachDecidesIndependently() {
        StoredClimb manual = climbWithSegments(51.0, 5.0, 2000, 2);
        manual.manualRefSec = 1000;
        StoredClimb ownPr = climbWithSegments(52.0, 6.0, 1000, 2);
        StoredClimb neither = climbWithSegments(53.0, 7.0, 1000, 2);

        StoredRoute route = new StoredRoute();
        route.climbs = new ArrayList<>(Arrays.asList(manual, ownPr, neither));

        String ownPrClimbId = ClimbIdentity.of(52.0, 6.0, 1000);
        List<StoredClimbAttempt> attempts = Arrays.asList(attempt(ownPrClimbId, new int[]{30, 30}));

        int[][] plan = CombinedRefTimePlanner.plan(route, attempts);

        assertArrayEquals(new int[]{500, 500}, plan[0]);
        assertArrayEquals(new int[]{30, 30}, plan[1]);
        assertNull(plan[2]);
    }

    @Test
    public void emptyRoute_returnsNull() {
        StoredRoute route = new StoredRoute();
        route.climbs = new ArrayList<>();
        assertNull(CombinedRefTimePlanner.plan(route, Collections.emptyList()));
        assertNull(CombinedRefTimePlanner.plan(null, Collections.emptyList()));
    }
}
