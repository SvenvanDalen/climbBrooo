package nl.paree.climbpro.domain.route;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Suggests shorter variants of a route within its own geometry (issue #205).
 *
 * <p>There is no road router, so a variant can only take a shortcut where the route already
 * passes close to itself: point {@code i} and a later point {@code j} lie within
 * {@link #JOIN_RADIUS_M} as the crow flies, so the rider can leave the route at {@code i} and
 * rejoin at {@code j}, skipping everything in between. This covers the useful cases:
 * <ul>
 *   <li>figure-eight and clover routes — skip one lobe (and the climb on it);</li>
 *   <li>out-and-back routes — turn around earlier;</li>
 *   <li>loops that pass the start halfway — stop after the first loop.</li>
 * </ul>
 * Shortcuts are deduplicated so near-identical cut points produce one suggestion. Pure Java,
 * no Android dependencies; the result is an ordinary stored route (no wire-format change).
 */
public final class RouteShortener {

    /** Max straight-line gap the rider must bridge between leaving and rejoining the route. */
    public static final double JOIN_RADIUS_M = 200.0;
    /** A shortcut must save at least this much distance to be worth suggesting. */
    public static final double MIN_SAVING_M = 1_000.0;
    /** The shortened route must keep at least this length. */
    public static final double MIN_REMAINING_M = 2_000.0;
    /** Two shortcuts whose leave and rejoin points both lie this close along the route are the same. */
    public static final double DEDUP_ALONG_M = 1_500.0;

    private static final double M_PER_DEG_LAT = 111_195.0;

    private RouteShortener() {}

    /** One suggested shortcut: leave the route at {@code fromIndex}, rejoin at {@code toIndex}. */
    public static final class Variant {
        public final int fromIndex;
        public final int toIndex;
        /** Along-route position (m) where the rider leaves the route. */
        public final double fromDistanceM;
        /** Along-route position (m) where the rider rejoins the route. */
        public final double toDistanceM;
        /** Straight-line connector between the two points (m). */
        public final double connectorM;
        /** Length of the shortened route (m). */
        public final double newLengthM;
        /** Distance saved compared to the full route (m). */
        public final double savedM;
        /** Positive elevation gain inside the skipped stretch (m). */
        public final double savedGainM;
        /** Indices (into the climbs passed in) of climbs that lie entirely in the skipped stretch. */
        public final List<Integer> skippedClimbs;
        /** Indices of climbs that the shortcut cuts through partially. */
        public final List<Integer> partialClimbs;

        Variant(int fromIndex, int toIndex, double fromDistanceM, double toDistanceM,
                double connectorM, double newLengthM, double savedM, double savedGainM,
                List<Integer> skippedClimbs, List<Integer> partialClimbs) {
            this.fromIndex     = fromIndex;
            this.toIndex       = toIndex;
            this.fromDistanceM = fromDistanceM;
            this.toDistanceM   = toDistanceM;
            this.connectorM    = connectorM;
            this.newLengthM    = newLengthM;
            this.savedM        = savedM;
            this.savedGainM    = savedGainM;
            this.skippedClimbs = Collections.unmodifiableList(skippedClimbs);
            this.partialClimbs = Collections.unmodifiableList(partialClimbs);
        }
    }

    /**
     * Suggests up to {@code max} shortcuts, largest saving first.
     *
     * @param lats       latitudes of the route points
     * @param lons       longitudes of the route points
     * @param elevations elevations (may be null or contain NaN; gain then counts as 0)
     * @param distances  cumulative distances (m) of the route points
     * @param climbRanges per climb {@code {startDistanceM, endDistanceM}}; may be null
     * @param max        maximum number of suggestions
     */
    public static List<Variant> suggest(double[] lats, double[] lons, double[] elevations,
                                        double[] distances, int[][] climbRanges, int max) {
        List<Variant> out = new ArrayList<>();
        if (lats == null || lons == null || distances == null || max <= 0) return out;
        int n = Math.min(Math.min(lats.length, lons.length), distances.length);
        if (n < 3) return out;
        double total = distances[n - 1];

        // Spatial hash on an equirectangular grid with cells of JOIN_RADIUS_M, so each point only
        // compares against the 3x3 neighbourhood instead of the whole route.
        double mPerDegLon = M_PER_DEG_LAT * Math.cos(Math.toRadians(lats[0]));
        Map<Long, List<Integer>> grid = new HashMap<>();
        for (int k = 0; k < n; k++) {
            grid.computeIfAbsent(cellKey(cellX(lons[k], mPerDegLon), cellY(lats[k])),
                    key -> new ArrayList<>()).add(k);
        }

        List<Variant> candidates = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            long cx = cellX(lons[i], mPerDegLon);
            long cy = cellY(lats[i]);
            int best = -1;
            double bestConnector = 0;
            for (long dx = -1; dx <= 1; dx++) {
                for (long dy = -1; dy <= 1; dy++) {
                    List<Integer> cell = grid.get(cellKey(cx + dx, cy + dy));
                    if (cell == null) continue;
                    for (int j : cell) {
                        if (j <= i || (best >= 0 && distances[j] <= distances[best])) continue;
                        double gap = CumulativeDistance.haversine(lats[i], lons[i], lats[j], lons[j]);
                        if (gap > JOIN_RADIUS_M) continue;
                        double saved = distances[j] - distances[i] - gap;
                        double remaining = total - (distances[j] - distances[i]) + gap;
                        if (saved < MIN_SAVING_M || remaining < MIN_REMAINING_M) continue;
                        best = j;
                        bestConnector = gap;
                    }
                }
            }
            if (best >= 0) {
                candidates.add(build(i, best, bestConnector, elevations, distances, total,
                        climbRanges));
            }
        }

        candidates.sort((a, b) -> Double.compare(b.savedM, a.savedM));
        for (Variant c : candidates) {
            boolean duplicate = false;
            for (Variant kept : out) {
                if (Math.abs(kept.fromDistanceM - c.fromDistanceM) < DEDUP_ALONG_M
                        && Math.abs(kept.toDistanceM - c.toDistanceM) < DEDUP_ALONG_M) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) out.add(c);
            if (out.size() >= max) break;
        }
        return out;
    }

    /**
     * Applies a shortcut: points {@code 0..fromIndex} followed by {@code toIndex..end}, with
     * cumulative distances recomputed (the connector counts as a straight line).
     */
    public static List<RoutePoint> apply(List<RoutePoint> points, int fromIndex, int toIndex) {
        if (points == null || fromIndex < 0 || toIndex <= fromIndex || toIndex >= points.size()) {
            throw new IllegalArgumentException("Invalid shortcut " + fromIndex + "→" + toIndex);
        }
        List<RoutePoint> joined = new ArrayList<>(points.subList(0, fromIndex + 1));
        joined.addAll(points.subList(toIndex, points.size()));
        return CumulativeDistance.compute(joined);
    }

    private static Variant build(int i, int j, double connector, double[] elevations,
                                 double[] distances, double total, int[][] climbRanges) {
        double gain = 0;
        if (elevations != null && j < elevations.length) {
            for (int k = i + 1; k <= j; k++) {
                double d = elevations[k] - elevations[k - 1];
                if (d > 0) gain += d; // NaN comparisons are false, so gaps count as 0
            }
        }
        List<Integer> skipped = new ArrayList<>();
        List<Integer> partial = new ArrayList<>();
        if (climbRanges != null) {
            for (int c = 0; c < climbRanges.length; c++) {
                int[] r = climbRanges[c];
                if (r == null || r.length < 2) continue;
                if (r[0] >= distances[i] && r[1] <= distances[j]) {
                    skipped.add(c);
                } else if (r[1] > distances[i] && r[0] < distances[j]) {
                    partial.add(c);
                }
            }
        }
        double skippedM = distances[j] - distances[i];
        return new Variant(i, j, distances[i], distances[j], connector,
                total - skippedM + connector, skippedM - connector, gain, skipped, partial);
    }

    private static long cellX(double lon, double mPerDegLon) {
        return (long) Math.floor(lon * mPerDegLon / JOIN_RADIUS_M);
    }

    private static long cellY(double lat) {
        return (long) Math.floor(lat * M_PER_DEG_LAT / JOIN_RADIUS_M);
    }

    private static long cellKey(long x, long y) {
        return (x << 32) ^ (y & 0xffffffffL);
    }
}
