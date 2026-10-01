package nl.paree.climbpro.domain.poi;

import java.util.Locale;

/**
 * A point of interest placed on a route (issue #208): where along the route it is passed and
 * how far it lies beside the route at that spot.
 */
public final class RoutePoi {

    public final String osmRef;
    /** Display name; null for an unnamed viewpoint. */
    public final String name;
    public final PoiType type;
    public final double lat;
    public final double lon;
    /** Distance from the route start to the closest route point, in metres. */
    public final double distanceAlongM;
    /** Straight-line distance from that closest route point to the POI, in metres. */
    public final double offsetM;

    public RoutePoi(String osmRef, String name, PoiType type, double lat, double lon,
                    double distanceAlongM, double offsetM) {
        this.osmRef = osmRef;
        this.name = name;
        this.type = type;
        this.lat = lat;
        this.lon = lon;
        this.distanceAlongM = distanceAlongM;
        this.offsetM = offsetM;
    }

    /** Name, or the type label when OSM has no name (unnamed viewpoints). */
    public String displayName() {
        return name != null ? name : type.label;
    }

    /** "km 12,4 · 80 m van de route" (Dutch decimal comma); "op de route" under 25 m. */
    public String positionText() {
        String km = String.format(Locale.GERMANY, "km %.1f", distanceAlongM / 1000.0);
        if (offsetM < 25) return km + " · op de route";
        return km + " · " + Math.round(offsetM) + " m van de route";
    }
}
