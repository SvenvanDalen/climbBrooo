package nl.paree.climbpro.domain.matching;

import nl.paree.climbpro.data.route.StoredClimbAttempt;

import java.util.List;

/**
 * Power detail of one climb pass, stored on the attempt at match time: peak power (issue
 * #389), normalized power (issue #384) and average power, heart rate and cadence per climb
 * segment (issue #385), the segments being the pass's own {@code segSplitSec}. Pure;
 * phone-only.
 */
public final class ClimbPassPower {

    private ClimbPassPower() {}

    /** NP needs at least this much of a pass (a 30 s rolling average has to settle). */
    static final int MIN_NP_SEC = 120;
    static final int ROLLING_SEC = 30;

    /** Fills the power fields of {@code a}; leaves them null when the stream is absent. */
    public static void apply(StoredClimbAttempt a, TrackSensors sensors, long[] timeSec,
                             int from, int to) {
        a.peakWatts = sensors.peakWatts(timeSec, from, to);
        double[] watts = sensors.hasWatts()
                ? ClimbPeakPower.perSecond(timeSec, sensors.watts, from, to, true) : null;
        if (a.peakWatts != null && watts != null && watts.length > 0) {
            a.normalizedPower = normalizedPower(watts);
            a.segWatts = orNull(segmentAverages(watts, a.segSplitSec));
        }
        a.segHeartrate = orNull(segments(sensors.hasHeartrate(), sensors.heartrate, timeSec, from,
                to, a.segSplitSec));
        a.segCadence = orNull(segments(sensors.hasCadence(), sensors.cadence, timeSec, from, to,
                a.segSplitSec));
    }

    /** Persisted attempt fields use null for "no data"; the calculators return empty arrays. */
    private static int[] orNull(int[] v) {
        return v.length == 0 ? null : v;
    }

    private static int[] segments(boolean has, List<Double> values, long[] timeSec, int from,
                                  int to, int[] splits) {
        if (!has) return new int[0];
        double[] perSec = ClimbPeakPower.perSecond(timeSec, values, from, to, false);
        return segmentAverages(perSec, splits);
    }

    /**
     * Mean per segment over the seconds each one took (NaN seconds skipped); 0 for a segment
     * without values. Null without splits or when none of the segments has a value.
     */
    static int[] segmentAverages(double[] perSec, int[] splits) {
        if (perSec == null || splits == null || splits.length == 0) return new int[0];
        int[] out = new int[splits.length];
        boolean any = false;
        int start = 0;
        for (int k = 0; k < splits.length; k++) {
            int end = Math.min(perSec.length, start + Math.max(0, splits[k]));
            double sum = 0;
            int n = 0;
            for (int s = start; s < end; s++) {
                if (Double.isNaN(perSec[s])) continue;
                sum += perSec[s];
                n++;
            }
            if (n > 0) {
                out[k] = (int) Math.round(sum / n);
                any |= out[k] > 0;
            }
            start = end;
        }
        return any ? out : new int[0];
    }

    /** 30 s rolling average, fourth-power mean, fourth root; null for a short pass. */
    static Integer normalizedPower(double[] watts) {
        if (watts == null || watts.length < MIN_NP_SEC) return null;
        double window = 0;
        double sum4 = 0;
        int n = 0;
        for (int s = 0; s < watts.length; s++) {
            window += watts[s];
            if (s >= ROLLING_SEC) window -= watts[s - ROLLING_SEC];
            if (s < ROLLING_SEC - 1) continue;
            double avg = window / ROLLING_SEC;
            sum4 += avg * avg * avg * avg;
            n++;
        }
        return n > 0 ? (int) Math.round(Math.pow(sum4 / n, 0.25)) : null;
    }
}
