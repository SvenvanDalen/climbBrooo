package nl.paree.climbpro.domain.ride;

import nl.paree.climbpro.data.ride.StoredRideStreamStats;

import java.util.ArrayList;
import java.util.List;

/**
 * Structured intervals in a ride's power stream (issue #400): the same hard stretches the
 * heart-rate recovery uses ({@link HeartRateRecoveryAnalyzer}, at least a minute with the 30 s
 * power ≥ 1.15 × the ride's NP), but stored per block with its real bounds, average power,
 * highest heart rate and the heart-rate drop afterwards. The 30 s average lags, so the block
 * is widened back to where the power first reached the threshold. Pure; called by
 * {@link RideStreamAnalyzer}.
 */
public final class IntervalAnalyzer {

    private IntervalAnalyzer() {}

    /** More blocks than this is noise (or a race), not a workout; the rest is dropped. */
    static final int MAX_INTERVALS = 30;

    /**
     * The ride's intervals in ride order; null without power (or below
     * {@link HeartRateRecoveryAnalyzer#MIN_NP_WATTS} NP), empty for a steady ride.
     */
    public static List<StoredRideStreamStats.Interval> detect(RideStreams s) {
        if (s == null || s.watts == null) return null;
        Integer np = NormalizedPowerAnalyzer.normalizedPower(s);
        if (np == null || np < HeartRateRecoveryAnalyzer.MIN_NP_WATTS) return null;
        double[] watts = PerSecondSeries.of(s, s.watts);
        if (watts == null) return null;
        double[] hr = s.heartrate != null ? PerSecondSeries.of(s, s.heartrate) : null;

        double threshold = HeartRateRecoveryAnalyzer.INTERVAL_FACTOR * np;
        boolean[] hard = HeartRateRecoveryAnalyzer.hardSeconds(watts, threshold);
        List<StoredRideStreamStats.Interval> out = new ArrayList<>();
        int n = watts.length;
        int t = 0;
        while (t < n && out.size() < MAX_INTERVALS) {
            if (!hard[t]) {
                t++;
                continue;
            }
            int start = t;
            while (t < n && hard[t]) t++;
            if (t - start < HeartRateRecoveryAnalyzer.MIN_INTERVAL_SEC) continue;
            out.add(block(watts, hr, hard, start, t, threshold));
        }
        return out;
    }

    /** The effort behind the hard run [start, stop) of the rolling 30 s average. */
    private static StoredRideStreamStats.Interval block(double[] watts, double[] hr,
                                                       boolean[] hard, int start, int stop,
                                                       double threshold) {
        int end = stop - 1;
        while (end > start && below(watts[end], threshold)) end--;
        int begin = Math.max(0, start - NormalizedPowerAnalyzer.ROLLING_SEC + 1);
        while (begin < end && below(watts[begin], threshold)) begin++;

        double sum = 0;
        int valid = 0;
        double maxHr = Double.NaN;
        for (int k = begin; k <= end; k++) {
            if (!Double.isNaN(watts[k])) {
                sum += watts[k];
                valid++;
            }
            if (hr != null && HeartRateRecoveryAnalyzer.plausible(hr[k])
                    && (Double.isNaN(maxHr) || hr[k] > maxHr)) {
                maxHr = hr[k];
            }
        }
        StoredRideStreamStats.Interval iv = new StoredRideStreamStats.Interval();
        iv.startSec = begin;
        iv.durationSec = end - begin + 1;
        iv.avgWatts = valid > 0 ? (int) Math.round(sum / valid) : 0;
        iv.maxHr = Double.isNaN(maxHr) ? null : (int) Math.round(maxHr);
        iv.recoveryBpm = hr != null
                ? HeartRateRecoveryAnalyzer.dropAfterInterval(watts, hr, hard, start, stop, threshold)
                : null;
        return iv;
    }

    private static boolean below(double w, double threshold) {
        return Double.isNaN(w) || w < threshold;
    }

    /**
     * Mechanical work in kJ (issue #404): the per-second power summed over the ride, pauses
     * left out. With ~24 % gross efficiency this is about the kcal burned. Null without power.
     */
    public static Integer workKj(RideStreams s) {
        if (s == null || s.watts == null) return null;
        double[] watts = PerSecondSeries.of(s, s.watts);
        if (watts == null) return null;
        double joules = 0;
        boolean any = false;
        for (double w : watts) {
            if (Double.isNaN(w) || w < 0) continue;
            joules += w;
            any = true;
        }
        return any ? (int) Math.round(joules / 1000) : null;
    }
}
