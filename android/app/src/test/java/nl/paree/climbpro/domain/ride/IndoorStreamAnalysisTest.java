package nl.paree.climbpro.domain.ride;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import nl.paree.climbpro.data.ride.StoredRideStreamStats;

import org.junit.Test;

/** Normalized power, cadence per gradient and heart-rate recovery (issues #391, #402, #403). */
public class IndoorStreamAnalysisTest {

    /** 1 Hz ride at 10 m/s with the given per-second power, heart rate, altitude and cadence. */
    private static RideStreams ride(double[] watts, double[] hr, double[] alt, double[] cad) {
        int n = watts != null ? watts.length : alt.length;
        int[] t = new int[n];
        double[] d = new double[n];
        for (int i = 0; i < n; i++) {
            t[i] = i;
            d[i] = i * 10.0;
        }
        return new RideStreams(t, d, watts, alt, hr, cad);
    }

    private static double[] constant(int n, double v) {
        double[] a = new double[n];
        java.util.Arrays.fill(a, v);
        return a;
    }

    // --- normalized power ---------------------------------------------------------------

    @Test
    public void steadyPowerHasNpEqualToAverage() {
        assertEquals(Integer.valueOf(200),
                NormalizedPowerAnalyzer.normalizedPower(ride(constant(1200, 200), null, null, null)));
    }

    @Test
    public void variablePowerHasNpAboveAverage() {
        double[] w = new double[1200];
        for (int i = 0; i < w.length; i++) w[i] = (i / 60) % 2 == 0 ? 300 : 100; // avg 200
        Integer np = NormalizedPowerAnalyzer.normalizedPower(ride(w, null, null, null));
        assertNotNull(np);
        assertTrue("np " + np, np > 220 && np < 300);
    }

    @Test
    public void shortOrPowerlessRideHasNoNp() {
        assertNull(NormalizedPowerAnalyzer.normalizedPower(ride(constant(300, 200), null, null, null)));
        assertNull(NormalizedPowerAnalyzer.normalizedPower(
                ride(null, null, constant(1200, 0), null)));
    }

    @Test
    public void pauseIsLeftOutOfNp() {
        // 10 min at 200 W, a 30-minute stop (no samples), 10 min at 200 W.
        int[] t = new int[1200];
        double[] d = new double[1200];
        for (int i = 0; i < 1200; i++) {
            t[i] = i < 600 ? i : i + 1800;
            d[i] = i * 10.0;
        }
        RideStreams s = new RideStreams(t, d, constant(1200, 200), null, null, null);
        assertEquals(Integer.valueOf(200), NormalizedPowerAnalyzer.normalizedPower(s));
    }

    // --- cadence per gradient class -----------------------------------------------------

    @Test
    public void cadenceIsBinnedByGradientClass() {
        // 600 s flat at 90 rpm, then 600 s at 7 % (0.7 m per 10 m) at 70 rpm.
        int n = 1200;
        double[] alt = new double[n];
        double[] cad = new double[n];
        for (int i = 0; i < n; i++) {
            alt[i] = i < 600 ? 0 : (i - 600) * 0.7;
            cad[i] = i < 600 ? 90 : 70;
        }
        CadenceByGradeAnalyzer.Result r =
                CadenceByGradeAnalyzer.analyze(ride(null, null, alt, cad));
        assertNotNull(r);
        assertEquals(90, Math.round(r.revolutions[0] * 60.0 / r.seconds[0]));
        assertEquals(70, Math.round(r.revolutions[3] * 60.0 / r.seconds[3]));
        assertEquals(0, r.seconds[5]);
    }

    @Test
    public void coastingAndMissingCadenceDontCount() {
        assertNull(CadenceByGradeAnalyzer.analyze(
                ride(null, null, constant(600, 0), constant(600, 0))));
        assertNull(CadenceByGradeAnalyzer.analyze(ride(null, null, constant(600, 0), null)));
    }

    // --- heart-rate recovery ------------------------------------------------------------

    /**
     * 20 min at 150 W, then three 2-minute intervals at 350 W each followed by 4 minutes at
     * 100 W, then 10 min at 150 W. Heart rate is 170 at the end of an interval and has dropped
     * to 140 one minute later.
     */
    private static RideStreams intervals() {
        int n = 1200 + 3 * 360 + 600;
        double[] w = new double[n];
        double[] hr = new double[n];
        for (int i = 0; i < n; i++) {
            w[i] = 150;
            hr[i] = 130;
        }
        for (int k = 0; k < 3; k++) {
            int start = 1200 + k * 360;
            for (int i = start; i < start + 120; i++) {
                w[i] = 350;
                hr[i] = 170;
            }
            for (int i = start + 120; i < start + 360; i++) {
                w[i] = 100;
                hr[i] = 140;
            }
        }
        return ride(w, hr, null, null);
    }

    @Test
    public void heartRateDropAfterEachInterval() {
        assertArrayEquals(new int[]{30, 30, 30}, HeartRateRecoveryAnalyzer.drops(intervals()));
    }

    @Test
    public void steadyRideHasNoIntervals() {
        int[] drops = HeartRateRecoveryAnalyzer.drops(
                ride(constant(1800, 200), constant(1800, 140), null, null));
        assertNotNull(drops);
        assertEquals(0, drops.length);
    }

    @Test
    public void noRecoveryWithoutHeartRate() {
        assertNull(HeartRateRecoveryAnalyzer.drops(ride(constant(1800, 200), null, null, null)));
    }

    @Test
    public void analyzerStoresTheNewFields() {
        StoredRideStreamStats st = RideStreamAnalyzer.analyze(1L, intervals());
        assertNotNull(st.normalizedPower);
        assertEquals(3, st.hrRecoveryDrops.length);
        assertNull(st.cadenceGradeSec);
    }
}
