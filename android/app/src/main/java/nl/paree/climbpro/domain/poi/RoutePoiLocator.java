package nl.paree.climbpro.domain.poi;

import nl.paree.climbpro.domain.route.CumulativeDistance;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Places POI candidates on a route (issue #208): distance along the route to the closest
 * point on it, and the lateral offset from there. Candidates beyond {@code maxOffsetM} are
 * dropped, duplicates merged, and the result sorted by distance along the route. Pure Java.
 *
 * <p>Projection uses a local equirectangular approximation per segment, which is accurate to
 * well under a metre at the few-hundred-metre scale that matters here.
 */
public final class RoutePoiLocator {

    private static final double EARTH_RADIUS_M = 6_371_000.0;
    /** Same type and name within this distance: one feature mapped twice (node + area). */
    static final double SAME_NAME_MERGE_M = 150;
    /** Unnamed features of the same type this close together count as one. */
    static final double UNNAMED_MERGE_M = 50;
    /**
     * A later pass must be this much closer to win: on out-and-back or loop routes a POI is
     * then shown at its first pass instead of flipping between near-equal passes.
     */
    private static final double LATER_PASS_MARGIN_M = 1.0;

    private RoutePoiLocator() {}

    /** Closest point on the route to (lat, lon): {distanceAlongM, offsetM}, or null. */
    public static double[] project(double[] lats, double[] lons, double[] cumulative,
                                   double lat, double lon) {
        int n = lats.length;
        if (n == 0) return null;
        if (n == 1) {
            return new double[]{0, CumulativeDistance.haversine(lats[0], lons[0], lat, lon)};
        }
        double cosLat = Math.cos(Math.toRadians(lat));
        double bestOffset = Double.MAX_VALUE;
        double bestAlong = 0;
        for (int i = 0; i < n - 1; i++) {
            // Local metres, origin at the POI.
            double ax = Math.toRadians(lons[i] - lon) * cosLat * EARTH_RADIUS_M;
            double ay = Math.toRadians(lats[i] - lat) * EARTH_RADIUS_M;
            double bx = Math.toRadians(lons[i + 1] - lon) * cosLat * EARTH_RADIUS_M;
            double by = Math.toRadians(lats[i + 1] - lat) * EARTH_RADIUS_M;
            double dx = bx - ax;
            double dy = by - ay;
            double len2 = dx * dx + dy * dy;
            double t = len2 == 0 ? 0 : Math.max(0, Math.min(1, -(ax * dx + ay * dy) / len2));
            double px = ax + t * dx;
            double py = ay + t * dy;
            double d = Math.sqrt(px * px + py * py);
            if (d < bestOffset - LATER_PASS_MARGIN_M) {
                bestOffset = d;
                bestAlong = cumulative[i] + t * (cumulative[i + 1] - cumulative[i]);
            }
        }
        return new double[]{bestAlong, bestOffset};
    }

    /** Cumulative haversine distance per route point, in metres. */
    public static double[] cumulative(double[] lats, double[] lons) {
        double[] cum = new double[lats.length];
        for (int i = 1; i < lats.length; i++) {
            cum[i] = cum[i - 1]
                    + CumulativeDistance.haversine(lats[i - 1], lons[i - 1], lats[i], lons[i]);
        }
        return cum;
    }

    public static List<RoutePoi> locate(List<PoiCandidate> candidates, double[] lats,
                                        double[] lons, double maxOffsetM) {
        List<RoutePoi> placed = new ArrayList<>();
        if (candidates == null || lats == null || lons == null || lats.length == 0
                || lats.length != lons.length) {
            return placed;
        }
        double[] cum = cumulative(lats, lons);
        Set<String> seenRefs = new HashSet<>();
        for (PoiCandidate c : candidates) {
            if (c == null || c.type == null || !seenRefs.add(c.osmRef)) continue;
            double[] pr = project(lats, lons, cum, c.lat, c.lon);
            if (pr == null || pr[1] > maxOffsetM) continue;
            placed.add(new RoutePoi(c.osmRef, c.name, c.type, c.lat, c.lon, pr[0], pr[1]));
        }
        List<RoutePoi> unique = dedupe(placed);
        Collections.sort(unique, Comparator
                .comparingDouble((RoutePoi p) -> p.distanceAlongM)
                .thenComparingDouble(p -> p.offsetM));
        return unique;
    }

    /** Keeps, per cluster of duplicates, the one closest to the route. */
    static List<RoutePoi> dedupe(List<RoutePoi> pois) {
        List<RoutePoi> byOffset = new ArrayList<>(pois);
        Collections.sort(byOffset, Comparator.comparingDouble(p -> p.offsetM));
        List<RoutePoi> kept = new ArrayList<>();
        for (RoutePoi p : byOffset) {
            boolean dup = false;
            for (RoutePoi k : kept) {
                if (isDuplicate(p, k)) {
                    dup = true;
                    break;
                }
            }
            if (!dup) kept.add(p);
        }
        return kept;
    }

    private static boolean isDuplicate(RoutePoi a, RoutePoi b) {
        if (a.type != b.type) return false;
        double d = CumulativeDistance.haversine(a.lat, a.lon, b.lat, b.lon);
        if (a.name == null && b.name == null) return d <= UNNAMED_MERGE_M;
        if (a.name == null || b.name == null) return false;
        return d <= SAME_NAME_MERGE_M
                && a.name.toLowerCase(Locale.ROOT).equals(b.name.toLowerCase(Locale.ROOT));
    }
}
