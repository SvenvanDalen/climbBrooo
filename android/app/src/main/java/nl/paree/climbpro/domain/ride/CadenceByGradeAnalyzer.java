package nl.paree.climbpro.domain.ride;

import nl.paree.climbpro.domain.segment.GradientColor;

/**
 * Cadence per gradient class of one ride (issue #403), using the same six classes as the
 * segment colors (0-2, 2-4, 4-6, 6-8, 8-10 and 10+ %). The gradient at a sample is measured
 * over the preceding {@link #GRADE_WINDOW_M} so a single noisy altitude reading doesn't jump a
 * class. Only pedalling seconds count (cadence above 0); descents steeper than
 * {@link #MIN_GRADE} are left out. Pure; called by {@link RideStreamAnalyzer}, which stores
 * seconds and revolutions per class so rides can be summed later.
 */
public final class CadenceByGradeAnalyzer {

    private CadenceByGradeAnalyzer() {}

    public static final int CLASSES = 6;
    static final double GRADE_WINDOW_M = 50;
    /** Slight downhill (rounding, MyWhoosh's flat sections) still counts as the 0-2 % class. */
    static final double MIN_GRADE = -0.01;
    /** A longer gap between samples is a pause, not pedalling. */
    static final int MAX_GAP_SEC = 10;
    /** Less pedalling than this in total gives no result. */
    static final int MIN_TOTAL_SEC = 60;
    static final double MAX_PLAUSIBLE_RPM = 200;

    /** Seconds and pedal revolutions per class, index 0-5. */
    public static final class Result {
        public final int[] seconds;
        public final int[] revolutions;

        Result(int[] seconds, int[] revolutions) {
            this.seconds = seconds;
            this.revolutions = revolutions;
        }
    }

    /** Null without cadence or altitude, or with too little pedalling. */
    public static Result analyze(RideStreams s) {
        if (s == null || !s.isUsable() || s.cadence == null || s.altitude == null) return null;
        double[] secs = new double[CLASSES];
        double[] revs = new double[CLASSES];
        int j = 0;
        for (int i = 1; i < s.time.length; i++) {
            int dt = s.time[i] - s.time[i - 1];
            if (dt <= 0 || dt > MAX_GAP_SEC) continue;
            double cad = s.cadence[i];
            if (Double.isNaN(cad) || cad <= 0 || cad > MAX_PLAUSIBLE_RPM) continue;
            while (j + 1 < i && s.distance[i] - s.distance[j + 1] >= GRADE_WINDOW_M) j++;
            double run = s.distance[i] - s.distance[j];
            if (run < GRADE_WINDOW_M) continue;
            double grade = (s.altitude[i] - s.altitude[j]) / run;
            if (Double.isNaN(grade) || grade < MIN_GRADE) continue;
            int cls = GradientColor.forGradient(grade);
            secs[cls] += dt;
            revs[cls] += cad * dt / 60.0;
        }
        int total = 0;
        int[] seconds = new int[CLASSES];
        int[] revolutions = new int[CLASSES];
        for (int c = 0; c < CLASSES; c++) {
            seconds[c] = (int) Math.round(secs[c]);
            revolutions[c] = (int) Math.round(revs[c]);
            total += seconds[c];
        }
        return total >= MIN_TOTAL_SEC ? new Result(seconds, revolutions) : null;
    }
}
