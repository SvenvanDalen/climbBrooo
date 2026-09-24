package nl.paree.climbpro.domain.weather;

import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredRoute;

/** Position and elevation of a climb's foot and top, interpolated along the route (issue #246). Pure. */
public final class ClimbEndpoints {

    public static final class Point {
        public final double lat;
        public final double lon;
        /** Metres above sea level, or NaN when the route has no elevations. */
        public final double elevationM;

        Point(double lat, double lon, double elevationM) {
            this.lat = lat;
            this.lon = lon;
            this.elevationM = elevationM;
        }
    }

    private ClimbEndpoints() {}

    public static Point foot(StoredRoute r, StoredClimb c) { return at(r, c, c.startDistance); }

    public static Point top(StoredRoute r, StoredClimb c) { return at(r, c, c.endDistance); }

    private static Point at(StoredRoute r, StoredClimb c, double distance) {
        if (r.lats == null || r.lons == null || r.distances == null) {
            return new Point(c.startLat, c.startLon, Double.NaN);
        }
        int n = Math.min(r.lats.length, Math.min(r.lons.length, r.distances.length));
        if (n == 0) return new Point(c.startLat, c.startLon, Double.NaN);
        boolean hasEle = r.elevations != null && r.elevations.length >= n;
        int i = 1;
        while (i < n && r.distances[i] < distance) i++;
        if (i >= n || distance <= r.distances[0]) {
            int k = distance <= r.distances[0] ? 0 : n - 1;
            return new Point(r.lats[k], r.lons[k], hasEle ? r.elevations[k] : Double.NaN);
        }
        double span = r.distances[i] - r.distances[i - 1];
        double t = span > 0 ? (distance - r.distances[i - 1]) / span : 0;
        return new Point(
                lerp(r.lats[i - 1], r.lats[i], t),
                lerp(r.lons[i - 1], r.lons[i], t),
                hasEle ? lerp(r.elevations[i - 1], r.elevations[i], t) : Double.NaN);
    }

    private static double lerp(double a, double b, double t) { return a + t * (b - a); }
}
