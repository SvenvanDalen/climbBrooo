package nl.paree.climbpro.data.route;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Serialised form of a detected climb stored inside a route JSON file.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class StoredClimb {

    public int startDistance;
    public int endDistance;
    public int length;
    public int elevationGain;
    public double avgGradient;
    public double startLat;
    public double startLon;
    public String name;
    public String userDisplayName;
    public List<StoredSegment> segments;
    public List<StoredCalibrationPoint> calibrationPoints; // null on routes stored before version 2
    public int segmentCount = 0; // 0 means use ClimbConstants.defaultSegmentCount() (for old stored routes)
    /**
     * Auto-computed shape classification (see {@code domain.climb.ClimbShape}), stored as the
     * enum name. Phone-only for now — not part of the wire payload. Null on routes stored
     * before this field existed; callers should fall back to classifying {@link #segments}
     * on the fly rather than treating null as a real category. Overwritten by re-detection /
     * re-segmentation, so it always reflects the current auto classification — never the
     * user's choice. See {@link #shapeOverride}.
     */
    public String shape;
    /**
     * Manually-entered world-record/pro reference time in seconds (issue #59), e.g.
     * Pogačar's Alpe d'Huez time. Null when unset. Distributed across segments by
     * {@code ManualRefTimePlanner} and fed into the same wire {@code refsec} field as
     * the rider's own PR, taking priority over it when set.
     */
    public Integer manualRefSec;
    /** Short label shown alongside {@link #manualRefSec}, e.g. "Pogačar 2024". Null when unset. */
    public String manualRefLabel;
    /**
     * User-marked "thuisklim" (home climb, issue #92). Phone-only privacy flag: when true,
     * {@code ClimbGpxWriter} obscures the start location on export/share. Never sent to the
     * watch and never affects matching, PR calculation or any other internal logic — those
     * always use the real {@link #startLat}/{@link #startLon}. Defaults to false so existing
     * stored routes (field absent from older JSON) come back unmarked.
     */
    public boolean isHome = false;
    /**
     * Centre of this home climb's privacy zone (see {@code CoordinateFuzzer}): drawn once from
     * a SecureRandom and reused on every export so repeated exports can't be intersected.
     * Null until the first home-climb export. Phone-only, preserved across resync.
     */
    public Double privacyCentreLat;
    public Double privacyCentreLon;
    /**
     * User-supplied override of {@link #shape} (issue #36), stored as the enum name. Null means
     * "no override — use the auto-computed {@link #shape}". Set/cleared only via explicit user
     * action (never by detection/re-segmentation) and carried across resync/re-import by
     * {@code RouteRepository#mergePreviousClimbUserData}, the same way {@link #userDisplayName}
     * survives a rename. Read through {@code ClimbShapeClassifier#effectiveShape}, never
     * directly, so override-wins-over-auto logic lives in one place.
     */
    public String shapeOverride;
}
