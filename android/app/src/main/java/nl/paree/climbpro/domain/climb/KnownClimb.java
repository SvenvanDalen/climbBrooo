package nl.paree.climbpro.domain.climb;

/** Immutable, route-independent description of a climb used for activity matching. */
public final class KnownClimb {

    public final String climbId;
    public final double startLat;
    public final double startLon;
    public final double endLat;
    public final double endLon;
    public final int    lengthM;

    public KnownClimb(String climbId, double startLat, double startLon,
                      double endLat, double endLon, int lengthM) {
        this.climbId  = climbId;
        this.startLat = startLat;
        this.startLon = startLon;
        this.endLat   = endLat;
        this.endLon   = endLon;
        this.lengthM  = lengthM;
    }
}
