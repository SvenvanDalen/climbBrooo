package nl.paree.climbpro.domain;

import nl.paree.climbpro.domain.power.ClimbTimeEstimate;
import nl.paree.climbpro.domain.power.RouteAwareClimbEstimator;
import nl.paree.climbpro.domain.power.RouteTile;
import nl.paree.climbpro.domain.power.RiderProfile;
import nl.paree.climbpro.domain.segment.SurfaceType;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class RouteAwareClimbEstimatorTest {

    private static final RiderProfile RIDER = new RiderProfile(250, 72.0, 8.0); // 80 kg

    /** A climb of lengthM at grad, split into 1 km asphalt tiles, tagged climbIndex. */
    private static void addClimb(List<RouteTile> tiles, int lengthM, double grad, int climbIndex) {
        int remaining = lengthM;
        while (remaining > 0) {
            int seg = Math.min(1000, remaining);
            tiles.add(new RouteTile(seg, grad, SurfaceType.ASPHALT, climbIndex));
            remaining -= seg;
        }
    }

    /** A flat non-climb stretch of lengthM at 0% asphalt. */
    private static void addFlat(List<RouteTile> tiles, int lengthM) {
        int remaining = lengthM;
        while (remaining > 0) {
            int seg = Math.min(1000, remaining);
            tiles.add(new RouteTile(seg, 0.0, SurfaceType.ASPHALT, -1));
            remaining -= seg;
        }
    }

    @Test
    public void incompleteProfileReturnsNull() {
        List<RouteTile> tiles = new ArrayList<>();
        addClimb(tiles, 2000, 0.08, 0);
        assertNull(RouteAwareClimbEstimator.estimate(tiles, 0, new RiderProfile(0, 72, 8)));
    }

    @Test
    public void unknownTargetReturnsNull() {
        List<RouteTile> tiles = new ArrayList<>();
        addClimb(tiles, 2000, 0.08, 0);
        assertNull(RouteAwareClimbEstimator.estimate(tiles, 5, RIDER));
    }

    @Test
    public void producesRealisticEstimateForLeadingClimb() {
        List<RouteTile> tiles = new ArrayList<>();
        addClimb(tiles, 2000, 0.08, 0); // climb first, fresh-ish
        addFlat(tiles, 5000);           // easy run-out after
        ClimbTimeEstimate e = RouteAwareClimbEstimator.estimate(tiles, 0, RIDER);
        assertTrue("power above CP", e.assumedPowerWatts > 250);
        assertTrue("2km@8% in a sane range, was " + e.totalSeconds,
                e.totalSeconds > 440 && e.totalSeconds < 700);
    }

    @Test
    public void sameClimbIsSlowerDeeperIntoAHardRoute() {
        // Route A: target climb is first.
        List<RouteTile> a = new ArrayList<>();
        addClimb(a, 2000, 0.08, 0);
        addFlat(a, 3000);
        int first = RouteAwareClimbEstimator.estimate(a, 0, RIDER).totalSeconds;

        // Route B: two hard climbs before an identical target climb (index 2).
        List<RouteTile> b = new ArrayList<>();
        addClimb(b, 4000, 0.08, 0);
        addFlat(b, 1000);
        addClimb(b, 4000, 0.08, 1);
        addFlat(b, 1000);
        addClimb(b, 2000, 0.08, 2); // identical geometry to route A's target
        int deep = RouteAwareClimbEstimator.estimate(b, 2, RIDER).totalSeconds;

        assertTrue("same climb must be slower when fatigued (first=" + first + ", deep=" + deep + ")",
                deep > first);
    }

    @Test
    public void higherRideIntensityLeavesLessForLaterClimb() {
        // A long flat (where intensity matters) then the target climb.
        List<RouteTile> easyTiles = new ArrayList<>();
        addFlat(easyTiles, 20000);
        addClimb(easyTiles, 3000, 0.08, 0);

        RiderProfile easy = new RiderProfile(250, 72.0, 8.0, 50); // recovers a lot on the flat
        RiderProfile hard = new RiderProfile(250, 72.0, 8.0, 90); // recovers little

        int easySecs = RouteAwareClimbEstimator.estimate(easyTiles, 0, easy).totalSeconds;
        int hardSecs = RouteAwareClimbEstimator.estimate(easyTiles, 0, hard).totalSeconds;
        assertTrue("higher ride intensity should not be faster (easy=" + easySecs + ", hard=" + hardSecs + ")",
                hardSecs >= easySecs);
    }
}
