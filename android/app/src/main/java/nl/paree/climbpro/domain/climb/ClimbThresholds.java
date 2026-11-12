package nl.paree.climbpro.domain.climb;

/**
 * The minimum length and average gradient a stretch needs to count as a climb (issue #69).
 * The defaults are the product's climb definition ({@link ClimbConstants#MIN_CLIMB_LENGTH_M} /
 * {@link ClimbConstants#MIN_AVG_GRADIENT}); riders in flat regions may relax them in settings.
 * Phone-only: the watch only receives the climbs that result, so the wire format is unchanged.
 * The values apply to climbs detected from now on; existing routes keep their stored climbs
 * until they are re-imported.
 */
public final class ClimbThresholds {

    public static final String PREF_MIN_LENGTH_M = "climb_min_length_m";
    public static final String PREF_MIN_GRADIENT_PCT10 = "climb_min_gradient_pct10";

    public static final int MIN_LENGTH_LOWER_M = 300;
    public static final int MIN_LENGTH_UPPER_M = 2000;
    /** Gradient is stored in tenths of a percent (30 = 3.0 %). */
    public static final int MIN_GRADIENT_LOWER_PCT10 = 10;
    public static final int MIN_GRADIENT_UPPER_PCT10 = 80;

    public static final int DEFAULT_LENGTH_M = ClimbConstants.MIN_CLIMB_LENGTH_M;
    public static final int DEFAULT_GRADIENT_PCT10 =
            (int) Math.round(ClimbConstants.MIN_AVG_GRADIENT * 1000);

    private static volatile int lengthM = DEFAULT_LENGTH_M;
    private static volatile int gradientPct10 = DEFAULT_GRADIENT_PCT10;

    private ClimbThresholds() {}

    public static int minLengthM() {
        return lengthM;
    }

    /** Minimum average gradient as a fraction (0.03 = 3 %). */
    public static double minGradient() {
        return gradientPct10 / 1000.0;
    }

    public static int minGradientPct10() {
        return gradientPct10;
    }

    public static void set(int requestedLengthM, int requestedGradientPct10) {
        lengthM = clampLength(requestedLengthM);
        gradientPct10 = clampGradient(requestedGradientPct10);
    }

    public static void reset() {
        set(DEFAULT_LENGTH_M, DEFAULT_GRADIENT_PCT10);
    }

    public static int clampLength(int v) {
        return Math.max(MIN_LENGTH_LOWER_M, Math.min(MIN_LENGTH_UPPER_M, v));
    }

    public static int clampGradient(int v) {
        return Math.max(MIN_GRADIENT_LOWER_PCT10, Math.min(MIN_GRADIENT_UPPER_PCT10, v));
    }
}
