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
    /**
     * Max distance (metres) between a Strava starred-segment endpoint and the nearest
     * route point for the segment to count as lying on the route. Generous enough to
     * absorb Douglas-Peucker simplification (~5 m epsilon) plus Strava/GPX rounding.
     */
    public static final int    STARRED_SEGMENT_MATCH_MAX_M  = 50;
    /** A stretch averaging below this gradient (fraction) counts as "vals plat" (false flat). */
    public static final double FALSE_FLAT_MAX_GRADIENT      = 0.02;
    /** Minimum length (metres) of a leading/trailing false flat before it is trimmed off a climb. */
    public static final int    FALSE_FLAT_MIN_LENGTH_M      = 200;

    /**
     * Reference climbing speed (m/s) used by {@link VamCalculator} to convert a gradient into an
     * implied VAM (vertical ascent metres/hour). Route points carry no elapsed-time data — routes
     * are precomputed from GPX/FIT geometry, not recorded rides — so a true VAM (elevation gain /
     * elapsed time) cannot be derived from a route file. ~3.5 m/s (12.6 km/h) is a representative
     * sustained climbing speed on a road bike; the resulting VAM is a planning estimate for
     * comparing segment intensity, not a measured ascent rate.
     */
    public static final double VAM_REFERENCE_SPEED_MPS      = 3.5;
    /**
     * Rolling window (metres) used by {@link VamCalculator} to find the peak VAM within a segment.
     * Distance-based rather than time-based because {@code RoutePoint} carries no timestamp.
     */
    public static final int    VAM_PEAK_WINDOW_M            = 100;
    /**
     * Minimum gap (fraction, e.g. 0.015 = 1.5 percentage points) between the average gradient
     * of a climb's last third and first third of segments for the trend to count as a real
     * ramp (STEEP_FINISH / EASY_START) rather than noise. Used by {@link ClimbShapeClassifier}.
     */
    public static final double SHAPE_TREND_DELTA_GRADIENT   = 0.015;
    /**
     * Population standard deviation (fraction) of segment gradients above which a climb with
     * no clear start-to-end trend is classified IRREGULAR instead of STEADY. Used by
     * {@link ClimbShapeClassifier}.
     */
    public static final double SHAPE_IRREGULAR_STDDEV       = 0.02;
    /**
     * Max distance (metres) between a newly-imported climb's start coordinate and an
     * already-known climb's start coordinate for the import flow to treat them as the
     * same physical climb and offer a merge instead of a duplicate (issue #11). Wider
     * than {@link #STARRED_SEGMENT_MATCH_MAX_M} because GPX sources (Strava export vs.
     * a manual recording of the same road) can disagree on the exact climb start by
     * more than simplification jitter alone.
     */
    public static final double DUPLICATE_CLIMB_MATCH_RADIUS_M = 150.0;
    /**
     * Minimum share (fraction of known-surface distance) either the paved or the unpaved
     * group must fall below for a climb to still count as purely PAVED/GRAVEL. If both groups
     * meet or exceed this minority share, or the climb has segments explicitly tagged MIXED,
     * the climb is classified MIXED instead. Used by {@link ClimbSurfaceClassifier}.
     */
    public static final double SURFACE_MIXED_MINORITY_FRACTION = 0.2;

    /**
     * Max distance (metres) between the start coordinates of two already-STORED climbs
     * (different {@link ClimbIdentity} keys, so the automatic bucketing already missed them)
     * for {@link NearDuplicateClimbFinder} to flag them as a manual-merge candidate (issue #76).
     * Wider than {@link #DUPLICATE_CLIMB_MATCH_RADIUS_M} since this catches
     * {@link ClimbIdentity}'s documented bucket-boundary gap, which needs more slack than the
     * import-time exact-duplicate check.
     */
    public static final double NEAR_DUPLICATE_MATCH_RADIUS_M     = 200.0;
    /**
     * Max relative difference in climb length (e.g. 0.20 = 20%) for two nearby climbs to still
     * count as the same physical climb in {@link NearDuplicateClimbFinder}. Guards against
     * flagging a short spur next to the start of a much longer climb as a near-duplicate.
     */
    public static final double NEAR_DUPLICATE_LENGTH_TOLERANCE   = 0.20;
    /**
     * Max absolute difference in average gradient (fraction, e.g. 0.02 = 2 percentage points)
     * for two nearby, similarly-sized climbs to count as the same physical climb in
     * {@link NearDuplicateClimbFinder}.
     */
    public static final double NEAR_DUPLICATE_GRADIENT_TOLERANCE = 0.02;

    /**
     * Minimum number of distinct matched ride attempts for a climb to count as "vaak herhaald"
     * (repeated often) and classify TRAINING (issue #44). Used by {@link ClimbUsageClassifier}.
     */
    public static final int    USAGE_TRAINING_MIN_ATTEMPTS  = 3;
    /**
     * Radius (metres) around a once-ridden climb's start within which another frequently-ridden
     * climb (attemptCount &gt;= {@link #USAGE_TRAINING_MIN_ATTEMPTS}) counts as the same "local
     * training area" — the practical "dicht bij huis" proxy used because no general
     * home-location field exists yet (see {@link ClimbUsageClassifier} doc comment). Used by
     * {@link ClimbUsageClassifier}.
     */
    public static final double USAGE_LOCAL_CLUSTER_RADIUS_M = 5_000.0;
}
