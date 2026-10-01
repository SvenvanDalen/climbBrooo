package nl.paree.climbpro.domain.poi;

import nl.paree.climbpro.domain.route.RoutePoint;
import nl.paree.climbpro.domain.route.RouteSimplifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Builds the Overpass QL query for POIs near a route (issue #208).
 *
 * <p>The route is Douglas-Peucker simplified and passed as a polyline to
 * {@code (around:R,lat1,lon1,lat2,lon2,…)}, which Overpass reads as a buffer around the line.
 * To keep the request bounded for long routes the simplification tolerance grows until at most
 * {@link #MAX_VERTICES} vertices remain. The simplified line lies within that tolerance of the
 * real route, so the search radius is widened by it: nothing within {@code maxOffsetM} of the
 * real route is missed, and {@link RoutePoiLocator} re-filters exactly against the full route.
 */
public final class OverpassQueryBuilder {

    /** Upper bound on polyline vertices per query (keeps the request a few kB). */
    public static final int MAX_VERTICES = 250;
    /** Server-side cap on returned elements; far above what a normal route yields. */
    public static final int MAX_RESULTS = 1000;
    /** Overpass server timeout in seconds. */
    public static final int TIMEOUT_S = 60;

    private static final double[] EPSILONS_M = {25, 50, 100, 200, 400, 800, 1600};

    private OverpassQueryBuilder() {}

    /** Simplified route used for the query, plus the tolerance it was simplified with. */
    public static final class Polyline {
        public final List<double[]> points;
        public final double epsilonM;

        Polyline(List<double[]> points, double epsilonM) {
            this.points = points;
            this.epsilonM = epsilonM;
        }
    }

    /**
     * The coarsest-needed simplification with at most {@link #MAX_VERTICES} vertices, or null
     * for a route without usable coordinates.
     */
    public static Polyline simplify(double[] lats, double[] lons) {
        if (lats == null || lons == null || lats.length == 0 || lats.length != lons.length) {
            return null;
        }
        List<RoutePoint> pts = new ArrayList<>(lats.length);
        for (int i = 0; i < lats.length; i++) {
            if (Double.isNaN(lats[i]) || Double.isNaN(lons[i])) continue;
            pts.add(new RoutePoint(lats[i], lons[i], 0, 0));
        }
        if (pts.isEmpty()) return null;
        List<RoutePoint> simplified = pts;
        double eps = 0;
        for (double e : EPSILONS_M) {
            eps = e;
            simplified = RouteSimplifier.simplify(pts, e);
            if (simplified.size() <= MAX_VERTICES) break;
        }
        if (simplified.size() > MAX_VERTICES) {
            // Pathological input (thousands of zig-zags): evenly thin out. Still bounded; the
            // widened radius below may then miss a POI on an extreme detour, never crash.
            List<RoutePoint> thinned = new ArrayList<>(MAX_VERTICES);
            double step = (simplified.size() - 1) / (double) (MAX_VERTICES - 1);
            for (int i = 0; i < MAX_VERTICES; i++) {
                thinned.add(simplified.get((int) Math.round(i * step)));
            }
            simplified = thinned;
        }
        List<double[]> out = new ArrayList<>(simplified.size());
        for (RoutePoint p : simplified) out.add(new double[]{p.lat, p.lon});
        return new Polyline(out, eps);
    }

    /** Overpass QL for every {@link PoiType} within {@code maxOffsetM} of the route, or null. */
    public static String build(double[] lats, double[] lons, int maxOffsetM) {
        Polyline line = simplify(lats, lons);
        if (line == null) return null;
        StringBuilder around = new StringBuilder("(around:")
                .append(Math.round(maxOffsetM + line.epsilonM));
        for (double[] p : line.points) {
            around.append(String.format(Locale.US, ",%.5f,%.5f", p[0], p[1]));
        }
        around.append(')');
        String a = around.toString();

        StringBuilder q = new StringBuilder()
                .append("[out:json][timeout:").append(TIMEOUT_S).append("];\n(\n");
        q.append("  nwr[\"tourism\"~\"^(").append(values("tourism")).append(")$\"]")
                .append(a).append(";\n");
        q.append("  nwr[\"historic\"~\"^(").append(values("historic")).append(")$\"]")
                .append(a).append(";\n");
        q.append(");\nout tags center ").append(MAX_RESULTS).append(";\n");
        return q.toString();
    }

    private static String values(String key) {
        StringBuilder sb = new StringBuilder();
        for (PoiType t : PoiType.values()) {
            if (!t.osmKey.equals(key)) continue;
            if (sb.length() > 0) sb.append('|');
            sb.append(t.osmValue);
        }
        return sb.toString();
    }
}
