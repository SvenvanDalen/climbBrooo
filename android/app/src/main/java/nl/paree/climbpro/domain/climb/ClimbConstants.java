package nl.paree.climbpro.domain.climb;

public final class ClimbConstants {

    private ClimbConstants() {}

    public static final int    MIN_CLIMB_LENGTH_M          = 800;
    public static final double MIN_AVG_GRADIENT             = 0.03;
    public static final int    SEGMENT_COUNT                = 16;
    /** Wire format version — bump when the payload layout changes. v2 = fixed-16-segment compact format. */
    public static final int    SEGMENT_VERSION              = 2;
    public static final int    CALIBRATION_MIN_DISTANCE_M   = 200;
    public static final int    ALERT_RADIUS_M               = 50;
    public static final int    ROUTE_MATCHING_HYSTERESIS_M  = 20;
    /** A stretch averaging below this gradient (fraction) counts as "vals plat" (false flat). */
    public static final double FALSE_FLAT_MAX_GRADIENT      = 0.02;
    /** Minimum length (metres) of a leading/trailing false flat before it is trimmed off a climb. */
    public static final int    FALSE_FLAT_MIN_LENGTH_M      = 200;
}
