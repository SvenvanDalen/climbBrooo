package nl.paree.climbpro.domain.route;

import java.util.ArrayDeque;
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

    /**
     * Iterative Douglas-Peucker with an explicit stack of index ranges. It keeps exactly the
     * same points as the former recursive form (same distance, same split at the first point
     * with the largest deviation), but its depth no longer grows with the input: a long track
     * whose points all deviate equally splits at {@code start + 1} every time, which recursed
     * once per point and overflowed the call stack. That degenerate case is still quadratic in
     * time, so the coordinates are copied into primitive arrays once to keep the scan cheap.
     */
    private static List<RoutePoint> douglasPeucker(
            List<RoutePoint> pts, int start, int end, double epsilon) {
        int n = pts.size();
        double[] lat = new double[n];
        double[] lon = new double[n];
        for (int i = 0; i < n; i++) {
            RoutePoint p = pts.get(i);
            lat[i] = p.lat;
            lon[i] = p.lon;
        }

        boolean[] keep = new boolean[n];
        keep[start] = true;
        keep[end] = true;

        ArrayDeque<int[]> stack = new ArrayDeque<>();
        stack.push(new int[]{start, end});
        while (!stack.isEmpty()) {
            int[] range = stack.pop();
            int from = range[0];
            int to = range[1];
            double maxDist = 0;
            int index = from;
            for (int i = from + 1; i < to; i++) {
                double d = perpendicularDistance(lat[i], lon[i],
                        lat[from], lon[from], lat[to], lon[to]);
                if (d > maxDist) {
                    maxDist = d;
                    index = i;
                }
            }
            if (maxDist > epsilon) {
                keep[index] = true;
                stack.push(new int[]{index, to});
                stack.push(new int[]{from, index});
            }
        }

        List<RoutePoint> result = new ArrayList<>();
        for (int i = start; i <= end; i++) {
            if (keep[i]) result.add(pts.get(i));
        }
        return result;
    }

    /** Perpendicular distance from point p to the line (a, b) in lat/lon degrees. */
    private static double perpendicularDistance(double pLat, double pLon,
            double aLat, double aLon, double bLat, double bLon) {
        double dx = bLon - aLon;
        double dy = bLat - aLat;
        if (dx == 0 && dy == 0) {
            double ddx = pLon - aLon;
            double ddy = pLat - aLat;
            return Math.sqrt(ddx * ddx + ddy * ddy);
        }
        double t = ((pLon - aLon) * dx + (pLat - aLat) * dy) / (dx * dx + dy * dy);
        t = Math.max(0, Math.min(1, t));
        double nx = aLon + t * dx - pLon;
        double ny = aLat + t * dy - pLat;
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
