package nl.paree.climbpro.domain.route;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Tunnels and technical descents along a route (issue #203). Pure Java.
 *
 * <p>Descents are detected from the stored route geometry: a stretch is a <b>technical
 * descent</b> when, over a {@link #WINDOW_M} window, it either drops steeply
 * ({@code ≤ STEEP_GRADIENT}) or drops moderately ({@code ≤ BENDY_GRADIENT}) while turning a lot
 * ({@code ≥ BENDY_TURN_DEG} of summed heading change — hairpins). Overlapping/near windows are
 * merged. Tunnels come from OpenStreetMap (see {@code OverpassTunnelClient}) and are projected
 * onto the route by {@link #matchTunnels}.
 *
 * <p>Wire: {@link #pack} produces the packed route-level {@code hz} array
 * {@code [startM, endM, type, ...]} with {@link #TYPE_TUNNEL} / {@link #TYPE_DESCENT}.
 */
public final class RouteHazards {

    public static final int TYPE_TUNNEL  = 0;
    public static final int TYPE_DESCENT = 1;

    /** Distance over which descent gradient and turning are measured. */
    public static final double WINDOW_M = 300.0;
    /** Average gradient at or below which a window is steep on its own. */
    public static final double STEEP_GRADIENT = -0.08;
    /** Average gradient at or below which a bendy window counts. */
    public static final double BENDY_GRADIENT = -0.05;
    /** Summed absolute heading change within a window that makes it bendy. */
    public static final double BENDY_TURN_DEG = 150.0;
    /** Hazards of the same type closer than this are merged. */
    public static final double MERGE_GAP_M = 150.0;
    /** A tunnel node further than this from the route is not on it. */
    public static final double TUNNEL_MATCH_M = 30.0;
    /** Max hazards on the wire (3 ints each). */
    public static final int MAX_WIRE_HAZARDS = 32;

    private static final double M_PER_DEG = 111_195.0;

    private RouteHazards() {}

    /** One hazard along the route, in metres from the route start. */
    public static final class Hazard {
        public final int startM;
        public final int endM;
        public final int type;

        public Hazard(int startM, int endM, int type) {
            this.startM = startM;
            this.endM = endM;
            this.type = type;
        }
    }

    /** Technical descents along the route; empty without usable elevation. */
    public static List<Hazard> detectDescents(double[] lats, double[] lons, double[] elevations,
                                              double[] distances) {
        List<Hazard> out = new ArrayList<>();
        if (lats == null || lons == null || elevations == null || distances == null) return out;
        int n = Math.min(Math.min(lats.length, lons.length),
                Math.min(elevations.length, distances.length));
        if (n < 2) return out;

        double[] turn = new double[n]; // absolute heading change at each interior point
        for (int i = 1; i < n - 1; i++) {
            double a = bearing(lats[i - 1], lons[i - 1], lats[i], lons[i]);
            double b = bearing(lats[i], lons[i], lats[i + 1], lons[i + 1]);
            if (Double.isNaN(a) || Double.isNaN(b)) continue;
            double d = Math.abs(b - a) % 360.0;
            turn[i] = d > 180.0 ? 360.0 - d : d;
        }

        int j = 0;
        double turnSum = 0; // sum of turn[i+1 .. j-1]
        List<int[]> raw = new ArrayList<>();
        for (int i = 0; i < n - 1; i++) {
            if (j <= i) {
                j = i + 1;
                turnSum = 0;
            }
            while (j < n - 1 && distances[j] - distances[i] < WINDOW_M) {
                turnSum += turn[j];
                j++;
            }
            double span = distances[j] - distances[i];
            if (span >= WINDOW_M * 0.5 && !Double.isNaN(elevations[i])
                    && !Double.isNaN(elevations[j])) {
                double grade = (elevations[j] - elevations[i]) / span;
                if (grade <= STEEP_GRADIENT
                        || (grade <= BENDY_GRADIENT && turnSum >= BENDY_TURN_DEG)) {
                    raw.add(new int[]{(int) Math.round(distances[i]),
                            (int) Math.round(distances[j])});
                }
            }
            if (i + 1 < j) turnSum -= turn[i + 1];
        }
        for (int[] r : mergeRanges(raw)) out.add(new Hazard(r[0], r[1], TYPE_DESCENT));
        return out;
    }

    /**
     * Projects OSM tunnel ways (each a {@code [lat, lon]} polyline) onto the route and returns
     * their along-route ranges. A way counts when at least two of its nodes lie within
     * {@link #TUNNEL_MATCH_M} of the route line.
     */
    public static List<Hazard> matchTunnels(double[] lats, double[] lons, double[] distances,
                                            List<double[][]> tunnelWays) {
        List<int[]> raw = new ArrayList<>();
        if (lats == null || lons == null || distances == null || tunnelWays == null) {
            return new ArrayList<>();
        }
        int n = Math.min(Math.min(lats.length, lons.length), distances.length);
        if (n < 2) return new ArrayList<>();
        double cosLat = Math.cos(Math.toRadians(lats[0]));
        for (double[][] way : tunnelWays) {
            if (way == null) continue;
            double lo = Double.MAX_VALUE;
            double hi = -Double.MAX_VALUE;
            int matched = 0;
            for (double[] node : way) {
                if (node == null || node.length < 2) continue;
                double along = project(lats, lons, distances, n, cosLat, node[0], node[1]);
                if (Double.isNaN(along)) continue;
                matched++;
                lo = Math.min(lo, along);
                hi = Math.max(hi, along);
            }
            if (matched >= 2 && hi > lo) {
                raw.add(new int[]{(int) Math.floor(lo), (int) Math.ceil(hi)});
            }
        }
        List<Hazard> out = new ArrayList<>();
        for (int[] r : mergeRanges(raw)) out.add(new Hazard(r[0], r[1], TYPE_TUNNEL));
        return out;
    }

    /**
     * Packs hazards into the wire {@code hz} array, sorted by start; null when there are none.
     * Keeps at most {@link #MAX_WIRE_HAZARDS} (the first ones along the route).
     */
    public static int[] pack(List<Hazard> hazards) {
        if (hazards == null || hazards.isEmpty()) return null;
        List<Hazard> sorted = new ArrayList<>(hazards);
        Collections.sort(sorted, (a, b) -> a.startM != b.startM
                ? Integer.compare(a.startM, b.startM) : Integer.compare(a.type, b.type));
        int count = Math.min(sorted.size(), MAX_WIRE_HAZARDS);
        int[] out = new int[count * 3];
        for (int i = 0; i < count; i++) {
            Hazard h = sorted.get(i);
            out[i * 3]     = Math.max(0, h.startM);
            out[i * 3 + 1] = Math.max(h.startM + 1, h.endM);
            out[i * 3 + 2] = h.type;
        }
        return out;
    }

    /** Sorts {start, end} ranges and merges overlapping or near (≤ MERGE_GAP_M) ones. */
    static List<int[]> mergeRanges(List<int[]> ranges) {
        List<int[]> sorted = new ArrayList<>(ranges);
        Collections.sort(sorted, (a, b) -> Integer.compare(a[0], b[0]));
        List<int[]> out = new ArrayList<>();
        for (int[] r : sorted) {
            int[] last = out.isEmpty() ? null : out.get(out.size() - 1);
            if (last != null && r[0] - last[1] <= MERGE_GAP_M) {
                last[1] = Math.max(last[1], r[1]);
            } else {
                out.add(new int[]{r[0], r[1]});
            }
        }
        return out;
    }

    /** Along-route distance of the nearest point on the route line, or NaN when too far. */
    private static double project(double[] lats, double[] lons, double[] distances, int n,
                                  double cosLat, double lat, double lon) {
        double best = TUNNEL_MATCH_M;
        double along = Double.NaN;
        double px = lon * M_PER_DEG * cosLat;
        double py = lat * M_PER_DEG;
        for (int i = 0; i < n - 1; i++) {
            double ax = lons[i] * M_PER_DEG * cosLat, ay = lats[i] * M_PER_DEG;
            double bx = lons[i + 1] * M_PER_DEG * cosLat, by = lats[i + 1] * M_PER_DEG;
            double dx = bx - ax, dy = by - ay;
            double len2 = dx * dx + dy * dy;
            double t = len2 == 0 ? 0 : ((px - ax) * dx + (py - ay) * dy) / len2;
            t = Math.max(0, Math.min(1, t));
            double qx = ax + t * dx - px, qy = ay + t * dy - py;
            double d = Math.sqrt(qx * qx + qy * qy);
            if (d <= best) {
                best = d;
                along = distances[i] + t * (distances[i + 1] - distances[i]);
            }
        }
        return along;
    }

    private static double bearing(double lat1, double lon1, double lat2, double lon2) {
        if (lat1 == lat2 && lon1 == lon2) return Double.NaN;
        double p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2);
        double dl = Math.toRadians(lon2 - lon1);
        double y = Math.sin(dl) * Math.cos(p2);
        double x = Math.cos(p1) * Math.sin(p2) - Math.sin(p1) * Math.cos(p2) * Math.cos(dl);
        return (Math.toDegrees(Math.atan2(y, x)) + 360.0) % 360.0;
    }
}
