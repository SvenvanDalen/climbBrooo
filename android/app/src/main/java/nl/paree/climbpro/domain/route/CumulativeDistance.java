package nl.paree.climbpro.domain.route;

import java.util.ArrayList;
import java.util.List;

/**
 * Computes haversine distances between successive route points and returns
 * a new list of RoutePoints with cumulative distances filled in.
 */
public final class CumulativeDistance {

    private static final double EARTH_RADIUS_M = 6_371_000.0;

    private CumulativeDistance() {}

    /**
     * Takes a list of RoutePoints (distances ignored / zero) and returns a new list
     * where each point's {@code distance} field holds the cumulative distance from
     * the first point in meters.
     */
    public static List<RoutePoint> compute(List<RoutePoint> points) {
        List<RoutePoint> result = new ArrayList<>(points.size());
        double cumulative = 0.0;
        RoutePoint prev = null;
        for (RoutePoint p : points) {
            if (prev != null) {
                cumulative += haversine(prev.lat, prev.lon, p.lat, p.lon);
            }
            result.add(new RoutePoint(p.lat, p.lon, p.elevation, cumulative));
            prev = p;
        }
        return result;
    }

    public static double haversine(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return EARTH_RADIUS_M * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
