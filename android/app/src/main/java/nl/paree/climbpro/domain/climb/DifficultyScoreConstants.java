package nl.paree.climbpro.domain.climb;

/**
 * Tunable magic numbers for {@link DifficultyScoreCalculator}. Kept separate from
 * {@link ClimbConstants} (which holds domain-rule thresholds) since these are
 * presentation-tuning knobs, not detection/segmentation rules.
 */
public final class DifficultyScoreConstants {

    private DifficultyScoreConstants() {}

    /**
     * Upper bound on how much harder fatigue can make a climb feel, as a fraction of the
     * base score. A value of 0.5 means a climb arbitrarily deep into a route can score at
     * most 50% higher than the same climb ridden fresh — the fatigue multiplier saturates
     * at {@code 1 + FATIGUE_MAX_BONUS} and never exceeds it, however long the route is.
     */
    public static final double FATIGUE_MAX_BONUS = 0.5;

    /**
     * Distance scale (km) controlling how quickly the fatigue bonus approaches its cap.
     * The multiplier follows {@code 1 - exp(-distanceIntoRouteKm / FATIGUE_SCALE_KM)}, so
     * a climb starting at this many kilometres into the route has already earned ~63% of
     * the maximum fatigue bonus; it keeps climbing (with diminishing returns) after that.
     */
    public static final double FATIGUE_SCALE_KM = 80.0;
}
