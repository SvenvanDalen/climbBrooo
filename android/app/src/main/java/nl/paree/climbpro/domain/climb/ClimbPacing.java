package nl.paree.climbpro.domain.climb;

/**
 * Pacing of one climb attempt (issue #386): each segment's power against the time-weighted
 * average over the whole climb, with segments more than {@link #FLAG_PCT} off marked, and a
 * one-line verdict from the first third against the last third. Pure; phone-only.
 */
public final class ClimbPacing {

    private ClimbPacing() {}

    public static final double FLAG_PCT = 10;

    public enum Verdict { EVEN, FAST_START, STRONG_FINISH, UNEVEN }

    public static final class Result {
        public final int avgWatts;
        /** Deviation from {@link #avgWatts} in percent per segment; NaN without power there. */
        public final double[] deviationPct;
        public final Verdict verdict;

        Result(int avgWatts, double[] deviationPct, Verdict verdict) {
            this.avgWatts = avgWatts;
            this.deviationPct = deviationPct;
            this.verdict = verdict;
        }

        public boolean flagged(int segment) {
            return Math.abs(deviationPct[segment]) > FLAG_PCT;
        }
    }

    /** Null without per-segment power, or when the splits don't line up with it. */
    public static Result analyze(int[] segWatts, int[] segSplitSec) {
        if (segWatts == null || segSplitSec == null || segWatts.length != segSplitSec.length
                || segWatts.length < 3) {
            return null;
        }
        double avg = weightedMean(segWatts, segSplitSec, 0, segWatts.length);
        if (Double.isNaN(avg) || avg <= 0) return null;
        double[] dev = new double[segWatts.length];
        boolean anyFlag = false;
        for (int k = 0; k < dev.length; k++) {
            dev[k] = segWatts[k] > 0 ? (segWatts[k] - avg) * 100 / avg : Double.NaN;
            anyFlag |= Math.abs(dev[k]) > FLAG_PCT;
        }
        int third = segWatts.length / 3;
        double first = weightedMean(segWatts, segSplitSec, 0, third);
        double last = weightedMean(segWatts, segSplitSec, segWatts.length - third, segWatts.length);
        Verdict verdict;
        if (!Double.isNaN(first) && !Double.isNaN(last) && first > last * (1 + FLAG_PCT / 100)) {
            verdict = Verdict.FAST_START;
        } else if (!Double.isNaN(first) && !Double.isNaN(last)
                && last > first * (1 + FLAG_PCT / 100)) {
            verdict = Verdict.STRONG_FINISH;
        } else {
            verdict = anyFlag ? Verdict.UNEVEN : Verdict.EVEN;
        }
        return new Result((int) Math.round(avg), dev, verdict);
    }

    /** Dutch one-liner for the verdict. */
    public static String label(Verdict v) {
        switch (v) {
            case FAST_START: return "te hard gestart";
            case STRONG_FINISH: return "sterk gefinisht";
            case UNEVEN: return "wisselend";
            default: return "constant";
        }
    }

    private static double weightedMean(int[] watts, int[] sec, int from, int to) {
        double sum = 0;
        double w = 0;
        for (int k = from; k < to; k++) {
            if (watts[k] <= 0 || sec[k] <= 0) continue;
            sum += (double) watts[k] * sec[k];
            w += sec[k];
        }
        return w > 0 ? sum / w : Double.NaN;
    }
}
