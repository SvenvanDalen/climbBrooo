package nl.paree.climbpro.domain.ride;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import nl.paree.climbpro.data.ride.StoredRide;
import nl.paree.climbpro.data.ride.StoredRideStreamStats;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class PowerCurveCalculatorTest {

    private static final long NOW = 1_800_000_000L;
    private static final long DAY = 86_400L;

    private static StoredRide ride(long id, String type, long daysAgo) {
        StoredRide r = new StoredRide();
        r.activityId = id;
        r.type = type;
        r.startEpochSec = NOW - daysAgo * DAY;
        return r;
    }

    private static StoredRideStreamStats curve(long id, int... watts) {
        StoredRideStreamStats s = new StoredRideStreamStats();
        s.activityId = id;
        s.hasStreams = true;
        s.powerCurve = watts;
        return s;
    }

    @Test
    public void takesBestPerDurationAcrossRides() {
        List<StoredRide> rides = Arrays.asList(ride(1, "Ride", 3), ride(2, "Ride", 10));
        PowerCurveCalculator.Result r = PowerCurveCalculator.compute(rides, Arrays.asList(
                curve(1, 900, 400, 300, 250, 0),
                curve(2, 800, 450, 320, 260, 230)), PowerCurveCalculator.Period.ALL, NOW);

        assertEquals(2, r.ridesWithPower);
        assertEquals(900, r.bests[0].watts);
        assertEquals(1L, r.bests[0].ride.activityId);
        assertEquals(450, r.bests[1].watts);
        assertEquals(2L, r.bests[1].ride.activityId);
        assertEquals(230, r.bests[4].watts);
        assertEquals(3600, r.bests[4].durationSec);
    }

    @Test
    public void periodLimitsRides() {
        List<StoredRide> rides = Arrays.asList(ride(1, "Ride", 3), ride(2, "Ride", 100));
        List<StoredRideStreamStats> stats = Arrays.asList(
                curve(1, 700, 350, 280, 240, 0), curve(2, 950, 500, 350, 300, 270));

        PowerCurveCalculator.Result sixWeeks = PowerCurveCalculator.compute(rides, stats,
                PowerCurveCalculator.Period.WEEKS_6, NOW);
        assertEquals(1, sixWeeks.ridesWithPower);
        assertEquals(700, sixWeeks.bests[0].watts);
        assertEquals(0, sixWeeks.bests[4].watts);
        assertNull(sixWeeks.bests[4].ride);

        PowerCurveCalculator.Result year = PowerCurveCalculator.compute(rides, stats,
                PowerCurveCalculator.Period.YEAR, NOW);
        assertEquals(950, year.bests[0].watts);
    }

    @Test
    public void eBikesAndRidesWithoutPowerAreSkipped() {
        List<StoredRide> rides = Arrays.asList(ride(1, "EBikeRide", 1), ride(2, "Ride", 1),
                ride(3, "VirtualRide", 1));
        StoredRideStreamStats noPower = curve(2);
        noPower.powerCurve = null;
        PowerCurveCalculator.Result r = PowerCurveCalculator.compute(rides, Arrays.asList(
                curve(1, 1500, 600, 500, 400, 350), noPower, curve(3, 600, 300, 250, 220, 200)),
                PowerCurveCalculator.Period.ALL, NOW);

        assertEquals(1, r.ridesWithPower);
        assertEquals(3L, r.bests[0].ride.activityId);
    }

    @Test
    public void tieGoesToTheEarliestRide() {
        List<StoredRide> rides = Arrays.asList(ride(1, "Ride", 2), ride(2, "Ride", 20));
        PowerCurveCalculator.Result r = PowerCurveCalculator.compute(rides, Arrays.asList(
                curve(1, 800, 400, 300, 250, 200), curve(2, 800, 390, 300, 250, 200)),
                PowerCurveCalculator.Period.ALL, NOW);
        assertEquals(2L, r.bests[0].ride.activityId);
        assertEquals(1L, r.bests[1].ride.activityId);
    }

    @Test
    public void emptyInput() {
        PowerCurveCalculator.Result r = PowerCurveCalculator.compute(null, null,
                PowerCurveCalculator.Period.ALL, NOW);
        assertTrue(r.isEmpty());
        assertEquals(5, r.bests.length);
    }
}
