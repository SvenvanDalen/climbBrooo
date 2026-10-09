package nl.paree.climbpro.domain.ride;

/**
 * A stream resampled to one value per second of the activity clock, shared by the normalized
 * power and heart-rate recovery analyses (issues #391, #402). A gap of at most
 * {@link #MAX_FILL_SEC} between two samples is filled with the later sample's value (the
 * recorder simply skipped identical readings); a longer gap is a pause and stays NaN. Pure.
 */
final class PerSecondSeries {

    /** Longer recordings are not resampled (48 h), like {@link PowerCurveAnalyzer}. */
    static final int MAX_RESAMPLE_SEC = 48 * 3600;
    /** Gaps up to this many seconds are filled; longer ones are a pause. */
    static final int MAX_FILL_SEC = 5;

    private PerSecondSeries() {}

    /**
     * Per-second values of {@code values} (index-aligned with {@code s.time}); NaN where the
     * ride paused or the device recorded nothing. Null when the stream is absent or the ride
     * is too long to resample.
     */
    static double[] of(RideStreams s, double[] values) {
        if (s == null || !s.isUsable() || values == null) return null;
        int end = s.time[s.time.length - 1];
        if (end <= 0 || end > MAX_RESAMPLE_SEC) return null;
        double[] out = new double[end + 1];
        java.util.Arrays.fill(out, Double.NaN);
        for (int i = 0; i < s.time.length; i++) {
            int t = s.time[i];
            if (t < 0 || t > end) continue;
            double v = values[i];
            out[t] = v;
            if (i == 0) continue;
            int prev = s.time[i - 1];
            int gap = t - prev;
            if (gap > 1 && gap <= MAX_FILL_SEC && prev >= 0) {
                for (int k = prev + 1; k < t; k++) out[k] = v;
            }
        }
        return out;
    }
}
