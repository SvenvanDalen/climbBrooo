package nl.paree.climbpro.domain.ride;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class PowerCurveAnalyzerTest {

    private static RideStreams ride(double[] watts) {
        int n = watts.length;
        int[] t = new int[n];
        double[] d = new double[n];
        for (int i = 0; i < n; i++) {
            t[i] = i;
            d[i] = i * 8.0;
        }
        return new RideStreams(t, d, watts, null);
    }

    private static double[] constant(int n, double w) {
        double[] a = new double[n];
        java.util.Arrays.fill(a, w);
        return a;
    }

    @Test
    public void steadyRideGivesFlatCurve() {
        int[] p = PowerCurveAnalyzer.bestPowers(ride(constant(4000, 220)));
        assertArrayEquals(new int[]{220, 220, 220, 220, 220}, p);
    }

    @Test
    public void picksBestWindowPerDuration() {
        double[] w = constant(4000, 150);
        for (int i = 1000; i < 1005; i++) w[i] = 900;  // sprint
        for (int i = 2000; i < 3200; i++) w[i] = 300;  // 20 min threshold block
        int[] p = PowerCurveAnalyzer.bestPowers(ride(w));

        assertEquals(900, p[0]);
        assertEquals(300, p[1]);
        assertEquals(300, p[2]);
        assertEquals(300, p[3]);
        // 60 min: the best window holds the 20 min block, the sprint and 150 W elsewhere.
        assertEquals(Math.round((1200 * 300 + 5 * 900 + 2395 * 150) / 3600.0), p[4]);
    }

    @Test
    public void durationsLongerThanTheRideAreZero() {
        int[] p = PowerCurveAnalyzer.bestPowers(ride(constant(600, 250)));
        assertEquals(250, p[2]);
        assertEquals(0, p[3]);
        assertEquals(0, p[4]);
    }

    @Test
    public void gapsCountAsZeroAndSpikesAreIgnored() {
        // Samples every 10 s at 400 W: only 1 in 10 seconds carries power.
        int n = 400;
        int[] t = new int[n];
        double[] d = new double[n];
        double[] w = new double[n];
        for (int i = 0; i < n; i++) {
            t[i] = i * 10;
            d[i] = i * 80.0;
            w[i] = 400;
        }
        w[50] = 5000; // glitch
        int[] p = PowerCurveAnalyzer.bestPowers(new RideStreams(t, d, w, null));
        assertEquals(80, p[0]);
        assertEquals(40, p[1]);
    }

    @Test
    public void noPowerStreamGivesNull() {
        assertNull(PowerCurveAnalyzer.bestPowers(
                new RideStreams(new int[]{0, 1, 2}, new double[]{0, 8, 16}, null, null)));
        assertNull(PowerCurveAnalyzer.bestPowers(ride(constant(100, Double.NaN))));
        assertNull(PowerCurveAnalyzer.bestPowers(null));
    }
}
