package nl.paree.climbpro.domain.border;

/** One national border crossing along a route (issue #209). Immutable. */
public final class BorderCrossing {

    /** Route distance of the crossing, in metres from the route start. */
    public final double distanceM;
    public final double lat;
    public final double lon;
    /** ISO 3166-1 alpha-2 code of the country being left. */
    public final String fromCountry;
    /** ISO 3166-1 alpha-2 code of the country being entered. */
    public final String toCountry;

    public BorderCrossing(double distanceM, double lat, double lon,
                          String fromCountry, String toCountry) {
        this.distanceM = distanceM;
        this.lat = lat;
        this.lon = lon;
        this.fromCountry = fromCountry;
        this.toCountry = toCountry;
    }

    @Override
    public String toString() {
        return "BorderCrossing{" + fromCountry + "->" + toCountry + " @" + Math.round(distanceM) + "m}";
    }
}
