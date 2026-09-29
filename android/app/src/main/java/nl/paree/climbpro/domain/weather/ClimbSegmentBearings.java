package nl.paree.climbpro.domain.weather;

import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredRoute;

/**
 * Direction of travel per climb segment (issue #47), for projecting wind onto the climb.
 * Each segment's bearing is the chord from its start to its end point, interpolated along the
 * route geometry, in degrees 0-360 (0 = north, 90 = east). NaN when the route has no usable
 * geometry or the segment has no length. Pure.
 */
public final class ClimbSegmentBearings {

    private ClimbSegmentBearings() {}

    public static double[] compute(StoredRoute r, StoredClimb c) {
        int n = c.segments == null ? 0 : c.segments.size();
        double[] out = new double[n];
        boolean hasGeometry = r != null && r.lats != null && r.lons != null && r.distances != null
                && Math.min(r.lats.length, Math.min(r.lons.length, r.distances.length)) >= 2;
        double from = c.startDistance;
        for (int i = 0; i < n; i++) {
            double to = from + c.segments.get(i).distance;
            if (!hasGeometry || to <= from) {
                out[i] = Double.NaN;
            } else {
                ClimbEndpoints.Point a = ClimbEndpoints.at(r, c, from);
                ClimbEndpoints.Point b = ClimbEndpoints.at(r, c, to);
                out[i] = bearingDeg(a.lat, a.lon, b.lat, b.lon);
            }
            from = to;
        }
        return out;
    }

    /** Initial great-circle bearing from point 1 to point 2; NaN for identical points. */
    public static double bearingDeg(double lat1, double lon1, double lat2, double lon2) {
        if (lat1 == lat2 && lon1 == lon2) return Double.NaN;
        double p1 = Math.toRadians(lat1);
        double p2 = Math.toRadians(lat2);
        double dl = Math.toRadians(lon2 - lon1);
        double y = Math.sin(dl) * Math.cos(p2);
        double x = Math.cos(p1) * Math.sin(p2) - Math.sin(p1) * Math.cos(p2) * Math.cos(dl);
        double deg = Math.toDegrees(Math.atan2(y, x));
        return (deg + 360) % 360;
    }
}
