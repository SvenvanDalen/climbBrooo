package nl.paree.climbpro.domain.ride;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import nl.paree.climbpro.data.ride.StoredRideStreamStats;

import org.junit.Test;

public class HeartRateDriftAnalyzerTest {

    /**
     * 1 Hz ride of {@code n} s at 8 m/s on flat road, heart rate rising linearly from
     * {@code hrStart} to {@code hrEnd}, constant {@code watts} (NaN for no power stream).
     */
    private static RideStreams ride(int n, double hrStart, double hrEnd, double watts) {
        int[] t = new int[n + 1];
        double[] d = new double[n + 1];
        double[] hr = new double[n + 1];
        double[] w = Double.isNaN(watts) ? null : new double[n + 1];
        double[] alt = new double[n + 1];
        for (int i = 0; i <= n; i++) {
            t[i] = i;
            d[i] = i * 8.0;
            hr[i] = hrStart + (hrEnd - hrStart) * i / n;
            if (w != null) w[i] = watts;
        }
        return new RideStreams(t, d, w, alt, hr);
    }

    @Test
    public void steadyHeartRateHasNoDrift() {
        HeartRateDriftAnalyzer.Drift drift =
                HeartRateDriftAnalyzer.analyze(ride(2 * 3600, 140, 140, 200));
        assertEquals(0, drift.percent, 1e-9);
        assertEquals(HeartRateDriftAnalyzer.BASIS_POWER, drift.basis);
    }

    @Test
    public void risingHeartRateAtSamePowerIsPositiveDrift() {
        HeartRateDriftAnalyzer.Drift drift =
                HeartRateDriftAnalyzer.analyze(ride(2 * 3600 + 600, 130, 150, 200));
        // Warm-up (first 10 min) skipped; halves average about 136.3 and 145.5 bpm.
        double hr1 = 130 + 20 * (600 + 1800.0) / 7800;
        double hr2 = 130 + 20 * (600 + 5400.0) / 7800;
        double expected = (200 / hr1 - 200 / hr2) / (200 / hr1) * 100;
        assertEquals(expected, drift.percent, 0.05);
        assertEquals(120, drift.minutes);
    }

    @Test
    public void usesSpeedWithoutPower() {
        HeartRateDriftAnalyzer.Drift drift =
                HeartRateDriftAnalyzer.analyze(ride(2 * 3600, 140, 140, Double.NaN));
        assertEquals(HeartRateDriftAnalyzer.BASIS_SPEED, drift.basis);
        assertEquals(0, drift.percent, 1e-9);
    }

    @Test
    public void hillySpeedBasedRideIsSkipped() {
        RideStreams r = ride(2 * 3600, 140, 140, Double.NaN);
        for (int i = 0; i < r.altitude.length; i++) {
            r.altitude[i] = (i / 300) % 2 == 0 ? (i % 300) * 0.5 : 150 - (i % 300) * 0.5;
        }
        assertNull(HeartRateDriftAnalyzer.analyze(r));
    }

    @Test
    public void shortRideIsSkipped() {
        assertNull(HeartRateDriftAnalyzer.analyze(ride(45 * 60, 140, 150, 200)));
    }

    @Test
    public void noHeartRateIsSkipped() {
        RideStreams r = ride(2 * 3600, 140, 150, 200);
        assertNull(HeartRateDriftAnalyzer.analyze(
                new RideStreams(r.time, r.distance, r.watts, r.altitude, null)));
    }

    @Test
    public void stoppedSamplesDoNotCount() {
        // Same steady ride, but with a 20-minute stop at a low heart rate in the second half.
        int n = 2 * 3600 + 1200;
        int[] t = new int[n + 1];
        double[] d = new double[n + 1];
        double[] hr = new double[n + 1];
        double[] w = new double[n + 1];
        double[] alt = new double[n + 1];
        for (int i = 1; i <= n; i++) {
            t[i] = i;
            boolean stopped = i > 5000 && i <= 6200;
            d[i] = d[i - 1] + (stopped ? 0 : 8);
            hr[i] = stopped ? 90 : 140;
            w[i] = stopped ? 0 : 200;
        }
        hr[0] = 140;
        w[0] = 200;
        HeartRateDriftAnalyzer.Drift drift =
                HeartRateDriftAnalyzer.analyze(new RideStreams(t, d, w, alt, hr));
        assertEquals(0, drift.percent, 1e-9);
    }

    @Test
    public void analyzeStoresDrift() {
        StoredRideStreamStats st = RideStreamAnalyzer.analyze(1L, ride(2 * 3600, 140, 140, 200));
        assertEquals(0, st.hrDriftPct, 1e-9);
        assertEquals(HeartRateDriftAnalyzer.BASIS_POWER, st.hrDriftBasis);
        assertEquals(110, (int) st.hrDriftMinutes);
        assertEquals(140, (int) st.avgHeartrate);
    }
}
