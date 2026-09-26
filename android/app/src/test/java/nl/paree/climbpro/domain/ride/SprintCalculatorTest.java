package nl.paree.climbpro.domain.ride;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import nl.paree.climbpro.data.ride.StoredRide;
import nl.paree.climbpro.data.ride.StoredRideStreamStats;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class SprintCalculatorTest {

    private static StoredRide ride(long id, String type) {
        StoredRide r = new StoredRide();
        r.activityId = id;
        r.type = type;
        r.startEpochSec = 1000 + id;
        return r;
    }

    private static StoredRideStreamStats stats(long id, Integer watts5s, Double speed) {
        StoredRideStreamStats s = new StoredRideStreamStats();
        s.activityId = id;
        s.hasStreams = true;
        s.sprint5sWatts = watts5s;
        s.sprint5sAtSec = watts5s != null ? 60 : null;
        s.sprint15sWatts = watts5s != null ? watts5s - 200 : null;
        s.sprint10sSpeedMps = speed;
        s.sprint10sSpeedAtSec = speed != null ? 90 : null;
        return s;
    }

    @Test
    public void ranksPowerAndSpeedSeparately() {
        List<StoredRide> rides = Arrays.asList(ride(1, "Ride"), ride(2, "Ride"), ride(3, "Ride"));
        SprintCalculator.Result r = SprintCalculator.compute(rides, Arrays.asList(
                stats(1, 900, 14.0), stats(2, 1100, null), stats(3, null, 16.5)));

        assertEquals(2, r.byPower.size());
        assertEquals(2L, r.byPower.get(0).ride.activityId);
        assertEquals(1100, r.byPower.get(0).watts5s);
        assertEquals(900, r.byPower.get(0).watts15s);
        assertEquals(2, r.bySpeed.size());
        assertEquals(3L, r.bySpeed.get(0).ride.activityId);
        assertEquals(16.5, r.bySpeed.get(0).speedMps, 1e-9);
        assertEquals(90, r.bySpeed.get(0).atSec);
    }

    @Test
    public void indoorCountsForPowerButNotSpeedAndEBikesForNeither() {
        List<StoredRide> rides = Arrays.asList(ride(1, "VirtualRide"), ride(2, "EBikeRide"));
        SprintCalculator.Result r = SprintCalculator.compute(rides, Arrays.asList(
                stats(1, 1000, 15.0), stats(2, 1200, 17.0)));

        assertEquals(1, r.byPower.size());
        assertEquals(1L, r.byPower.get(0).ride.activityId);
        assertTrue(r.bySpeed.isEmpty());
    }

    @Test
    public void keepsTopFive() {
        List<StoredRide> rides = new ArrayList<>();
        List<StoredRideStreamStats> stats = new ArrayList<>();
        for (int i = 1; i <= 7; i++) {
            rides.add(ride(i, "Ride"));
            stats.add(stats(i, 700 + i * 10, 12.0 + i));
        }
        SprintCalculator.Result r = SprintCalculator.compute(rides, stats);
        assertEquals(SprintCalculator.TOP_N, r.byPower.size());
        assertEquals(770, r.byPower.get(0).watts5s);
        assertEquals(SprintCalculator.TOP_N, r.bySpeed.size());
    }

    @Test
    public void emptyInput() {
        SprintCalculator.Result r = SprintCalculator.compute(Collections.emptyList(), null);
        assertTrue(r.isEmpty());
    }
}
