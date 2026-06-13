package nl.paree.climbpro.service;

import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.data.route.StoredSegment;
import nl.paree.climbpro.domain.power.RiderProfile;
import nl.paree.climbpro.domain.segment.SurfaceType;
import org.junit.Test;

import java.util.ArrayList;

import static org.junit.Assert.*;

public class RoutePacingPlannerTest {

    private static final RiderProfile RIDER = new RiderProfile(250, 72.0, 8.0, 65);

    /** A route with distance/elevation arrays and one climb (so the fatigue-aware path runs). */
    private static StoredRoute routeWithOneClimb() {
        StoredRoute r = new StoredRoute();
        r.routeId = "r1";
        // 3 km route: flat to 1km, climb 1km @ ~8%, flat to 3km.
        r.distances  = new double[]{0, 1000, 2000, 3000};
        r.elevations = new double[]{0, 0, 80, 80};
        r.climbs = new ArrayList<>();
        StoredClimb c = new StoredClimb();
        c.startDistance = 1000;
        c.endDistance = 2000;
        c.length = 1000;
        c.elevationGain = 80;
        c.avgGradient = 0.08;
        c.segments = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            StoredSegment s = new StoredSegment();
            s.distance = 125;
            s.elevationGain = 10;
            s.gradient = 0.08;
            s.colorIndex = 4;
            s.surfaceType = SurfaceType.ASPHALT;
            c.segments.add(s);
        }
        r.climbs.add(c);
        return r;
    }

    @Test
    public void incompleteProfileReturnsNull() {
        assertNull(RoutePacingPlanner.plan(routeWithOneClimb(), new RiderProfile(0, 72, 8)));
    }

    @Test
    public void planHasOneEntryPerClimbWithSegmentLengthArrays() {
        int[][] plan = RoutePacingPlanner.plan(routeWithOneClimb(), RIDER);
        assertNotNull(plan);
        assertEquals(1, plan.length);
        assertNotNull(plan[0]);
        assertEquals("one target per segment", 8, plan[0].length);
        for (int sec : plan[0]) assertTrue("each segment time positive", sec > 0);
    }

    @Test
    public void fallsBackToPerClimbWhenRouteHasNoElevationArrays() {
        StoredRoute r = routeWithOneClimb();
        r.distances = null;   // forces RouteEffortProfileBuilder.build() to return null
        r.elevations = null;
        int[][] plan = RoutePacingPlanner.plan(r, RIDER);
        assertNotNull(plan);
        assertEquals(1, plan.length);
        assertNotNull("fallback per-climb estimate fills the entry", plan[0]);
        assertEquals(8, plan[0].length);
    }

    @Test
    public void nullOrEmptyClimbsReturnsEmptyPlan() {
        StoredRoute r = new StoredRoute();
        r.climbs = new ArrayList<>();
        int[][] plan = RoutePacingPlanner.plan(r, RIDER);
        assertNotNull(plan);
        assertEquals(0, plan.length);
    }

    @Test
    public void climbWithNullSegmentsGetsNullEntry() {
        StoredRoute r = routeWithOneClimb();
        r.climbs.get(0).segments = null;
        int[][] plan = RoutePacingPlanner.plan(r, RIDER);
        assertNotNull(plan);
        assertEquals(1, plan.length);
        assertNull(plan[0]);
    }
}
