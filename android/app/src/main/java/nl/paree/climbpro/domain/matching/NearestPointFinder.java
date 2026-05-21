package nl.paree.climbpro.domain.matching;

import nl.paree.climbpro.domain.climb.ClimbConstants;
import nl.paree.climbpro.domain.route.CumulativeDistance;
import nl.paree.climbpro.domain.route.RoutePoint;

import java.util.List;

/**
 * Finds the nearest point on a route polyline to a GPS position,
 * with hysteresis to prevent progress jumping backwards.
 *
 * Usage (stateful — one instance per active route):
 * <pre>
 *   NearestPointFinder finder = new NearestPointFinder(route);
 *   double distAlongRoute = finder.update(lat, lon);
 * </pre>
 */
public final class NearestPointFinder {

    private final List<RoutePoint> route;
    private double lastDistance = -1.0;

    public NearestPointFinder(List<RoutePoint> route) {
        this.route = route;
    }

    /**
     * Update with a new GPS position.
     * @return distance along the route in metres, or -1 if the route is empty.
     */
    public double update(double lat, double lon) {
        if (route == null || route.size() < 2) return -1;

        double bestDist = Double.MAX_VALUE;
        double bestRoutePos = lastDistance < 0 ? 0 : lastDistance;

        int n = route.size();
        for (int i = 0; i < n - 1; i++) {
            RoutePoint a = route.get(i);
            RoutePoint b = route.get(i + 1);

            double[] proj = projectOntoSegment(lat, lon, a, b);
            double crossDist = proj[0];
            double routePos  = proj[1];

            if (crossDist < bestDist) {
                // Apply hysteresis: only accept points that would move progress forward,
                // or are significantly closer laterally.
                boolean movesBackward = lastDistance >= 0
                        && routePos < lastDistance - ClimbConstants.ROUTE_MATCHING_HYSTERESIS_M;
                if (!movesBackward) {
                    bestDist = crossDist;
                    bestRoutePos = routePos;
                }
            }
        }

        lastDistance = bestRoutePos;
        return bestRoutePos;
    }

    public void reset() {
        lastDistance = -1.0;
    }

    /**
     * @return [0] = perpendicular distance to segment (metres),
     *         [1] = projected distance along route (metres) at the nearest point
     */
    private static double[] projectOntoSegment(
            double lat, double lon, RoutePoint a, RoutePoint b) {
        double ax = a.lon, ay = a.lat;
        double bx = b.lon, by = b.lat;
        double px = lon,   py = lat;

        double dx = bx - ax, dy = by - ay;
        double lenSq = dx * dx + dy * dy;
        double t = lenSq > 0 ? ((px - ax) * dx + (py - ay) * dy) / lenSq : 0;
        t = Math.max(0, Math.min(1, t));

        double nearX = ax + t * dx;
        double nearY = ay + t * dy;

        double crossDist = CumulativeDistance.haversine(py, px, nearY, nearX);
        double routePos  = a.distance + t * (b.distance - a.distance);
        return new double[]{crossDist, routePos};
    }
}
