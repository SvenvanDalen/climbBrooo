package nl.paree.climbpro.domain.climb;

/** Immutable, route-independent description of a climb used for activity matching. */
public final class KnownClimb {

    public final String climbId;
    public final double startLat;
    public final double startLon;
    public final double endLat;
    public final double endLon;
    public final int    lengthM;
    /** Per-segment length (m), in climb order; null when the route has no segments. */
    public final int[]  segLengthsM;
    /**
     * Calibration-point latitudes/longitudes, in climb order — the known intermediate
     * road geometry used by {@link nl.paree.climbpro.domain.matching.ClimbRouteDeviationDetector}.
     * Null (or fewer than 2 entries) when the climb has no usable calibration geometry yet.
     */
    public final double[] calibLats;
    public final double[] calibLons;

    public KnownClimb(String climbId, double startLat, double startLon,
                      double endLat, double endLon, int lengthM) {
        this(climbId, startLat, startLon, endLat, endLon, lengthM, null, null, null);
    }

    public KnownClimb(String climbId, double startLat, double startLon,
                      double endLat, double endLon, int lengthM, int[] segLengthsM) {
        this(climbId, startLat, startLon, endLat, endLon, lengthM, segLengthsM, null, null);
    }

    public KnownClimb(String climbId, double startLat, double startLon,
                      double endLat, double endLon, int lengthM, int[] segLengthsM,
                      double[] calibLats, double[] calibLons) {
        this.climbId     = climbId;
        this.startLat     = startLat;
        this.startLon     = startLon;
        this.endLat       = endLat;
        this.endLon       = endLon;
        this.lengthM      = lengthM;
        this.segLengthsM  = segLengthsM;
        this.calibLats    = calibLats;
        this.calibLons    = calibLons;
    }
}
