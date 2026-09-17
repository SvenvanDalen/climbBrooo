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

    public KnownClimb(String climbId, double startLat, double startLon,
                      double endLat, double endLon, int lengthM) {
        this(climbId, startLat, startLon, endLat, endLon, lengthM, null);
    }

    public KnownClimb(String climbId, double startLat, double startLon,
                      double endLat, double endLon, int lengthM, int[] segLengthsM) {
        this.climbId     = climbId;
        this.startLat     = startLat;
        this.startLon     = startLon;
        this.endLat       = endLat;
        this.endLon       = endLon;
        this.lengthM      = lengthM;
        this.segLengthsM  = segLengthsM;
    }
}
