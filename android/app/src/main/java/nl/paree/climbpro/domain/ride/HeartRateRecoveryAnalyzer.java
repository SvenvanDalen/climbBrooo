package nl.paree.climbpro.domain.ride;

import java.util.ArrayList;
import java.util.List;

/**
 * Heart-rate recovery after intervals (issue #402). An interval is a stretch of at least
 * {@link #MIN_INTERVAL_SEC} where the 30-second power stays at or above
 * {@link #INTERVAL_FACTOR} × the ride's normalized power — relative to the ride itself, so no
 * FTP is needed at analysis time and a steady ride has none. Its recovery is the drop from the
 * highest heart rate in the last {@link #PEAK_WINDOW_SEC} of the interval to the heart rate
 * {@link #RECOVERY_SEC} later, counted only when no new interval starts in between. Pure;
 * called by {@link RideStreamAnalyzer}.
 */
public final class HeartRateRecoveryAnalyzer {

    private HeartRateRecoveryAnalyzer() {}

    static final double INTERVAL_FACTOR = 1.15;
    static final int MIN_INTERVAL_SEC = 60;
    static final int RECOVERY_SEC = 60;
    static final int PEAK_WINDOW_SEC = 10;
    /** Below this NP the ride is too easy for intervals to mean anything. */
    static final int MIN_NP_WATTS = 80;
    static final double MIN_PLAUSIBLE_BPM = 40;
    static final double MAX_PLAUSIBLE_BPM = 230;

    /**
     * Heart-rate drop in bpm after each interval, in ride order (negative when it rose); null
     * without power and heart rate, or an empty array when the ride has no qualifying interval.
     */
    public static int[] drops(RideStreams s) {
        if (s == null || s.watts == null || s.heartrate == null) return null;
        Integer np = NormalizedPowerAnalyzer.normalizedPower(s);
        if (np == null || np < MIN_NP_WATTS) return null;
        double[] watts = PerSecondSeries.of(s, s.watts);
        double[] hr = PerSecondSeries.of(s, s.heartrate);
        if (watts == null || hr == null) return null;

        int n = watts.length;
        double threshold = INTERVAL_FACTOR * np;
        boolean[] hard = new boolean[n];
        double window = 0;
        int valid = 0;
        for (int t = 0; t < n; t++) {
            double w = Double.isNaN(watts[t]) ? 0 : watts[t];
            window += w;
            valid++;
            if (t >= NormalizedPowerAnalyzer.ROLLING_SEC) {
                double old = watts[t - NormalizedPowerAnalyzer.ROLLING_SEC];
                window -= Double.isNaN(old) ? 0 : old;
                valid--;
            }
            hard[t] = valid == NormalizedPowerAnalyzer.ROLLING_SEC
                    && window / NormalizedPowerAnalyzer.ROLLING_SEC >= threshold;
        }

        List<Integer> out = new ArrayList<>();
        int t = 0;
        while (t < n) {
            if (!hard[t]) {
                t++;
                continue;
            }
            int start = t;
            while (t < n && hard[t]) t++;
            // The 30 s average lags: the effort really ended at the last hard second itself.
            int end = t - 1;
            while (end > start && (Double.isNaN(watts[end]) || watts[end] < threshold)) end--;
            if (t - start < MIN_INTERVAL_SEC) continue;
            int after = end + RECOVERY_SEC;
            if (after >= n || anyHard(hard, t, after)) continue;
            double peak = Double.NaN;
            for (int k = Math.max(0, end - PEAK_WINDOW_SEC + 1); k <= end; k++) {
                if (plausible(hr[k]) && (Double.isNaN(peak) || hr[k] > peak)) peak = hr[k];
            }
            if (Double.isNaN(peak) || !plausible(hr[after])) continue;
            out.add((int) Math.round(peak - hr[after]));
        }
        int[] drops = new int[out.size()];
        for (int i = 0; i < drops.length; i++) drops[i] = out.get(i);
        return drops;
    }

    private static boolean anyHard(boolean[] hard, int from, int to) {
        for (int k = from; k <= to; k++) if (hard[k]) return true;
        return false;
    }

    private static boolean plausible(double bpm) {
        return !Double.isNaN(bpm) && bpm >= MIN_PLAUSIBLE_BPM && bpm <= MAX_PLAUSIBLE_BPM;
    }
}
