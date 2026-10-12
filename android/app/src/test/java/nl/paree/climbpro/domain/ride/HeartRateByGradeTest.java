package nl.paree.climbpro.domain.ride;

import nl.paree.climbpro.data.ride.StoredRideStreamStats;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/** Issue #24: heart rate per gradient class, per ride and summed into zones. */
public class HeartRateByGradeTest {

    /** 1 sample/s at 5 m/s for {@code n} seconds, climbing at {@code grade}, constant hr. */
    private static RideStreams climb(int n, double grade, double hr) {
        int[] t = new int[n];
        double[] d = new double[n];
        double[] alt = new double[n];
        double[] h = new double[n];
        for (int i = 0; i < n; i++) {
            t[i] = i;
            d[i] = i * 5.0;
            alt[i] = 100 + d[i] * grade;
            h[i] = hr;
        }
        return new RideStreams(t, d, null, alt, h);
    }

    @Test
    public void analyzer_putsSecondsAndBeatsInTheGradientClass() {
        HeartRateByGradeAnalyzer.Result r = HeartRateByGradeAnalyzer.analyze(climb(400, 0.07, 150));
        assertNotNull(r);
        // 7 % = class 3; the first 10 samples have no 50 m grade window yet.
        assertEquals(390, r.seconds[3]);
        assertEquals(Math.round(390 * 150 / 60.0), r.beats[3]);
        assertEquals(0, r.seconds[0]);
    }

    @Test
    public void analyzer_nullWithoutHeartRateOrTooShort() {
        RideStreams noHr = new RideStreams(new int[]{0, 1, 2}, new double[]{0, 5, 10}, null,
                new double[]{0, 1, 2});
        assertNull(HeartRateByGradeAnalyzer.analyze(noHr));
        assertNull(HeartRateByGradeAnalyzer.analyze(climb(30, 0.07, 150)));
    }

    @Test
    public void analyzer_skipsImplausibleHeartRate() {
        assertNull(HeartRateByGradeAnalyzer.analyze(climb(400, 0.07, 20)));
    }

    @Test
    public void analyzer_storedByRideStreamAnalyzer() {
        StoredRideStreamStats st = RideStreamAnalyzer.analyze(1L, climb(400, 0.07, 150));
        assertNotNull(st.hrGradeSec);
        assertEquals(390, st.hrGradeSec[3]);
    }

    private static StoredRideStreamStats stats(int[] sec, int[] beats) {
        StoredRideStreamStats s = new StoredRideStreamStats();
        s.hrGradeSec = sec;
        s.hrGradeBeats = beats;
        return s;
    }

    @Test
    public void zones_averageBpmAsShareOfMax() {
        // Class 1: 300 s at 120 bpm (60 % of 200 -> zone 2); class 4: 300 s at 185 bpm (zone 5).
        StoredRideStreamStats s = stats(new int[]{0, 300, 0, 0, 300, 0},
                new int[]{0, 600, 0, 0, 925, 0});
        assertArrayEquals(new int[]{0, 2, 0, 0, 5, 0},
                HeartRateByGrade.zones(Collections.singletonList(s), 200));
    }

    @Test
    public void zones_sumsRidesAndIgnoresThinClasses() {
        StoredRideStreamStats a = stats(new int[]{60, 0, 0, 0, 0, 0}, new int[]{150, 0, 0, 0, 0, 0});
        StoredRideStreamStats b = stats(new int[]{60, 0, 0, 0, 0, 100}, new int[]{150, 0, 0, 0, 0, 300});
        // Class 0: 120 s at 150 bpm = 75 % -> zone 3; class 5 only 100 s -> unknown.
        assertArrayEquals(new int[]{3, 0, 0, 0, 0, 0},
                HeartRateByGrade.zones(Arrays.asList(a, b, null), 200));
    }

    @Test
    public void zones_nullWithoutMaxHrOrData() {
        StoredRideStreamStats s = stats(new int[]{300, 0, 0, 0, 0, 0}, new int[]{750, 0, 0, 0, 0, 0});
        assertNull(HeartRateByGrade.zones(Collections.singletonList(s), 0));
        assertNull(HeartRateByGrade.zones(Collections.singletonList(new StoredRideStreamStats()), 200));
    }
}
