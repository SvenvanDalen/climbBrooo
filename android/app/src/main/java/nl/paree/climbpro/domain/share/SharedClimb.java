package nl.paree.climbpro.domain.share;

/**
 * One climb as it travels inside a share code (issue #216): its name and its geometry, a little
 * wider than the climb itself so the receiving phone detects the same climb again. Arrays are
 * index-aligned; elevation in metres.
 */
public final class SharedClimb {

    public final String name;
    public final double[] lats;
    public final double[] lons;
    public final double[] elevations;

    public SharedClimb(String name, double[] lats, double[] lons, double[] elevations) {
        this.name = name;
        this.lats = lats;
        this.lons = lons;
        this.elevations = elevations;
    }

    public int size() { return lats != null ? lats.length : 0; }
}
