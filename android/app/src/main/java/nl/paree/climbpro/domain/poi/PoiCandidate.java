package nl.paree.climbpro.domain.poi;

/**
 * One OpenStreetMap feature as the Overpass API returned it (issue #208), before it is placed
 * on the route. {@code osmRef} is {@code "<type>/<id>"}, e.g. {@code "node/123"}.
 */
public final class PoiCandidate {

    public final String osmRef;
    /** Display name; null for an unnamed viewpoint. */
    public final String name;
    public final PoiType type;
    public final double lat;
    public final double lon;

    public PoiCandidate(String osmRef, String name, PoiType type, double lat, double lon) {
        this.osmRef = osmRef;
        this.name = name;
        this.type = type;
        this.lat = lat;
        this.lon = lon;
    }
}
