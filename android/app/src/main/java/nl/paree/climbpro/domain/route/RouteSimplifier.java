package nl.paree.climbpro.domain.route;

import java.util.ArrayList;
import java.util.List;

/**
 * Douglas-Peucker route simplification.
 * Operates on lat/lon coordinates; epsilon is in degrees (≈ metres / 111 320).
 * A convenience overload accepts epsilon directly in metres.
 */
public final class RouteSimplifier {

    private static final double METRES_PER_DEGREE = 111_320.0;
    private static final int MAX_POINTS_AFTER_SIMPLIFICATION = 10_000;

    private RouteSimplifier() {}

    /**
     * Simplify with epsilon in metres.
     */
    public static List<RoutePoint> simplify(List<RoutePoint> points, double epsilonMetres) {
        return simplifyDegrees(points, epsilonMetres / METRES_PER_DEGREE);
    }

    private static List<RoutePoint> simplifyDegrees(List<RoutePoint> points, double epsilon) {
        if (points == null) return new ArrayList<>();
        if (points.size() <= 2) return new ArrayList<>(points);
        List<RoutePoint> result = douglasPeucker(points, 0, points.size() - 1, epsilon);
        if (result.size() > MAX_POINTS_AFTER_SIMPLIFICATION) {
            result = resample(result, MAX_POINTS_AFTER_SIMPLIFICATION);
        }
        return result;
    }

    private static List<RoutePoint> douglasPeucker(
            List<RoutePoint> pts, int start, int end, double epsilon) {
        double maxDist = 0;
        int index = start;
        for (int i = start + 1; i < end; i++) {
            double d = perpendicularDistance(pts.get(i), pts.get(start), pts.get(end));
            if (d > maxDist) {
                maxDist = d;
                index = i;
            }
        }
        List<RoutePoint> result = new ArrayList<>();
        if (maxDist > epsilon) {
            List<RoutePoint> left  = douglasPeucker(pts, start, index, epsilon);
            List<RoutePoint> right = douglasPeucker(pts, index, end, epsilon);
            result.addAll(left);
            right.remove(0);
            result.addAll(right);
        } else {
            result.add(pts.get(start));
            result.add(pts.get(end));
        }
        return result;
    }

    /** Perpendicular distance from point p to the line (a, b) in lat/lon degrees. */
    private static double perpendicularDistance(RoutePoint p, RoutePoint a, RoutePoint b) {
        double dx = b.lon - a.lon;
        double dy = b.lat - a.lat;
        if (dx == 0 && dy == 0) {
            double ddx = p.lon - a.lon;
            double ddy = p.lat - a.lat;
            return Math.sqrt(ddx * ddx + ddy * ddy);
        }
        double t = ((p.lon - a.lon) * dx + (p.lat - a.lat) * dy) / (dx * dx + dy * dy);
        t = Math.max(0, Math.min(1, t));
        double nx = a.lon + t * dx - p.lon;
        double ny = a.lat + t * dy - p.lat;
        return Math.sqrt(nx * nx + ny * ny);
    }

    private static List<RoutePoint> resample(List<RoutePoint> pts, int target) {
        List<RoutePoint> out = new ArrayList<>(target);
        double step = (double) (pts.size() - 1) / (target - 1);
        for (int i = 0; i < target; i++) {
            out.add(pts.get((int) Math.round(i * step)));
        }
        return out;
    }
}
