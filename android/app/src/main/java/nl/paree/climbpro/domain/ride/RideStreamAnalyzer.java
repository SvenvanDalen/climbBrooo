package nl.paree.climbpro.domain.ride;

import nl.paree.climbpro.data.ride.StoredRideStreamStats;

/**
 * Derives per-ride efforts from Strava streams: fastest distances (issue #225), sprints
 * (issue #224) and heart-rate drift (issue #222). Runs once per ride on the phone
 * during the ride-archive sync; only the result is stored.
 */
public final class RideStreamAnalyzer {

    private RideStreamAnalyzer() {}

    /** Bump when the analysis changes, so stored stats from an older version are redone. */
    public static final int VERSION = 3;

    /** Sprint windows (issue #224): peak power over 5 and 15 s, peak speed over 10 s. */
    public static final int SPRINT_POWER_SHORT_SEC = 5;
    public static final int SPRINT_POWER_LONG_SEC = 15;
    public static final int SPRINT_SPEED_SEC = 10;

    public static final double[] EFFORT_DISTANCES_M = {10_000, 40_000, 100_000};

    /**
     * Faster than this between two samples is a GPS jump, not riding (108 km/h). A window
     * spanning such a jump is skipped rather than rewarded.
     */
    static final double MAX_PLAUSIBLE_SPEED_MPS = 30;

    public static StoredRideStreamStats analyze(long activityId, RideStreams streams) {
        StoredRideStreamStats st = new StoredRideStreamStats();
        st.activityId = activityId;
        st.version = VERSION;
        st.hasStreams = streams != null && streams.isUsable();
        if (!st.hasStreams) return st;
        st.best10kSec = fastestDistanceSec(streams, EFFORT_DISTANCES_M[0]);
        st.best40kSec = fastestDistanceSec(streams, EFFORT_DISTANCES_M[1]);
        st.best100kSec = fastestDistanceSec(streams, EFFORT_DISTANCES_M[2]);

        SprintAnalyzer.Peak p5 = SprintAnalyzer.peakPower(streams, SPRINT_POWER_SHORT_SEC);
        if (p5 != null) {
            st.sprint5sWatts = (int) Math.round(p5.value);
            st.sprint5sAtSec = p5.atSec;
        }
        SprintAnalyzer.Peak p15 = SprintAnalyzer.peakPower(streams, SPRINT_POWER_LONG_SEC);
        if (p15 != null) st.sprint15sWatts = (int) Math.round(p15.value);
        SprintAnalyzer.Peak v = SprintAnalyzer.peakSpeed(streams, SPRINT_SPEED_SEC);
        if (v != null) {
            st.sprint10sSpeedMps = v.value;
            st.sprint10sSpeedAtSec = v.atSec;
        }

        st.avgHeartrate = HeartRateDriftAnalyzer.averageMovingHeartrate(streams);
        HeartRateDriftAnalyzer.Drift drift = HeartRateDriftAnalyzer.analyze(streams);
        if (drift != null) {
            st.hrDriftPct = drift.percent;
            st.hrDriftBasis = drift.basis;
            st.hrDriftMinutes = drift.minutes;
        }
        return st;
    }

    /**
     * Shortest elapsed time (s) to cover {@code targetM} anywhere in the ride, or null when the
     * ride has no clean stretch that long. Elapsed, not moving, time: a stop inside the window
     * counts, like a real 40 km time trial. The window end is a sample; its start is
     * interpolated between samples so sparse recording doesn't round the distance up.
     */
    public static Integer fastestDistanceSec(RideStreams s, double targetM) {
        if (s == null || !s.isUsable() || targetM <= 0) return null;
        int[] t = s.time;
        double[] d = s.distance;
        double best = Double.POSITIVE_INFINITY;
        int start = 0;     // window start segment: samples start..start+1
        int firstClean = 0; // no window may start before this sample (after a GPS jump)
        for (int j = 1; j < t.length; j++) {
            if (!isPlausible(t[j - 1], d[j - 1], t[j], d[j])) {
                firstClean = j;
                start = j;
                continue;
            }
            if (start < firstClean) start = firstClean;
            if (d[j] - d[firstClean] < targetM) continue;
            // Advance while the next sample still leaves a full target distance to j.
            while (start + 1 < j && d[j] - d[start + 1] >= targetM) start++;
            double startDist = d[j] - targetM;
            double span = d[start + 1] - d[start];
            double frac = span > 0 ? (startDist - d[start]) / span : 0;
            double startTime = t[start] + frac * (t[start + 1] - t[start]);
            double elapsed = t[j] - startTime;
            if (elapsed > 0 && elapsed < best) best = elapsed;
        }
        return best == Double.POSITIVE_INFINITY ? null : (int) Math.round(best);
    }

    static boolean isPlausible(int t0, double d0, int t1, double d1) {
        double dd = d1 - d0;
        if (Double.isNaN(dd) || dd < 0) return false;
        int dt = t1 - t0;
        if (dt <= 0) return dd == 0;
        return dd / dt <= MAX_PLAUSIBLE_SPEED_MPS;
    }
}
