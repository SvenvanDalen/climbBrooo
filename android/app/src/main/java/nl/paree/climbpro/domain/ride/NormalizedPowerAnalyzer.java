package nl.paree.climbpro.domain.ride;

/**
 * Normalized power of one ride (issue #391): the fourth-power mean of the 30-second rolling
 * average, over the seconds the rider was actually recording (pauses are left out, not counted
 * as 0 W). Pure; called by {@link RideStreamAnalyzer}.
 */
public final class NormalizedPowerAnalyzer {

    private NormalizedPowerAnalyzer() {}

    static final int ROLLING_SEC = 30;
    /** Shorter recordings give no meaningful NP. */
    static final int MIN_SECONDS = 10 * 60;

    /** Rounded NP in watts, or null without (enough) power data. */
    public static Integer normalizedPower(RideStreams s) {
        double[] perSecond = powerPerSecond(s);
        if (perSecond == null) return null;
        double np = normalizedPower(perSecond);
        return np > 0 ? (int) Math.round(np) : null;
    }

    /**
     * Per-second power with pauses removed (the recording is joined up), invalid readings as
     * 0 W; null without a power stream or with less than {@link #MIN_SECONDS}.
     */
    static double[] powerPerSecond(RideStreams s) {
        if (s == null) return null;
        double[] raw = PerSecondSeries.of(s, s.watts);
        if (raw == null) return null;
        double[] joined = new double[raw.length];
        int n = 0;
        boolean any = false;
        for (double w : raw) {
            if (Double.isNaN(w)) continue;
            double v = w > 0 && w <= SprintAnalyzer.MAX_PLAUSIBLE_WATTS ? w : 0;
            if (v > 0) any = true;
            joined[n++] = v;
        }
        if (!any || n < MIN_SECONDS) return null;
        return java.util.Arrays.copyOf(joined, n);
    }

    /** NP of a gap-free per-second series of at least {@link #ROLLING_SEC} values. */
    static double normalizedPower(double[] perSecond) {
        if (perSecond.length < ROLLING_SEC) return 0;
        double window = 0;
        for (int i = 0; i < ROLLING_SEC; i++) window += perSecond[i];
        double sum4 = 0;
        int count = 0;
        for (int i = ROLLING_SEC - 1; i < perSecond.length; i++) {
            if (i >= ROLLING_SEC) window += perSecond[i] - perSecond[i - ROLLING_SEC];
            double avg = window / ROLLING_SEC;
            sum4 += avg * avg * avg * avg;
            count++;
        }
        return Math.pow(sum4 / count, 0.25);
    }
}
