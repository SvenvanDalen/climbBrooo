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
     * on the fly rather than treating null as a real category.
     */
    public String shape;
    /**
     * User-marked "thuisklim" (home climb, issue #92). Phone-only privacy flag: when true,
     * {@code ClimbGpxWriter} obscures the start location on export/share. Never sent to the
     * watch and never affects matching, PR calculation or any other internal logic — those
     * always use the real {@link #startLat}/{@link #startLon}. Defaults to false so existing
     * stored routes (field absent from older JSON) come back unmarked.
     */
    public boolean isHome = false;
}
