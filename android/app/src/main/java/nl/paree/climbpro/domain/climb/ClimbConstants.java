package nl.paree.climbpro.domain.climb;

public final class ClimbConstants {

    private ClimbConstants() {}

    public static final int    MIN_CLIMB_LENGTH_M          = 800;
    public static final double MIN_AVG_GRADIENT             = 0.03;
    /** Each segment covers this fraction of the climb length; yields ⌈1/0.08⌉ = 13 segments. */
    public static final double SEGMENT_FRACTION             = 0.08;
    /** Wire format version — bump when the payload layout changes. v3 = 8%-fraction segments (13 per climb). */
    public static final int    SEGMENT_VERSION              = 3;

    /** Returns the nominal segment count derived from {@link #SEGMENT_FRACTION}: always 13. */
    public static int defaultSegmentCount() {
        return (int) Math.ceil(1.0 / SEGMENT_FRACTION);
    }
    public static final int    CALIBRATION_MIN_DISTANCE_M   = 200;
    public static final int    ALERT_RADIUS_M               = 50;
    public static final int    ROUTE_MATCHING_HYSTERESIS_M  = 20;
    /** A stretch averaging below this gradient (fraction) counts as "vals plat" (false flat). */
    public static final double FALSE_FLAT_MAX_GRADIENT      = 0.02;
    /** Minimum length (metres) of a leading/trailing false flat before it is trimmed off a climb. */
    public static final int    FALSE_FLAT_MIN_LENGTH_M      = 200;
}
