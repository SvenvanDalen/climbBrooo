package nl.paree.climbpro.domain.matching;

import java.util.List;

/**
 * Best average power over 30 s, 1, 5 and 20 min inside one climb pass (issue #389). The pass
 * is resampled to one value per second (each sample's power holds back to the previous
 * sample, a missing reading counts as 0 W like coasting) and a sliding window finds the best
 * average per duration. A pass shorter than a duration gets 0 for it. Pure; phone-only.
 */
public final class ClimbPeakPower {

    private ClimbPeakPower() {}

    public static final int[] DURATIONS_SEC = {30, 60, 300, 1200};
    /** Longer passes are not resampled (a climb never takes this long). */
    static final int MAX_PASS_SEC = 4 * 3600;

    /**
     * Peaks aligned with {@link #DURATIONS_SEC} over samples {@code from..to}; null when the
     * pass has no time span or no power at all.
     */
    public static int[] compute(long[] timeSec, List<Double> watts, int from, int to) {
        if (timeSec == null || watts == null || from < 0 || to <= from
                || to >= timeSec.length || to >= watts.size()) {
            return null;
        }
        long span = timeSec[to] - timeSec[from];
        if (span <= 0 || span > MAX_PASS_SEC) return null;
        double[] perSec = new double[(int) span];
        boolean any = false;
        for (int j = from + 1; j <= to; j++) {
            Double w = watts.get(j);
            double v = w == null || Double.isNaN(w) || w < 0 ? 0 : w;
            if (v > 0) any = true;
            int a = (int) Math.max(0, timeSec[j - 1] - timeSec[from]);
            int b = (int) Math.min(span, timeSec[j] - timeSec[from]);
            for (int s = a; s < b; s++) perSec[s] = v;
        }
        if (!any) return null;
        int[] out = new int[DURATIONS_SEC.length];
        for (int k = 0; k < out.length; k++) out[k] = best(perSec, DURATIONS_SEC[k]);
        return out;
    }

    private static int best(double[] perSec, int window) {
        if (window > perSec.length) return 0;
        double sum = 0;
        for (int s = 0; s < window; s++) sum += perSec[s];
        double max = sum;
        for (int s = window; s < perSec.length; s++) {
            sum += perSec[s] - perSec[s - window];
            if (sum > max) max = sum;
        }
        return (int) Math.round(max / window);
    }
}
