package nl.paree.climbpro.domain.advice;

/**
 * Thresholds driving {@link TirePressureAdvisor}. All values are deliberately simple, named
 * rules of thumb (not a physics model) — see the class doc on {@link TirePressureAdvisor}
 * for why. Kept in one place so the reasoning behind each number lives next to it.
 */
public final class TirePressureConstants {

    private TirePressureConstants() {}

    /**
     * Below this fraction of known route distance being gravel/dirt/cobblestone/mixed, the
     * route counts as "mostly asphalt" — a little unpaved (a driveway, a short connector) isn't
     * worth compromising rolling resistance for.
     */
    public static final double MOSTLY_ASPHALT_MAX_OFFROAD_FRACTION = 0.15;

    /**
     * At/above this off-road fraction, unpaved surface dominates the route rather than just
     * being present, so grip/comfort/puncture resistance should win over rolling resistance.
     * Between this and {@link #MOSTLY_ASPHALT_MAX_OFFROAD_FRACTION} is treated as a genuine
     * mix, warranting a middle-ground compromise pressure.
     */
    public static final double SIGNIFICANT_OFFROAD_MIN_FRACTION = 0.65;

    /**
     * Average gradient (fraction, 0.08 = 8%) at/above which the route's hardest climb counts
     * as "steep" for tire-pressure purposes — roughly CAT_2/CAT_1 grade, the point where
     * losing grip on a loose surface matters more than a few watts of rolling resistance.
     */
    public static final double STEEP_CLIMB_GRADIENT = 0.08;

    // Base ranges in PSI — common starting points for road/gravel tyres, meant to be
    // fine-tuned by the rider for weight, tyre width and tubeless setup, not followed exactly.
    public static final int ASPHALT_MIN_PSI = 90;
    public static final int ASPHALT_MAX_PSI = 100;
    public static final int MIXED_MIN_PSI = 70;
    public static final int MIXED_MAX_PSI = 80;
    public static final int OFFROAD_MIN_PSI = 55;
    public static final int OFFROAD_MAX_PSI = 65;

    /** Used when the route has no known surface data at all — a generic, safe middle range. */
    public static final int DEFAULT_MIN_PSI = 65;
    public static final int DEFAULT_MAX_PSI = 75;

    /**
     * Extra pressure shaved off both ends of the range when a steep climb coincides with
     * meaningful off-road surface: grip on a loose, steep stretch matters enough to trade
     * away some rolling resistance for it.
     */
    public static final int STEEP_OFFROAD_ADJUSTMENT_PSI = 5;

    /** Floor so the steep/off-road adjustment above never suggests a dangerously low pressure. */
    public static final int MIN_SENSIBLE_PSI = 40;
}
