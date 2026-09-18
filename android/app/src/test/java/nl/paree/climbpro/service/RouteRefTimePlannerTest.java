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

public class RouteRefTimePlannerTest {

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
    public void plan_returnsBestSplitsPerClimbPosition() {
        StoredClimb c = climbWithSegments(51.0, 5.0, 2000, 4);
        StoredRoute route = new StoredRoute();
        route.climbs = new ArrayList<>(Arrays.asList(c));

        String climbId = ClimbIdentity.of(51.0, 5.0, 2000);
        List<StoredClimbAttempt> attempts = Arrays.asList(
                attempt(climbId, new int[]{60, 60, 60, 60}),
                attempt(climbId, new int[]{55, 65, 55, 65}));

        int[][] plan = RouteRefTimePlanner.plan(route, attempts);

        assertArrayEquals(new int[]{55, 60, 55, 60}, plan[0]);
    }

    @Test
    public void plan_climbWithoutMatchingAttempt_isNullEntry() {
        StoredClimb c = climbWithSegments(51.0, 5.0, 2000, 4);
        StoredRoute route = new StoredRoute();
        route.climbs = new ArrayList<>(Arrays.asList(c));

        int[][] plan = RouteRefTimePlanner.plan(route, Collections.emptyList());

        assertNull(plan[0]);
    }

    @Test
    public void plan_climbWithNoSegments_isNullEntry() {
        StoredClimb c = new StoredClimb();
        c.segments = null;
        StoredRoute route = new StoredRoute();
        route.climbs = new ArrayList<>(Arrays.asList(c));

        int[][] plan = RouteRefTimePlanner.plan(route, Collections.emptyList());

        assertNull(plan[0]);
    }

    @Test
    public void plan_emptyRoute_returnsNull() {
        StoredRoute route = new StoredRoute();
        route.climbs = new ArrayList<>();
        assertNull(RouteRefTimePlanner.plan(route, Collections.emptyList()));
        assertNull(RouteRefTimePlanner.plan(null, Collections.emptyList()));
    }
}
