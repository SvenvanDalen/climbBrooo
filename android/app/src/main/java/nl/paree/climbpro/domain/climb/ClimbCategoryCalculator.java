package nl.paree.climbpro.domain.climb;

/**
 * Pure presentation logic: a Tour de France-style climb category (HC/1/2/3/4), used
 * purely for a display badge alongside the existing climb stats. Not part of the wire
 * protocol — phone-only, same as {@link DifficultyScoreCalculator} and
 * {@link ClimbShapeClassifier}.
 *
 * <p><b>Formula.</b> {@code score = lengthMeters * avgGradientPercent} — the classic
 * "length times gradient" heuristic real-world race organizers use as a starting point
 * for categorizing climbs (a longer climb and a steeper climb both push the score up
 * linearly; a 1000 m climb at 8% and a 2000 m climb at 4% score the same). The score is
 * then bucketed against {@link ClimbCategoryConstants} thresholds from hardest (HC) to
 * easiest (CAT_4); climbs that clear the {@link ClimbConstants} detection minimums
 * (800 m / 3%, score 2400) but fall short of the CAT_4 cutoff (8000) are
 * {@link ClimbCategory#UNCATEGORIZED} rather than forced into a badge that overstates
 * them — most detected climbs are modest local ramps, not race-worthy climbs, and that
 * is an expected, not exceptional, outcome of this formula.
 */
public final class ClimbCategoryCalculator {

    private ClimbCategoryCalculator() {}

    /**
     * @param lengthM     the climb's own length, in metres (>= 0).
     * @param avgGradient the climb's own average gradient as a fraction (0.072 = 7.2%).
     * @return the category bucket for this climb's length/gradient combination. Never null.
     */
    public static ClimbCategory categorize(int lengthM, double avgGradient) {
        double score = score(lengthM, avgGradient);

        if (score >= ClimbCategoryConstants.HC_SCORE_THRESHOLD)    return ClimbCategory.HC;
        if (score >= ClimbCategoryConstants.CAT_1_SCORE_THRESHOLD) return ClimbCategory.CAT_1;
        if (score >= ClimbCategoryConstants.CAT_2_SCORE_THRESHOLD) return ClimbCategory.CAT_2;
        if (score >= ClimbCategoryConstants.CAT_3_SCORE_THRESHOLD) return ClimbCategory.CAT_3;
        if (score >= ClimbCategoryConstants.CAT_4_SCORE_THRESHOLD) return ClimbCategory.CAT_4;
        return ClimbCategory.UNCATEGORIZED;
    }

    /** Convenience overload taking a {@link Climb} directly. */
    public static ClimbCategory categorize(Climb climb) {
        return categorize(climb.length, climb.avgGradient);
    }

    /**
     * The raw {@code length * gradient%} score alone, exposed for callers that want to
     * display or test it independently of the bucketed category.
     */
    public static double score(int lengthM, double avgGradient) {
        double gradientPercent = Math.max(0, avgGradient) * 100;
        return Math.max(0, lengthM) * gradientPercent;
    }
}
