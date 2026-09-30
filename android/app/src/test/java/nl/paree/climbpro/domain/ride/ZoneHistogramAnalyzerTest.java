package nl.paree.climbpro.domain.ride;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class ZoneHistogramAnalyzerTest {

    private static RideStreams ride(int[] t, double[] watts, double[] hr) {
        double[] d = new double[t.length];
        for (int i = 0; i < t.length; i++) d[i] = t[i] * 8.0;
        return new RideStreams(t, d, watts, null, hr);
    }

    @Test
    public void heartRateTimeIsWeightedByGapToNextSample() {
        int[] t = {0, 1, 3, 6, 7};
        double[] hr = {120, 120.4, 150, 150, 199};
        int[] bins = ZoneHistogramAnalyzer.heartRateSeconds(ride(t, null, hr));

        assertEquals(3, bins[120 - ZoneHistogramAnalyzer.HR_MIN_BPM]); // 1 s + 2 s
        assertEquals(4, bins[150 - ZoneHistogramAnalyzer.HR_MIN_BPM]); // 3 s + 1 s
        // The last sample has no following interval; trimmed after 150 bpm.
        assertEquals(150 - ZoneHistogramAnalyzer.HR_MIN_BPM + 1, bins.length);
    }

    @Test
    public void pausesAndImplausibleValuesDontCount() {
        int[] t = {0, 1, 100, 101, 102, 103};
        double[] hr = {130, 130, 30, Double.NaN, 140, 140};
        int[] bins = ZoneHistogramAnalyzer.heartRateSeconds(ride(t, null, hr));

        // 0->1 counts, 1->100 is a pause, 30 bpm and NaN are skipped, 102->103 counts.
        assertEquals(1, bins[130 - ZoneHistogramAnalyzer.HR_MIN_BPM]);
        assertEquals(1, bins[140 - ZoneHistogramAnalyzer.HR_MIN_BPM]);
    }

    @Test
    public void powerIsBinnedPerTenWattsWithCoastingAndOverflow() {
        int[] t = {0, 2, 3, 4, 5, 6};
        double[] w = {0, 245, 249.9, 1800, 5000, 100};
        int[] bins = ZoneHistogramAnalyzer.powerSeconds(ride(t, w, null));

        assertEquals(2, bins[0]);   // coasting
        assertEquals(2, bins[24]);  // 245 and 249.9 W
        assertEquals(1, bins[ZoneHistogramAnalyzer.POWER_MAX_BIN]); // 1800 W lumped at the top
        assertEquals(ZoneHistogramAnalyzer.POWER_MAX_BIN + 1, bins.length); // 5000 W skipped
    }

    @Test
    public void missingStreamsGiveNull() {
        int[] t = {0, 1, 2};
        assertNull(ZoneHistogramAnalyzer.heartRateSeconds(ride(t, null, null)));
        assertNull(ZoneHistogramAnalyzer.powerSeconds(ride(t, null, null)));
        assertNull(ZoneHistogramAnalyzer.heartRateSeconds(
                ride(t, null, new double[]{Double.NaN, Double.NaN, Double.NaN})));
    }
}
