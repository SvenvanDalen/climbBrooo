package nl.paree.climbpro.domain.route;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Issue #204: joins two routes (A then B) into one point list, e.g. an approach route
 * followed by a climbing loop. Pure — the caller re-runs climb detection on the result.
 *
 * <p>Joint handling:
 * <ul>
 *   <li>If B starts within {@link #JOINT_DUPLICATE_M} of A's end, B's first point is dropped
 *       so the joint isn't a zero-length duplicate (B's elevation wins if A's is missing).</li>
 *   <li>Otherwise the last point of A and the first point of B are simply adjacent: the gap is
 *       bridged by a straight line whose length counts towards the cumulative distance. A gap
 *       over {@link #LARGE_GAP_WARNING_M} is flagged via {@link Result#hasLargeGap()} so the UI
 *       can warn the user before saving — we never invent road geometry.</li>
 * </ul>
 * Missing elevation stays {@link Double#NaN}; the detector already skips NaN samples.
 */
public final class RouteJoiner {

    /** Below this distance, A's end and B's start are treated as the same point. */
    public static final double JOINT_DUPLICATE_M = 5.0;
    /** Above this distance the straight bridge is considered unrealistic enough to warn. */
    public static final double LARGE_GAP_WARNING_M = 250.0;

    private RouteJoiner() {}

    public static final class Result {
        /** Joined points with cumulative distance recomputed from the first point of A. */
        public final List<RoutePoint> points;
        /** Straight-line distance between A's last and B's first point (0 if either is empty). */
        public final double gapM;

        Result(List<RoutePoint> points, double gapM) {
            this.points = points;
            this.gapM = gapM;
        }

        public boolean hasLargeGap() {
            return gapM > LARGE_GAP_WARNING_M;
        }
    }

    public static Result join(List<RoutePoint> first, List<RoutePoint> second) {
        List<RoutePoint> a = first != null ? first : Collections.<RoutePoint>emptyList();
        List<RoutePoint> b = second != null ? second : Collections.<RoutePoint>emptyList();

        List<RoutePoint> raw = new ArrayList<>(a.size() + b.size());
        raw.addAll(a);
        double gap = 0.0;
        int bStart = 0;
        if (!a.isEmpty() && !b.isEmpty()) {
            RoutePoint end = a.get(a.size() - 1);
            RoutePoint start = b.get(0);
            gap = CumulativeDistance.haversine(end.lat, end.lon, start.lat, start.lon);
            if (gap < JOINT_DUPLICATE_M) {
                bStart = 1;
                if (Double.isNaN(end.elevation) && !Double.isNaN(start.elevation)) {
                    raw.set(raw.size() - 1,
                            new RoutePoint(end.lat, end.lon, start.elevation, end.distance));
                }
            }
        }
        for (int i = bStart; i < b.size(); i++) raw.add(b.get(i));
        return new Result(CumulativeDistance.compute(raw), gap);
    }

    /**
     * Rebuilds points from the parallel arrays of a stored route. A null or short elevation
     * array yields NaN elevations; mismatched lat/lon lengths are clipped to the shorter.
     */
    public static List<RoutePoint> toPoints(double[] lats, double[] lons, double[] elevations) {
        if (lats == null || lons == null) return new ArrayList<>();
        int n = Math.min(lats.length, lons.length);
        List<RoutePoint> pts = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            double ele = elevations != null && i < elevations.length ? elevations[i] : Double.NaN;
            pts.add(new RoutePoint(lats[i], lons[i], ele, 0.0));
        }
        return pts;
    }

    /** Default display name for the joined route, e.g. "Aanloop + Lus". */
    public static String defaultName(String nameA, String nameB) {
        return orRoute(nameA) + " + " + orRoute(nameB);
    }

    private static String orRoute(String name) {
        return name == null || name.trim().isEmpty() ? "Route" : name.trim();
    }
}
