package nl.paree.climbpro.domain.ride;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import nl.paree.climbpro.data.ride.StoredRide;
import nl.paree.climbpro.data.ride.StoredRideStreamStats;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class FastestDistanceCalculatorTest {

    private static StoredRide ride(long id, String type, long start) {
        StoredRide r = new StoredRide();
        r.activityId = id;
        r.type = type;
        r.name = "Rit " + id;
        r.startEpochSec = start;
        return r;
    }

    private static StoredRideStreamStats stats(long id, Integer b10, Integer b40, Integer b100) {
        StoredRideStreamStats s = new StoredRideStreamStats();
        s.activityId = id;
        s.version = RideStreamAnalyzer.VERSION;
        s.hasStreams = true;
        s.best10kSec = b10;
        s.best40kSec = b40;
        s.best100kSec = b100;
        return s;
    }

    @Test
    public void ranksFastestFirstPerDistance() {
        List<StoredRide> rides = Arrays.asList(ride(1, "Ride", 100), ride(2, "Ride", 200),
                ride(3, "GravelRide", 300));
        List<FastestDistanceCalculator.Distance> out = FastestDistanceCalculator.compute(rides,
                Arrays.asList(stats(1, 1000, 4400, null), stats(2, 950, 4600, null),
                        stats(3, 1100, null, null)));

        assertEquals(3, out.size());
        FastestDistanceCalculator.Distance d10 = out.get(0);
        assertEquals(10_000, d10.distanceM, 0);
        assertEquals(3, d10.efforts.size());
        assertEquals(2L, d10.efforts.get(0).ride.activityId);
        assertEquals(950, d10.efforts.get(0).seconds);
        assertEquals(10_000 / 950.0, d10.efforts.get(0).avgSpeedMps(), 1e-9);
        assertEquals(1L, out.get(1).efforts.get(0).ride.activityId);
        assertTrue(out.get(2).efforts.isEmpty());
    }

    @Test
    public void keepsAtMostTopThree() {
        List<StoredRide> rides = Arrays.asList(ride(1, "Ride", 1), ride(2, "Ride", 2),
                ride(3, "Ride", 3), ride(4, "Ride", 4));
        List<FastestDistanceCalculator.Distance> out = FastestDistanceCalculator.compute(rides,
                Arrays.asList(stats(1, 1000, null, null), stats(2, 990, null, null),
                        stats(3, 980, null, null), stats(4, 970, null, null)));
        assertEquals(FastestDistanceCalculator.TOP_N, out.get(0).efforts.size());
        assertEquals(4L, out.get(0).efforts.get(0).ride.activityId);
    }

    @Test
    public void skipsVirtualAndEBikeRidesAndOrphanStats() {
        List<StoredRide> rides = Arrays.asList(ride(1, "VirtualRide", 1), ride(2, "EBikeRide", 2),
                ride(3, "Ride", 3));
        List<FastestDistanceCalculator.Distance> out = FastestDistanceCalculator.compute(rides,
                Arrays.asList(stats(1, 500, null, null), stats(2, 600, null, null),
                        stats(3, 1000, null, null), stats(99, 400, null, null)));
        assertEquals(1, out.get(0).efforts.size());
        assertEquals(3L, out.get(0).efforts.get(0).ride.activityId);
    }

    @Test
    public void tieGoesToTheEarlierRide() {
        List<StoredRide> rides = Arrays.asList(ride(1, "Ride", 500), ride(2, "Ride", 100));
        List<FastestDistanceCalculator.Distance> out = FastestDistanceCalculator.compute(rides,
                Arrays.asList(stats(1, 1000, null, null), stats(2, 1000, null, null)));
        assertEquals(2L, out.get(0).efforts.get(0).ride.activityId);
    }

    @Test
    public void emptyInputGivesEmptyDistances() {
        List<FastestDistanceCalculator.Distance> out =
                FastestDistanceCalculator.compute(Collections.emptyList(), null);
        assertEquals(3, out.size());
        assertTrue(FastestDistanceCalculator.isEmpty(out));
    }
}
