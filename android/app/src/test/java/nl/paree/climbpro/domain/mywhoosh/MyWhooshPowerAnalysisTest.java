package nl.paree.climbpro.domain.mywhoosh;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import nl.paree.climbpro.data.ride.StoredRide;
import nl.paree.climbpro.data.ride.StoredRideStreamStats;
import nl.paree.climbpro.data.route.StoredClimbAttempt;
import nl.paree.climbpro.domain.ride.PowerCurveCalculator;
import nl.paree.climbpro.domain.ride.RideStreams;

import org.junit.Test;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Source filter, EF trend, indoor/outdoor curve and ride profile (issues #390-#398). */
public class MyWhooshPowerAnalysisTest {

    private static final long WEEK = 7 * 86_400L;
    /** A Wednesday, so week boundaries are never on the timestamp itself. */
    private static final long NOW =
            LocalDate.of(2026, 3, 4).atTime(12, 0).toEpochSecond(ZoneOffset.UTC);

    private static StoredRide ride(long id, String name, String type, long start) {
        StoredRide r = new StoredRide();
        r.activityId = id;
        r.name = name;
        r.type = type;
        r.sportType = type;
        r.startEpochSec = start;
        r.movingTimeSec = 3600;
        return r;
    }

    private static StoredRide myWhoosh(long id, long start) {
        return ride(id, "MyWhoosh - Hautacam", "VirtualRide", start);
    }

    private static StoredRide outdoor(long id, long start) {
        return ride(id, "Ochtendrit", "Ride", start);
    }

    private static StoredRide zwift(long id, long start) {
        return ride(id, "Zwift - Watopia", "VirtualRide", start);
    }

    private static StoredRideStreamStats stats(long id, Integer np, Integer hr, int[] curve) {
        StoredRideStreamStats s = new StoredRideStreamStats();
        s.activityId = id;
        s.normalizedPower = np;
        s.avgHeartrate = hr;
        s.powerCurve = curve;
        return s;
    }

    // --- RideSource (#392) --------------------------------------------------------------

    @Test
    public void sourceFiltersRides() {
        List<StoredRide> rides = Arrays.asList(myWhoosh(1, 0), outdoor(2, 0), zwift(3, 0));
        assertSame(rides, RideSource.ALL.filter(rides));
        assertEquals(1, RideSource.MYWHOOSH.filter(rides).size());
        assertEquals(2L, RideSource.OUTDOOR.filter(rides).get(0).activityId);
        assertEquals(1, RideSource.OUTDOOR.filter(rides).size());
        assertFalse(RideSource.MYWHOOSH.includes(null));
    }

    @Test
    public void sourceFiltersAttemptsByTheirRide() {
        List<StoredRide> rides = Arrays.asList(myWhoosh(1, 0), outdoor(2, 0), zwift(3, 0));
        List<StoredClimbAttempt> attempts = new ArrayList<>();
        for (long id : new long[]{1, 2, 3, 99}) {
            StoredClimbAttempt a = new StoredClimbAttempt();
            a.activityId = id;
            attempts.add(a);
        }
        assertEquals(1, RideSource.MYWHOOSH.filterAttempts(attempts, rides).size());
        // Outdoor: the outdoor ride and the file import without a known ride.
        List<StoredClimbAttempt> out = RideSource.OUTDOOR.filterAttempts(attempts, rides);
        assertEquals(2, out.size());
        assertEquals(99L, out.get(1).activityId);
        assertSame(attempts, RideSource.ALL.filterAttempts(attempts, rides));
    }

    @Test
    public void sourceFromNameFallsBackToAll() {
        assertEquals(RideSource.OUTDOOR, RideSource.fromName("OUTDOOR"));
        assertEquals(RideSource.ALL, RideSource.fromName("nonsense"));
        assertEquals(RideSource.ALL, RideSource.fromName(null));
    }

    // --- EfficiencyFactorTrend (#398) ---------------------------------------------------

    @Test
    public void efficiencyFactorIsNpOverHeartRateAveragedPerWeek() {
        List<StoredRide> rides = new ArrayList<>();
        Map<Long, StoredRideStreamStats> st = new HashMap<>();
        // Ten weeks ago and nine weeks ago: EF 1.5; this week two rides: EF 2.0 and 1.8.
        rides.add(myWhoosh(1, NOW - 10 * WEEK));
        st.put(1L, stats(1, 210, 140, null));
        rides.add(myWhoosh(2, NOW - 9 * WEEK));
        st.put(2L, stats(2, 210, 140, null));
        rides.add(myWhoosh(3, NOW));
        st.put(3L, stats(3, 260, 130, null));
        rides.add(myWhoosh(4, NOW - 3600));
        st.put(4L, stats(4, 234, 130, null));
        // Left out: outdoor, too short, no heart rate, older than the window.
        rides.add(outdoor(5, NOW));
        st.put(5L, stats(5, 300, 100, null));
        StoredRide shortRide = myWhoosh(6, NOW);
        shortRide.movingTimeSec = 600;
        rides.add(shortRide);
        st.put(6L, stats(6, 300, 100, null));
        rides.add(myWhoosh(7, NOW));
        st.put(7L, stats(7, 300, null, null));
        rides.add(myWhoosh(8, NOW - 30 * WEEK));
        st.put(8L, stats(8, 300, 100, null));

        EfficiencyFactorTrend.Result r =
                EfficiencyFactorTrend.compute(rides, st, NOW, ZoneOffset.UTC);
        assertEquals(3, r.weeks.size());
        assertEquals(1.5, r.weeks.get(0).ef, 1e-9);
        EfficiencyFactorTrend.Week last = r.weeks.get(2);
        assertEquals(2, last.rides);
        assertEquals(1.9, last.ef, 1e-9);
        assertNotNull(r.change);
        assertEquals(0.4, r.change, 1e-9);
    }

    @Test
    public void efficiencyFactorWithoutEarlierWeeksHasNoTrend() {
        Map<Long, StoredRideStreamStats> st = new HashMap<>();
        st.put(1L, stats(1, 200, 100, null));
        EfficiencyFactorTrend.Result r = EfficiencyFactorTrend.compute(
                Arrays.asList(myWhoosh(1, NOW)), st, NOW, ZoneOffset.UTC);
        assertFalse(r.isEmpty());
        assertNull(r.change);
        assertTrue(EfficiencyFactorTrend.compute(null, st, NOW, ZoneOffset.UTC).isEmpty());
        assertNull(EfficiencyFactorTrend.of(myWhoosh(1, NOW), null));
    }

    // --- IndoorOutdoorPowerCurve (#394) -------------------------------------------------

    @Test
    public void indoorCurveIsComparedWithOutdoorPerDuration() {
        List<StoredRide> rides = Arrays.asList(myWhoosh(1, NOW - 86_400), outdoor(2, NOW - 86_400),
                zwift(3, NOW - 86_400));
        List<StoredRideStreamStats> st = Arrays.asList(
                stats(1, null, null, new int[]{900, 400, 300, 282, 0}),
                stats(2, null, null, new int[]{1000, 400, 320, 300, 250}),
                stats(3, null, null, new int[]{2000, 2000, 2000, 2000, 2000}));
        IndoorOutdoorPowerCurve.Result r = IndoorOutdoorPowerCurve.compute(
                rides, st, PowerCurveCalculator.Period.DAYS_90, NOW);
        assertTrue(r.isComparable());
        assertArrayEquals(new int[]{900, 400, 300, 282, 0}, r.indoorWatts);
        assertEquals(-10.0, r.differencePct(0), 1e-9);
        assertEquals(0.0, r.differencePct(1), 1e-9);
        assertEquals(-6.0, r.differencePct(3), 1e-9);
        assertNull(r.differencePct(4));
    }

    @Test
    public void indoorCurveWithoutOutdoorRidesIsNotComparable() {
        IndoorOutdoorPowerCurve.Result r = IndoorOutdoorPowerCurve.compute(
                Arrays.asList(myWhoosh(1, NOW)),
                Arrays.asList(stats(1, null, null, new int[]{900, 400, 300, 282, 0})),
                PowerCurveCalculator.Period.DAYS_90, NOW);
        assertFalse(r.isComparable());
        assertEquals(0, r.outdoorRides);
    }

    // --- RideProfile (#390) -------------------------------------------------------------

    @Test
    public void profileBinsStreamsPerHundredMetres() {
        // 1 Hz at 5 m/s for 100 s = 500 m: five bins of 20 samples.
        int n = 101;
        int[] t = new int[n];
        double[] d = new double[n];
        double[] alt = new double[n];
        double[] w = new double[n];
        for (int i = 0; i < n; i++) {
            t[i] = i;
            d[i] = i * 5.0;
            alt[i] = i < 40 ? 10 : 20;
            w[i] = i < 40 ? 100 : 300;
        }
        RideProfile p = RideProfile.of(new RideStreams(t, d, w, alt, null, null));
        assertNotNull(p);
        assertEquals(5, p.size());
        assertEquals(10, p.altitude[0], 1e-9);
        assertEquals(300, p.watts[4], 1e-9);
        assertTrue(p.has(p.watts));
        assertFalse(p.has(p.heartrate));
        assertTrue(Double.isNaN(p.cadence[0]));
    }

    @Test
    public void profileNeedsDistance() {
        assertNull(RideProfile.of(null));
        assertNull(RideProfile.of(new RideStreams(new int[]{0, 1}, new double[]{0, 0})));
    }
}
