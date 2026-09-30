package nl.paree.climbpro.domain.climb;

/**
 * Score thresholds for {@link ClimbCategoryCalculator}. Kept separate from
 * {@link ClimbConstants} (which holds detection/segmentation rules) since these are
 * presentation-tuning knobs for an auto-computed badge, not domain-detection rules.
 *
 * <p>Score is {@code lengthMeters * avgGradientPercent} — the classic FICP/ASO-style
 * "length times gradient" heuristic used to categorize Tour de France climbs. The
 * thresholds below are the commonly cited rough boundaries for that formula.
 */
public final class ClimbCategoryConstants {

    private ClimbCategoryConstants() {}

    /** Score at/above which a climb is "hors categorie" — beyond the numbered scale. */
    public static final double HC_SCORE_THRESHOLD    = 80_000;
    /** Score at/above which a climb is category 1 (and below {@link #HC_SCORE_THRESHOLD}). */
    public static final double CAT_1_SCORE_THRESHOLD = 64_000;
    /** Score at/above which a climb is category 2. */
    public static final double CAT_2_SCORE_THRESHOLD = 32_000;
    /** Score at/above which a climb is category 3. */
    public static final double CAT_3_SCORE_THRESHOLD = 16_000;
    /** Score at/above which a climb is category 4 — the lowest badge; below this is uncategorized. */
    public static final double CAT_4_SCORE_THRESHOLD = 8_000;
}
