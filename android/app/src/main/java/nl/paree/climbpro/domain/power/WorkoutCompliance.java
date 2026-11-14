package nl.paree.climbpro.domain.power;

import java.util.ArrayList;
import java.util.List;

/**
 * Planned versus ridden power per interval block (issue #401): each climb carrying an
 * {@link IntervalBlock} is a planned step (target band in % FTP, exported as the .zwo/.erg
 * climb step); the ridden step is the matched attempt's average power. Compliance is the
 * ridden average as a percentage of the band's target. Pure; phone-only.
 */
public final class WorkoutCompliance {

    private WorkoutCompliance() {}

    /** Where a ridden average sits relative to the planned band. */
    public enum Verdict { BELOW, IN_BAND, ABOVE }

    /** One planned step and what was ridden. {@code avgWatts} is null without a power meter. */
    public static final class Step {
        public final String name;
        public final int targetWatts;
        public final int lowWatts;
        public final int highWatts;
        public final Integer avgWatts;
        public final int elapsedSec;
        /** Ridden average as % of the target; null without power. */
        public final Integer compliancePct;
        /** Null without power. */
        public final Verdict verdict;

        Step(String name, int[] wattsBand, Integer avgWatts, int elapsedSec) {
            this.name = name;
            this.targetWatts = wattsBand[0];
            this.lowWatts = wattsBand[1];
            this.highWatts = wattsBand[2];
            this.avgWatts = avgWatts;
            this.elapsedSec = elapsedSec;
            if (avgWatts == null || avgWatts <= 0 || targetWatts <= 0) {
                this.compliancePct = null;
                this.verdict = null;
            } else {
                this.compliancePct = (int) Math.round(100.0 * avgWatts / targetWatts);
                this.verdict = verdictFor(avgWatts, lowWatts, highWatts);
            }
        }
    }

    private static Verdict verdictFor(int avgWatts, int lowWatts, int highWatts) {
        if (avgWatts < lowWatts) return Verdict.BELOW;
        return avgWatts > highWatts ? Verdict.ABOVE : Verdict.IN_BAND;
    }

    /** Input for one planned step: the block, and what the matched attempt delivered. */
    public static final class Planned {
        final String name;
        final IntervalBlock block;
        final Integer avgWatts;
        final int elapsedSec;

        public Planned(String name, IntervalBlock block, Integer avgWatts, int elapsedSec) {
            this.name = name;
            this.block = block;
            this.avgWatts = avgWatts;
            this.elapsedSec = elapsedSec;
        }
    }

    /**
     * The steps in the given order; entries without a valid block are skipped. Empty when the
     * FTP is unknown, because the band cannot be turned into watts then.
     */
    public static List<Step> compare(List<Planned> planned, int ftpWatts) {
        List<Step> out = new ArrayList<>();
        if (planned == null || ftpWatts <= 0) return out;
        for (Planned p : planned) {
            Step step = stepFor(p, ftpWatts);
            if (step != null) out.add(step);
        }
        return out;
    }

    private static Step stepFor(Planned p, int ftpWatts) {
        if (p == null || p.block == null) return null;
        int[] band = p.block.wireWatts(ftpWatts);
        return band == null ? null : new Step(p.name, band, p.avgWatts, p.elapsedSec);
    }

    /** Mean compliance over the steps that have power; null when none do. */
    public static Integer overallPct(List<Step> steps) {
        double sum = 0;
        int n = 0;
        for (Step s : steps) {
            if (s.compliancePct != null) {
                sum += s.compliancePct;
                n++;
            }
        }
        return n == 0 ? null : (int) Math.round(sum / n);
    }
}
