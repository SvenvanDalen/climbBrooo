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

    /** Seconds whose rolling 30 s average power (over a full window) reaches the threshold. */
    private static boolean[] hardSeconds(double[] watts, double threshold) {
        int n = watts.length;
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
        return hard;
    }

    /**
     * Heart-rate drop after the hard interval [start, stop): peak near its end minus the heart
     * rate {@link #RECOVERY_SEC} later. Null when the interval is too short, the recovery is
     * interrupted or runs past the ride, or the heart rate isn't plausible.
     */
    private static Integer dropAfterInterval(double[] watts, double[] hr, boolean[] hard,
                                             int start, int stop, double threshold) {
        // The 30 s average lags: the effort really ended at the last hard second itself.
        int end = stop - 1;
        while (end > start && (Double.isNaN(watts[end]) || watts[end] < threshold)) end--;
        if (stop - start < MIN_INTERVAL_SEC) return null;
        int after = end + RECOVERY_SEC;
        if (after >= watts.length || anyHard(hard, stop, after)) return null;
        double peak = peakHeartRate(hr, end);
        if (Double.isNaN(peak) || !plausible(hr[after])) return null;
        return (int) Math.round(peak - hr[after]);
    }

    /** Highest plausible heart rate in the {@link #PEAK_WINDOW_SEC} up to {@code end}; NaN if none. */
    private static double peakHeartRate(double[] hr, int end) {
        double peak = Double.NaN;
        for (int k = Math.max(0, end - PEAK_WINDOW_SEC + 1); k <= end; k++) {
            if (plausible(hr[k]) && (Double.isNaN(peak) || hr[k] > peak)) peak = hr[k];
        }
        return peak;
    }

    /**
     * Heart-rate drop in bpm after each interval, in ride order (negative when it rose); null
     * without power and heart rate, or an empty array when the ride has no qualifying interval.
     */
    public static int[] drops(RideStreams s) {
        if (s == null || s.watts == null || s.heartrate == null) return new int[0];
        Integer np = NormalizedPowerAnalyzer.normalizedPower(s);
        if (np == null || np < MIN_NP_WATTS) return new int[0];
        double[] watts = PerSecondSeries.of(s, s.watts);
        double[] hr = PerSecondSeries.of(s, s.heartrate);
        if (watts.length == 0 || hr.length == 0) return new int[0];

        int n = watts.length;
        double threshold = INTERVAL_FACTOR * np;
        boolean[] hard = hardSeconds(watts, threshold);

        List<Integer> out = new ArrayList<>();
        int t = 0;
        while (t < n) {
            if (!hard[t]) {
                t++;
                continue;
            }
            int start = t;
            while (t < n && hard[t]) t++;
            Integer drop = dropAfterInterval(watts, hr, hard, start, t, threshold);
            if (drop != null) out.add(drop);
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
