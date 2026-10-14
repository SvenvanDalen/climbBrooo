package nl.paree.climbpro.domain.matching;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Peak power inside a climb pass (issue #389). */
public class ClimbPeakPowerTest {

    /** 1 Hz samples: 600 s at 200 W with a 60 s block at 400 W starting at 100 s. */
    private static List<Double> watts(int n) {
        List<Double> w = new ArrayList<>();
        for (int i = 0; i < n; i++) w.add(i > 100 && i <= 160 ? 400.0 : 200.0);
        return w;
    }

    private static long[] seconds(int n) {
        long[] t = new long[n];
        for (int i = 0; i < n; i++) t[i] = 1_000 + i;
        return t;
    }

    @Test
    public void bestAveragesPerDurationInsideThePass() {
        int n = 601;
        int[] peaks = ClimbPeakPower.compute(seconds(n), watts(n), 0, n - 1);
        // 30 s and 1 min inside the 400 W block, 5 min = (60×400 + 240×200)/300 = 240,
        // 20 min longer than the pass.
        assertArrayEquals(new int[]{400, 400, 240, 0}, peaks);
    }

    @Test
    public void sparseSamplesHoldTheirPowerBackToThePreviousSample() {
        // One sample every 10 s, 300 W throughout, 5 minutes.
        long[] t = new long[31];
        List<Double> w = new ArrayList<>();
        for (int i = 0; i < t.length; i++) {
            t[i] = i * 10L;
            w.add(300.0);
        }
        assertArrayEquals(new int[]{300, 300, 300, 0}, ClimbPeakPower.compute(t, w, 0, 30));
    }

    @Test
    public void missingPowerOrBadRangeGivesNull() {
        assertNull(ClimbPeakPower.compute(seconds(3), Arrays.asList(null, null, 0.0), 0, 2));
        assertNull(ClimbPeakPower.compute(seconds(3), watts(3), 2, 1));
        assertNull(ClimbPeakPower.compute(new long[]{5, 5, 5}, watts(3), 0, 2));
        assertNull(ClimbPeakPower.compute(null, watts(3), 0, 2));
    }

    @Test
    public void trackSensorsOnlyReportPeaksWithAPowerStream() {
        TrackSensors with = new TrackSensors(true, false, false);
        TrackSensors without = new TrackSensors(false, false, false);
        for (int i = 0; i < 61; i++) {
            with.add(250.0, null, null);
            without.add(null, null, null);
        }
        assertArrayEquals(new int[]{250, 250, 0, 0}, with.peakWatts(seconds(61), 0, 60));
        assertNull(without.peakWatts(seconds(61), 0, 60));
    }
}
