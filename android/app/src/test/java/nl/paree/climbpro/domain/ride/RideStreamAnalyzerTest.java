package nl.paree.climbpro.domain.ride;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import nl.paree.climbpro.data.ride.StoredRideStreamStats;

import org.junit.Test;

public class RideStreamAnalyzerTest {

    /** Constant speed: one sample per second, {@code mps} metres per second. */
    private static RideStreams steady(int seconds, double mps) {
        int[] t = new int[seconds + 1];
        double[] d = new double[seconds + 1];
        for (int i = 0; i <= seconds; i++) {
            t[i] = i;
            d[i] = i * mps;
        }
        return new RideStreams(t, d);
    }

    @Test
    public void steadyRideTakesDistanceOverSpeed() {
        RideStreams s = steady(3600, 10); // 36 km/h for an hour = 36 km
        Integer sec = RideStreamAnalyzer.fastestDistanceSec(s, 10_000);
        assertEquals(1000, (int) sec);
    }

    @Test
    public void rideShorterThanTargetHasNoEffort() {
        assertNull(RideStreamAnalyzer.fastestDistanceSec(steady(3600, 10), 40_000));
    }

    @Test
    public void findsFastestWindowInsideALongerRide() {
        // 10 km at 5 m/s, then 10 km at 10 m/s, then 10 km at 5 m/s.
        int n = 2000 + 1000 + 2000;
        int[] t = new int[n + 1];
        double[] d = new double[n + 1];
        for (int i = 1; i <= n; i++) {
            t[i] = i;
            double v = i > 2000 && i <= 3000 ? 10 : 5;
            d[i] = d[i - 1] + v;
        }
        Integer sec = RideStreamAnalyzer.fastestDistanceSec(new RideStreams(t, d), 10_000);
        assertEquals(1000, (int) sec);
    }

    @Test
    public void interpolatesWindowStartBetweenSamples() {
        // Samples every 10 s at 10 m/s: 10 km lands between samples when offset.
        int[] t = new int[201];
        double[] d = new double[201];
        for (int i = 0; i <= 200; i++) {
            t[i] = i * 10;
            d[i] = i * 100 + 50;
        }
        Integer sec = RideStreamAnalyzer.fastestDistanceSec(new RideStreams(t, d), 10_000);
        assertEquals(1000, (int) sec);
    }

    @Test
    public void pauseCountsTowardsElapsedTime() {
        // 5 km, a 10-minute stop, another 5 km, all at 10 m/s.
        int[] t = new int[1002];
        double[] d = new double[1002];
        for (int i = 0; i <= 500; i++) { t[i] = i; d[i] = i * 10; }
        for (int i = 501; i <= 1001; i++) { t[i] = i + 600 - 1; d[i] = (i - 1) * 10; }
        Integer sec = RideStreamAnalyzer.fastestDistanceSec(new RideStreams(t, d), 10_000);
        assertEquals(1600, (int) sec);
    }

    @Test
    public void rejectsImplausibleGpsJump() {
        // Steady 8 m/s, but one sample jumps 3 km ahead in one second.
        int[] t = new int[1501];
        double[] d = new double[1501];
        for (int i = 1; i <= 1500; i++) {
            t[i] = i;
            d[i] = d[i - 1] + (i == 700 ? 3000 : 8);
        }
        Integer sec = RideStreamAnalyzer.fastestDistanceSec(new RideStreams(t, d), 10_000);
        // Without the jump, 10 km at 8 m/s takes 1250 s; the jump must not make it faster.
        assertNull(sec);
    }

    @Test
    public void analyzeFillsAllDistancesAndVersion() {
        StoredRideStreamStats st = RideStreamAnalyzer.analyze(7L, steady(5000, 10)); // 50 km
        assertEquals(7L, st.activityId);
        assertEquals(RideStreamAnalyzer.VERSION, st.version);
        assertTrue(st.hasStreams);
        assertEquals(1000, (int) st.best10kSec);
        assertEquals(4000, (int) st.best40kSec);
        assertNull(st.best100kSec);
    }

    @Test
    public void analyzeWithoutStreamsMarksRideAsEmpty() {
        StoredRideStreamStats st = RideStreamAnalyzer.analyze(7L, null);
        assertFalse(st.hasStreams);
        assertEquals(RideStreamAnalyzer.VERSION, st.version);
        assertNull(st.best10kSec);
    }

    @Test
    public void mismatchedOrTooShortStreamsAreTreatedAsEmpty() {
        assertFalse(RideStreamAnalyzer.analyze(1L,
                new RideStreams(new int[]{0, 1, 2}, new double[]{0, 5})).hasStreams);
        assertFalse(RideStreamAnalyzer.analyze(1L,
                new RideStreams(new int[]{0}, new double[]{0})).hasStreams);
    }
}
