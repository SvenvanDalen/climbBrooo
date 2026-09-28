package nl.paree.climbpro.ui.routes;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.data.route.StoredSegment;
import nl.paree.climbpro.domain.route.CumulativeDistance;
import nl.paree.climbpro.domain.segment.GradientColor;

/**
 * Drawable elevation profile of a whole route (issue #207), prepared off the UI thread so
 * {@link RouteElevationProfileView} only has to scale and paint.
 *
 * <p>Built from the stored route geometry: samples with a missing (NaN) or zero elevation are
 * skipped (zero is how dropped GPX/FIT elevation often shows up; real sea-level routes still
 * have plenty of non-zero samples), long routes are downsampled to per-bucket min/max pairs so
 * summits and valleys survive, and every climb contributes colored bands — one per stored
 * segment, using the segment's gradient color index (see {@link GradientColor}), or a single
 * band colored by the climb's average gradient when it has no segments.
 */
public final class RouteElevationProfile {

    /** Bucket count used by the route detail screen; roughly one bucket per horizontal pixel pair. */
    public static final int DEFAULT_MAX_BUCKETS = 300;

    private static final int MAX_COLOR_INDEX = 5;

    /** A highlighted stretch of the profile belonging to one climb segment. */
    public static final class Band {
        public final double startDistance;
        public final double endDistance;
        /** Gradient color index 0–5, see {@link GradientColor}. */
        public final int colorIndex;
        /** Position of the owning climb in {@link StoredRoute#climbs}. */
        public final int climbIndex;

        public Band(double startDistance, double endDistance, int colorIndex, int climbIndex) {
            this.startDistance = startDistance;
            this.endDistance = endDistance;
            this.colorIndex = colorIndex;
            this.climbIndex = climbIndex;
        }
    }

    private static final RouteElevationProfile EMPTY = new RouteElevationProfile(
            new double[0], new double[0], 0, 0, 0, Collections.<Band>emptyList());

    /** Cumulative distance in meters, non-decreasing. */
    public final double[] distances;
    /** Elevation in meters, same length as {@link #distances}. */
    public final double[] elevations;
    public final double minElevation;
    public final double maxElevation;
    public final double totalDistance;
    public final List<Band> bands;

    private RouteElevationProfile(double[] distances, double[] elevations, double minElevation,
                                  double maxElevation, double totalDistance, List<Band> bands) {
        this.distances = distances;
        this.elevations = elevations;
        this.minElevation = minElevation;
        this.maxElevation = maxElevation;
        this.totalDistance = totalDistance;
        this.bands = bands;
    }

    /** True when there is nothing meaningful to draw (fewer than two usable samples). */
    public boolean isEmpty() {
        return distances.length < 2 || totalDistance <= 0;
    }

    /**
     * @param maxBuckets upper bound on distance buckets; the result has at most
     *                   {@code 2 * maxBuckets + 2} points.
     */
    public static RouteElevationProfile from(StoredRoute route, int maxBuckets) {
        if (route == null || route.elevations == null) return EMPTY;
        double[] dist = resolveDistances(route);
        if (dist == null || dist.length != route.elevations.length) return EMPTY;

        // Keep only usable samples.
        int n = dist.length;
        double[] d = new double[n];
        double[] e = new double[n];
        int count = 0;
        for (int i = 0; i < n; i++) {
            double ele = route.elevations[i];
            if (Double.isNaN(ele) || Double.isInfinite(ele) || ele == 0.0) continue;
            if (Double.isNaN(dist[i])) continue;
            d[count] = dist[i];
            e[count] = ele;
            count++;
        }
        if (count < 2) return EMPTY;

        double[][] sampled = downsample(d, e, count, Math.max(1, maxBuckets));
        double[] sd = sampled[0];
        double[] se = sampled[1];
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        for (double v : se) {
            min = Math.min(min, v);
            max = Math.max(max, v);
        }
        double start = sd[0];
        double end = sd[sd.length - 1];
        if (end <= start) return EMPTY;
        return new RouteElevationProfile(sd, se, min, max, end,
                buildBands(route.climbs, start, end));
    }

    private static double[] resolveDistances(StoredRoute route) {
        if (route.distances != null) return route.distances;
        if (route.lats == null || route.lons == null || route.lats.length != route.lons.length) {
            return null;
        }
        double[] out = new double[route.lats.length];
        for (int i = 1; i < out.length; i++) {
            out[i] = out[i - 1] + CumulativeDistance.haversine(
                    route.lats[i - 1], route.lons[i - 1], route.lats[i], route.lons[i]);
        }
        return out;
    }

    /**
     * Splits [first, last] into equal distance buckets and keeps each bucket's lowest and highest
     * sample in distance order, plus the first and last sample. Returns the input unchanged when
     * it is already small enough.
     */
    private static double[][] downsample(double[] d, double[] e, int count, int maxBuckets) {
        if (count <= 2 * maxBuckets + 2) {
            double[] od = new double[count];
            double[] oe = new double[count];
            System.arraycopy(d, 0, od, 0, count);
            System.arraycopy(e, 0, oe, 0, count);
            return new double[][]{od, oe};
        }
        double first = d[0];
        double span = d[count - 1] - first;
        List<Integer> keep = new ArrayList<>(2 * maxBuckets + 2);
        keep.add(0);
        int i = 1;
        for (int b = 0; b < maxBuckets && i < count - 1; b++) {
            double bucketEnd = first + span * (b + 1) / maxBuckets;
            int minIdx = -1;
            int maxIdx = -1;
            while (i < count - 1 && (d[i] <= bucketEnd || b == maxBuckets - 1)) {
                if (minIdx < 0 || e[i] < e[minIdx]) minIdx = i;
                if (maxIdx < 0 || e[i] > e[maxIdx]) maxIdx = i;
                i++;
            }
            if (minIdx < 0) continue;
            if (minIdx == maxIdx) {
                keep.add(minIdx);
            } else {
                keep.add(Math.min(minIdx, maxIdx));
                keep.add(Math.max(minIdx, maxIdx));
            }
        }
        keep.add(count - 1);
        double[] od = new double[keep.size()];
        double[] oe = new double[keep.size()];
        for (int k = 0; k < keep.size(); k++) {
            od[k] = d[keep.get(k)];
            oe[k] = e[keep.get(k)];
        }
        return new double[][]{od, oe};
    }

    private static List<Band> buildBands(List<StoredClimb> climbs, double start, double end) {
        if (climbs == null || climbs.isEmpty()) return Collections.emptyList();
        List<Band> bands = new ArrayList<>();
        for (int ci = 0; ci < climbs.size(); ci++) {
            StoredClimb c = climbs.get(ci);
            if (c == null) continue;
            if (c.segments == null || c.segments.isEmpty()) {
                addBand(bands, c.startDistance, c.endDistance,
                        GradientColor.forGradient(c.avgGradient), ci, start, end);
                continue;
            }
            double segStart = c.startDistance;
            for (StoredSegment s : c.segments) {
                if (s == null) continue;
                double segEnd = segStart + Math.max(0, s.distance);
                addBand(bands, segStart, segEnd, s.colorIndex, ci, start, end);
                segStart = segEnd;
            }
        }
        return bands;
    }

    private static void addBand(List<Band> out, double from, double to, int colorIndex,
                                int climbIndex, double start, double end) {
        double a = Math.max(from, start);
        double b = Math.min(to, end);
        if (b <= a) return;
        int color = Math.max(0, Math.min(MAX_COLOR_INDEX, colorIndex));
        out.add(new Band(a, b, color, climbIndex));
    }
}
