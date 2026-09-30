package nl.paree.climbpro.domain.climb;

/**
 * Auto-computed Tour de France-style climb category, derived purely from a climb's own
 * length and average gradient ({@link ClimbCategoryCalculator}). Phone-only presentation
 * label — not part of the wire protocol (see {@link ClimbCategoryCalculator} doc comment).
 *
 * <p>Ordered from hardest to easiest, matching the classic UCI/ASO convention: HC
 * ("hors categorie" — beyond categorization) is the toughest tier, CAT_4 the easiest
 * still worth a badge. {@link #UNCATEGORIZED} covers climbs that clear the detection
 * thresholds ({@link ClimbConstants#MIN_CLIMB_LENGTH_M} / {@link ClimbConstants#MIN_AVG_GRADIENT})
 * but are too short/gentle to reach even the CAT_4 score cutoff.
 */
public enum ClimbCategory {
    HC,
    CAT_1,
    CAT_2,
    CAT_3,
    CAT_4,
    UNCATEGORIZED
}
