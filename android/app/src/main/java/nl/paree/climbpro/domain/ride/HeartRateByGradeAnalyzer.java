package nl.paree.climbpro.domain.ride;

import nl.paree.climbpro.domain.segment.GradientColor;

/**
 * Heart rate per gradient class of one ride (issue #24), the counterpart of
 * {@link CadenceByGradeAnalyzer}: the same six segment color classes, the gradient measured
 * over the preceding {@link CadenceByGradeAnalyzer#GRADE_WINDOW_M}, descents steeper than
 * {@link CadenceByGradeAnalyzer#MIN_GRADE} left out. Only moving seconds with a plausible heart
 * rate count. Pure; called by {@link RideStreamAnalyzer}, which stores seconds and heartbeats
 * per class so rides can be summed later ({@link HeartRateByGrade}).
 */
public final class HeartRateByGradeAnalyzer {

    private HeartRateByGradeAnalyzer() {}

    static final double MIN_PLAUSIBLE_BPM = 40;
    static final double MAX_PLAUSIBLE_BPM = 230;

    /** Seconds and heartbeats per class, index 0-5. */
    public static final class Result {
        public final int[] seconds;
        public final int[] beats;

        Result(int[] seconds, int[] beats) {
            this.seconds = seconds;
            this.beats = beats;
        }
    }

    /** Null without heart rate or altitude, or with too little riding. */
    public static Result analyze(RideStreams s) {
        if (s == null || !s.isUsable() || s.heartrate == null || s.altitude == null) return null;
        int n = CadenceByGradeAnalyzer.CLASSES;
        double[] secs = new double[n];
        double[] beats = new double[n];
        int j = 0;
        for (int i = 1; i < s.time.length; i++) {
            int dt = s.time[i] - s.time[i - 1];
            if (!isRidingSample(s, i, dt)) continue;
            double hr = s.heartrate[i];
            while (j + 1 < i
                    && s.distance[i] - s.distance[j + 1] >= CadenceByGradeAnalyzer.GRADE_WINDOW_M) {
                j++;
            }
            double run = s.distance[i] - s.distance[j];
            double grade = run >= CadenceByGradeAnalyzer.GRADE_WINDOW_M
                    ? (s.altitude[i] - s.altitude[j]) / run : Double.NaN;
            if (!Double.isNaN(grade) && grade >= CadenceByGradeAnalyzer.MIN_GRADE) {
                int cls = GradientColor.forGradient(grade);
                secs[cls] += dt;
                beats[cls] += hr * dt / 60.0;
            }
        }
        return toResult(secs, beats);
    }

    /** A moving sample after a normal time step, with a plausible heart rate. */
    private static boolean isRidingSample(RideStreams s, int i, int dt) {
        if (dt <= 0 || dt > CadenceByGradeAnalyzer.MAX_GAP_SEC) return false;
        if (!(s.distance[i] > s.distance[i - 1])) return false;   // standing still
        double hr = s.heartrate[i];
        return !Double.isNaN(hr) && hr >= MIN_PLAUSIBLE_BPM && hr <= MAX_PLAUSIBLE_BPM;
    }

    /** Rounded per-class totals, or null with too little riding overall. */
    private static Result toResult(double[] secs, double[] beats) {
        int total = 0;
        int[] seconds = new int[secs.length];
        int[] heartbeats = new int[secs.length];
        for (int c = 0; c < secs.length; c++) {
            seconds[c] = (int) Math.round(secs[c]);
            heartbeats[c] = (int) Math.round(beats[c]);
            total += seconds[c];
        }
        return total >= CadenceByGradeAnalyzer.MIN_TOTAL_SEC ? new Result(seconds, heartbeats) : null;
    }
}
