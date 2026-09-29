package nl.paree.climbpro.domain.nutrition;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import nl.paree.climbpro.domain.power.RiderProfile;
import nl.paree.climbpro.domain.power.RouteTile;
import nl.paree.climbpro.domain.segment.SurfaceType;

public class RideEffortEstimatorTest {

    private static final RiderProfile PROFILE = new RiderProfile(250, 70.0, 8.0, 65);

    private static List<RouteTile> flat(int meters) {
        List<RouteTile> tiles = new ArrayList<>();
        tiles.add(new RouteTile(meters, 0.0, SurfaceType.ASPHALT, -1));
        return tiles;
    }

    @Test
    public void incompleteProfileOrNoTiles_null() {
        assertNull(RideEffortEstimator.estimate(flat(10_000), new RiderProfile(0, 70, 8)));
        assertNull(RideEffortEstimator.estimate(null, PROFILE));
        assertNull(RideEffortEstimator.estimate(new ArrayList<>(), PROFILE));
    }

    @Test
    public void flatRide_plausibleSpeedAndWork() {
        RideEffort e = RideEffortEstimator.estimate(flat(60_000), PROFILE);
        double kmh = 60.0 / (e.seconds / 3600.0);
        assertTrue("speed " + kmh, kmh > 25 && kmh < 40);
        // Work = ride-intensity power * time.
        double expectedKj = 250 * 0.65 * e.seconds / 1000.0;
        assertEquals(expectedKj, e.workKj, 1.0);
        assertTrue(e.fromPowerModel);
    }

    @Test
    public void climbsAreSlowerAndHarderThanFlat() {
        List<RouteTile> tiles = flat(10_000);
        tiles.add(new RouteTile(5_000, 0.07, SurfaceType.ASPHALT, 0));
        RideEffort withClimb = RideEffortEstimator.estimate(tiles, PROFILE);
        RideEffort flatOnly = RideEffortEstimator.estimate(flat(15_000), PROFILE);
        assertTrue(withClimb.seconds > flatOnly.seconds);
        assertTrue(withClimb.workKj > flatOnly.workKj);
    }

    @Test
    public void steepDescentIsCoastedAndSpeedCapped() {
        List<RouteTile> tiles = new ArrayList<>();
        tiles.add(new RouteTile(14_000, -0.08, SurfaceType.ASPHALT, -1));
        RideEffort e = RideEffortEstimator.estimate(tiles, PROFILE);
        assertEquals(0.0, e.workKj, 1e-9);
        // Capped at DESCENT_MAX_SPEED_MPS -> 14 km at 14 m/s = 1000 s.
        assertEquals(Math.round(14_000 / RideEffortEstimator.DESCENT_MAX_SPEED_MPS), e.seconds);
    }

    @Test
    public void fallback_speedPlusClimbingTime_noWork() {
        RideEffort e = RideEffortEstimator.fallback(50_000, 1000);
        // 50 km at 25 km/h = 2 h, plus 1 h per 1000 hm.
        assertEquals(3 * 3600, e.seconds);
        assertTrue(Double.isNaN(e.workKj));
        assertFalse(e.fromPowerModel);
    }

    @Test
    public void totalAscent_sumsRisesOnly() {
        assertEquals(30, RideEffortEstimator.totalAscent(new double[]{100, 110, 105, 125, 90}));
        assertEquals(0, RideEffortEstimator.totalAscent(null));
        assertEquals(0, RideEffortEstimator.totalAscent(new double[]{5}));
    }
}
