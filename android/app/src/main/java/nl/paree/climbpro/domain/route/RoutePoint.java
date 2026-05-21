package nl.paree.climbpro.domain.route;

public final class RoutePoint {

    public final double lat;
    public final double lon;
    public final double elevation;
    public final double distance;

    public RoutePoint(double lat, double lon, double elevation, double distance) {
        this.lat = lat;
        this.lon = lon;
        this.elevation = elevation;
        this.distance = distance;
    }

    @Override
    public String toString() {
        return "RoutePoint{lat=" + lat + ", lon=" + lon
                + ", ele=" + elevation + ", dist=" + distance + "}";
    }
}
