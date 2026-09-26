package nl.paree.climbpro.domain.ride;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import nl.paree.climbpro.data.ride.StoredRide;
import nl.paree.climbpro.data.ride.StoredRideStreamStats;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class HeartRateDriftCalculatorTest {

    private static final long NOW = 1_800_000_000L;
    private static final long DAY = 86_400L;

    private static StoredRide ride(long id, String type, long daysAgo) {
        StoredRide r = new StoredRide();
        r.activityId = id;
        r.type = type;
        r.startEpochSec = NOW - daysAgo * DAY;
        return r;
    }

    private static StoredRideStreamStats drift(long id, Double pct) {
        StoredRideStreamStats s = new StoredRideStreamStats();
        s.activityId = id;
        s.hasStreams = true;
        s.hrDriftPct = pct;
        s.hrDriftBasis = HeartRateDriftAnalyzer.BASIS_POWER;
        s.hrDriftMinutes = 120;
        return s;
    }

    @Test
    public void listsRidesWithDriftNewestFirstWithLevels() {
        List<StoredRide> rides = Arrays.asList(ride(1, "Ride", 20), ride(2, "Ride", 2),
                ride(3, "Ride", 10), ride(4, "Ride", 5));
        HeartRateDriftCalculator.Result r = HeartRateDriftCalculator.compute(rides, Arrays.asList(
                drift(1, 3.0), drift(2, 12.5), drift(3, 6.0), drift(4, null)), NOW);

        assertEquals(3, r.entries.size());
        assertEquals(2L, r.entries.get(0).ride.activityId);
        assertEquals(HeartRateDriftCalculator.Level.HIGH, r.entries.get(0).level);
        assertEquals(HeartRateDriftCalculator.Level.MODERATE, r.entries.get(1).level);
        assertEquals(HeartRateDriftCalculator.Level.STABLE, r.entries.get(2).level);
    }

    @Test
    public void comparesRecentSixWeeksWithTheSixWeeksBefore() {
        List<StoredRide> rides = Arrays.asList(ride(1, "Ride", 3), ride(2, "Ride", 30),
                ride(3, "Ride", 50), ride(4, "Ride", 80), ride(5, "Ride", 100));
        HeartRateDriftCalculator.Result r = HeartRateDriftCalculator.compute(rides, Arrays.asList(
                drift(1, 4.0), drift(2, 6.0), drift(3, 9.0), drift(4, 7.0), drift(5, 1.0)), NOW);

        assertEquals(5.0, r.recentAvg, 1e-9);
        assertEquals(2, r.recentCount);
        assertEquals(8.0, r.previousAvg, 1e-9);
        assertEquals(2, r.previousCount);
    }

    @Test
    public void eBikeRidesAreLeftOut() {
        HeartRateDriftCalculator.Result r = HeartRateDriftCalculator.compute(
                Collections.singletonList(ride(1, "EBikeRide", 1)),
                Collections.singletonList(drift(1, 2.0)), NOW);
        assertTrue(r.entries.isEmpty());
        assertNull(r.recentAvg);
    }

    @Test
    public void levelBoundaries() {
        assertEquals(HeartRateDriftCalculator.Level.STABLE, HeartRateDriftCalculator.levelOf(-2));
        assertEquals(HeartRateDriftCalculator.Level.STABLE, HeartRateDriftCalculator.levelOf(4.99));
        assertEquals(HeartRateDriftCalculator.Level.MODERATE, HeartRateDriftCalculator.levelOf(5));
        assertEquals(HeartRateDriftCalculator.Level.HIGH, HeartRateDriftCalculator.levelOf(10));
    }
}
