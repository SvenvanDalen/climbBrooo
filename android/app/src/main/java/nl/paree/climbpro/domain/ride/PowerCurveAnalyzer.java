package nl.paree.climbpro.domain.ride;

/**
 * One ride's power curve (issue #219): the best average power over each of
 * {@link #DURATIONS_SEC}. Pure; called by {@link RideStreamAnalyzer}, which stores only the
 * resulting numbers.
 */
public final class PowerCurveAnalyzer {

    private PowerCurveAnalyzer() {}

    /** The standard durations: sprint, anaerobic, VO2max, threshold and one hour. */
    public static final int[] DURATIONS_SEC = {5, 60, 300, 1200, 3600};

    /** Longer recordings are not resampled to 1 Hz (48 h), like {@link SprintAnalyzer}. */
    private static final int MAX_RESAMPLE_SEC = 48 * 3600;

    /**
     * Best average watts per entry of {@link #DURATIONS_SEC}, rounded; 0 where the ride is
     * shorter than that duration. Null without a power stream or when the ride has no power
     * at all. Seconds without a sample count as 0 W, as in {@link SprintAnalyzer#peakPower},
     * so a pause inside a window lowers it instead of being skipped.
     */
    public static int[] bestPowers(RideStreams s) {
        if (s == null || !s.isUsable() || s.watts == null) return null;
        int end = s.time[s.time.length - 1];
        if (end <= 0 || end > MAX_RESAMPLE_SEC) return null;
        double[] perSecond = new double[end + 1];
        boolean any = false;
        for (int i = 0; i < s.time.length; i++) {
            int t = s.time[i];
            double w = s.watts[i];
            if (t < 0 || t > end || Double.isNaN(w) || w <= 0
                    || w > SprintAnalyzer.MAX_PLAUSIBLE_WATTS) continue;
            perSecond[t] = w;
            any = true;
        }
        if (!any) return null;
        double[] prefix = new double[perSecond.length + 1];
        for (int i = 0; i < perSecond.length; i++) prefix[i + 1] = prefix[i] + perSecond[i];

        int[] best = new int[DURATIONS_SEC.length];
        for (int k = 0; k < DURATIONS_SEC.length; k++) {
            int window = DURATIONS_SEC[k];
            if (window > perSecond.length) continue;
            double max = 0;
            for (int start = 0; start + window <= perSecond.length; start++) {
                double sum = prefix[start + window] - prefix[start];
                if (sum > max) max = sum;
            }
            best[k] = (int) Math.round(max / window);
        }
        return best;
    }
}
