package nl.paree.climbpro.domain.ride;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import nl.paree.climbpro.data.ride.StoredRideStreamStats;

import org.junit.Test;

public class SprintAnalyzerTest {

    /** 1 Hz ride of {@code n} seconds at {@code mps}, flat, with {@code watts} throughout. */
    private static double[][] base(int n, double mps, double watts) {
        double[] d = new double[n + 1];
        double[] w = new double[n + 1];
        double[] alt = new double[n + 1];
        for (int i = 0; i <= n; i++) {
            d[i] = i * mps;
            w[i] = watts;
            alt[i] = 10;
        }
        return new double[][]{d, w, alt};
    }

    private static int[] seconds(int n) {
        int[] t = new int[n + 1];
        for (int i = 0; i <= n; i++) t[i] = i;
        return t;
    }

    @Test
    public void peakPowerIsBestRollingAverage() {
        double[][] b = base(600, 9, 200);
        for (int i = 300; i < 305; i++) b[1][i] = 1000; // 5 s at 1000 W
        for (int i = 305; i < 315; i++) b[1][i] = 700;  // then 10 s at 700 W
        RideStreams s = new RideStreams(seconds(600), b[0], b[1], b[2]);

        SprintAnalyzer.Peak p5 = SprintAnalyzer.peakPower(s, 5);
        assertEquals(1000, p5.value, 1e-9);
        assertEquals(300, p5.atSec);
        SprintAnalyzer.Peak p15 = SprintAnalyzer.peakPower(s, 15);
        assertEquals((5 * 1000 + 10 * 700) / 15.0, p15.value, 1e-9);
    }

    @Test
    public void missingSecondsCountAsZeroPower() {
        // Samples every 2 s: 1000 W reported on both, but only 3 of 5 seconds are covered.
        int[] t = {0, 2, 4, 100};
        double[] d = {0, 20, 40, 1000};
        double[] w = {1000, 1000, 1000, 100};
        double[] alt = {0, 0, 0, 0};
        SprintAnalyzer.Peak p5 = SprintAnalyzer.peakPower(new RideStreams(t, d, w, alt), 5);
        assertEquals(600, p5.value, 1e-9);
    }

    @Test
    public void powerSpikesAreIgnored() {
        double[][] b = base(300, 9, 250);
        b[1][100] = 4000; // meter glitch
        SprintAnalyzer.Peak p5 = SprintAnalyzer.peakPower(
                new RideStreams(seconds(300), b[0], b[1], b[2]), 5);
        assertEquals(250, p5.value, 1e-9);
    }

    @Test
    public void noPowerStreamMeansNoPowerPeak() {
        double[][] b = base(300, 9, 0);
        assertNull(SprintAnalyzer.peakPower(new RideStreams(seconds(300), b[0], null, b[2]), 5));
    }

    @Test
    public void peakSpeedFindsFlatSprint() {
        double[][] b = base(600, 9, 200);
        double[] d = b[0];
        for (int i = 201; i <= 600; i++) {
            double v = i > 400 && i <= 410 ? 16 : 9;
            d[i] = d[i - 1] + v;
        }
        SprintAnalyzer.Peak v = SprintAnalyzer.peakSpeed(
                new RideStreams(seconds(600), d, b[1], b[2]), 10);
        assertEquals(16, v.value, 1e-9);
        assertEquals(400, v.atSec);
    }

    @Test
    public void descentsDoNotCountAsSprints() {
        double[][] b = base(600, 9, 200);
        double[] d = b[0];
        double[] alt = b[2];
        for (int i = 201; i <= 600; i++) {
            boolean downhill = i > 400 && i <= 410;
            d[i] = d[i - 1] + (downhill ? 20 : 9);
            alt[i] = alt[i - 1] - (downhill ? 1.6 : 0); // 8 % down
        }
        SprintAnalyzer.Peak v = SprintAnalyzer.peakSpeed(
                new RideStreams(seconds(600), d, b[1], alt), 10);
        assertEquals(9, v.value, 1e-9);
    }

    @Test
    public void noAltitudeMeansNoSpeedSprint() {
        double[][] b = base(600, 9, 200);
        assertNull(SprintAnalyzer.peakSpeed(new RideStreams(seconds(600), b[0], b[1], null), 10));
    }

    @Test
    public void speedWindowAcrossAPauseIsSkipped() {
        // Two samples 30 s apart look like 10 s of riding only if the gap is ignored.
        int[] t = {0, 5, 10, 40, 45, 50};
        double[] d = {0, 45, 90, 700, 745, 790};
        double[] alt = new double[6];
        assertEquals(9, SprintAnalyzer.peakSpeed(
                new RideStreams(t, d, null, alt), 10).value, 1e-9);
    }

    @Test
    public void analyzeStoresSprintFields() {
        double[][] b = base(600, 9, 200);
        for (int i = 300; i < 305; i++) b[1][i] = 900;
        StoredRideStreamStats st = RideStreamAnalyzer.analyze(1L,
                new RideStreams(seconds(600), b[0], b[1], b[2]));
        assertEquals(900, (int) st.sprint5sWatts);
        assertEquals(300, (int) st.sprint5sAtSec);
        assertEquals(9 * 3.6, st.sprint10sSpeedMps * 3.6, 1e-6);
    }
}
