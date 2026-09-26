package nl.paree.climbpro.domain.ride;

/**
 * Finds a ride's best sprint (issue #224): the highest rolling-average power over a few
 * seconds, and the highest speed over a short window on flat or rising road. Pure; called by
 * {@link RideStreamAnalyzer}.
 */
public final class SprintAnalyzer {

    private SprintAnalyzer() {}

    /** Readings above this are power-meter glitches, not sprints. */
    static final double MAX_PLAUSIBLE_WATTS = 2500;
    /** A speed window steeper downhill than this is a descent, not a sprint (1 %). */
    static final double MAX_DESCENT_FRACTION = 0.01;
    /** A speed window may stretch this much past its length before it counts as a pause. */
    static final int MAX_WINDOW_SLACK_SEC = 2;
    /** Longer recordings are not resampled to 1 Hz (48 h). */
    private static final int MAX_RESAMPLE_SEC = 48 * 3600;

    /** A peak value and the second (since activity start) its window begins. */
    public static final class Peak {
        public final double value;
        public final int atSec;

        Peak(double value, int atSec) {
            this.value = value;
            this.atSec = atSec;
        }
    }

    /**
     * Highest average power over {@code windowSec} consecutive seconds, or null without a power
     * stream. Seconds without a sample count as 0 W (coasting or a pause), so sparse recording
     * can't stretch a single high reading over the whole window.
     */
    public static Peak peakPower(RideStreams s, int windowSec) {
        if (s == null || !s.isUsable() || s.watts == null || windowSec <= 0) return null;
        int end = s.time[s.time.length - 1];
        if (end < windowSec || end > MAX_RESAMPLE_SEC) return null;
        double[] perSecond = new double[end + 1];
        for (int i = 0; i < s.time.length; i++) {
            int t = s.time[i];
            double w = s.watts[i];
            if (t < 0 || t > end || Double.isNaN(w) || w < 0 || w > MAX_PLAUSIBLE_WATTS) continue;
            perSecond[t] = w;
        }
        double sum = 0;
        for (int i = 0; i < windowSec; i++) sum += perSecond[i];
        double best = sum;
        int bestStart = 0;
        for (int start = 1; start + windowSec <= perSecond.length; start++) {
            sum += perSecond[start + windowSec - 1] - perSecond[start - 1];
            if (sum > best) {
                best = sum;
                bestStart = start;
            }
        }
        return best > 0 ? new Peak(best / windowSec, bestStart) : null;
    }

    /**
     * Highest average speed (m/s) over at least {@code windowSec} seconds of continuous riding
     * that doesn't descend more than {@link #MAX_DESCENT_FRACTION}, or null without an altitude
     * stream: without it a fast descent would pass for a sprint.
     */
    public static Peak peakSpeed(RideStreams s, int windowSec) {
        if (s == null || !s.isUsable() || s.altitude == null || windowSec <= 0) return null;
        int[] t = s.time;
        double[] d = s.distance;
        double[] alt = s.altitude;
        double best = 0;
        int bestStart = -1;
        int i = 0;
        int firstClean = 0;
        for (int j = 1; j < t.length; j++) {
            if (!RideStreamAnalyzer.isPlausible(t[j - 1], d[j - 1], t[j], d[j])) {
                firstClean = j;
                continue;
            }
            if (i < firstClean) i = firstClean;
            // Latest start that still gives a window of at least windowSec.
            while (i + 1 < j && t[j] - t[i + 1] >= windowSec) i++;
            int span = t[j] - t[i];
            if (span < windowSec || span > windowSec + MAX_WINDOW_SLACK_SEC) continue;
            double dist = d[j] - d[i];
            if (dist <= 0) continue;
            double drop = alt[i] - alt[j];
            if (Double.isNaN(drop) || drop / dist > MAX_DESCENT_FRACTION) continue;
            double v = dist / span;
            if (v > best) {
                best = v;
                bestStart = t[i];
            }
        }
        return bestStart >= 0 ? new Peak(best, bestStart) : null;
    }
}
